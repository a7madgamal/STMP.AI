package com.mido.phonebridge.plugins.oauth

import android.content.Context
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.OutboundVerb
import com.mido.phonebridge.core.ParamSpec
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.StmpFolder
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.plugins.files.FilesStore
import com.mido.phonebridge.plugins.secrets.HostAllowlist
import com.mido.phonebridge.plugins.secrets.SecretBroker
import com.mido.phonebridge.policy.Mode
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Calls an API that authenticates with an OAuth2 access token, without the credentials or the token
 * ever leaving the phone.
 *
 * **The shape is the JWT plugin's, because the capability is identical:** hold a credential, mint a
 * bearer, make the gated call. Only the minting differs — a JWT is signed locally, an access token
 * is exchanged at a provider's token endpoint. So everything that makes this safe is the same code:
 * the per-credential host allowlist, the refusal to follow redirects with a credential attached,
 * the timeouts, and the scrubbing of the injected value out of the response come from
 * [SecretBroker], not from a second implementation that could drift.
 *
 * **There is deliberately no route that returns a token.** An access token is a live bearer
 * credential. Handing one back would let a caller use it outside the gate, which makes the vault
 * decorative: the point is that the call is approved, not that the caller is issued a credential.
 *
 * **Three routes, and the third is the one that makes store submission possible:**
 * - `GET /oauth` — names and public config. No values. PARANOID floor.
 * - `POST /oauth/{name}/call` — JSON, `***` becomes `Authorization: Bearer …`.
 * - `POST /oauth/{name}/upload` — streams a file from this plugin's OWN granted folder as the
 *   outbound body, raw or multipart.
 *
 * **Why `upload` does not go through the broker.** It streams a body, which is precisely the thing
 * the broker's JSON path cannot do. So every property the broker would have given for free is
 * re-established here explicitly — see [upload] — because silently reusing a route shape as if it
 * carried the guarantees over is how a security property gets lost.
 */
