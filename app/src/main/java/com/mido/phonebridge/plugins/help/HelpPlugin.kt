package com.mido.phonebridge.plugins.help

import android.content.Context
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.BuildConfig
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.core.ErrorCodes
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.notify.NotifyGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * `GET /help` — what this bridge is, and everything it can do.
 *
 * **This is the agent's entry point, and its update mechanism.** An agent calls it once, learns
 * the route table, the error vocabulary and which services are configured, and notes
 * [CONTRACT_VERSION]. When `/help` later reports a different version, the agent re-reads it — the
 * endpoint IS the update, so there is no changelog to parse and nothing to reconcile.
 *
 * **A normal gated route, not an exemption.** It ships with a seeded `help/ANY → NOTIFY` rule, so
 * a first call costs a 5-second card rather than a full-screen modal — a downgrade, never a
 * bypass. The human can see and change that rule on the exceptions screen.
 *
 * **Two renderers, one source.** The JSON fields and the `markdown` string are built from the same
 * objects in the same pass, so the prose cannot drift from the data. The markdown is a FIELD, not
 * a second response shape: one route, one envelope, so there is no format parameter to discover
 * and no second path to test. A caller that wants the raw text does `jq -r .markdown`.
 *
 * **Markdown carries FACTS, never advice.** "`denied` — no approval was recorded" is a fact about
 * the code. "Do not retry a `denied`" is judgment, and judgment lives in the agent skill. Keeping
 * that line clean is what stops this route from becoming a second, diverging skill.
 *
 * **What is deliberately absent:** token values (the metadata path never reads them — structural,
 * not a filter), and the gate's current state. Capability is describable; policy is not.
 */
class HelpPlugin(private val ctx: Context) : Plugin {

