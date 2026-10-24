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

    override fun routes(): List<Route> = listOf(
        Route(
            "POST", "/ssh/{name}", "RUN COMMAND OVER SSH", Verb.EXECUTE,
            // The command is required, and a blank one would open an interactive shell (which
            // hangs). Checked pre-gate so a malformed request costs no tap.
            validate = { r ->
                if (r.body.optString("command").isBlank()) {
                    throw PluginError("bad_request", "missing required 'command'")
                }
            }
        ) { run(it) }
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
                    // The command, verbatim, in the monospace long field. Not truncated in the
                    // modal: a long command that is silently clipped is exactly the "you approved
                    // something you could not read" failure this design must avoid.
                    add(ApprovalRow("COMMAND", command, ApprovalRow.Style.LONG))
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

        val result = SshExec.run(conn, command, timeout, trustFingerprint = trust)

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
        val count = try {
            SecretVault.names(ctx).count { it.startsWith("ssh-") }
        } catch (e: Exception) {
            0
        }
        return if (count == 0) {
            PluginStatus("SSH", PluginStatus.State.OFF, "no connections configured")
        } else {
            PluginStatus("SSH", PluginStatus.State.OK, "$count connection(s)")
        }
    }
}
