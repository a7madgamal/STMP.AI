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
 * Google OAuth for a DESKTOP-type client, using the loopback redirect flow.
 *
 * Why this flow: the Device Authorization Grant cannot carry Gmail's restricted
 * scopes (Google rejects them with invalid_scope), so loopback is the only
 * supported path for this app. No fallback exists.
 *
 * The desktop client secret is embedded; Google documents installed/desktop-app
 * secrets as non-secret (anyone unpacking the APK can read it, and it alone
 * grants nothing without the user's Google login and the app's approval gate).
 */
object OAuthLoopback {
    private const val TAG = "OAuthLoopback"

    /**
     * Fixed loopback port. Static (not random) because a Web/Desktop client
     * must have the exact redirect URI registered in the Google console.
     * Register: http://127.0.0.1:8899
     */
    const val LOOPBACK_PORT = 8899

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"

    class Session(
        val port: Int,
        val authUrl: String,
        val serverSocket: java.net.ServerSocket,
        val redirectUri: String
    )

    /** Bind the fixed loopback listener and build the consent URL. */
    fun start(clientId: String, scopes: List<String>): Session {
        val socket = java.net.ServerSocket(LOOPBACK_PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val redirect = "http://127.0.0.1:$port"
        val url = buildString {
            append(AUTH_URL)
            append("?client_id=").append(enc(clientId))
            append("&redirect_uri=").append(enc(redirect))
            append("&response_type=code")
            append("&scope=").append(enc(scopes.joinToString(" ")))
            append("&access_type=offline")
            append("&prompt=consent")
        }
        Log.i(TAG, "loopback listener on 127.0.0.1:$port")
        return Session(port, url, socket, redirect)
    }

    /** Block until Google redirects back with ?code=..., then exchange it. */
    fun waitForCode(
        ctx: Context, clientId: String, clientSecret: String, session: Session, scopes: List<String>
    ): String {
        val code: String
        try {
            session.serverSocket.soTimeout = 5 * 60 * 1000
            val client = session.serverSocket.accept()
            client.use { c ->
                val request = BufferedReader(InputStreamReader(c.getInputStream())).readLine() ?: ""
                val path = request.split(" ").getOrNull(1) ?: "/"
                val params = parseQuery(path.substringAfter('?', ""))
                code = params["code"] ?: ""
                val err = params["error"]
                val html = if (code.isNotEmpty()) {
                    "<html><body style='font-family:sans-serif;background:#101418;color:#fff'>" +
                        "<h2>PhoneBridge</h2><p>Login complete. You can close this tab.</p></body></html>"
                } else {
                    "<html><body style='font-family:sans-serif;background:#101418;color:#fff'>" +
                        "<h2>PhoneBridge</h2><p>Login failed: ${err ?: "no code"}</p></body></html>"
                }
                c.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html")
                        .toByteArray())
                    flush()
                }
                if (code.isEmpty()) {
                    throw PluginError("denied", "authorization failed: ${err ?: "no code returned"}")
                }
            }
        } finally {
            try { session.serverSocket.close() } catch (_: Exception) {}
        }

        Log.i(TAG, "received authorization code; exchanging for tokens")
        return exchangeCode(ctx, clientId, clientSecret, code, session.redirectUri, scopes)
    }

    private fun exchangeCode(
        ctx: Context, clientId: String, clientSecret: String,
        code: String, redirect: String, scopes: List<String>
    ): String {
        val form = "code=${enc(code)}" +
            "&client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&redirect_uri=${enc(redirect)}" +
            "&grant_type=authorization_code"
        val obj = JSONObject(post(TOKEN_URL, form))
        val refresh = obj.optString("refresh_token")
        if (refresh.isBlank()) {
            throw PluginError("auth_error", "no refresh_token in response: $obj")
        }
        TokenStore.save(ctx, refresh)
        AuthManager.setAccessToken(obj.optString("access_token"), obj.optInt("expires_in", 3600), scopes)
        return refresh
    }

    /** Refresh an expired access token. */
    @Synchronized
    fun refresh(ctx: Context, clientId: String, clientSecret: String): String {
        val refresh = TokenStore.refreshToken(ctx)
            ?: throw PluginError("auth_required", "not logged in")
        val form = "client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&refresh_token=${enc(refresh)}" +
            "&grant_type=refresh_token"
        val obj = JSONObject(post(TOKEN_URL, form))
        if (obj.optString("error") == "invalid_grant") {
            throw PluginError("auth_expired", "refresh token rejected; re-login required")
        }
        val at = obj.optString("access_token")
        if (at.isBlank()) throw PluginError("auth_error", "refresh failed: $obj")
        AuthManager.setAccessToken(at, obj.optInt("expires_in", 3600), null)
        return at
    }

    fun fetchEmail(accessToken: String): String? = try {
        val conn = (URL(USERINFO_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 10_000; readTimeout = 15_000
        }
        JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
            .optString("email").ifBlank { null }
    } catch (e: Exception) {
        Log.w(TAG, "fetchEmail failed: ${e.message}"); null
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split("&").filter { it.contains("=") }.associate {
            java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") to
                java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, form: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(form.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).readText()
        if (code !in 200..299) Log.w(TAG, "POST $url -> $code")
        return body
    }
}
