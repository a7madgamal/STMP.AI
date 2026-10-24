package com.mido.phonebridge

import com.mido.phonebridge.core.BoundedCopy
import com.mido.phonebridge.core.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The boundary rule for streaming a request body.
 *
 * **This exists because `InputStream.copyTo` shipped and failed on the first real upload**
 * (0.36.0, verified on device 2026-10-21). `copyTo` reads to END OF STREAM; an HTTP body ends at
 * `Content-Length`, and a keep-alive client holds the socket open afterwards. So the first upload
 * copied its 20,232 bytes correctly, then blocked on a read that could never return and died with
 * `SocketTimeoutException: Read timed out`, surfaced as a `500 internal_error`. The stack pointed
 * at `SocketInputStream.read` — we were waiting on our own client, not on Google.
 *
 * The rule is small, pure and entirely testable, which is exactly why it is a function rather
 * than an inline loop: an inline copy in the I/O path cannot be exercised without a socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BoundedCopyTest {

    @Test
    fun `copies exactly the requested bytes and stops`() {
        // The core property: a stream with MORE data available must not be read past `length`.
        // That is precisely what `copyTo` did — it kept reading because the stream had not ended.
        val source = ByteArrayInputStream("0123456789".toByteArray())
        val sink = ByteArrayOutputStream()
        BoundedCopy.exactly(source, sink, 4)
        assertEquals("0123", sink.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `leaves the rest of the stream unread`() {
        // Proof that it STOPPED rather than merely truncated its output: the remaining bytes are
        // still in the source. `copyTo` would have consumed everything.
        val source = ByteArrayInputStream("abcdefgh".toByteArray())
        val sink = ByteArrayOutputStream()
        BoundedCopy.exactly(source, sink, 3)
        assertEquals("abc", sink.toString(Charsets.UTF_8.name()))
        assertEquals('d'.code, source.read())
    }

    @Test
    fun `a zero length writes nothing and reads nothing`() {
        val source = ByteArrayInputStream("data".toByteArray())
        val sink = ByteArrayOutputStream()
        BoundedCopy.exactly(source, sink, 0)
        assertEquals(0, sink.size())
        // Untouched: a 0-byte upload must not consume the caller's stream.
        assertEquals('d'.code, source.read())
    }

    @Test
    fun `a stream that ends early fails loudly rather than uploading a truncated file`() {
        // The dangerous case: a half-file that reports success. Nobody goes looking for the
        // missing half, so this must throw rather than write what arrived.
        val source = ByteArrayInputStream("abc".toByteArray())
        val sink = ByteArrayOutputStream()
        val e = assertThrows(PluginError::class.java) {
            BoundedCopy.exactly(source, sink, 10)
        }
        assertEquals("bad_request", e.code)
        assertTrue(
            "the error should say how much arrived: ${e.message}",
            e.message!!.contains("3 of 10")
        )
    }

    @Test
    fun `a body larger than one buffer is copied in full`() {
        // Chunking must not lose bytes at a boundary. 200 KB through a 64 KB buffer exercises
        // three full reads and a partial one.
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val sink = ByteArrayOutputStream()
        BoundedCopy.exactly(ByteArrayInputStream(payload), sink, payload.size.toLong())
        assertEquals(payload.size, sink.size())
        assertTrue("content must be byte-identical", payload.contentEquals(sink.toByteArray()))
    }

    @Test
    fun `the final partial read asks only for what is left`() {
        // The same bug in a smaller form: reading a full buffer when fewer bytes remain blocks
        // waiting for input the client will never send. Here the source has exactly 5 bytes and
        // the buffer is 64 KB — a request for 64 KB would hang on a real socket.
        val source = ByteArrayInputStream("12345".toByteArray())
        val sink = ByteArrayOutputStream()
        BoundedCopy.exactly(source, sink, 5, bufferSize = 64 * 1024)
        assertEquals("12345", sink.toString(Charsets.UTF_8.name()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative length is a caller bug, not an empty upload`() {
        // Treating -1 as "nothing to copy" would present an empty file as a complete one.
        BoundedCopy.exactly(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), -1)
    }
}
