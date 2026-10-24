package com.mido.phonebridge.core

import java.net.HttpURLConnection

/**
 * The one setting that decides whether a request body is STREAMED or buffered in the heap.
 *
 * **A `Content-Length` request property is not streaming.** For a `doOutput = true` request,
 * `HttpURLConnection` writes into an internal poster stream (a `ByteArrayOutputStream`) and only
 * opens the socket once the whole body has been collected — unless the body length is declared
 * through the streaming API. So a 385 MB upload against the app's 256 MB `javaMaxBytes` throws
 * `OutOfMemoryError`, the request thread dies, and the client sees a **connection reset at roughly
 * 150 MB**. That is exactly the 2026-10-24 upload failure, and it looked like a network fault
 * because the phone's own log said nothing about memory.
 *
 * Proven on the JVM at the real size and the real heap ceiling:
 *
 * ```
 * property only:                       OOM java heap space, 128 MB sent, heapUsed 195 MB
 *   at java.io.ByteArrayOutputStream.write
 *   at sun.net.www.http.PosterOutputStream.write
 * setFixedLengthStreamingMode(385 MB): 385 MB sent, heapUsed flat at 3 MB
 * ```
 *
 * **This exists as a function so the rule is provable without a phone.** `HttpStreamTest` drives a
 * real `HttpURLConnection` against a real server and asserts the server receives the first half of
 * the body BEFORE the client writes the second half — the observable difference between streaming
 * and buffering. That assertion fails loudly if anyone goes back to setting the header.
 *
 * Call it BEFORE `getOutputStream()`: the streaming mode cannot be changed once the output stream
 * exists. The length is passed as `Long` because Drive files are routinely larger than an `Int`.
 */
object HttpStream {

    /**
     * Declare [length] bytes and switch the connection to fixed-length streaming.
     *
     * `setFixedLengthStreamingMode` also emits `Content-Length`, so the header must NOT be set
     * separately: two sources for one number invite a mismatch, and a `Content-Length` that
     * disagrees with the bytes written is a request the far end cannot interpret.
     *
     * @throws IllegalArgumentException for a negative length — a caller bug. A `-1` sent as a
     *   length would otherwise produce a malformed request rather than a clear failure.
     */
    fun fixedLength(conn: HttpURLConnection, length: Long) {
        require(length >= 0) { "length must not be negative, got $length" }
        conn.setFixedLengthStreamingMode(length)
    }
}
