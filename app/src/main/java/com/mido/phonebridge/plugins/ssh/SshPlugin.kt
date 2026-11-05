package com.mido.phonebridge.plugins.ssh

import android.content.Context
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Verb
import org.json.JSONObject

/**
 * A generic SSH gate: run a command on a host, with the human approving the exact command.
 *
 * **The design is deliberately a mechanism, not a policy.** Mido, 2026-10-21: *"the bridge offer a
 * mechanism. the gate's role is showing me exactly what I'm approving, if I approved something
 * stupid that's on me"*. So there is no command allowlist: whatever string arrives is what runs,
 * and the approval card shows it verbatim. The safety property is that the card and the wire are
 * the same string — not that the string is filtered.
 *
 * **The key never leaves the phone.** It is generated on the device ([SshKeygen]), stored in the
 * biometric vault, and used in memory to open an outbound connection. Nothing is sent anywhere for
 * the credential to leak into.
 *
 * **Host keys are confirmed, never assumed.** A first connection to an unknown host REFUSES
 * (`ssh_host_key_unknown`) and reports the fingerprint; the caller repeats the call with
 * `trustHostKey` naming it, which is then pinned. A changed key refuses loudly thereafter.
 */
class SshPlugin(private val ctx: Context) : Plugin {

    override val id = "ssh"
    override val displayName = "SSH"

    /**
     * No OAuth scopes: SSH authenticates with a key held on the phone.
     *
     * It also declares no `hosts` allowlist of its own — the vault's per-secret host rules govern
     * where a *secret* may be sent, and an SSH connection's "host" is the destination of the
     * connection itself, not an outbound HTTP call.
     */
    override val scopes: List<String> = emptyList()

    private companion object {
        /** Default ceiling for a detached job. Long enough for a build or an agent run. */
        const val DETACHED_TIMEOUT_MS = 900_000L

        /**
         * Hard ceiling for a detached job.
         *
         * Larger than the 300s synchronous ceiling because the whole point of detaching is work
         * that cannot finish in one request — but still bounded, because a job nobody stops holds
         * an open session on a phone. Fifteen minutes covers a build; anything longer should be a
         * service.
         */
        const val MAX_DETACHED_TIMEOUT_MS = 900_000L
    }

    override fun routes(): List<Route> = listOf(
        Route(
            "POST", "/ssh/{name}", "RUN COMMAND OVER SSH", Verb.EXECUTE,
            // The command is required, and a blank one would open an interactive shell (which
            // hangs). Checked pre-gate so a malformed request costs no tap.
            validate = { r ->
                val cmd = r.body.optString("command")
                if (cmd.isBlank()) {
                    throw PluginError("bad_request", "missing required 'command'")
                }
                // A sudo command that cannot read stdin can only fail — sudo answers
                // "a terminal is required to authenticate" — so refuse it here rather
                // than spend the human's tap on a guaranteed error.
                SudoCommand.requireFeedable(cmd)
            },
            // Only a sudo command needs a password. Decided from the payload, pre-gate,
            // because the same route runs both kinds and the dispatcher has to force the
            // mode floor BEFORE the modal opens.
            needsPassword = { r -> SudoCommand.invokes(r.body.optString("command")) }
        ) { run(it) },
        Route(
            "POST", "/ssh/{name}/detach", "START A DETACHED COMMAND OVER SSH", Verb.EXECUTE,
            validate = { r ->
                val cmd = r.body.optString("command")
                if (cmd.isBlank()) {
                    throw PluginError("bad_request", "missing required 'command'")
                }
                // A detached command cannot carry a password. The service wipes `secretInput`
                // the instant the handler returns, and the job runs AFTER that — so a sudo here
                // would either prompt into a closed stdin or keep a live password in a thread
                // nobody is watching. Refused pre-gate, so it costs no tap.
                if (SudoCommand.invokes(cmd)) {
                    throw PluginError(
                        "bad_request",
                        "a detached command cannot use sudo: the password is wiped when the " +
                            "request ends. Run it in the foreground, or scope a NOPASSWD rule."
                    )
                }
            },
            // No password field: see the refusal above. Decided from the payload, pre-gate.
            needsPassword = { false }
        ) { runDetach(it) },
        Route(
            // Distinct 3-segment shape from `/ssh/{name}/detach` so the two can never be confused
            // for one another, and a connection literally named "job" cannot shadow the route.
            "GET", "/ssh/job/{id}", "READ A DETACHED SSH JOB", Verb.READ
        ) { pollJob(it) }
    )

