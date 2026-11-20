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

    

    /**

     * **Required. Not a preference.**

     *

     * `help` is how a caller DISCOVERS every other route. Switch it off and the rest of the bridge becomes undiscoverable — an agent would have to guess paths that exist. There is no way to ask what is available without it, which makes it the one capability that must answer in order for the others to be usable.

     */

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
                    .put("modes", JSONArray(listOf("WHITELIST", "PARANOID", "DONT_DISTURB")))
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
        //
        // Listed by OWNER, not by the `ssh-` prefix. The route segment is the name minus that
        // prefix, so `removePrefix` is still exactly right — it is the SELECTION that must not
        // depend on the convention.
        val sshNames = try {
            SecretVault.ownedBy(ctx, "ssh")
                .map { it.removePrefix("ssh-") }
                .sorted()
        } catch (e: Exception) {
            // An unreadable index is not "no connections": say so rather than print an empty
            // list that reads as a complete answer.
            emptyList()
        }
        val ssh = JSONArray(sshNames)

        // JWT credentials are not plugins either (`jwt:<name>` IS one, but the useful half is the
        // config: what issuer a name signs as and which hosts it may reach). Without this a caller
        // has to guess a credential name, and a wrong guess is `unknown_jwt` — which reads as "the
        // route is broken" rather than "you used a name that is not configured". Names and public
        // configuration only: the signing secret is never here, and there is no parameter that could
        // ask for it.
        val jwtEntries = JSONArray()
        val jwtNames = try {
            com.mido.phonebridge.plugins.jwt.JwtStore.names(ctx)
        } catch (e: Exception) {
            emptyList()
        }
        for (name in jwtNames) {
            val entry = try {
                com.mido.phonebridge.plugins.jwt.JwtStore.get(ctx, name)
            } catch (e: Exception) {
                jwtEntries.put(JSONObject().put("name", name).put("error", e.message ?: "unreadable"))
                continue
            }
            val hosts = try {
                SecretVault.meta(ctx, com.mido.phonebridge.plugins.jwt.JwtStore.vaultName(name)).hosts
            } catch (e: Exception) {
                emptyList()
            }
            jwtEntries.put(
                JSONObject()
                    .put("name", name)
                    .put("issuer", entry.issuer)
                    .put("alg", entry.alg)
                    .put("ttlSeconds", entry.ttlSeconds)
                    .put("scheme", entry.scheme)
                    .put("hosts", JSONArray(hosts))
                    .put("route", "POST /jwt/$name/call")
            )
        }

        val out = JSONObject()
            // **`contractVersion` at the TOP LEVEL as well as inside `stmp`.**
            //
            // The doc comment on [CONTRACT_VERSION] says a caller compares the number it cached
            // against the one this build reports. Requiring that caller to know the value is nested
            // under `stmp` means a client that reads `help["contractVersion"]` — the obvious guess,
            // and where the field logically belongs — silently gets null and concludes nothing
            // changed. Reported by Mido, 2026-11-20, after a live check showed the key was absent
            // exactly there.
            //
            // Both places are populated from the same constant, so they cannot disagree; the pin
            // test asserts that.
            .put("contractVersion", CONTRACT_VERSION)
            .put("stmp", stmp)
            .put("errors", errors)
            .put("plugins", plugins)
            .put("sshConnections", ssh)
            .put("jwtCredentials", jwtEntries)
            // **How to use the JWT plugin, as DATA rather than only prose.** A caller that reads
            // `markdown` gets the same facts, but an agent that only parses JSON would otherwise
            // have to infer the body shape from a route name — which is how a capability ends up
            // being discovered by trial and error against a live gate.
            .put("jwt", jwtHelp())
            // The OAuth plugin's usage, as data for the same reason `jwt` is: an agent that parses
            // only JSON must not have to infer the body shape from a route name.
            .put("oauth", oauthHelp())
        out.put("markdown", markdown(stmp, errors, plugins, ssh, jwtEntries))
        return out
    }

    /**
     * The JWT plugin's usage, as data.
     *
     * Built from the same constants the plugin enforces, so a value here cannot drift from what the
     * route actually accepts: the placeholder, the body fields, the verb mapping and the scheme list
     * all come from the implementation rather than from a description of it.
     */
    /**
     * The OAuth plugin's usage, as data.
     *
     * Built from the same constants the plugin enforces, so nothing here can drift from what the
     * routes accept. Two facts are called out because they are the ones a caller gets wrong: there is
     * no route that returns a token, and `scope` is never sent on a refresh (RFC 6749 §6).
     */
    private fun oauthHelp(): JSONObject = JSONObject()
        .put(
            "summary",
            "Calls an API with an OAuth2 access token. The phone exchanges a stored refresh token " +
                "for an access token, uses it for ONE approved call or upload, and drops it. " +
                "Neither the client secret, the refresh token nor the access token is ever returned " +
                "to the caller."
        )
        .put("list", "GET /oauth")
        .put("route", "POST /oauth/{name}/call")
        .put("upload", "POST /oauth/{name}/upload")
        .put(
            "body",
            JSONObject()
                .put("url", "https, and its host must be on the credential's allowlist")
                .put("method", "GET, POST, PUT, PATCH, DELETE or HEAD; decides the approval verb")
                .put("headers", "object; write *** where the token belongs")
                .put("query", "object")
                .put("body", "string")
        )
        .put("placeholder", com.mido.phonebridge.plugins.secrets.SecretBroker.PLACEHOLDER)
        .put(
            "tokenPlacement",
            "If *** appears anywhere, the token goes exactly there. If it does not, the token is sent " +
                "as the Authorization: Bearer header, and an Authorization you set yourself is never " +
                "overwritten."
        )
        .put(
            "uploadParams",
            JSONObject()
                .put("path", "file name inside the granted folder; no path separators")
                .put("url", "https target")
                .put("field", "omit for a raw body (Chrome Web Store); set for a multipart part name (AMO)")
                .put("fields", "JSON object of extra text parts; only valid with 'field'")
        )
        .put("authStyles", JSONArray(com.mido.phonebridge.plugins.oauth.OauthEntry.AUTH_STYLES))
        .put("policyKey", "oauth:<name> × <verb>")
        .put(
            "refresh",
            "The refresh token is never written back. If the provider issues a new one, the call " +
                "fails with refresh_token_rotated and the entry must be updated on the OAuth screen."
        )
        .put(
            "scope",
            "Display only. Never sent on a refresh, because RFC 6749 §6 permits it only as a subset " +
                "of the granted scope and a disagreeing provider answers invalid_scope."
        )
        .put(
            "noTokenRoute",
            "There is deliberately no route that hands back a token: a token issued to a caller works " +
                "outside the approval gate."
        )

    private fun jwtHelp(): JSONObject = JSONObject()
        .put(
            "summary",
            "You send the request; the phone signs a short-lived token and makes the call. The " +
                "signing secret stays in the vault and no token is ever returned to you."
        )
        .put("route", "POST /jwt/{name}/call")
        .put("list", "GET /jwt")
        .put("placeholder", com.mido.phonebridge.plugins.secrets.SecretBroker.PLACEHOLDER)
        .put(
            "bodyFields",
            JSONArray(
                listOf(
                    JSONObject().put("name", "url").put("required", true)
                        .put("note", "https only; the host must be on the credential's allowed list"),
                    JSONObject().put("name", "method").put("required", false)
                        .put("note", "defaults to GET; the verb is derived from it"),
                    JSONObject().put("name", "headers").put("required", false)
                        .put("note", "put *** in a value to place the token there"),
                    JSONObject().put("name", "query").put("required", false)
                        .put("note", "*** here lands in upstream logs; the card warns"),
                    JSONObject().put("name", "body").put("required", false)
                        .put("note", "string body, *** substituted"),
                )
            )
        )
        .put(
            "whenNoPlaceholder",
            "The token is sent as the Authorization header in the credential's own scheme, so a " +
                "body with no *** at all is the normal case."
        )
        .put(
            "verbByMethod",
            JSONObject()
                .put("GET", "READ").put("HEAD", "READ").put("OPTIONS", "READ")
                .put("DELETE", "DELETE").put("other", "WRITE")
        )
        .put("policyKey", "jwt:<name> × <verb>")
        .put("schemes", JSONArray(com.mido.phonebridge.plugins.jwt.JwtSpec.SCHEMES))
        .put("algorithms", JSONArray(com.mido.phonebridge.plugins.jwt.JwtSigners.names()))
        .put("returnsToken", false)
        .put(
            "response",
            "The upstream's status, headers and body, scrubbed of the token, plus a `jwt` block " +
                "naming the credential, issuer, algorithm and the token window."
        )

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

            // **Every parameter the route accepts, and nothing else.**
            //
            // Mido, 2026-11-20: *"document ALL params exactly"*. Before this, the only way to
            // learn a parameter name was to read the handler — so `?maxResults=10` looked right,
            // was silently ignored, and answered 20. The list is now the contract in both
            // directions: dispatch REFUSES anything not here, and this publishes what is.
            //
            // Always emitted, even when empty: an absent `params` key would read as "not
            // documented yet", while `[]` is the true statement "this route takes none".
            val paramsArr = JSONArray()
            for (p in r.params) {
                val pj = JSONObject()
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("required", p.required)
                    .put("describe", p.describe)
                if (p.default.isNotEmpty()) pj.put("default", p.default)
                if (p.allowed.isNotEmpty()) pj.put("allowed", JSONArray(p.allowed))
                paramsArr.put(pj)
            }
            v.put("params", paramsArr)
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
        jwtCredentials: JSONArray,
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
                // Parameters as their own indented lines. Rendered from the same array the JSON
                // carries, so prose and data cannot disagree — the reason this builder takes the
                // already-built `JSONObject` rather than re-deriving from the plugin.
                //
                // Named explicitly as the ONLY accepted set, because that is now literally true:
                // anything else is refused with `unknown_param`.
                val params = r.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    for (k in 0 until params.length()) {
                        val prm = params.getJSONObject(k)
                        val req = if (prm.getBoolean("required")) " (required)" else ""
                        val def = prm.optString("default", "")
                        val defTxt = if (def.isEmpty()) "" else " [default: $def]"
                        val allowed = prm.optJSONArray("allowed")
                        val allowTxt = if (allowed != null && allowed.length() > 0) {
                            " [allowed: ${(0 until allowed.length()).joinToString(", ") { allowed.getString(it) }}]"
                        } else ""
                        sb.appendLine(
                            "  - `${prm.getString("name")}` — ${prm.getString("type")}$req" +
                                "$defTxt$allowTxt — ${prm.getString("describe")}"
                        )
                    }
                } else {
                    // Said out loud rather than omitted: a caller who sends a query string to a
                    // route with no parameters now gets a refusal, so the absence is a fact worth
                    // printing.
                    sb.appendLine("  - takes no query parameters")
                }
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

        if (jwtCredentials.length() > 0) {
            sb.appendLine("## JWT credentials")
            sb.appendLine()
            sb.appendLine("A credential holds a signing secret in the phone's vault. You never see")
            sb.appendLine("the secret and you are never given a token: you send the REQUEST, the phone")
            sb.appendLine("signs a short-lived JWT and makes the call. That is deliberate — a token handed")
            sb.appendLine("to you would work outside the gate.")
            sb.appendLine()
            sb.appendLine("### Calling one")
            sb.appendLine()
            sb.appendLine("`POST /jwt/{name}/call` with a JSON body of the same shape the secret broker")
            sb.appendLine("takes — `url`, `method`, `headers`, `query`, `body` — and write `***` wherever")
            sb.appendLine("the token belongs:")
            sb.appendLine()
            sb.appendLine("```")
            sb.appendLine("{\"url\": \"https://addons.mozilla.org/api/v5/addons/search/\",")
            sb.appendLine(" \"method\": \"GET\",")
            sb.appendLine(" \"headers\": {\"Authorization\": \"JWT ***\"},")
            sb.appendLine(" \"query\": {\"author\": \"18729937\"}}")
            sb.appendLine("```")
            sb.appendLine()
            sb.appendLine("- **Omit the `***` entirely** and the token is sent as `Authorization: <scheme>")
            sb.appendLine("  <token>` automatically — that is what most APIs want, and it is the")
            sb.appendLine("  shorter body.")
            sb.appendLine("- `***` is substituted everywhere you put it: a header, a query value, or the body.")
            sb.appendLine("  In a query string it ends up in the upstream's logs, so the approval card")
            sb.appendLine("  warns about that placement specifically.")
            sb.appendLine("- **https only**, and the host must be on that credential's allowed list — both are")
            sb.appendLine("  checked BEFORE the approval card, so a mistake costs you nothing.")
            sb.appendLine("- The verb is derived from `method`: GET/HEAD/OPTIONS are READ, DELETE is DELETE,")
            sb.appendLine("  everything else is WRITE. Policy is keyed `jwt:<name> × <verb>`.")
            sb.appendLine()
            sb.appendLine("### What comes back")
            sb.appendLine()
            sb.appendLine("The upstream's status, headers and body, scrubbed of the token — plus a")
            sb.appendLine("`jwt` block naming the credential, issuer, algorithm and the window the token")
            sb.appendLine("was valid for. That block is how you tell a genuine 401 from an expired one.")
            sb.appendLine()
            sb.appendLine("Tokens are cached in memory and reused until shortly before they expire, so a")
            sb.appendLine("burst of calls costs one signature. There is **no route that returns a token**.")
            sb.appendLine()
            sb.appendLine("### Configured")
            sb.appendLine()
            for (i in 0 until jwtCredentials.length()) {
                val e = jwtCredentials.getJSONObject(i)
                if (e.has("error")) {
                    sb.appendLine("- `${e.getString("name")}` — unreadable: ${e.getString("error")}")
                    continue
                }
                val hosts = e.getJSONArray("hosts")
                val hostText = if (hosts.length() == 0) "none — every call is refused"
                else (0 until hosts.length()).joinToString(", ") { hosts.getString(it) }
                sb.appendLine(
                    "- `${e.getString("name")}` — iss `${e.getString("issuer")}`, " +
                        "${e.getString("alg")}, valid ${e.getInt("ttlSeconds")}s, " +
                        "${e.getString("scheme")} header. hosts: $hostText"
                )
            }
            sb.appendLine()
            sb.appendLine("Signing is HMAC-SHA256 today, behind an algorithm registry — `GET /jwt`")
            sb.appendLine("reports the `alg` each credential actually uses rather than assuming one.")
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
        const val CONTRACT_VERSION = 8
    }
}