    override val id = "help"
    override val displayName = "Help"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/help", "Describe this bridge and everything it can do", Verb.READ,
            // No AI: describing the bridge must not depend on the model being provisioned, and
            // the whole point of /help is to work on a fresh, unconfigured install.
            aiSummary = false,
            // The response is derived entirely from the running build, so storing it would put
            // the same description in the log on every call. The record still keeps outcome,
            // status and summary.
            auditResponseBody = false,
        ) { respond(it) }
    )

    override fun status(): PluginStatus = PluginStatus(
        "Help",
        PluginStatus.State.OK,
        "GET /help — ${PluginRegistry.all().size} plugin(s), contract v$CONTRACT_VERSION"
    )

    private fun respond(req: Req): Resp {
        val json = describe()
        // ONE response shape. There used to be a `?format=md` branch returning `text/markdown`,
        // and it was removed: the markdown is already a field here, so the parameter bought a
        // Content-Type and nothing else — at the price of two shapes on one route, which is
        // where a bug hides and the path nobody tests as often. A caller that wants the raw text
        // does `jq -r .markdown`, which is a one-liner rather than a second code path.
        // **Copy every key, never an enumerated list.** This used to name four keys by hand, so
        // `sshConnections` — added to `describe()` later — was silently dropped from the response
        // while still appearing in the markdown. The prose and the data disagreed, which is
        // exactly the drift one builder is supposed to prevent: the builder was right and the
        // hand-written copy was not.
        return Resp(200, JSONObject().put("ok", true).also { out ->
            for (key in json.keys()) out.put(key, json.get(key))
        })
    }

    /**
     * The one builder. Every field below is read from the running app — nothing is restated from
     * memory or from a document, which is why `/help` cannot describe a route this build does not
     * have.
     */
    private fun describe(): JSONObject {
        val stmp = JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appBuild", BuildConfig.VERSION_CODE)
            .put("contractVersion", CONTRACT_VERSION)
            .put("baseUrl", "http://${BridgeService.bindAddress}:${BridgeService.PORT}")
            .put(
                "gate",
                JSONObject()
                    .put("modes", JSONArray(listOf("AUTOMATIC", "SECURE", "NOTIFY")))
                    .put("secureWindowSeconds", ApprovalGate.TIMEOUT_SECONDS)
                    .put("notifyWindowSeconds", NotifyGate.WINDOW_MS / 1000)
            )

        val errors = JSONArray()
        for (code in ErrorCodes.classified().sorted()) {
            errors.put(
                JSONObject()
                    .put("code", code)
                    .put("status", ErrorCodes.statusFor(code))
                    // The class is the 4xx/5xx line that already decides noise: "caller" faults
                    // are the caller's to fix, "ours" means the phone has a problem.
                    .put("class", if (ErrorCodes.statusFor(code) < 500) "caller" else "ours")
                    .put("meaning", ErrorCodes.meaningOf(code) ?: "")
            )
        }

        val plugins = JSONArray()
        for (plugin in PluginRegistry.all().sortedBy { it.id }) {
            plugins.put(describePlugin(plugin))
        }

        // SSH connections are NOT plugins, so they do not appear in `plugins` above. Without
        // this block a caller has to GUESS a connection name — and a wrong guess is
        // `404 not_found`, which reads as "the route is broken" rather than "you used a name
        // that does not exist". Names only: the private key never leaves the vault, and this
        // route is reachable by any verified caller.
        val sshNames = try {
            SecretVault.names(ctx)
                .filter { it.startsWith("ssh-") }
                .map { it.removePrefix("ssh-") }
                .sorted()
        } catch (e: Exception) {
            // An unreadable index is not "no connections": say so rather than print an empty
            // list that reads as a complete answer.
            emptyList()
        }
        val ssh = JSONArray(sshNames)

        val out = JSONObject()
            .put("stmp", stmp)
            .put("errors", errors)
            .put("plugins", plugins)
            .put("sshConnections", ssh)
        out.put("markdown", markdown(stmp, errors, plugins, ssh))
        return out
    }

    private fun describePlugin(plugin: Plugin): JSONObject {
        val o = JSONObject().put("id", plugin.id)
        // A secret plugin is identified by a matching vault entry, not by a flag on the Plugin
        // interface — that is the honest source, since the vault is what decides the routing.
        val secretNames = try {
            SecretVault.names(ctx)
        } catch (e: Exception) {
            emptyList()
        }
        if (plugin.id in secretNames) {
            o.put("kind", "secret")
            val meta = try {
                SecretVault.meta(ctx, plugin.id)
            } catch (e: Exception) {
                null
            }
            if (meta != null) {
                o.put("docs", meta.docs)
                o.put("key", meta.key)
                // Hosts are here because `host_not_allowed` is refused PRE-GATE: a caller that
                // knows the allowlist can check before spending a tap, which turns a flat
                // refusal into a decision.
                o.put("hosts", JSONArray(meta.hosts))
            }
        } else {
            o.put("kind", "builtin")
        }

        val routes = JSONArray()
        for (r in plugin.routes()) {
            val v = JSONObject()
                .put("method", r.method)
                .put("path", r.path)
                .put("describe", r.describe)
                // The broker derives its verb from the outbound method, so its static value is
                // a fallback rather than an answer. Saying so beats printing EXECUTE for a call
                // that is actually a READ.
                .put("verb", if (r.verbFor != null) "READ|WRITE|EDIT|DELETE|EXECUTE" else r.verb.name)
                .put("gated", !r.gateExempt)
            routes.put(v)
        }
        o.put("routes", routes)
        return o
    }

    /**
     * Render the same facts as prose.
     *
     * Built from the same [JSONObject]s the caller gets, so a field added above appears here
     * automatically or not at all — there is no second place to forget.
     */
    private fun markdown(
        stmp: JSONObject,
        errors: JSONArray,
        plugins: JSONArray,
        ssh: JSONArray,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# STMP ${stmp.getString("appVersion")} (build ${stmp.getInt("appBuild")})")
        sb.appendLine()
        sb.appendLine("An Android phone acting as an HTTP server for an agent, with a human approval")
        sb.appendLine("gate on every call. Base URL: `${stmp.getString("baseUrl")}`")
        sb.appendLine()
        sb.appendLine("Contract version: **${stmp.getInt("contractVersion")}**. Note this number; if a")
        sb.appendLine("later `GET /help` reports a different one, re-read this page and treat it as current.")
        sb.appendLine()

        val gate = stmp.getJSONObject("gate")
        sb.appendLine("## How calls are gated")
        sb.appendLine()
        sb.appendLine("- **SECURE** — a full-screen modal, ${gate.getLong("secureWindowSeconds")}s to answer.")
        sb.appendLine("- **NOTIFY** — a small card, ${gate.getLong("notifyWindowSeconds")}s. Silence approves.")
        sb.appendLine("- **AUTOMATIC** — resolves per `plugin × verb` rule; with no rule it means SECURE.")
        sb.appendLine()
        sb.appendLine("A route with `gated: true` waits for a human; one with `gated: false` runs immediately.")
        sb.appendLine()

        sb.appendLine("## Errors")
        sb.appendLine()
        sb.appendLine("| code | status | class | meaning |")
        sb.appendLine("|---|---|---|---|")
        for (i in 0 until errors.length()) {
            val e = errors.getJSONObject(i)
            sb.appendLine(
                "| `${e.getString("code")}` | ${e.getInt("status")} | ${e.getString("class")} | " +
                    "${e.getString("meaning")} |"
            )
        }
        sb.appendLine()

        sb.appendLine("## Routes")
        sb.appendLine()
        for (i in 0 until plugins.length()) {
            val p = plugins.getJSONObject(i)
            val kind = p.getString("kind")
            sb.appendLine("### ${p.getString("id")} ($kind)")
            sb.appendLine()
            if (kind == "secret") {
                if (p.has("docs")) sb.appendLine("- docs: ${p.getString("docs")}")
                if (p.has("key")) sb.appendLine("- key: ${p.getString("key")}")
                if (p.has("hosts")) {
                    val hosts = p.getJSONArray("hosts")
                    val list = (0 until hosts.length()).joinToString(", ") { hosts.getString(it) }
                    sb.appendLine("- allowed hosts: ${if (list.isBlank()) "(none)" else list}")
                }
                sb.appendLine()
            }
            val routes = p.getJSONArray("routes")
            for (j in 0 until routes.length()) {
                val r = routes.getJSONObject(j)
                val gated = if (r.getBoolean("gated")) "gated" else "no gate"
                sb.appendLine(
                    "- `${r.getString("method")} ${r.getString("path")}` — " +
                        "${r.getString("verb")} · ${r.getString("describe")} · $gated"
                )
            }
            sb.appendLine()
        }

        if (ssh.length() > 0) {
            sb.appendLine("## SSH connections")
            sb.appendLine()
            sb.appendLine("Use the name as `{name}` in `POST /ssh/{name}`. Names only — the")
            sb.appendLine("private key never leaves the phone.")
            sb.appendLine()
            for (i in 0 until ssh.length()) {
                sb.appendLine("- `${ssh.getString(i)}`")
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd() + "\n"
    }

    companion object {
        /**
         * The version of the AGENT-FACING surface, moved independently of the app.
         *
         * Bump this when a caller's existing understanding becomes wrong: a route added or
         * removed, an error code added, gate semantics changed. Do NOT bump it for cosmetic or
         * internal releases — the whole point is that an agent can cache its understanding across
         * builds that did not change what it can do.
         *
         * **Guarded by a test**, because a number nobody checks is a number that is wrong. The
         * suite pins the route set against this build; changing a route without bumping fails.
         */
        const val CONTRACT_VERSION = 4
    }
}