    /**
     * The card: the target, and the command EXACTLY as it will be sent.
     *
     * **The command row is the whole safety argument.** It is rendered from the same string that
     * goes to `channel.setCommand`, with no quoting, trimming or escaping in between — so what the
     * human reads is what the far end executes, character for character. A modal that showed a
     * prettified version of a shell command would be worse than no modal.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        reads = emptySet(),
        writes = mapOf(
            "POST /ssh/{name}" to { r ->
                val name = requireName(r.pathParams["name"])
                val command = r.body.optString("command")
                if (command.isBlank()) throw PluginError("bad_request", "missing required 'command'")

                // The connection must EXIST before the card opens: naming a host that is not
                // configured would be asking a human to approve something that cannot run.
                val conn = loadConnection(name)
                    ?: throw PluginError("not_found", "no SSH connection named '$name'")

                buildList {
                    add(ApprovalRow("HOST", "${conn.user}@${conn.host}:${conn.port}", ApprovalRow.Style.WARN))
                    // The command, verbatim, in the CODE field: monospace, syntax-coloured, and
                    // broken at token boundaries. Not truncated in the modal: a long command that
                    // is silently clipped is exactly the "you approved something you could not
                    // read" failure this design must avoid.
                    //
                    // **CODE, not LONG.** `LONG` is prose and renders in the handwritten face —
                    // right for a message body, unreadable for a shell command. Colour and line
                    // breaks are display only: `SshExec.run` still receives `command` untouched.
                    add(ApprovalRow("COMMAND", command, ApprovalRow.Style.CODE))
                    // Elevation is stated as its own row, never folded into the command text.
                    // A `sudo` buried mid-string is easy to miss on a glance, and "this runs as
                    // root" is the single fact most worth seeing before a tap.
                    if (SudoCommand.invokes(command)) {
                        add(
                            ApprovalRow(
                                "ELEVATION",
                                "runs as root — sudo, with the password you type below",
                                ApprovalRow.Style.WARN
                            )
                        )
                    }
                    val trust = r.body.optString("trustHostKey").trim()
                    if (trust.isNotBlank()) {
                        // Trusting a key is part of what is being approved, so it is on the card.
                        add(ApprovalRow("TRUST HOST KEY", trust, ApprovalRow.Style.MONO))
                    }
                    // Only shown when nothing is pinned yet: it tells the human WHY this first call
                    // may refuse instead of running.
                    if (conn.hostKeyFingerprint == null && trust.isBlank()) {
                        add(
                            ApprovalRow(
                                "HOST KEY",
                                "not yet trusted — this run will report the fingerprint and stop",
                                ApprovalRow.Style.NORMAL
                            )
                        )
                    }
                }
            },
            "POST /ssh/{name}/detach" to { r ->
                val name = requireName(r.pathParams["name"])
                val command = r.body.optString("command")
                if (command.isBlank()) throw PluginError("bad_request", "missing required 'command'")
                val conn = loadConnection(name)
                    ?: throw PluginError("not_found", "no SSH connection named '$name'")
                buildList {
                    add(ApprovalRow("HOST", "${conn.user}@${conn.host}:${conn.port}", ApprovalRow.Style.WARN))
                    // The command verbatim, exactly as the synchronous route shows it: detaching
                    // changes WHEN it runs, never WHAT runs.
                    add(ApprovalRow("COMMAND", command, ApprovalRow.Style.CODE))
                    // Said plainly, because "detached" is the one fact that changes what the
                    // human is agreeing to: this outlives the request and keeps running.
                    add(
                        ApprovalRow(
                            "MODE",
                            "detached — keeps running after this request returns; poll /ssh/job/{id}",
                            ApprovalRow.Style.WARN
                        )
                    )
                    val trust = r.body.optString("trustHostKey").trim()
                    if (trust.isNotBlank()) {
                        add(ApprovalRow("TRUST HOST KEY", trust, ApprovalRow.Style.MONO))
                    }
                }
            }
        )
    )

    private suspend fun run(req: Req): Resp {
        val name = requireName(req.pathParams["name"])
        val command = req.body.optString("command")
        if (command.isBlank()) throw PluginError("bad_request", "missing required 'command'")
        val trust = req.body.optString("trustHostKey").trim().takeUnless { it.isBlank() }
        val timeout = req.body.optLong("timeoutMs", SshExec.DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000L, 300_000L)

        val conn = loadConnection(name)
            ?: throw PluginError("not_found", "no SSH connection named '$name'")

        // The password, if the human typed one. Read from the request rather than a
        // parameter because that is where the dispatcher attaches it — and it is wiped
        // there, at the single site that knows the handler is done, never here.
        val stdin = req.secretInput

        // **Diagnostic, never the value.** A rejected password is indistinguishable from a mangled
        // one without knowing what actually arrived: length and a checksum settle it, and neither
        // can be turned back into the secret. This is the line that answers "is the password
        // reaching sudo intact" from the device's own log instead of by asking the human to
        // re-type it and guess (Mido, 2026-10-27: *"the password is right"* while sudo said
        // otherwise).
        if (stdin != null) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            // Hash the CHARS as UTF-8 bytes without ever building a String.
            val enc = Charsets.UTF_8.newEncoder()
            val buf = enc.encode(java.nio.CharBuffer.wrap(stdin))
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val sha = digest.digest(bytes)
            bytes.fill(0)
            if (buf.hasArray()) java.util.Arrays.fill(buf.array(), 0.toByte())
            com.mido.phonebridge.Debug.log(
                "ssh: password received len=${stdin.size} sha256=${sha.take(4).joinToString("") { "%02x".format(it) }}"
            )
        } else {
            com.mido.phonebridge.Debug.log("ssh: no password attached to this request")
        }

        val result = SshExec.run(conn, command, timeout, trustFingerprint = trust, stdin = stdin)

        // PIN IT NOW. This only happens on a run that succeeded, which means the fingerprint was
        // either already stored or was the one the human just named — so writing it here cannot
        // pin anything unconfirmed.
        if (conn.hostKeyFingerprint == null && result.hostKeyFingerprint.isNotBlank()) {
            try {
                saveConnection(name, conn.copy(hostKeyFingerprint = result.hostKeyFingerprint))
            } catch (e: Exception) {
                // The command already ran; failing the response now would claim it did not. Say
                // the pin failed instead, so the NEXT call refuses rather than silently trusting.
                com.mido.phonebridge.Debug.log("ssh: could not pin host key for '$name': ${e.message}")
            }
        }

        return Resp.ok(
            JSONObject()
                .put("exitCode", result.exitCode)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("truncated", result.truncated)
                .put("timedOut", result.timedOut)
                .put("durationMs", result.durationMs)
                .put("hostKeyFingerprint", result.hostKeyFingerprint)
                .put("hostKeyType", result.hostKeyType)
        )
    }

    // --- storage ---

    /**
     * A connection lives in the vault under `ssh-<name>`, marked `kind = SSH`.
     *
     * **The vault is the right home and the marking is not cosmetic.** Reusing it means one
     * credential store, one biometric unlock and one place to delete — and `kind = SSH` is what
     * stops `SecretsActivity` from putting the private key behind a reveal button.
     */
    private fun loadConnection(name: String): SshConnection? {
        val vaultName = vaultNameFor(name)
        if (!SecretVault.has(ctx, vaultName)) return null
        val raw = try {
            SecretVault.get(ctx, vaultName)
        } catch (e: PluginError) {
            // `secret_locked` is a real, distinct state the caller must see: the vault being shut
            // is not the same as the connection being missing.
            throw e
        } catch (e: Exception) {
            throw PluginError("ssh_bad_key", "stored connection '$name' is unreadable: ${e.message}")
        }
        return try {
            SshConnection.fromJson(raw)
        } catch (e: Exception) {
            throw PluginError("ssh_bad_key", "stored connection '$name' is malformed: ${e.message}")
        }
    }

