package com.mido.phonebridge.plugins.jwt

import android.content.Context
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.OutboundVerb
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.plugins.secrets.HostAllowlist
import com.mido.phonebridge.plugins.secrets.SecretBroker
import com.mido.phonebridge.policy.Mode
import org.json.JSONObject

/**
 * Calls an API that authenticates with a signed JWT, without the secret or the token ever leaving
 * the phone.
 *
 * **The shape is the secret broker's, with one substitution.** The caller sends the same body
 * `POST /secrets/{name}/call` takes — `url`, `method`, `headers`, `query`, `body` — and writes
 * `***` where the token belongs. This plugin mints (or reuses) a token and hands the whole thing to
 * [SecretBroker], which is what makes the security properties identical: the per-credential host
 * allowlist, the refusal to follow redirects with a credential attached, the timeouts, and the
 * scrubbing of the credential out of the response are the same tested code rather than a second
 * implementation that could drift.
 *
 * **There is deliberately no route that returns a token.** A minted token is a live bearer
 * credential. Handing one back would mean a caller could use it outside the gate, which would make
 * the vault decorative — the point of this plugin is that the credential stays on the device and the
 * *call* is approved, not that the caller is issued with one.
 *
 * **Which credential is decided by the path, not the body.** `POST /jwt/{name}/call` carries the
 * name, so policy keys on `jwt:<name> × <verb>` and a per-credential rule is expressible — the same
 * reason `SecretPlugin` exists per secret.
 */
