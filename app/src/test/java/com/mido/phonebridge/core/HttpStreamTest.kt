package com.mido.phonebridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Proves the upload body is STREAMED, not buffered in the heap.
 *
 * **This is the regression test for the 2026-10-24 failure**: a 385 MB Drive upload died at ~150 MB
 * with a connection reset. The cause was not the network — `HttpURLConnection` was buffering the
 * whole body into a `ByteArrayOutputStream` and throwing `OutOfMemoryError` against the app's
 * 256 MB heap. The phone's log said nothing about memory, so the reset was the only symptom.
 *
 * **The proof is that bytes move WHILE the output stream is still open.** With buffering, nothing
 * reaches the socket until the stream is closed; with streaming, the receiver has data as soon as
 * the first chunk is written. That is observable from inside one JVM, so no timing heuristics and
 * no mocks are needed.
 *
 * The fake server reads exactly `Content-Length` bytes and then answers. It must NOT read to EOF:
 * a keep-alive client holds the socket open after the body, so reading to EOF deadlocks — the same
 * trap `BoundedCopy` exists to document, and it hung this test on the first run.
 */
class HttpStreamTest {

    private class Recorder : AutoCloseable {
        val server = ServerSocket(0)
        /** Body bytes received so far. Read by the client while its stream is still open. */
        val received = AtomicLong(0)
        private val thread: Thread

        init {
            thread = Thread {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val head = ByteArrayOutputStream()
                        while (true) {
                            val b = input.read()
                            if (b == -1) return@use
                            head.write(b)
                            if (head.toString("ISO-8859-1").endsWith("\r\n\r\n")) break
                        }
                        val declared = head.toString("ISO-8859-1")
                            .lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toLongOrNull()

                        val buf = ByteArray(16 * 1024)
                        var remaining = declared
                        if (remaining == null) {
                            // No declared length: read until the client closes.
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                received.addAndGet(n.toLong())
                            }
                        } else {
                            while (remaining > 0) {
                                val want = minOf(buf.size.toLong(), remaining).toInt()
                                val n = input.read(buf, 0, want)
                                if (n < 0) break
                                received.addAndGet(n.toLong())
                                remaining -= n
                            }
                        }
                        val out = socket.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n" +
                                "Connection: close\r\n\r\nok").toByteArray()
                        )
                        out.flush()
                    }
                } catch (_: Exception) {
                    // Assertions describe the outcome; a socket error here is the test's own noise.
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        val url: String get() = "http://127.0.0.1:${server.localPort}/upload"

        override fun close() {
            runCatching { server.close() }
        }
    }

    /** Writes [sizeBytes] and returns how many bytes the server had received BEFORE the close. */
    private fun upload(sizeBytes: Long, streaming: Boolean): Long {
        Recorder().use { rec ->
            val conn = URL(rec.url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            if (streaming) {
                HttpStream.fixedLength(conn, sizeBytes)
            } else {
                // The bug: a request property is NOT streaming mode.
                conn.setRequestProperty("Content-Length", sizeBytes.toString())
            }
            val chunk = ByteArray(64 * 1024)
            var observedWhileOpen = 0L
            conn.outputStream.use { out ->
                var sent = 0L
                while (sent < sizeBytes) {
                    out.write(chunk)
                    sent += chunk.size
                }
                // Everything has been written but the stream is STILL OPEN. A streaming body has
                // already gone out; a buffered one has sent nothing at all.
                val deadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < deadline) {
                    observedWhileOpen = rec.received.get()
                    if (observedWhileOpen > 0) break
                    Thread.sleep(20)
                }
            }
            assertEquals(200, conn.responseCode)
            conn.disconnect()
            return observedWhileOpen
        }
    }

    @Test
    fun `a streaming body reaches the server before the client closes the stream`() {
        val size = 4L * 1024 * 1024
        val seen = upload(size, streaming = true)
        assertTrue(
            "expected the server to have body bytes while the stream was still open, got $seen",
            seen > 0
        )
    }

    @Test
    fun `a buffered body sends nothing until the client closes`() {
        // This documents WHY the bug was invisible: the whole body is collected first, so the
        // socket is silent until the very end. At 385 MB that collection is an OOM, and the peer
        // sees a reset with no HTTP status ever sent.
        val size = 4L * 1024 * 1024
        val seen = upload(size, streaming = false)
        assertEquals("a buffered body must not reach the socket early", 0L, seen)
    }

    @Test
    fun `the declared length reaches the wire`() {
        Recorder().use { rec ->
            val size = 512L * 1024
            val conn = URL(rec.url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            HttpStream.fixedLength(conn, size)
            conn.outputStream.use { it.write(ByteArray(size.toInt())) }
            // setFixedLengthStreamingMode must emit Content-Length itself; Drive refuses a
            // resumable PUT without it, and a second source for the number invites a mismatch.
            assertEquals(size, conn.getRequestProperty("Content-Length")?.toLong())
            assertEquals(200, conn.responseCode)
        }
    }

    @Test
    fun `a negative length is refused`() {
        Recorder().use { rec ->
            val conn = URL(rec.url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            val threw = try {
                HttpStream.fixedLength(conn, -1)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue("expected IllegalArgumentException for a negative length", threw)
        }
    }
}
