package com.mido.phonebridge.plugins.secrets

import android.content.Context
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.PluginError
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The generic secret broker (plan D1–D5, R1–R4).
 *
 * One mechanism, no per-service code: the caller names the URL, the method, the
 * safe params, and writes `***` where the secret goes. The bridge injects the
 * secret, makes the call, and returns a fixed envelope. We do NOT model upstream
 * shapes — we return what came back, minus the secret.
 *
 * Every hard rule here exists because the alternative is trivially exploitable:
 * no redirects with the secret attached (R1), boundary-correct host matching (R2),
 * loopback/private ranges refused (R3), and the secret scrubbed from the ENTIRE
 * response before it is returned or logged (R4).
 */
object SecretBroker {

    /** The placeholder the caller writes where the secret belongs. */
    const val PLACEHOLDER = "***"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000     // plan: fixed 60s, post-tap
    private const val MAX_BODY_BYTES = 512 * 1024

    /**
     * Perform one brokered call. [secret] is the plaintext value, already fetched
     * from the vault by the caller — this function never touches the vault, so the
     * "locked" decision stays in exactly one place.
     */
    fun call(ctx: Context, secret: String, body: JSONObject): JSONObject {
        val rawUrl = body.optString("url", "").trim()
        if (rawUrl.isEmpty()) throw PluginError("bad_request", "missing 'url'")
        val method = body.optString("method", "GET").trim().uppercase()
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            throw PluginError("bad_request", "unsupported method '$method'")
        }

        val url = URL(rawUrl)
        if (url.protocol != "https") {
            throw PluginError("bad_request", "only https is allowed, got '${url.protocol}'")
        }
        val host = url.host.lowercase()
        guardPrivateHost(host)                        // R3
        if (!HostAllowlist.isAllowed(ctx, host)) {    // D3
            throw PluginError(
                "host_not_allowed",
                "host '$host' is not in the allowlist; add it in the app's Secrets screen"
            )
        }

        val headers = body.optJSONObject("headers") ?: JSONObject()
        val query = body.optJSONObject("query") ?: JSONObject()
        val outBody = body.optString("body", "").takeIf { it.isNotEmpty() }

        // Inject: the placeholder is replaced EVERYWHERE the caller put it, so a
        // static header, a templated header ("Bearer ***"), a query param and a body
        // field are all the same rule. No five-way taxonomy, by design.
        val finalUrl = buildUrl(url, query, secret)
        val finalHeaders = HashMap<String, String>()
        for (k in headers.keys()) {
            finalHeaders[k] = headers.getString(k).replace(PLACEHOLDER, secret)
        }
        val finalBody = outBody?.replace(PLACEHOLDER, secret)

        val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // R1: never follow a redirect with the secret attached.
            instanceFollowRedirects = false
            for ((k, v) in finalHeaders) setRequestProperty(k, v)
        }
        if (finalBody != null) {
            conn.doOutput = true
            if (finalHeaders.keys.none { it.equals("Content-Type", true) }) {
                conn.setRequestProperty("Content-Type", "application/json")
            }
        }

        val status: Int
        val contentType: String?
        val rawBody: String
        try {
            if (finalBody != null) conn.outputStream.use { it.write(finalBody.toByteArray()) }
            status = conn.responseCode
            contentType = conn.contentType
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            rawBody = stream?.let { readBounded(it) } ?: ""
        } catch (e: java.net.SocketTimeoutException) {
            throw PluginError("upstream_timeout", "upstream did not answer within ${READ_TIMEOUT_MS / 1000}s")
        } catch (e: Exception) {
            throw PluginError("upstream_failed", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }

        if (status in 300..399) {
            // A redirect is refused rather than followed: following it would carry the
            // secret to a host the allowlist never approved.
            throw PluginError("redirect_refused", "upstream answered $status; redirects are not followed")
        }

        // Non-JSON is refused (resolved edge A). Todoist is JSON; a PDF/image/zip is a
        // second round trip, not a silent base64 blob nobody asked for.
        val isJson = contentType?.contains("json", ignoreCase = true) == true
        if (!isJson) {
            throw PluginError(
                "unsupported_content_type",
                "expected a JSON response, got '${contentType ?: "unknown"}'"
            )
        }

        val parsed = try {
            JSONObject(rawBody)
        } catch (e: Exception) {
            throw PluginError("upstream_failed", "upstream JSON did not parse: ${e.message}")
        }

        // R4: scrub the secret from the WHOLE response before anything sees it.
        val safeHeaders = JSONObject()
        for (k in conn.headerFields.keys) {
            if (k == null) continue
            val v = conn.headerFields[k]?.joinToString(", ") ?: ""
            safeHeaders.put(k, v.replace(secret, PLACEHOLDER))
        }
        val safeBody = scrub(parsed, secret)

        return JSONObject().apply {
            put("ok", true)
            put("status", status)
            put("headers", safeHeaders)
            put("body", safeBody)
        }
    }

    /** Build the final URL, injecting the secret into query values. */
    private fun buildUrl(url: URL, query: JSONObject, secret: String): String {
        val base = url.toString()
        if (query.length() == 0) return base.replace(PLACEHOLDER, secret)
        val sb = StringBuilder(base)
        sb.append(if (base.contains('?')) '&' else '?')
        val parts = mutableListOf<String>()
        for (k in query.keys()) {
            val v = query.getString(k).replace(PLACEHOLDER, secret)
            parts.add("${enc(k)}=${enc(v)}")
        }
        sb.append(parts.joinToString("&"))
        return sb.toString()
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** Recursively replace the secret anywhere it appears in the parsed response. */
    private fun scrub(value: Any?, secret: String): Any? = when (value) {
        is JSONObject -> JSONObject().apply {
            for (k in value.keys()) put(k, scrub(value.get(k), secret))
        }
        is org.json.JSONArray -> org.json.JSONArray().apply {
            for (i in 0 until value.length()) put(scrub(value.get(i), secret))
        }
        is String -> value.replace(secret, PLACEHOLDER)
        else -> value
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val out = StringBuilder()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            val buf = CharArray(8192)
            var n: Int
            var total = 0
            while (r.read(buf).also { n = it } > 0) {
                total += n
                if (total > MAX_BODY_BYTES) {
                    throw PluginError("upstream_failed", "response exceeded ${MAX_BODY_BYTES / 1024} KiB")
                }
                out.append(buf, 0, n)
            }
        }
        return out.toString()
    }

    /**
     * R3: refuse loopback and private ranges unconditionally. The bridge itself lives
     * on 127.0.0.1:8787 and the LAN sits behind the tailnet — a "generic call" must
     * not be able to reach either.
     */
    private fun guardPrivateHost(host: String) {
        val h = host.trim('[', ']')
        val blocked = when {
            h == "localhost" || h == "::1" -> true
            h.startsWith("127.") -> true
            h.startsWith("10.") -> true
            h.startsWith("192.168.") -> true
            h.startsWith("169.254.") -> true
            h.startsWith("172.") -> {
                val second = h.split(".").getOrNull(1)?.toIntOrNull()
                second != null && second in 16..31
            }
            h.endsWith(".local") -> true
            h.endsWith(".internal") -> true
            else -> false
        }
        if (blocked) {
            Debug.log("broker: refused private/loopback host '$h'")
            throw PluginError("host_not_allowed", "host '$h' is loopback/private and always refused")
        }
    }
}