class OauthPlugin(
    private val ctx: Context,
    private val name: String,
) : Plugin {

    override val id: String = "oauth:$name"
    override val displayName: String = "OAuth: $name"

    /** This plugin's own SAF grant, over the folder the packages are in. Never another plugin's. */
    private val store = FilesStore(ctx, grantPrefs = GRANT_PREFS)

    override fun routes(): List<Route> = listOf(
        Route(
            "POST",
            "/oauth/$name/call",
            "Call an API as the '$name' OAuth credential",
            // FALLBACK verb for a body too broken to name a method. EXECUTE is the honest reading of
            // "an outbound call of unknown effect", and a route must be classifiable before its body
            // parses.
            Verb.EXECUTE,
            // The real verb comes from the OUTBOUND method, exactly as the secret broker does it, so
            // "reads only" is expressible.
            verbFor = { req -> OutboundVerb.of(req.body.optString("method", "GET")) },
            // Input that can never succeed must not cost a human a tap. Shape and host only — the
            // vault is not touched until after approval.
            validate = { req -> validateCall(req) },
            // The access token is minted from a vault-held secret. Declaring this makes dispatch
            // unlock BEFORE it builds rows, so a locked vault is a fingerprint prompt rather than an
            // instant `secret_locked` after the human already approved.
            needsVault = true,
        ) { req -> call(req) },

        Route(
            "POST",
            "/oauth/$name/upload",
            "Upload a file as the '$name' OAuth credential",
            // Always a WRITE: an upload adds bytes to someone else's service. There is no method to
            // derive it from — the route decides the method, not the caller.
            Verb.WRITE,
            params = listOf(
                ParamSpec(
                    "path",
                    "File name inside this plugin's granted folder. No separators.",
                    required = true,
                ),
                ParamSpec("url", "The https upload endpoint. Its host must be on the allowlist.", required = true),
                ParamSpec(
                    "field",
                    "Multipart part name for the file. OMIT for a raw body (Chrome Web Store); " +
                        "SET it for multipart (AMO).",
                ),
                ParamSpec(
                    "fields",
                    "Extra multipart text parts as a JSON object, e.g. {\"channel\":\"listed\"}. " +
                        "Only valid together with 'field'.",
                ),
            ),
            // The body IS the file: never buffered (a 200 MiB body in a String is an OOM) and never
            // audited (its first 32 KiB would be copied permanently into an append-only log).
            auditRequestBody = false,
            auditResponseBody = false,
            aiSummary = false,
            validate = { req -> validateUpload(req) },
            needsVault = true,
        ) { req -> upload(req) }
    )

    /**
     * The card for a call: which credential, where the request goes, and where the token lands.
     *
     * Implemented rather than left to `rowsFor`, because this is a POST whose rows matter for reads
     * as much as writes: a GET through this route still needs a target on the card.
     */
    override fun describeRows(req: Req): List<ApprovalRow> {
        val url = req.body.optString("url", "(no url)")
        val method = req.body.optString("method", "GET").uppercase()
        val rows = mutableListOf<ApprovalRow>()

        rows.add(ApprovalRow("CREDENTIAL", name, ApprovalRow.Style.NORMAL))

        // The client id decides WHOSE application this call is attributed to, so it belongs on the
        // card rather than in the human's memory.
        try {
            val entry = OauthStore.get(ctx, name)
            rows.add(ApprovalRow("CLIENT", entry.clientId.ifEmpty { "(no client id)" }, ApprovalRow.Style.MONO))
            rows.add(ApprovalRow("TOKEN FROM", entry.tokenUrl, ApprovalRow.Style.MONO))
        } catch (e: Exception) {
            rows.add(ApprovalRow("CONFIG", "unreadable: ${e.message}", ApprovalRow.Style.WARN))
        }

        rows.add(ApprovalRow("TARGET", "$method $url", ApprovalRow.Style.MONO))
        runCatching { URL(url).host }.getOrNull()?.let {
            rows.add(ApprovalRow("HOST", it, ApprovalRow.Style.WARN))
        }

        rows.add(
            ApprovalRow(
                "TOKEN",
                if (SecretBrokerPlaceholder.present(req.body)) {
                    "inserted where you wrote *** — check the placement below"
                } else {
                    "sent as the Authorization: Bearer header"
                },
                ApprovalRow.Style.NORMAL,
            )
        )
        return rows
    }

    override fun status(): PluginStatus {
        val entry = runCatching { OauthStore.get(ctx, name) }.getOrNull()
        return if (entry == null) {
            PluginStatus(displayName, PluginStatus.State.ERROR, "config unreadable — open the OAuth screen")
        } else {
            PluginStatus(
                displayName,
                PluginStatus.State.OK,
                "POST /oauth/$name/call · ${entry.authStyle} auth · ${entry.tokenUrl}"
            )
        }
    }

    // --- call -----------------------------------------------------------------

    /**
     * Runs only after an approval.
     *
     * Order: config, then secret, then token, then the call. Each failure names itself rather than
     * surfacing later as an upstream 401 — a missing config and a revoked refresh token both end in
     * "unauthorized" at the far end, and that is exactly where the explanation is missing.
     */
    private fun call(req: Req): Resp {
        val entry = OauthStore.get(ctx, name)
        val secret = OauthSecret.fromVault(SecretVault.get(ctx, OauthStore.vaultName(name)))

        val token = OauthTokenCache.tokenFor(name) { OauthMinter.mint(entry, secret) }

        val body = withToken(req.body, token.accessToken)
        val hosts = SecretVault.meta(ctx, OauthStore.vaultName(name)).hosts

        Debug.log("oauth '$name': ${req.body.optString("method", "GET")} ${req.body.optString("url", "")}")

        val result = SecretBroker.call(ctx, token.accessToken, hosts, body)

        // Which token was used, never the token. A caller debugging a 401 needs to know whether a
        // cached token expired in flight.
        result.put(
            "oauth",
            JSONObject()
                .put("credential", name)
                .put("clientId", entry.clientId)
                .put("tokenExpiresAt", token.expiresAtSec)
        )
        return Resp(200, result)
    }

    /**
     * Put the token where the caller asked for it, defaulting to the Authorization header.
     *
     * One rule: if `***` appears anywhere the broker's existing substitution handles it. If it does
     * not, the token goes in an `Authorization: Bearer` header — what every OAuth2 API wants and what
     * a caller should not have to spell out. An `Authorization` the caller set deliberately is never
     * overwritten.
     */
    private fun withToken(body: JSONObject, token: String): JSONObject {
        val out = JSONObject(body.toString())
        if (SecretBrokerPlaceholder.present(body)) return out
        val headers = out.optJSONObject("headers")?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (!headers.has("Authorization")) headers.put("Authorization", "Bearer $token")
        out.put("headers", headers)
        return out
    }

    // --- upload ---------------------------------------------------------------

    /**
     * Stream a file from the granted folder as the outbound body.
     *
     * **Raw and multipart are different verbs for different APIs, not a preference.** Chrome's
     * `:upload` takes the package as the whole request body; AMO's source upload takes it as one
     * part of a multipart form. One route serves both because the choice is expressed by the
     * parameters, and `field` is the whole of it: absent means raw, present means multipart.
     */
    private fun upload(req: Req): Resp {
        val entry = OauthStore.get(ctx, name)
        val secret = OauthSecret.fromVault(SecretVault.get(ctx, OauthStore.vaultName(name)))
        // Minting happens here as well as in `call`: an upload is authenticated the same way, and
        // reusing the cache means a call followed by an upload costs one mint, not two.
        val token = OauthTokenCache.tokenFor(name) { OauthMinter.mint(entry, secret) }

        val file = store.requireName(requireParam(req, "path"))
        val found = store.find(file)
            ?: throw PluginError(
                "file_not_found",
                "'$file' is not in the granted folder (${StmpFolder.PATH}). Grant the folder on the " +
                    "OAuth screen, or check the name."
            )

        val target = requireParam(req, "url")
        val host = requireHttpsHost(target)
        val hosts = SecretVault.meta(ctx, OauthStore.vaultName(name)).hosts
        if (!HostAllowlist.allows(hosts, host)) {
            throw PluginError(
                "host_not_allowed",
                "host '$host' is not allowed for '$name'; add it on the OAuth screen"
            )
        }

        val field = req.query["field"]?.takeIf { it.isNotBlank() }
        val extras = req.query["fields"]?.takeIf { it.isNotBlank() }
        if (field == null && extras != null) {
            throw PluginError(
                "bad_request",
                "'fields' only makes sense with 'field': raw mode sends the file as the whole body, " +
                    "so there is nowhere for text parts to go"
            )
        }

        val boundary = if (field != null) "----stmp" + java.util.UUID.randomUUID().toString().replace("-", "") else null
        val textParts = if (extras != null) parseFields(extras) else emptyList()

        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = UPLOAD_CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_READ_TIMEOUT_MS
            // **Never follow a redirect with a bearer token attached.** The broker refuses this for
            // every call; an upload carries the same token, so the same rule applies.
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${token.accessToken}")
            setRequestProperty("Accept", "application/json")
            if (boundary != null) {
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            } else {
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        }

        var sent = 0L
        try {
            conn.outputStream.use { sink ->
                if (boundary != null) {
                    // Text parts FIRST: a provider that reads the file part and then stops parsing
                    // would otherwise never see `channel`/`license`.
                    for ((k, v) in textParts) {
                        sink.write(
                            "--$boundary\r\nContent-Disposition: form-data; name=\"${k}\"\r\n\r\n$v\r\n"
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                    }
                    sink.write(
                        (
                            "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; " +
                                "filename=\"$file\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                            ).toByteArray(StandardCharsets.UTF_8)
                    )
                }

                ctx.contentResolver.openInputStream(found.uri)?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    val source = BufferedInputStream(input)
                    while (true) {
                        val n = source.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                    }
                } ?: throw PluginError("file_not_found", "the provider refused to open '$file'")

                if (boundary != null) {
                    sink.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                }
                sink.flush()
            }

            val status = conn.responseCode
            if (status in 300..399) {
                throw PluginError(
                    "oauth_upload_refused",
                    "the target answered HTTP $status (a redirect), which is refused: following it " +
                        "would send the bearer token to ${conn.getHeaderField("Location") ?: "another host"}"
                )
            }

            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.let { readBounded(it, MAX_RESPONSE_BYTES) } ?: ""
            val ok = status in 200..299
            if (!ok) Debug.log("oauth '$name': upload of '$file' ($sent bytes) failed HTTP $status")

            val out = JSONObject()
                .put("uploaded", file)
                .put("bytes", sent)
                .put("host", host)
                .put("status", status)
                .put("body", body)
            return Resp(if (ok) 200 else status, out)
        } catch (e: PluginError) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw PluginError(
                "oauth_upload_timeout",
                "the upload did not finish within ${UPLOAD_READ_TIMEOUT_MS / 1000}s after sending " +
                    "$sent bytes. The file may be partly transferred — check the provider before retrying."
            )
        } catch (e: Exception) {
            throw PluginError(
                "oauth_upload_failed",
                "upload failed after $sent bytes: ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            conn.disconnect()
        }
    }

    /** `fields` is a small JSON object of text parts. */
    private fun parseFields(raw: String): List<Pair<String, String>> {
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw PluginError("bad_request", "'fields' must be a JSON object of text parts")
        }
        return o.keys().asSequence().map { it to o.getString(it) }.toList()
    }

    // --- validation -----------------------------------------------------------

    private fun validateCall(req: Req) {
        val host = requireHttpsHost(req.body.optString("url", "").trim())
        val hosts = try {
            SecretVault.meta(ctx, OauthStore.vaultName(name)).hosts
        } catch (e: Exception) {
            throw PluginError("oauth_bad_config", "cannot read '$name' hosts: ${e.message}")
        }
        if (!HostAllowlist.allows(hosts, host)) {
            throw PluginError(
                "host_not_allowed",
                "host '$host' is not allowed for '$name'; add it on the OAuth screen"
            )
        }
        // A config that cannot mint is a guaranteed failure, and a human's attention is the scarce
        // resource this app exists to protect.
        OauthStore.get(ctx, name)
    }

    private fun validateUpload(req: Req) {
        // The grant is checked BEFORE the card: a request that can never succeed must not cost a tap.
        try {
            store.root()
        } catch (e: FilesStore.NotGranted) {
            throw PluginError(
                "no_folder_granted",
                "no folder has been granted for uploads. Open the OAuth screen and grant " +
                    "${StmpFolder.PATH}, then retry. Nothing was sent."
            )
        }
        store.requireName(requireParam(req, "path"))
        requireHttpsHost(requireParam(req, "url"))
        val hosts = try {
            SecretVault.meta(ctx, OauthStore.vaultName(name)).hosts
        } catch (e: Exception) {
            throw PluginError("oauth_bad_config", "cannot read '$name' hosts: ${e.message}")
        }
        if (!HostAllowlist.allows(hosts, requireHttpsHost(requireParam(req, "url")))) {
            throw PluginError(
                "host_not_allowed",
                "host '${requireHttpsHost(requireParam(req, "url"))}' is not allowed for '$name'"
            )
        }
        OauthStore.get(ctx, name)
    }

    private fun requireHttpsHost(raw: String): String {
        if (raw.isEmpty()) throw PluginError("bad_request", "missing 'url'")
        val parsed = try {
            URL(raw)
        } catch (e: Exception) {
            throw PluginError("bad_request", "url is not parseable: ${e.message}")
        }
        if (parsed.protocol != "https") {
            throw PluginError(
                "bad_request",
                "only https is allowed, got '${parsed.protocol}' — a token over http is a token in the clear"
            )
        }
        return parsed.host.lowercase()
    }

    private fun requireParam(req: Req, key: String): String =
        req.query[key]?.takeIf { it.isNotBlank() }
            ?: throw PluginError("bad_request", "'$key' is required")

    private fun readBounded(stream: java.io.InputStream, max: Int): String {
        BufferedInputStream(stream).use { input ->
            val buf = ByteArray(8 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }

    private companion object {
        /**
         * THIS PLUGIN'S OWN grant preferences.
         *
         * **Not `files_grant`.** Mido, 2026-11-19: *"each plugin should handle its own secrets"* —
         * and its own file grant. Sharing the stored tree URI would mean turning `files` off breaks
         * uploads here, which is the defect that decision was corrected for. The folder NAME is
         * shared ([StmpFolder]); the permission is not.
         */
        const val GRANT_PREFS = "oauth_grant"

        const val UPLOAD_CONNECT_TIMEOUT_MS = 20_000
        const val UPLOAD_READ_TIMEOUT_MS = 300_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

/** Reads whether the caller wrote the broker's placeholder anywhere. */
private object SecretBrokerPlaceholder {
    fun present(body: JSONObject): Boolean {
        val marker = SecretBroker.PLACEHOLDER
        if (body.optString("url", "").contains(marker)) return true
        if (body.optString("body", "").contains(marker)) return true
        body.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) if (h.optString(k, "").contains(marker)) return true
        }
        body.optJSONObject("query")?.let { q ->
            for (k in q.keys()) if (q.optString(k, "").contains(marker)) return true
        }
        return false
    }
}

/**
 * `GET /oauth` — every configured credential's non-secret half.
 *
 * A caller needs the names before it can use one, and a wrong guess is `unknown_oauth` — which reads
 * as "the route is broken" rather than "you used a name that is not configured".
 */
class OauthIndexPlugin(private val ctx: Context) : Plugin {

    override val id = "oauth"
    override val displayName = "OAuth"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/oauth",
            "List every OAuth credential's non-secret configuration",
            Verb.READ,
            // **A tap.** The reply is a map of which accounts this phone can act as: every client id,
            // every token endpoint, every API it may reach.
            minMode = Mode.PARANOID,
        ) { list() }
    )

    private fun list(): Resp {
        val arr = org.json.JSONArray()
        for (n in OauthStore.names(ctx)) {
            val entry = runCatching { OauthStore.get(ctx, n) }.getOrNull()
            if (entry == null) {
                arr.put(JSONObject().put("name", n).put("error", "config unreadable"))
                continue
            }
            val hosts = runCatching {
                SecretVault.meta(ctx, OauthStore.vaultName(n)).hosts
            }.getOrDefault(emptyList())
            arr.put(
                JSONObject()
                    .put("name", n)
                    .put("clientId", entry.clientId)
                    .put("tokenUrl", entry.tokenUrl)
                    .put("authStyle", entry.authStyle)
                    .put("scope", entry.scope)
                    .put("hosts", org.json.JSONArray(hosts))
                    .put("call", "POST /oauth/$n/call")
                    .put("upload", "POST /oauth/$n/upload")
            )
        }
        return Resp(200, JSONObject().put("credentials", arr))
    }

    override fun status(): PluginStatus {
        val names = runCatching { OauthStore.names(ctx) }.getOrElse { emptyList() }
        return if (names.isEmpty()) {
            PluginStatus("OAuth", PluginStatus.State.OK, "no credentials yet — add one on the OAuth screen")
        } else {
            PluginStatus("OAuth", PluginStatus.State.OK, "${names.size} credential(s): ${names.joinToString(", ")}")
        }
    }
}
