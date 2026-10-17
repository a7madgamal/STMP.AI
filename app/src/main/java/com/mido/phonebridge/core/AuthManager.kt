package com.mido.phonebridge.core

import android.content.Context
import android.util.Log
import com.mido.phonebridge.Debug
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google OAuth token operations shared by every plugin.
 *
 * The app identifies to Google via the Android client (package + SHA-1), so no
 * client secret is embedded. Offline access is obtained by exchanging a
 * one-time server auth code (issued by Play Services) for a refresh token.
 *
 * FAIL LOUD: any auth problem surfaces as a coded PluginError, never a silent retry.
 */
object AuthManager {
    private const val TAG = "AuthManager"

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiry: Long = 0L

    /** Android-client id (used for refresh-token exchanges). */
    @Volatile var clientId: String = ""

    /** Web-client id, sent as serverClientId for offline access. */
    @Volatile var serverClientId: String = ""

    /** Web-client secret. Required to authenticate the server-code exchange. */
    @Volatile var clientSecret: String = ""

    @Volatile var grantedScopes: Set<String> = emptySet()
    @Volatile var accountEmail: String? = null

    /**
     * Configure the OAuth clients. Safe to call from anywhere and repeatedly.
     *
     * The values are ALSO persisted, so an app update that keeps data (and any
     * cold start before the service runs) still has a working config. Explicit
     * build-time values win; persisted values fill in when the caller has none.
     */
    fun init(ctx: Context, clientId: String, serverClientId: String, clientSecret: String) {
        if (clientId.isNotBlank()) {
            this.clientId = clientId
            this.serverClientId = serverClientId
            this.clientSecret = clientSecret
            TokenStore.saveConfig(ctx, clientId, serverClientId, clientSecret)
        } else {
            // No build-time config: fall back to the persisted copy, if present.
            TokenStore.config(ctx)?.let { (a, b, c) ->
                this.clientId = a
                this.serverClientId = b
                this.clientSecret = c
            }
        }
        // Restore the account email so the UI never shows "unknown" after a restart.
        accountEmail = accountEmail ?: TokenStore.email(ctx)
        TokenStore.load(ctx)
    }

    /** Prime the in-memory access-token cache (from Play Services). */
    fun setAccessToken(token: String, expiresInSec: Int, scopes: List<String>?) {
        accessToken = token
        accessTokenExpiry = System.currentTimeMillis() + expiresInSec * 1000L
        if (scopes != null) grantedScopes = scopes.toSet()
    }

    /**
     * Exchange a one-time server auth code for an access + refresh token.
     *
     * redirect_uri must be EMPTY for a server auth code obtained from the
     * Android/Play Services SDK ("postmessage" applies only to codes from the
     * Web SDK; sending it here yields invalid_grant "Malformed auth code.").
     */
    @Synchronized
    fun exchangeServerAuthCode(ctx: Context, serverAuthCode: String): String {
        // Per Google's requestOfflineAccess docs, the code is redeemed by the
        // SERVER identified by serverClientId (the Web client), using that
        // client's secret.
        val redeemClient = serverClientId.ifBlank { clientId }
        if (redeemClient.isBlank()) {
            throw PluginError("auth_not_configured", "no client id configured")
        }
        val form = "code=${enc(serverAuthCode)}" +
            "&client_id=${enc(redeemClient)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&grant_type=authorization_code" +
            "&redirect_uri="
        // Full outgoing request, unmasked (redaction is off, per Mido).
        Debug.log("EXCHANGE POST $TOKEN_URL")
        Debug.log("EXCHANGE FORM: $form")
        Debug.log("EXCHANGE form_len=${form.length}")
        val (code, res) = postRaw(TOKEN_URL, form)
        val obj = try { JSONObject(res) } catch (e: Exception) {
            throw PluginError("auth_error", "token endpoint returned non-JSON (HTTP $code): $res")
        }
        if (obj.has("error") || code !in 200..299) {
            throw PluginError(
                "auth_error",
                "server code exchange failed (HTTP $code): $res"
            )
        }
        val refresh = obj.optString("refresh_token")
        if (refresh.isBlank()) {
            throw PluginError("auth_error", "no refresh_token in exchange response: $res")
        }
        TokenStore.save(ctx, refresh)
        accessToken = obj.optString("access_token").ifBlank { null }
        accessTokenExpiry = System.currentTimeMillis() + obj.optInt("expires_in", 3600) * 1000L
        Log.i(TAG, "refresh token stored from server auth code")
        return refresh
    }

