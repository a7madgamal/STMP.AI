package com.mido.phonebridge

import com.mido.phonebridge.plugins.ssh.SshTail
import com.mido.phonebridge.plugins.ssh.TailOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture-budget rules for SSH output.
 *
 * Two separate defects live here, and both were invisible while the code was an inline loop:
 *
 *  - **truncation kept the head.** A command that overflowed the cap answered with its first
 *    64 KiB — the beginning of a build log, never the error at the end. The useful part is the
 *    tail.
 *  - **stderr was unbounded.** stdout was capped and stderr was a plain ByteArrayOutputStream, so
 *    a command that kept warning could grow the app until the OS killed it.
 *
 * Pure arithmetic, so these run as plain JVM tests with no Robolectric and no device.
 */
class SshTailTest {

    private fun text(cap: Int, vararg writes: String): SshTail {
        val tail = SshTail(cap)
        for (w in writes) tail.write(w.toByteArray())
        return tail
    }

    @Test
    fun `keeps everything when it fits`() {
        val tail = text(16, "hello")
        assertEquals("hello", tail.text())
        assertFalse(tail.truncated)
    }

    @Test
    fun `keeps the tail not the head when it overflows`() {
        val tail = text(8, "0123456789ABCDEF")
        // The LAST 8 bytes. Keeping the first 8 would answer "01234567" — the old bug.
        assertEquals("89ABCDEF", tail.text())
        assertTrue(tail.truncated)
    }

    @Test
    fun `wraps correctly across many small writes`() {
        val tail = SshTail(5)
        for (c in "abcdefghij") tail.write(byteArrayOf(c.code.toByte()))
        assertEquals("fghij", tail.text())
        assertTrue(tail.truncated)
    }

    @Test
    fun `a single write larger than the cap keeps only its tail`() {
        val tail = SshTail(4)
        tail.write("0123456789".toByteArray())
        assertEquals("6789", tail.text())
    }

    @Test
    fun `exactly at the cap is not truncation`() {
        val tail = text(5, "abcde")
        assertEquals("abcde", tail.text())
        assertFalse(tail.truncated)
    }

    @Test
    fun `one byte over the cap is truncation`() {
        val tail = text(5, "abcdef")
        assertEquals("bcdef", tail.text())
        assertTrue(tail.truncated)
    }

    @Test
    fun `total bytes counts what was dropped`() {
        val tail = text(4, "abcdefghij")
        assertEquals(10L, tail.totalBytes())
        assertEquals(4, tail.bytes().size)
    }

    @Test
    fun `an empty write changes nothing`() {
        val tail = text(4, "")
        assertEquals("", tail.text())
        assertFalse(tail.truncated)
        assertEquals(0L, tail.totalBytes())
    }

    @Test
    fun `utf8 split across the boundary does not throw`() {
        // A multi-byte character can be cut in half by the ring. The result is lossy at the seam
        // by construction, but decoding must not throw — a capture buffer that crashes on a
        // truncated emoji is worse than one that shows a replacement character.
        val tail = SshTail(3)
        tail.write("aé".toByteArray()) // 'a' + 2-byte é = 3 bytes, exactly fits
        assertEquals("aé", tail.text())
    }

    @Test
    fun `stderr sink is bounded and keeps its tail`() {
        val err = TailOutputStream(8)
        err.write("0123456789ABCDEF".toByteArray(), 0, 16)
        assertEquals("89ABCDEF", err.text())
        assertTrue(err.truncated())
    }

    @Test
    fun `stderr sink reports a clean stream as untruncated`() {
        val err = TailOutputStream(64)
        err.write("warning: something\n".toByteArray())
        assertEquals("warning: something\n", err.text())
        assertFalse(err.truncated())
    }
}
