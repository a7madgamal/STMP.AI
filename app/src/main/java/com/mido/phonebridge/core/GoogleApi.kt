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

    /**
     * Open a Google endpoint for a STREAMED download.
     *
     * **Returns the connection, not the bytes.** A file download must not be buffered: the whole
     * point of the streaming plumbing is that `Response` copies from an `InputStream` straight to
     * the socket. So this hands back the open connection and the caller is responsible for
     * closing it — which the HTTP layer does once the response body is written.
     *
     * The status is checked here so a 404/401 fails with a coded error BEFORE any bytes are
     * promised. A stream that starts and then dies mid-transfer cannot be reported as an HTTP
     * error, which is why the check has to happen first.
     */
    fun openStream(ctx: Context, url: String): Pair<HttpURLConnection, java.io.InputStream> {
        val token = AuthManager.accessToken(ctx)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            // A download is not an API call: 30s is right for JSON and far too short for a file.
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        if (code == 401) {
            conn.disconnect()
            throw PluginError("auth_expired", "Google returned 401 on download")
        }
        if (code !in 200..299) {
            val text = try {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) {
                ""
            }
            conn.disconnect()
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            }
            throw PluginError("google_error", "HTTP $code: ${msg ?: text}")
        }
        val stream = conn.inputStream ?: run {
            conn.disconnect()
            throw PluginError("google_error", "Google returned no body for $url")
        }
        return conn to stream
    }

    /**
     * POST JSON and return the `Location` header instead of a body.
     *
     * This exists for Drive's RESUMABLE upload: the first call sends only metadata and answers
     * `200` with a session URI in `Location`, and the bytes go to that URI afterwards. Parsing the
     * body would find nothing useful — the header IS the result — so returning a `JSONObject`
     * here would quietly lose the only thing the call produces.
     */
    fun postJsonForLocation(ctx: Context, url: String, body: String): String {
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
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = try {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) {
                ""
            }
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            }
            throw PluginError("google_error", "HTTP $code: ${msg ?: text}")
        }
        val location = conn.getHeaderField("Location")
            ?: throw PluginError("google_error", "no Location header on the upload session")
        return location
    }

    /**
     * PUT a byte stream to an upload-session URI and parse the resulting file metadata.
     *
     * The second half of Drive's resumable upload. Bytes are copied straight from [input] to the
     * socket — never buffered — so a large file costs the same memory as a small one.
     *
     * `Content-Length` is set explicitly because the session URI requires it for a single-shot
     * upload; the length comes from the caller, who got it from the request. A wrong length here
     * fails the upload rather than truncating it silently, which is the safe direction.
     */
    fun putStream(ctx: Context, url: String, input: java.io.InputStream, length: Long, contentType: String): JSONObject {
        val token = AuthManager.accessToken(ctx)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            // A file upload is not an API call: 30s is right for JSON and far too short here.
            connectTimeout = 15_000
            readTimeout = 300_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
        }
        // STREAM THE BODY, or HttpURLConnection buffers all of it in the heap.
        //
        // **This is the line that made a 385 MB upload die at ~150 MB with a connection
        // reset.** Setting a `Content-Length` REQUEST PROPERTY does not enable streaming: for a
        // `doOutput = true` request, `HttpURLConnection` writes into a `PosterOutputStream`, which
        // is a `ByteArrayOutputStream` that grows to the whole body before a single byte is sent.
        // Against the app's 256 MB `javaMaxBytes` that is an `OutOfMemoryError`, and the JVM dying
        // mid-request is what the far end sees as a reset — so the failure looked like the network
        // and the phone's log said nothing about the real cause. Proven on the JVM with this exact
        // file size and this exact heap ceiling:
        //   POSTER (property only):  OOM java heap space at 128MB sent, heapUsed 195MB
        //     at java.io.ByteArrayOutputStream.write
        //     at sun.net.www.http.PosterOutputStream.write
        //   setFixedLengthStreamingMode(385MB): 385MB sent, heapUsed flat at 3MB
        //
        // `setFixedLengthStreamingMode` must be called BEFORE `getOutputStream()` and after
        // `doOutput`, and it also sets `Content-Length` itself — the explicit header is gone
        // because a second, disagreeing source for the same number is a bug waiting to happen.
        // It also gives the correct behaviour [BoundedCopy] relies on: bytes go out as they are
        // read, so a stalled receiver applies backpressure instead of filling the heap.
        HttpStream.fixedLength(conn, length)
        try {
            // EXACTLY [length] bytes, not "until the stream ends".
            //
            // `copyTo` reads to EOF, but an HTTP body ends at Content-Length and a keep-alive
            // client holds the socket open afterwards — so the first real upload (0.36.0,
            // 2026-10-21) copied the file, then blocked on a read that could never return, and
            // died with `SocketTimeoutException: Read timed out` surfacing as a 500. See
            // [BoundedCopy] for the full trace.
            conn.outputStream.use { out -> BoundedCopy.exactly(input, out, length) }
            return readJson(conn)
        } finally {
            conn.disconnect()
        }
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