class JwtPlugin(
    private val ctx: Context,
    private val name: String,
) : Plugin {

    override val id: String = "jwt:$name"
    override val displayName: String = "JWT: $name"

    override fun routes(): List<Route> = listOf(
        Route(
            "POST",
            "/jwt/$name/call",
            "Call an API as the '$name' JWT credential",
            // FALLBACK verb, used only if `verbFor` returns null — i.e. a body too broken to name a
            // method. EXECUTE is the honest reading of "an outbound call of unknown effect", and a
            // route must be classifiable before its body parses.
            Verb.EXECUTE,
            // The real verb comes from the OUTBOUND method, exactly as the secret broker does it, so
            // "reads only" is expressible: a GET to a JWT API keys policy on READ and cannot be
            // silenced into also permitting a DELETE.
            verbFor = { req -> OutboundVerb.of(req.body.optString("method", "GET")) },
            // Pre-gate: input that can never succeed must not cost a human a tap. Only the SHAPE and
            // the host are checked here; the vault is not touched until after approval.
            validate = { req -> validate(req) },
            // The token is signed with a vault-held secret, and the card names the issuer from
            // plaintext config. Declaring it makes dispatch unlock BEFORE it builds rows, so a locked
            // vault becomes a fingerprint prompt rather than an instant `secret_locked` refusal after
            // the human already approved.
            needsVault = true,
        ) { req -> call(req) }
    )

    /**
     * The card: which credential, what it will sign as, and exactly where the request goes.
     *
     * Implemented directly rather than through `rowsFor`, because this is a POST whose rows exist for
     * BOTH reads and writes — a GET through this route needs a target just as much as a POST does,
     * and the `rowsFor` default of "GET means no rows" would produce an approval the human cannot
     * judge.
     */
    override fun describeRows(req: Req): List<ApprovalRow> {
        val url = req.body.optString("url", "(no url)")
        val method = req.body.optString("method", "GET").uppercase()
        val rows = mutableListOf<ApprovalRow>()

        // The friendly name leads: it is what the human recognises, and the URL is the detail.
        rows.add(ApprovalRow("CREDENTIAL", name, ApprovalRow.Style.NORMAL))

        // The issuer is the thing that decides WHOSE account this call is made against, so it is on
        // the card rather than left to be remembered.
        try {
            val entry = JwtStore.get(ctx, name)
            rows.add(ApprovalRow("ISSUER", entry.issuer, ApprovalRow.Style.MONO))
            rows.add(
                ApprovalRow(
                    "SIGNED",
                    "${entry.alg} · valid ${entry.ttlSeconds}s · sent as ${entry.scheme}",
                    ApprovalRow.Style.NORMAL,
                )
            )
        } catch (e: Exception) {
            // A missing config is not something to hide on the card: it means the call cannot work,
            // and the human should see that BEFORE approving.
            rows.add(ApprovalRow("CONFIG", "unreadable: ${e.message}", ApprovalRow.Style.WARN))
        }

        rows.add(ApprovalRow("TARGET", "$method $url", ApprovalRow.Style.MONO))
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

        val host = runCatching { java.net.URL(url).host }.getOrNull()
        if (host != null) rows.add(ApprovalRow("HOST", host, ApprovalRow.Style.WARN))

        // Where the token actually lands is a security fact, not a detail: the human is being asked
        // about a credential leaving the device, and "in a query string" is materially different from
        // "in a header" because query strings are written into upstream access logs.
        val placement = placementOf(req.body)
        rows.add(
            ApprovalRow(
                "TOKEN",
                when (placement) {
                    Placement.HEADER -> "inserted into the headers you supplied"
                    Placement.QUERY -> "inserted into the query string — this ends up in upstream logs"
                    Placement.BODY -> "inserted into the request body"
                    Placement.DEFAULT_HEADER -> "sent as the Authorization header"
                },
                if (placement == Placement.QUERY) ApprovalRow.Style.WARN else ApprovalRow.Style.NORMAL,
            )
        )

        val body = req.body.optString("body", "")
        if (body.isNotEmpty()) {
            com.mido.phonebridge.core.PayloadShape.of(body)?.let {
                rows.add(ApprovalRow("PAYLOAD", it, ApprovalRow.Style.NORMAL))
            }
            rows.add(ApprovalRow("BODY", body, ApprovalRow.Style.LONG))
        }
        return rows
    }

    override fun status(): PluginStatus {
        val entry = runCatching { JwtStore.get(ctx, name) }.getOrNull()
        return if (entry == null) {
            PluginStatus(
                displayName,
                PluginStatus.State.ERROR,
                "config unreadable — open the JWT screen"
            )
        } else {
            PluginStatus(
                displayName,
                PluginStatus.State.OK,
                "POST /jwt/$name/call · ${entry.alg} · iss ${entry.issuer}"
            )
        }
    }

    // --- handler ------------------------------------------------------------

    /**
     * Runs only after an approval.
     *
     * Order matters here: the config is read, then the secret, then the token. Each failure names
     * itself rather than surfacing later as an upstream 401 — a missing config and a wrong `iss` both
     * end in "unauthorized" at the far end, and that is where the explanation is missing.
     */
    private fun call(req: Req): Resp {
        val entry = JwtStore.get(ctx, name)

        // The vault is open by now: `needsVault` made dispatch unlock before the card. This still
        // throws `secret_locked` if the unlock was declined, which is the right answer.
        val secret = SecretVault.get(ctx, JwtStore.vaultName(name))
        if (secret.isBlank()) {
            throw PluginError("jwt_bad_secret", "the stored secret for '$name' is empty")
        }

        val token = JwtTokenCache.tokenFor(
            ctx, name, entry, secret, System.currentTimeMillis()
        )

        // The bookkeeping body: the caller's own request, with the token available as `***` through
        // the broker's existing placeholder.
        val outbound = bodyWithToken(req.body, token.token, entry.scheme)

        Debug.log(
            "jwt '$name': ${req.body.optString("method", "GET")} " +
                "${req.body.optString("url", "")} (exp ${token.expiresAtSec})"
        )

        // The hosts are the SECRET'S own allowlist — the same list `/secrets` reports and the same
        // one `host_not_allowed` is refused against. A JWT credential with no allowed host can never
        // send its token anywhere, which is the intended default.
        val hosts = SecretVault.meta(ctx, JwtStore.vaultName(name)).hosts
        val result = SecretBroker.call(ctx, token.token, hosts, outbound)

        // State which token was used, without ever including it. A caller debugging a 401 needs to
        // know whether the phone reused a token that expired in flight.
        result.put(
            "jwt",
            JSONObject()
                .put("credential", name)
                .put("issuer", entry.issuer)
                .put("alg", entry.alg)
                .put("issuedAt", token.issuedAtSec)
                .put("expiresAt", token.expiresAtSec)
        )
        return Resp(200, result)
    }

    // --- validation ---------------------------------------------------------

    private fun validate(req: Req) {
        val url = req.body.optString("url", "").trim()
        if (url.isEmpty()) throw PluginError("bad_request", "missing 'url'")

        val parsed = try {
            java.net.URL(url)
        } catch (e: Exception) {
            throw PluginError("bad_request", "url is not parseable: ${e.message}")
        }
        if (parsed.protocol != "https") {
            throw PluginError(
                "bad_request",
                "only https is allowed, got '${parsed.protocol}' — a token over http is a token in the clear"
            )
        }
        val host = parsed.host.lowercase()
        val hosts = try {
            SecretVault.meta(ctx, JwtStore.vaultName(name)).hosts
        } catch (e: Exception) {
            // Metadata is plaintext, so this is not a locked-vault case: the entry itself is wrong.
            throw PluginError("jwt_bad_config", "cannot read '$name' hosts: ${e.message}")
        }
        if (!HostAllowlist.allows(hosts, host)) {
            throw PluginError(
                "host_not_allowed",
                "host '$host' is not allowed for '$name'; add it on the JWT screen"
            )
        }

        // Fail here rather than after the tap: a config that cannot sign is a guaranteed failure, and
        // the human's attention is the scarce resource this whole app is built to protect.
        val entry = JwtStore.get(ctx, name)
        if (!JwtSigners.isSupported(entry.alg)) {
            throw PluginError("jwt_bad_config", "alg '${entry.alg}' is not supported by this build")
        }
    }

    /**
     * The caller's body, with the token made available as `***`.
     *
     * **A placement is chosen once and used everywhere.** If the caller wrote `***` anywhere, the
     * broker's existing substitution handles it and nothing is added — that keeps one rule instead of
     * a second one that could disagree. If they wrote nothing, the token goes into an `Authorization`
     * header in the configured scheme, which is what every JWT API in practice wants and what a
     * caller should not have to spell out.
     */
    private fun bodyWithToken(body: JSONObject, token: String, scheme: String): JSONObject {
        val out = JSONObject(body.toString())
        if (placementOf(body) != Placement.DEFAULT_HEADER) return out

        val headers = out.optJSONObject("headers")?.let { JSONObject(it.toString()) } ?: JSONObject()
        // Never overwrite an Authorization the caller set deliberately; if one is already there the
        // caller has taken responsibility for the placement and this plugin stays out of it.
        if (!headers.has("Authorization")) {
            headers.put("Authorization", "$scheme $token")
        }
        out.put("headers", headers)
        return out
    }

    private fun placementOf(body: JSONObject): Placement {
        val headers = body.optJSONObject("headers")
        if (headers != null) {
            for (k in headers.keys()) {
                if (headers.optString(k, "").contains(SecretBroker.PLACEHOLDER)) return Placement.HEADER
            }
        }
        val query = body.optJSONObject("query")
        if (query != null) {
            for (k in query.keys()) {
                if (query.optString(k, "").contains(SecretBroker.PLACEHOLDER)) return Placement.QUERY
            }
        }
        if (body.optString("body", "").contains(SecretBroker.PLACEHOLDER)) return Placement.BODY
        if (body.optString("url", "").contains(SecretBroker.PLACEHOLDER)) return Placement.QUERY
        return Placement.DEFAULT_HEADER
    }

    private enum class Placement { HEADER, QUERY, BODY, DEFAULT_HEADER }
}