    /**
     * Persist a connection under its NAME. Requires the vault unlocked, which it already is for a
     * run.
     *
     * The name is passed in rather than derived from the connection: `SshConnection` holds
     * host/user/key and deliberately knows nothing about what the user called the entry, so the
     * route segment stays the single source of truth for where it lives.
     */
    private fun saveConnection(name: String, conn: SshConnection) {
        SecretVault.put(
            ctx, vaultNameFor(name), conn.toJson().toString(),
            kind = SecretVault.SecretMeta.Kind.SSH
        )
    }

    private fun vaultNameFor(name: String): String = "ssh-$name"

    private suspend fun runDetach(req: Req): Resp {
        val name = requireName(req.pathParams["name"])
        val command = req.body.optString("command")
        if (command.isBlank()) throw PluginError("bad_request", "missing required 'command'")
        val trust = req.body.optString("trustHostKey").trim().takeUnless { it.isBlank() }
        // A detached job may outlive any single request, so the ceiling is the LONGEST run the
        // app will supervise rather than a per-call window. Still bounded: an unbounded job is a
        // leaked session on a phone.
        val timeout = req.body.optLong("timeoutMs", DETACHED_TIMEOUT_MS)
            .coerceIn(1_000L, MAX_DETACHED_TIMEOUT_MS)

        val conn = loadConnection(name)
            ?: throw PluginError("not_found", "no SSH connection named '$name'")

        // No stdin: the route refuses sudo pre-gate, because the password is wiped when this
        // handler returns and the job starts after that.
        val job = SshJobs.start(conn, name, command, timeout, trust, stdin = null)

        return Resp.ok(
            JSONObject()
                .put("jobId", job.id)
                .put("done", false)
                .put("poll", "GET /ssh/job/${job.id}")
                .put("startedAtMs", job.startedAtMs)
                .put("timeoutMs", timeout)
        )
    }

