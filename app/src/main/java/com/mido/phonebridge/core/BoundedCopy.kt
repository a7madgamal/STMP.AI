package com.mido.phonebridge.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Copy exactly N bytes from [input] to [output].
 *
 * **Why `InputStream.copyTo` is wrong here, and this exists.** `copyTo` reads until END OF STREAM.
 * An HTTP request body does not end at end-of-stream — it ends at `Content-Length`, and a client
 * using a keep-alive connection holds the socket open afterwards waiting for the response. So
 * `copyTo` copies the whole body correctly and then BLOCKS forever waiting for a byte that will
 * never arrive, until the socket read timeout fires.
 *
 * That is exactly what happened on the first real upload (0.36.0, verified on device 2026-10-21):
 * a 20,232-byte PDF uploaded in ~10 s and died with `SocketTimeoutException: Read timed out`
 * inside `copyTo`, surfacing as a `500 internal_error`. Google was never involved — the stack
 * showed `SocketInputStream.read`, i.e. we were waiting on our OWN client.
 *
 * **Also the reason this is a function and not an inline loop.** The failure is a boundary rule
 * ("the body has exactly this many bytes"), which is the kind of thing a unit test can prove and
 * an inline copy in the I/O path cannot. `BoundedCopyTest` covers the short-stream case, which is
 * the one that would otherwise silently upload a truncated file.
 */
object BoundedCopy {

    /**
     * Copy exactly [length] bytes.
     *
     * @throws PluginError `bad_request` when the stream ends before [length] bytes. A truncated
     *   body must FAIL rather than upload what arrived: a half-file that reports success is worse
     *   than a refusal, because nobody goes looking for the missing half.
     * @throws IllegalArgumentException for a negative length — that is a caller bug, and treating
     *   it as 0 would present an empty upload as a complete one.
     */
    fun exactly(input: InputStream, output: OutputStream, length: Long, bufferSize: Int = 64 * 1024) {
        require(length >= 0) { "length must not be negative, got $length" }
        require(bufferSize > 0) { "bufferSize must be positive, got $bufferSize" }
        if (length == 0L) return

        var remaining = length
        val buf = ByteArray(bufferSize)
        while (remaining > 0) {
            // Never ask for more than is left: asking for a full buffer on the final partial read
            // would block waiting for bytes the client is not going to send — the same bug in a
            // smaller form.
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) {
                throw PluginError(
                    "bad_request",
                    "request body ended after ${length - remaining} of $length bytes"
                )
            }
            output.write(buf, 0, n)
            remaining -= n
        }
    }
}