/**
 * `GET /jwt` — every configured credential's non-secret half.
 *
 * **Why this route exists.** A caller needs to know which credential names exist before it can use
 * one, and a wrong guess is `unknown_jwt` — which reads as "the route is broken" rather than "you
 * used a name that is not configured". The same dead end `/secrets` was built to close.
 *
 * **What it returns:** name, issuer, alg, ttl, scheme, docs link and allowed hosts. Not the secret,
 * not a token, and there is no parameter that could ask for either.
 */
class JwtIndexPlugin(private val ctx: Context) : Plugin {

    override val id = "jwt"
    override val displayName = "JWT"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/jwt",
            "List every JWT credential's non-secret configuration",
            Verb.READ,
            // **A tap.** The reply is a map of which accounts this phone can act as: every issuer,
            // every API it may reach. Nothing about diagnosing a broken bridge needs that, and it was
            // quiet by default for `/secrets` before that was corrected — the same reasoning applies
            // one level down.
            minMode = Mode.PARANOID,
        ) { list() }
    )

    override fun status(): PluginStatus {
        val names = try {
            JwtStore.names(ctx)
        } catch (e: Exception) {
            return PluginStatus(
                "JWT",
                PluginStatus.State.ERROR,
                "config unreadable: ${e.message}"
            )
        }
        return if (names.isEmpty()) {
            PluginStatus("JWT", PluginStatus.State.OFF, "no credentials configured")
        } else {
            PluginStatus(
                "JWT",
                PluginStatus.State.OK,
                "${names.size} credential(s): ${names.joinToString(", ")}"
            )
        }
    }

    private fun list(): Resp {
        val names = JwtStore.names(ctx)
        val entries = org.json.JSONArray()
        for (name in names) {
            val entry = try {
                JwtStore.get(ctx, name)
            } catch (e: Exception) {
                // One unreadable record must not hide the rest, but it must not be silently dropped
                // either — it is reported as an entry with an error field so the caller can see it.
                entries.put(
                    JSONObject()
                        .put("name", name)
                        .put("error", e.message ?: "unreadable")
                )
                continue
            }
            val hosts = try {
                SecretVault.meta(ctx, JwtStore.vaultName(name)).hosts
            } catch (e: Exception) {
                emptyList()
            }
            entries.put(
                JSONObject()
                    .put("name", name)
                    .put("issuer", entry.issuer)
                    .put("alg", entry.alg)
                    .put("ttlSeconds", entry.ttlSeconds)
                    .put("scheme", entry.scheme)
                    .put("kid", entry.kid)
                    .put("docs", entry.docs)
                    .put("hosts", org.json.JSONArray(hosts))
                    .put("route", "POST /jwt/$name/call")
            )
        }
        return Resp.ok(JSONObject().put("credentials", entries))
    }
}
