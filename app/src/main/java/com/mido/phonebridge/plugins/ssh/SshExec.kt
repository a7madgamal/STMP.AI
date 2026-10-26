package com.mido.phonebridge.plugins.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.mido.phonebridge.core.PluginError
import java.io.ByteArrayOutputStream

/**
 * Host keys are CONFIRMED, never auto-accepted.
 *
 * Mido, 2026-10-21: *"I confirm first"*. So a first connection to an unknown host does NOT run the
 * command — it fails with `ssh_host_key_unknown` and reports the fingerprint, and the caller must
 * re-issue the request naming that fingerprint. Only then is it pinned.
 *
 * **Implementation note: this is a [HostKeyRepository], so JSch calls it DURING the handshake.**
 * Verifying after `connect()` would be too late in principle (the host is already authenticated to
 * us by then) and impossible in practice — the earlier draft tried to read `session.hostKey`, which
 * does not exist in this JSch version. Returning `NOT_INCLUDED` / `CHANGED` from here makes JSch
 * abort the handshake itself, which is the correct place for the decision.
 *
 * `add()` is deliberately inert: JSch would otherwise ask to remember the key, and "remember it"
 * is exactly the auto-accept this class exists to prevent. Pinning happens in [SshExec] after an
 * explicit human confirmation.
 */
