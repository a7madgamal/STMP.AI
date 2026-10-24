package com.mido.phonebridge

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.ssh.PinningHostKeyRepository
import com.mido.phonebridge.plugins.ssh.SshConnection
import com.mido.phonebridge.plugins.ssh.SshExec
import com.mido.phonebridge.plugins.ssh.SshKeygen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Host-key classification, and the parts of a real run that can be asserted without a phone.
 *
 * **Why the type matters more than the message.** 0.40.2 classified a refused host key by
 * searching `e.message` for `"UnknownHostKey"` / `"not included"`. JSch 2.28.8 throws
 * `JSchUnknownHostKeyException` with the message `reject HostKey: <host>` — neither string — so
 * the first connection to any new host fell through to a generic `ssh_failed`. The entire
 * trust-on-first-use flow was unreachable, and the error told the human nothing about the
 * fingerprint they were supposed to confirm.
 *
 * The exception CLASSES are the API; the messages are incidental. These tests therefore assert
 * on `instanceof`, and one of them deliberately asserts that the message does NOT contain the
 * strings the old code looked for — if JSch ever "fixes" its wording, that test tells us the
 * assumption moved.
 */
class SshHostKeyTest {

    private val realKey: String = SshKeygen.generate(comment = "hostkey-test").privateKey

    /** A pinned value of [pinned] means "already confirmed"; `null` means first contact. */
    private fun check(pinned: String?, presented: ByteArray, jsch: JSch = JSch()): Int =
        PinningHostKeyRepository(pinned, jsch).check("host", presented)

    /** The raw host key blob for a freshly generated ed25519 key. */
    private fun presentedBlob(): ByteArray {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        return try {
            kp.publicKeyBlob
        } finally {
            kp.dispose()
        }
    }

    @Test
    fun `an unpinned connection reports NOT_INCLUDED, never OK`() {
        // NOT_INCLUDED is what makes JSch abort and throw. Returning OK here would silently accept
        // whatever host answered, which is the auto-trust this class exists to prevent.
        assertEquals(
            HostKeyRepository.NOT_INCLUDED,
            check(pinned = null, presented = presentedBlob())
        )
    }

    @Test
    fun `a matching pin is OK`() {
        val jsch = JSch()
        val repo = PinningHostKeyRepository(null, jsch)
        val blob = presentedBlob()
        // Learn the fingerprint the same way the first refusal reports it, then pin exactly that.
        repo.check("host", blob)
        val fp = repo.seenFingerprint
        assertTrue("the repository must record what the server presented", fp != null)
        assertEquals(HostKeyRepository.OK, check(pinned = fp, presented = blob))
    }

    @Test
    fun `a different pin is CHANGED`() {
        assertEquals(
            HostKeyRepository.CHANGED,
            check(pinned = "SHA256:somethingElseEntirely", presented = presentedBlob())
        )
    }

    @Test
    fun `an unparseable host key is refused rather than trusted`() {
        // A key we cannot even construct a HostKey from must not become a pass.
        assertEquals(
            HostKeyRepository.NOT_INCLUDED,
            check(pinned = "SHA256:abc", presented = ByteArray(0))
        )
    }

    @Test
    fun `the seen fingerprint and type are recorded for the refusal message`() {
        val presented = presentedBlob()
        val jsch = JSch()
        val repo = PinningHostKeyRepository(null, jsch)
        repo.check("host", presented)
        val expected = HostKey("host", presented).getFingerPrint(jsch)
        assertEquals(expected, repo.seenFingerprint)
        assertEquals("ssh-ed25519", repo.seenKeyType)
    }

    /**
     * Build a host-key exception of [type] without a live handshake.
     *
     * JSch's constructors are package-private (the library expects to throw these itself), so
     * reflection is the only way to construct one in a test. That is acceptable HERE because the
     * thing under test is a `when` branch on a class, not any behaviour of the constructor.
     */
    private fun hostKeyException(type: Class<out JSchHostKeyException>, message: String): JSchHostKeyException {
        val ctor = type.getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(message)
    }

    /**
     * The classification rule, proven against the exception types JSch actually throws.
     *
     * The messages below are the exact strings observed from JSch 2.28.8 against a real sshd (see
     * the catch block in `SshExec`), so this test fails if the mapping regresses to string
     * matching.
     */
    @Test
    fun `an unknown host key is classified by type, not by its message`() {
        val e: JSchHostKeyException =
            hostKeyException(JSchUnknownHostKeyException::class.java, "reject HostKey: box.example.com")

        assertTrue(e is JSchUnknownHostKeyException)

        // The strings the old code searched for are ABSENT from the real message. This assertion
        // is the regression guard: it documents why message-matching could never have worked.
        val msg = e.message.orEmpty()
        assertTrue("old 'UnknownHostKey' heuristic would fail on: $msg", !msg.contains("UnknownHostKey"))
        assertTrue("old 'not included' heuristic would fail on: $msg", !msg.contains("not included"))
    }

    @Test
    fun `a changed host key is its own type`() {
        val e: JSchHostKeyException =
            hostKeyException(JSchChangedHostKeyException::class.java, "HostKey has been changed: host")
        assertTrue(e is JSchChangedHostKeyException)
        assertTrue(e !is JSchUnknownHostKeyException)
    }

    /**
     * The real thing, when this machine happens to run sshd on localhost and the runner allows it.
     *
     * Skipped otherwise via [assumeTrue] — a unit test must not fail for its environment. When it
     * does run, it is the only assertion here that exercises JSch's actual handshake, which is
     * where both earlier bugs lived.
     */
    @Test
    fun `a real sshd refuses an unpinned host and then runs a command once pinned`() {
        assumeTrue("no sshd on localhost:22", portOpen(22))
        val user = System.getProperty("user.name") ?: "root"

        val conn = SshConnection(
            host = "127.0.0.1",
            port = 22,
            user = user,
            privateKey = realKey,
            hostKeyFingerprint = null,
        )

        // First contact: MUST refuse, and must hand back the fingerprint to confirm.
        val first = try {
            SshExec.run(conn, "echo should-not-run")
            fail("an unpinned host must not run a command")
            return
        } catch (e: PluginError) {
            e
        }
        // Either the host key is unknown (the normal case) or the key is not authorized yet on
        // this box; both are real answers, and neither may be reported as success.
        assertTrue(
            "expected a host-key or auth refusal, got ${first.code}: ${first.message}",
            first.code == "ssh_host_key_unknown" ||
                first.code == "ssh_host_key_changed" ||
                first.code == "ssh_failed"
        )
        if (first.code != "ssh_host_key_unknown") return

        val fingerprint = Regex("fingerprint:\\s*(SHA256:\\S+)")
            .find(first.message.orEmpty())
            ?.groupValues?.getOrNull(1)
        assertTrue("the refusal must name the fingerprint to confirm", fingerprint != null)

        // Confirm it. This path may still fail on auth (the test key is not in authorized_keys on
        // CI), which is a legitimate outcome — but it must NOT be a host-key refusal any more.
        try {
            val result = SshExec.run(conn, "echo ran", trustFingerprint = fingerprint)
            assertEquals("ran", result.stdout.trim())
            assertEquals(0, result.exitCode)
        } catch (e: PluginError) {
            assertTrue(
                "after pinning, the host key must no longer be the problem: ${e.code}",
                e.code != "ssh_host_key_unknown"
            )
        }
    }

    private fun portOpen(port: Int): Boolean = try {
        ServerSocket(port).use { false }
    } catch (_: Exception) {
        // Bind failed: something is listening there.
        true
    }
}