    /** Returns a valid access token, refreshing silently when needed. */
    @Synchronized
    fun accessToken(ctx: Context): String {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessTokenExpiry - 60_000) return it }

        val refresh = TokenStore.refreshToken(ctx)
            ?: throw PluginError("auth_required", "not logged in")
        // The refresh token belongs to the Web client (the offline-access
        // client), so the refresh must also name that client.
        val refreshClient = serverClientId.ifBlank { clientId }
        val form = "client_id=${enc(refreshClient)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&refresh_token=${enc(refresh)}" +
            "&grant_type=refresh_token"
        val (code, res) = postRaw(TOKEN_URL, form)
        val obj = JSONObject(res)
        if (obj.optString("error") == "invalid_grant") {
            throw PluginError("auth_expired", "refresh token rejected; re-login required")
        }
        val at = obj.optString("access_token")
        if (at.isBlank()) {
            throw PluginError("auth_error", "refresh failed (HTTP $code): $res")
        }
        accessToken = at
        accessTokenExpiry = System.currentTimeMillis() + obj.optInt("expires_in", 3600) * 1000L
        return at
    }

    /**
     * Whether Google access is currently usable.
     *
     * "Logged in" means EITHER a refresh token exists on disk OR a live access
     * token is still valid in memory.
     */
    fun isLoggedIn(ctx: Context): Boolean {
        if (TokenStore.refreshToken(ctx) != null) return true
        val t = accessToken
        return t != null && System.currentTimeMillis() < accessTokenExpiry
    }

    /** Milliseconds until the cached access token expires (0 if none). */
    fun tokenRemainingMs(): Long {
        val t = accessToken ?: return 0
        if (t.isBlank()) return 0
        return (accessTokenExpiry - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun storeRefreshToken(ctx: Context, token: String) = TokenStore.save(ctx, token)
    fun clear(ctx: Context) {
        TokenStore.clear(ctx)
        TokenStore.clearIdentity(ctx)
        accessToken = null
        accessTokenExpiry = 0
        accountEmail = null
    }

    /** Fetch the account email. Uses [bearer] when supplied. Logs every step. */
    fun fetchEmail(ctx: Context, bearer: String? = null): String? {
        val token = bearer ?: accessToken(ctx)
        Debug.log("EMAIL GET $USERINFO_URL (bearer len=${token.length})")
        return try {
            val conn = (URL(USERINFO_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000; readTimeout = 15_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().readText()
            // Full unmasked response (redaction is off, per Mido).
            Debug.log("EMAIL RESP HTTP $code: $body")
            if (code !in 200..299) {
                throw PluginError("email_lookup_failed", "userinfo HTTP $code: $body")
            }
            val email = JSONObject(body).optString("email").ifBlank { null }
            if (email != null) {
                accountEmail = email
                TokenStore.saveEmail(ctx, email)
            }
            Debug.log("EMAIL RESOLVED: ${email ?: "(none in response)"}")
            email
        } catch (e: PluginError) {
            Debug.log("EMAIL FAILED: ${e.code}: ${e.message}")
            throw e
        } catch (e: Exception) {
            Debug.log("EMAIL FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw PluginError("email_lookup_failed", "userinfo request failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // --- helpers ---

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** POST form data; returns (httpStatus, body) and never hides the body. */
    private fun postRaw(url: String, form: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(form.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).readText()
        // Full unmasked response (redaction is off, per Mido).
        Debug.log("HTTP POST $url -> $code")
        Debug.log("HTTP RESP BODY: $body")
        return code to body
    }
}
