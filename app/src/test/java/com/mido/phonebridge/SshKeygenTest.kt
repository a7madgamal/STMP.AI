package com.mido.phonebridge

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.mido.phonebridge.plugins.ssh.SshKeygen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key generation, exercised against the real JSch jar.
 *
 * **This test exists because the feature shipped broken and a unit test would have caught it.**
 * 0.40.0 called `KeyPair.writePrivateKey`, which is built on `KeyPairEdDSA.getPrivateKey` — a
 * method that class does not implement. The very first press of "generate key" on the phone threw
 * `UnsupportedOperationException`, and the UI reported only "could not create '<name>': ...".
 *
 * Nothing about that failure needs a device: JSch is a plain JVM library, so the generation path
 * runs here exactly as it does on the phone. The lesson is not "add a test later" — it is that the
 * one untested call was the one that could not work.
 */
class SshKeygenTest {

    @Test
    fun `generating a key produces an OpenSSH private key that reloads`() {
        val gen = SshKeygen.generate(comment = "test")

        // The PEM frame JSch writes and reads. `writePrivateKey` produced nothing at all here —
        // it threw — so the header assertion is what pins the correct writer.
        assertTrue(
            "expected an OpenSSH private key block, got: ${gen.privateKey.take(40)}",
            gen.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
        )
        assertTrue(gen.privateKey.contains("-----END OPENSSH PRIVATE KEY-----"))
        assertTrue("key must not be empty", gen.privateKey.length > 200)
    }

    @Test
    fun `the generated key is ed25519 and the public line matches the private half`() {
        val gen = SshKeygen.generate(comment = "roundtrip")

        assertTrue(
            "expected an ssh-ed25519 public line, got: ${gen.publicKey}",
            gen.publicKey.startsWith("ssh-ed25519 ")
        )
        assertTrue("the comment should be carried", gen.publicKey.endsWith(" roundtrip"))

        // The stored connection keeps ONLY the private key and derives the public line on demand.
        // If those two can disagree, the SSH screen shows a key the server will reject.
        assertEquals(gen.publicKey, SshKeygen.publicKeyOf(gen.privateKey, comment = "roundtrip"))

        // The comment is a label, not key material: the same private key must derive the same
        // blob whatever label is asked for, or two screens would show different-looking keys.
        val keyBlob = gen.publicKey.split(" ")[1]
        assertEquals(keyBlob, SshKeygen.publicKeyOf(gen.privateKey)!!.split(" ")[1])
        assertEquals("ssh-ed25519", SshKeygen.keyTypeOf(gen.privateKey))
    }

    @Test
    fun `JSch accepts the generated key as an identity`() {
        val gen = SshKeygen.generate()

        // This is the call `SshExec.run` makes before opening a session. A key that generates but
        // cannot be loaded as an identity would fail every run with `ssh_bad_key`, which would
        // look like a storage problem rather than a generator problem.
        val jsch = JSch()
        jsch.addIdentity("phonebridge", gen.privateKey.toByteArray(Charsets.UTF_8), null, null)
    }

    @Test
    fun `an unparseable private key yields null rather than throwing`() {
        // The SSH screen calls these for display. A corrupt vault entry must degrade to "public key
        // unavailable", not take the whole screen down with an exception.
        assertEquals(null, SshKeygen.publicKeyOf("not a key"))
        assertEquals(null, SshKeygen.keyTypeOf(""))
    }

    @Test
    fun `two generated keys are different`() {
        // A generator returning a constant would be catastrophic and completely silent.
        val a = SshKeygen.generate()
        val b = SshKeygen.generate()
        assertNotNull(a.privateKey)
        assertTrue("two generated keys must not be identical", a.privateKey != b.privateKey)
        assertTrue(a.publicKey != b.publicKey)
    }
}
