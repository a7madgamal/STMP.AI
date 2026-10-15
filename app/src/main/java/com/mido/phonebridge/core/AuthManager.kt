package com.mido.phonebridge.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Handles the ONE Google OAuth credential shared by every plugin.
 *
 * Device Authorization Grant (RFC 8628): no redirect URI, no custom scheme.
 * The token is stored via TokenStore (Keystore-backed, Phase 2 biometric-gated).
 *
 * FAIL LOUD: any auth problem surfaces as a coded PluginError, never a silent retry.
 */
object AuthManager {
    private const val TAG = "AuthManager"

    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiry: Long = 0L

    /** Client id of the "TVs and Limited Input devices" OAuth client. */
    @Volatile var clientId: String = ""

    /** Desktop-client secret (non-secret per Google's installed-app docs). */
    @Volatile var clientSecret: String = ""

    /** Scopes currently granted (persisted alongside the refresh token). */
    @Volatile var grantedScopes: Set<String> = emptySet()

    @Volatile var accountEmail: String? = null

    fun init(ctx: Context, clientId: String, clientSecret: String) {
        this.clientId = clientId
        this.clientSecret = clientSecret
        TokenStore.load(ctx)
    }

    /** Step 1 of the grant: ask Google for a user code to show the human. */
    fun requestDeviceCode(scopes: List<String>): DeviceCode {
        if (clientId.isBlank()) {
            throw PluginError("auth_not_configured", "no OAuth client id configured")
        }
        val form = "client_id=${enc(clientId)}&scope=${enc(scopes.joinToString(" "))}"
        val res = post(DEVICE_CODE_URL, form)
        val obj = JSONObject(res)
        val device = obj.optString("device_code")
        val user = obj.optString("user_code")
        val verify = obj.optString("verification_url", "https://www.google.com/device")
        val interval = obj.optInt("interval", 5)
        val expires = obj.optInt("expires_in", 1800)
        if (device.isBlank() || user.isBlank()) {
            throw PluginError("auth_error", "device code response missing fields: $res")
        }
        return DeviceCode(device, user, verify, interval, expires, scopes)
    }

    /**
     * Step 2: poll until the human consents. Blocking; call off the main thread.
     * Handles `authorization_pending` and `slow_down` per RFC 8628.
     */
    fun pollForToken(dc: DeviceCode): String {
        val deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L
        var interval = dc.intervalSec.coerceAtLeast(2)
        while (System.currentTimeMillis() < deadline) {
            val form = "client_id=${enc(clientId)}" +
                "&device_code=${enc(dc.deviceCode)}" +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            val res = post(TOKEN_URL, form)
            val obj = JSONObject(res)
            obj.optString("error").takeIf { it.isNotBlank() }?.let { err ->
                when (err) {
                    "authorization_pending" -> { /* keep waiting */ }
                    "slow_down" -> interval += 5
                    "access_denied" -> throw PluginError("denied", "user denied device authorization")
                    "expired_token" -> throw PluginError("auth_expired", "device code expired")
                    else -> throw PluginError("auth_error", "token error: $err ($res)")
                }
            }
            if (obj.has("access_token")) {
                onTokenResponse(obj, dc.scopes)
                return obj.getString("refresh_token")
            }
            Thread.sleep(interval * 1000L)
        }
        throw PluginError("auth_expired", "device authorization timed out")
    }

    /** Returns a valid access token, refreshing silently when needed. */
    @Synchronized
    fun accessToken(ctx: Context): String {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessTokenExpiry - 60_000) return it }

        val refresh = TokenStore.refreshToken(ctx)
            ?: throw PluginError("auth_required", "not logged in")
        val form = "client_id=${enc(clientId)}" +
            "&refresh_token=${enc(refresh)}" +
            "&grant_type=refresh_token"
        val res = post(TOKEN_URL, form)
        val obj = JSONObject(res)
        if (obj.optString("error") == "invalid_grant") {
            // Testing-mode 7-day expiry, or revoked.
            throw PluginError("auth_expired", "refresh token rejected; re-login required")
        }
        if (!obj.has("access_token")) {
            throw PluginError("auth_error", "refresh failed: $res")
        }
        onTokenResponse(obj, null)
        return obj.getString("access_token")
    }

    private fun onTokenResponse(obj: JSONObject, scopes: List<String>?) {
        accessToken = obj.getString("access_token")
        val expiresIn = obj.optInt("expires_in", 3600)
        accessTokenExpiry = System.currentTimeMillis() + expiresIn * 1000L
        if (scopes != null) grantedScopes = scopes.toSet()
        Log.i(TAG, "access token refreshed (expires in ${expiresIn}s)")
    }

    fun storeRefreshToken(ctx: Context, token: String) = TokenStore.save(ctx, token)
    fun clear(ctx: Context) { TokenStore.clear(ctx); accessToken = null; accessTokenExpiry = 0 }

    /** Used by the loopback flow to prime the shared access-token cache. */
    fun setAccessToken(token: String, expiresInSec: Int, scopes: List<String>?) {
        accessToken = token
        accessTokenExpiry = System.currentTimeMillis() + expiresInSec * 1000L
        if (scopes != null) grantedScopes = scopes.toSet()
    }

    /** Whether we have a refresh token on disk. */
    fun isLoggedIn(ctx: Context): Boolean = TokenStore.refreshToken(ctx) != null

    /** Fetch the account email (best effort, for the UI). */
    fun fetchEmail(ctx: Context): String? {
        return try {
            val token = accessToken(ctx)
            val conn = (URL(USERINFO_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000; readTimeout = 15_000
            }
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body).optString("email").ifBlank { null }?.also { accountEmail = it }
        } catch (e: Exception) {
            Log.w(TAG, "fetchEmail failed: ${e.message}")
            null
        }
    }

    // --- helpers ---

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, form: String): String {
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
        if (code !in 200..299) {
            // Google returns a JSON body with "error" for device-flow states; let
            // the caller interpret it, but log the raw status.
            Log.w(TAG, "POST $url -> $code")
        }
        return body
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresInSec: Int,
        val scopes: List<String>
    )
}
