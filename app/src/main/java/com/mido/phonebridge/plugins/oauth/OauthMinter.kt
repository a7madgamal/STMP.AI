package com.mido.phonebridge.plugins.oauth

import android.util.Base64
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.PluginError
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Exchanges a refresh token for an access token, on the phone, in memory.
 *
 * **Implements exactly one grant.** RFC 6749 §6 `grant_type=refresh_token`, form-encoded, with the
 * client authenticated either by HTTP Basic (§2.3.1) or by credentials in the body. Nothing else:
 * authorization-code, PKCE, client-credentials and JWT-bearer are different grants with different
 * inputs, and pretending one entry could cover them would produce a plugin that is wrong for all.
 *
 * **Why this is not the secret broker.** The broker sends a caller-supplied request with one
 * substituted value. A refresh needs the client secret AND the refresh token in the same body,
 * plus a response parse and a cache — that is a different operation, not a different URL. The
 * properties the broker provides are re-established here deliberately and visibly, because
 * inheriting them silently is how a security property gets lost between two code paths.
 *
 * **Everything the broker does that matters is done here too:**
 * - https only, checked at save time and again immediately before the socket opens.
 * - **Redirects are never followed.** A 3xx is a refused mint, not an invitation to send a client
 *   secret to a host nobody approved — the same rule the broker applies to a credential.
 * - A dedicated 15s timeout, not the broker's 60s: a token endpoint is one small POST, and a
 *   request that already cost a human a fingerprint tap should not hang for a minute behind it.
 * - Bounded read, so a hostile or broken token endpoint cannot stream unbounded bytes into memory.
 *
 * **The token endpoint host is deliberately NOT on the credential's host allowlist.** The allowlist
 * is where an access token may be SENT; it is not where a token is MINTED. Requiring
 * `oauth2.googleapis.com` on the same list as `chromewebstore.googleapis.com` would conflate two
 * different permissions and teach the human to widen the wrong one.
 */
object OauthMinter {

    /** One small POST. Long enough for a slow provider, short enough not to sit on an approval. */
    const val TIMEOUT_MS = 15_000

    /** A token response is a few hundred bytes. This is a ceiling on a hostile endpoint, not a guess. */
    private const val MAX_BODY_BYTES = 64 * 1024

    /**
     * Mint an access token from [entry] and [secret].
     *
     * Throws by name at every failure: `oauth_token_timeout`, `invalid_grant`, `invalid_client`,
     * `refresh_token_rotated`, `oauth_bad_response`. A generic "request failed" would collapse
     * "your token was revoked" and "the network was busy" into one unactionable string.
     */
    fun mint(entry: OauthEntry, secret: OauthSecret): OauthToken {
        val url = requireHttps(entry.tokenUrl)

        // Form-encoded per §6. `scope` is deliberately absent — see OauthEntry.scope.
        val form = buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(enc(secret.refreshToken))
            if (entry.authStyle == OauthEntry.AUTH_BODY) {
                append("&client_id=").append(enc(entry.clientId))
                append("&client_secret=").append(enc(secret.clientSecret))
            }
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            if (entry.authStyle == OauthEntry.AUTH_BASIC) {
                // §2.3.1. RFC 7617 says to base64 the id:secret pair, and §2.3.1 note 1 says both
                // halves are form-encoded before being joined and encoded.
                val pair = enc(entry.clientId) + ":" + enc(secret.clientSecret)
                val b64 = Base64.encodeToString(
                    pair.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
                )
                setRequestProperty("Authorization", "Basic $b64")
            }
        }

        try {
            conn.outputStream.use { out ->
                out.write(form.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val status = conn.responseCode

            // **A redirect is a refusal.** `instanceFollowRedirects = false` stops the library from
            // replaying the POST, but the 3xx still arrives — and answering it with "ok" would leave
            // a caller thinking a token was minted.
            if (status in 300..399) {
                throw PluginError(
                    "oauth_token_refused",
                    "the token endpoint answered HTTP $status (a redirect), which is refused: " +
                        "following it would send the client secret to ${conn.getHeaderField("Location") ?: "another host"}"
                )
            }

            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { readBounded(it) } ?: ""

            if (status !in 200..299) {
                throw PluginError("oauth_token_refused", tokenErrorLine(status, body))
            }

            return parse(body)
        } catch (e: PluginError) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            // Its own code, on purpose: "the provider was slow to MINT" and "the provider was slow
            // to answer" are different facts, and only one of them means the credential is fine.
            throw PluginError(
                "oauth_token_timeout",
                "the token endpoint did not answer within ${TIMEOUT_MS / 1000}s. Nothing was sent " +
                    "to the target; retry, and check the device's connection."
            )
        } catch (e: Exception) {
            throw PluginError(
                "oauth_token_failed",
                "could not reach the token endpoint: ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Read at most [MAX_BODY_BYTES].
     *
     * A token response is tiny. Reading until EOF on an endpoint that never stops would be an OOM
     * on a phone, and the failure would present as the app dying mid-call rather than as a bad
     * provider.
     */
    private fun readBounded(stream: java.io.InputStream): String {
        BufferedInputStream(stream).use { input ->
            val buf = ByteArray(8 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val n = input.read(buf, 0, minOf(buf.size, MAX_BODY_BYTES - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }

    /**
     * Parse §5.1: `access_token` is required, `expires_in` may be absent.
     *
     * **An absent `expires_in` is not a guess at 3600.** Caching a token for an hour because a
     * provider did not say would mean a wrong guess surfaces as a 401 an hour later. Caching it for
     * 60s means the worst case is one extra mint — a few hundred bytes on the phone, invisible.
     */
    private fun parse(body: String): OauthToken {
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw PluginError(
                "oauth_bad_response",
                "the token endpoint answered 2xx with something that is not JSON — a provider that " +
                    "answers form-encoded is not supported by this build"
            )
        }

        // A rotated refresh token must replace the old one (§6). This build refuses rather than
        // writing to the vault mid-request, and says so by name.
        if (o.optString("refresh_token", "").trim().isNotEmpty()) throw rotatedRefusal()

        val access = o.optString("access_token", "").trim()
        if (access.isEmpty()) {
            throw PluginError(
                "oauth_bad_response",
                "the token endpoint answered 2xx with no access_token"
            )
        }

        val expiresIn = if (o.has("expires_in")) o.optLong("expires_in", 0L) else 0L
        val lifetimeSec = if (expiresIn > 0L) expiresIn else FALLBACK_LIFETIME_SEC
        val expiresAt = System.currentTimeMillis() / 1000L + lifetimeSec

        Debug.log("oauth: minted a token valid ${lifetimeSec}s (expires_in ${if (expiresIn > 0) "given" else "absent"})")
        return OauthToken(accessToken = access, expiresAtSec = expiresAt)
    }

    /** When the provider does not say how long the token lives. See [parse]. */
    private const val FALLBACK_LIFETIME_SEC = 60L

    /**
     * https, or refuse.
     *
     * Checked here as well as at save time, because a stored record can be older than a rule, and a
     * client secret over cleartext is a credential in someone else's log.
     */
    private fun requireHttps(raw: String): URL {
        val parsed = try {
            URL(raw)
        } catch (e: Exception) {
            throw PluginError("oauth_bad_config", "token url is not parseable: ${e.message}")
        }
        if (parsed.protocol != "https") {
            throw PluginError(
                "oauth_bad_config",
                "the token url must be https, got '${parsed.protocol}' — a client secret over " +
                    "http is a secret in the clear"
            )
        }
        return parsed
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
