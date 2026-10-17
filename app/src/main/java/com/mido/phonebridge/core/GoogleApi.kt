package com.mido.phonebridge.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Google REST client. Plugins build the URL/body; this handles auth header,
 * timeouts, and loud error surfacing.
 */
object GoogleApi {
    private const val TAG = "GoogleApi"

    fun get(ctx: Context, url: String): JSONObject {
        val token = AuthManager.accessToken(ctx)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return readJson(conn)
    }

    fun post(ctx: Context, url: String, body: String): JSONObject {
        val token = AuthManager.accessToken(ctx)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readJson(conn)
    }

    fun patch(ctx: Context, url: String, body: String): JSONObject {
        val token = AuthManager.accessToken(ctx)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readJson(conn)
    }

    fun delete(ctx: Context, url: String): JSONObject {
        val token = AuthManager.accessToken(ctx)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).readText()
        if (code == 401) {
            throw PluginError("auth_expired", "Google returned 401: $text")
        }
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
            throw PluginError("google_error", "HTTP $code: ${msg ?: text}")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private typealias Context = android.content.Context
