package com.mido.phonebridge.plugins.secrets

import android.content.Context
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Verb

/**
 * One plugin per secret (plan D7/D8).
 *
 * The secret NAME is the plugin id, so the rules screen reads `todoist × EXECUTE`
 * with no change to `Rule`, `parse`, `resolve` or the policy UI. The route is
 * `POST /secrets/{name}/call` with a FIXED verb — the outbound HTTP method is just
 * data in the body, so the caller never mentions a verb and no method→verb
 * inference exists anywhere.
 *
 * The plugin is thin on purpose: all the security lives in [SecretBroker] (host
 * allowlist, injection, scrubbing) and [SecretVault] (the secret at rest). This
 * class only declares the route, describes the card, and wires the two together.
 */
class SecretPlugin(private val ctx: Context, private val secretName: String) : Plugin {

    override val id: String = secretName
    override val displayName: String = "Secret: $secretName"

    override fun routes(): List<Route> = listOf(
        Route(
            "POST",
            "/secrets/$secretName/call",
            "Call an API with the '$secretName' secret",
            // FALLBACK verb, used only if `verbFor` returns null. It stays EXECUTE because
            // that is what an unrecognised method genuinely is, and because a route must be
            // classifiable even when its body is unparseable (the draft binds a label before
            // the parse runs).
            Verb.EXECUTE,
            // The real verb comes from the OUTBOUND METHOD in the payload, not the path.
            //
            // This route carries every third-party call, so a fixed EXECUTE made "reads
            // only" inexpressible: policy, the audit label, the exception button and the AI
            // facts all keyed on `todoist/EXECUTE`, and an exception tapped while reading
            // also silenced DELETEs. Mido, 2026-10-19: *"I clicked meaning it as a read only
            // operation."*
            //
            // Mapping is by EFFECT, and errs loud: anything that is not obviously a read is
            // a write, so a POST-based search API stays visible rather than silently
            // becoming approvable-by-silence.
            verbFor = { req -> com.mido.phonebridge.core.OutboundVerb.of(req.body.optString("method", "GET")) },
            // Validate BEFORE the gate: a request with no URL, a non-https URL, or a
            // host that is not on the allowlist can never succeed, so it must not cost
            // a tap. Only the shape and the host are checked here; the secret itself
            // is not touched until after approval.
            validate = { req -> validate(req) },
            // The broker spends a stored credential, and its card names the target from plaintext
            // metadata — but the VALUE it will send is in the vault. Declared so a locked vault
            // prompts instead of failing after the human has already approved.
            needsVault = true
        ) { req -> call(req) }
    )

    /**
     * The card must name the target even though this is a POST that reads as often as
     * it writes — and the app's `rowsFor` convention renders GET with NO rows. The
     * URL *is* the card here, so this implements `describeRows` DIRECTLY rather than
     * routing through `rowsFor` (which would either throw on an unlisted route or
     * produce an empty approval the human cannot judge).
     */
    override fun describeRows(req: Req): List<ApprovalRow> {
        val url = req.body.optString("url", "(no url)")
        val method = req.body.optString("method", "GET").uppercase()
        val rows = mutableListOf(
            // **The friendly name leads.** `todoist` is what the human named this credential and
            // what they recognise; `POST https://api.todoist.com/…` is the detail. Reporting the
            // URL first made the reader work out which secret was about to be spent, and on a
            // scrolled list of approvals that is exactly the confusion the card exists to prevent.
            ApprovalRow("SECRET", secretName, ApprovalRow.Style.NORMAL),
            ApprovalRow("TARGET", "$method $url", ApprovalRow.Style.MONO)
        )
        // The QUERY is part of what is being approved, and for a read it is often the ONLY
        // substantive content: a filtered list, a project scope, a search term. Without it the
        // modal says "read todoist api" and the human has nothing to judge — reported on device
        // 2026-10-19: *"says read todoist api both times, absolutely useless"*.
        req.body.optJSONObject("query")?.let { q ->
            val parts = buildList {
                for (k in q.keys()) {
                    val v = q.optString(k, "")
                    if (v.isNotBlank()) add("$k=$v")
                }
            }
            if (parts.isNotEmpty()) {
                rows.add(ApprovalRow("QUERY", parts.joinToString(", "), ApprovalRow.Style.MONO))
            }
        }
        // Name the host explicitly: it is the security boundary, so the human should
        // see the decision they are making about where the secret goes.
        val host = runCatching { java.net.URL(url).host }.getOrNull()
        if (host != null) rows.add(ApprovalRow("HOST", host, ApprovalRow.Style.WARN))
        val body = req.body.optString("body", "")
        if (body.isNotEmpty()) {
            // ADDITIVE, not a replacement: BODY still shows the payload, and PAYLOAD states its
            // shape as its own row. Mido, 2026-10-19: *"the itemadd x 8 thing should be an
            // addition to the context not a replacement"*. Both facts matter — the raw payload
            // is what gets sent, the shape is what a human (and the summariser) can actually
            // reason about. Putting the shape in the summary line instead displaced it.
            com.mido.phonebridge.core.PayloadShape.of(body)?.let {
                rows.add(ApprovalRow("PAYLOAD", it, ApprovalRow.Style.NORMAL))
            }
            rows.add(ApprovalRow("BODY", body, ApprovalRow.Style.LONG))
        }
        return rows
    }

    override fun status(): PluginStatus {
        // The docs link rides on the card: it is the answer to "what does this API do",
        // and it is plaintext so showing it costs nothing.
        val docs = try {
            SecretVault.meta(ctx, secretName).docs
        } catch (e: Exception) {
            ""
        }
        val detail = if (docs.isEmpty()) "POST /secrets/$secretName/call"
        else "POST /secrets/$secretName/call · $docs"
        return PluginStatus("Secret: $secretName", PluginStatus.State.OK, detail)
    }

    private fun validate(req: Req) {
        val url = req.body.optString("url", "").trim()
        if (url.isEmpty()) throw PluginError("bad_request", "missing 'url'")
        val parsed = try {
            java.net.URL(url)
        } catch (e: Exception) {
            throw PluginError("bad_request", "url is not parseable: ${e.message}")
        }
        if (parsed.protocol != "https") {
            throw PluginError("bad_request", "only https is allowed, got '${parsed.protocol}'")
        }
        val host = parsed.host.lowercase()
        if (!HostAllowlist.allows(hostsFor(), host)) {
            throw PluginError(
                "host_not_allowed",
                "host '$host' is not allowed for secret '$secretName'; add it in the app's Secrets screen"
            )
        }
    }

    /**
     * This secret's own allowed hosts. Plaintext, so this works while the vault is
     * locked — which is required: the pre-gate check must be able to answer
     * "host not allowed" without decrypting anything.
     */
    private fun hostsFor(): List<String> = try {
        SecretVault.meta(ctx, secretName).hosts
    } catch (e: Exception) {
        // An unreadable index must refuse, not fall open to an empty list — an empty
        // list already means "nothing allowed", but the reason differs and must be said.
        throw PluginError("vault_corrupt", "could not read hosts for '$secretName': ${e.message}")
    }

    private fun call(req: Req): Resp {
        // Read the secret only now: the gate has already approved, and this is the one
        // place the vault is consulted. A locked vault fails loud with `secret_locked`
        // — never a prompt from here (a plugin rule cannot raise a modal).
        val secret = SecretVault.get(ctx, secretName)
        val result = SecretBroker.call(ctx, secret, hostsFor(), req.body)
        Debug.log("broker: ${req.body.optString("method", "GET")} ${req.body.optString("url")} -> ${result.optInt("status")}")
        return Resp(200, result)
    }
}