class PinningHostKeyRepository(
    /** The fingerprint stored for this connection, or null when it has never been confirmed. */
    private val pinnedFingerprint: String?,
    private val jsch: JSch,
) : HostKeyRepository {

    /** What the server actually presented, so the caller can report it on a refusal. */
    @Volatile
    var seenFingerprint: String? = null
        private set

    /** The key algorithm the server offered, e.g. `ssh-ed25519`. Shown on the confirmation card. */
    @Volatile
    var seenKeyType: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val hk = try {
            HostKey(host, key)
        } catch (e: Exception) {
            // A key we cannot even parse must not be treated as trusted.
            return HostKeyRepository.NOT_INCLUDED
        }
        seenFingerprint = hk.getFingerPrint(jsch)
        seenKeyType = hk.type
        val pinned = pinnedFingerprint ?: return HostKeyRepository.NOT_INCLUDED
        return if (pinned == seenFingerprint) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    /** Never remembered automatically. Pinning is an explicit, human-confirmed act. */
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "phonebridge-pinned"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/**
 * Runs one command over SSH and returns what came back.
 *
 * **The phone opens the connection.** Nothing here listens, and the private key never leaves the
 * device — Mido's framing, which is the whole reason this design exists: *"the key never needs to
 * reach me"*.
 *
 * **What is deliberately NOT here: a command allowlist.** Mido, 2026-10-21: *"the bridge offer a
 * mechanism. the gate's role is showing me exactly what I'm approving, if I approved something
 * stupid that's on me"*. The command arrives as a raw string, is passed to SSH verbatim, and the
 * approval card shows exactly those characters. Nothing is quoted, rewritten or filtered, so the
 * card cannot show something more reassuring than what runs.
 */
object SshExec {

    init {
        // **Register the ed25519 hooks on the CONNECTION path, not just the generation path.**
        // JSch resolves them from static config when it signs, and its defaults are the Android
        // stubs. A run that only imports a stored key never touches SshKeygen, so registering
        // there alone left every connection after an app restart falling back to BouncyCastle
        // classes that do not exist on the device — which surfaces as a bare `Auth fail`, with
        // the real `NoClassDefFoundError` swallowed inside JSch's signer.
        JschHooks.install()
    }

    /** Hard cap on captured stdout. Big enough for a log tail, small enough to survive. */
    const val MAX_OUTPUT_BYTES = 64 * 1024

    /** How long a command may run before the session is torn down. */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** Connect timeout — separate from the command timeout; a dead host must fail fast. */
    private const val CONNECT_TIMEOUT_MS = 15_000

    /** What a command produced. */
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        /** True when stdout hit [MAX_OUTPUT_BYTES] and the text is a prefix. */
        val truncated: Boolean,
        val durationMs: Long,
        /** The fingerprint the server presented, so a first run can be confirmed and pinned. */
        val hostKeyFingerprint: String,
        /** The key algorithm, e.g. `ssh-ed25519`. */
        val hostKeyType: String,
    )

    /**
     * Run [command] on [conn].
     *
     * @param trustFingerprint when non-null, this is the fingerprint the human just confirmed for a
     *   connection that has none stored. It is checked against what the server presents before
     *   anything runs, then returned so the caller can persist it. When null and the connection has
     *   no stored fingerprint, the call REFUSES with `ssh_host_key_unknown` and reports the
     *   fingerprint instead of running.
     */
    fun run(
        conn: SshConnection,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        trustFingerprint: String? = null,
        /**
         * The sudo password, or null when the command needs none.
         *
         * **Written to the command's stdin and nowhere else.** It never becomes an
         * argument (world-readable in `/proc/PID/cmdline`), never an environment
         * variable, and never part of [command] — because `command` is shown verbatim on
         * the approval card AND recorded in the audit body. It is consumed here and the
         * caller wipes its copy.
         *
         * `sudo -S` reads exactly one line from stdin, which is why the trailing newline
         * is added. A command that prompts again later (apt-get without `-y`) gets EOF —
         * that is the documented limit, and appending `-y` here would be rewriting the
         * command the human approved.
         */
        stdin: CharArray? = null,
    ): Result {
        if (command.isBlank()) {
            // An empty command opens an interactive shell on most servers, which would hang until
            // the timeout — the opposite of "run nothing".
            throw PluginError("bad_request", "command must not be empty")
        }
        val started = System.currentTimeMillis()
        val jsch = JSch()

        // The key is handed over as BYTES from memory. It is never written to a temp file: a file
        // on disk is a copy that outlives the request, and this is the one credential that must
        // not have one.
        try {
            jsch.addIdentity("phonebridge", conn.privateKey.toByteArray(Charsets.UTF_8), null, null)
        } catch (e: Exception) {
            throw PluginError("ssh_bad_key", "the stored private key could not be read: ${e.message}")
        }

        // The pin to verify against: a stored one wins; otherwise the one the human just confirmed.
        val pinned = conn.hostKeyFingerprint ?: trustFingerprint
        val repo = PinningHostKeyRepository(pinned, jsch)

        var session: Session? = null
        try {
            session = jsch.getSession(conn.user, conn.host, conn.port)
            session.hostKeyRepository = repo
            // 'yes' (not 'no', not 'ask'): the repository is the only authority, and NOT_INCLUDED
            // or CHANGED must ABORT rather than silently accept. With 'no' JSch would happily
            // connect to an unverified host, which is the thing this release is fixing.
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.serverAliveInterval = 10_000
            session.connect(CONNECT_TIMEOUT_MS)

            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            var truncated = false

            val channel = session.openChannel("exec") as ChannelExec
            // VERBATIM. No quoting, no rewriting — the card showed this exact string.
            channel.setCommand(command)
            // stdin carries the password when there is one, and is CLOSED otherwise.
            //
            // Closing matters: with an open-but-empty stdin, a command that reads (or a
            // sudo that fails to authenticate and re-prompts) would wait for input that
            // never comes, burning the whole timeout instead of failing.
            //
            // Encoding goes through a CharsetEncoder over a CharBuffer, never through
            // String: a String cannot be wiped, and building one here would leave an
            // unremovable copy of the password in the heap.
            val stdinBytes: ByteArray? = stdin?.let { chars ->
                val enc = Charsets.UTF_8.newEncoder()
                val buf = enc.encode(java.nio.CharBuffer.wrap(chars))
                val outBytes = ByteArray(buf.remaining() + 1)
                buf.get(outBytes, 0, buf.remaining())
                outBytes[outBytes.size - 1] = '\n'.code.toByte()
                // The intermediate buffer is ours alone; wipe it before it can be seen.
                if (buf.hasArray()) java.util.Arrays.fill(buf.array(), 0.toByte())
                outBytes
            }
            channel.setInputStream(stdinBytes?.let { java.io.ByteArrayInputStream(it) })
            channel.setErrStream(err)
            val input = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)

            // The bytes are on the wire now, so the local copy has done its job.
            stdinBytes?.fill(0)

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(8 * 1024)
            try {
                while (true) {
                    val available = input.available()
                    if (available > 0) {
                        val n = input.read(buf, 0, minOf(buf.size, available))
                        if (n > 0) {
                            if (out.size() + n > MAX_OUTPUT_BYTES) {
                                val room = MAX_OUTPUT_BYTES - out.size()
                                if (room > 0) out.write(buf, 0, room)
                                truncated = true
                                break
                            }
                            out.write(buf, 0, n)
                        }
                    } else if (channel.isClosed) {
                        break
                    } else {
                        if (System.currentTimeMillis() > deadline) {
                            throw PluginError(
                                "ssh_timeout",
                                "the command was still running after ${timeoutMs}ms and was stopped"
                            )
                        }
                        Thread.sleep(50)
                    }
                }
            } finally {
                channel.disconnect()
            }

            return Result(
                exitCode = channel.exitStatus,
                stdout = out.toString(Charsets.UTF_8.name()),
                stderr = err.toString(Charsets.UTF_8.name()),
                truncated = truncated,
                durationMs = System.currentTimeMillis() - started,
                hostKeyFingerprint = repo.seenFingerprint ?: "",
                hostKeyType = repo.seenKeyType ?: "",
            )
        } catch (e: com.jcraft.jsch.JSchHostKeyException) {
            // The handshake aborted because the repository said NOT_INCLUDED or CHANGED. These are
            // the two states a human must act on, so they get their own codes rather than being
            // flattened into a generic ssh_failed.
            //
            // **Classify by EXCEPTION TYPE, never by message text.** The previous version matched
            // `"UnknownHostKey"` / `"not included"` against `e.message`, and JSch 2.28.8 says
            // neither: an untrusted host throws `JSchUnknownHostKeyException` with the message
            // `reject HostKey: <host>`. So the first call to any new host — the entire
            // trust-on-first-use flow — fell through to a generic `ssh_failed` that reported no
            // fingerprint and no way to proceed. Verified against a real sshd:
            //   no pin  -> JSchUnknownHostKeyException  "reject HostKey: 127.0.0.1"
            //   bad pin -> JSchChangedHostKeyException  "HostKey has been changed: 127.0.0.1"
            // The subclasses are the contract; the strings are just what they happen to say
            // today.
            val seen = repo.seenFingerprint
            val type = repo.seenKeyType ?: "unknown"
            when {
                e is com.jcraft.jsch.JSchUnknownHostKeyException && seen != null -> throw PluginError(
                    "ssh_host_key_unknown",
                    "This is the first connection to ${conn.host}, so its host key is not yet " +
                        "trusted.\n\n  algorithm:   $type\n  fingerprint: $seen\n\n" +
                        "Nothing was run. To trust it, repeat the call with " +
                        "\"trustHostKey\": \"$seen\". Compare the fingerprint with what the " +
                        "server reports (ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub) before you do."
                )
                e is com.jcraft.jsch.JSchChangedHostKeyException && seen != null -> throw PluginError(
                    "ssh_host_key_changed",
                    "The host key for ${conn.host} CHANGED.\n\n" +
                        "  pinned: $pinned\n  now:    $seen\n\n" +
                        "This is what a man-in-the-middle looks like. It is also what a rebuilt " +
                        "server looks like. Nothing was run. If you rebuilt it, delete this " +
                        "connection and create it again to re-pin."
                )
                // A host-key refusal we cannot name. Still NOT a generic failure: it is a
                // host-key decision, and saying so keeps the fingerprint visible.
                seen != null -> throw PluginError(
                    "ssh_host_key_refused",
                    "The host key for ${conn.host} was refused (${e.javaClass.simpleName}).\n\n" +
                        "  algorithm:   $type\n  fingerprint: $seen\n  pinned:      ${pinned ?: "none"}\n\n" +
                        "Nothing was run."
                )
                else -> throw PluginError(
                    "ssh_host_key_refused",
                    "The host key for ${conn.host} was refused (${e.javaClass.simpleName}): " +
                        "${e.message ?: "no detail"}. Nothing was run."
                )
            }
        } catch (e: com.jcraft.jsch.JSchException) {
            // Everything else the handshake can fail with: refused, auth rejected, algorithm
            // mismatch, timeout. Still worth a coded error — an escaping raw exception becomes a
            // 500 with no explanation of which host failed.
            throw PluginError("ssh_failed", "${conn.user}@${conn.host}:${conn.port}: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: PluginError) {
            throw e
        } catch (e: Exception) {
            throw PluginError("ssh_failed", "${conn.user}@${conn.host}:${conn.port}: ${e.message}")
        } finally {
            // Always tear down. A session left open holds a thread and a socket, and this runs
            // inside a foreground service that is supposed to stay quiet when idle.
            try {
                session?.disconnect()
            } catch (ignored: Exception) {
                // Disconnect failure after a completed command is not worth failing the request.
            }
        }
    }
}