    private suspend fun pollJob(req: Req): Resp {
        val id = req.pathParams["id"]?.trim().orEmpty()
        if (id.isBlank()) throw PluginError("bad_request", "missing job id")
        return Resp.ok(SshJobs.get(id).snapshot())
    }

    private fun requireName(raw: String?): String {
        val v = raw?.trim().orEmpty()
        // The route segment becomes part of a vault name, which is constrained to
        // [a-z0-9_-]. Checked here so the error names the problem rather than surfacing
        // later as a vault naming complaint.
        if (!v.matches(Regex("[a-z0-9][a-z0-9_-]{0,48}"))) {
            throw PluginError(
                "bad_request",
                "connection name must be lowercase [a-z0-9_-], 1-49 chars, starting alphanumeric"
            )
        }
        return v
    }

    override fun status(): PluginStatus {
        val namesSnapshot = try {
            SecretVault.names(ctx).filter { it.startsWith("ssh-") }
        } catch (e: Exception) {
            emptyList()
        }
        val count = namesSnapshot.size
        // **Name the connections, not just the count.**
        //
        // Mido, 2026-11-04: *"ssh still shown number of connections but no connection details"*.
        // A bare count is the one summary that cannot be acted on: you cannot tell which host a
        // call would reach, or whether the one you added is the one that failed to save.
        //
        // The names come from the vault's own index, which is plaintext while the vault is locked
        // — so this list is readable at the moment you most need it, right after a call refused.
        // Nothing secret is exposed: the names are `ssh-<user>@<host>` labels they typed.
        return if (count == 0) {
            PluginStatus("SSH", PluginStatus.State.OFF, "no connections configured")
        } else {
            val names = namesSnapshot.mapNotNull { it.removePrefix("ssh-").takeIf { n -> n.isNotEmpty() } }
            val shown = names.take(4).joinToString(", ")
            val extra = if (names.size > 4) " +${names.size - 4} more" else ""
            PluginStatus(
                "SSH",
                PluginStatus.State.OK,
                if (shown.isEmpty()) "$count connection(s)" else "$shown$extra",
            )
        }
    }
}
