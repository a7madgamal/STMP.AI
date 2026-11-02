package com.mido.phonebridge

import com.mido.phonebridge.plugins.tts.TtsLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TTS rules, as rules.
 *
 * These are asserted here rather than observed on the phone because they are DECISIONS — what a
 * caller may say, how long a thread may be held — and a decision that can only be checked by
 * listening to a device is a decision nobody will re-check. The engine itself is not testable on
 * the JVM; everything around it is, and that is where the numbers that matter live.
 */
class TtsLimitsTest {

    @Test
    fun `text within the cap is accepted`() {
        assertNull(TtsLimits.rejectReason("hello", TtsLimits.MAX_CHARS))
    }

    @Test
    fun `text over the cap is refused, never truncated`() {
        val cap = 10
        val reason = TtsLimits.rejectReason("x".repeat(cap + 1), cap)
        assertNotNull("over-cap text must be refused, not silently shortened", reason)
        // The caller needs both numbers to fix it: what they sent, and what is allowed.
        assertTrue("the reason must name the actual length", reason!!.contains("${cap + 1}"))
        assertTrue("the reason must name the cap", reason.contains("$cap"))
    }

    @Test
    fun `text exactly at the cap is accepted`() {
        val cap = 10
        assertNull("the cap is inclusive", TtsLimits.rejectReason("x".repeat(cap), cap))
    }

    @Test
    fun `blank text is refused`() {
        assertNotNull(TtsLimits.rejectReason("", 400))
        assertNotNull(TtsLimits.rejectReason("   ", 400))
    }

    @Test
    fun `the cap cannot be configured above the ceiling`() {
        // "Make the cap bigger" must not become "read me a novel": the route blocks until the
        // utterance ends, so an unbounded text pins a server thread.
        assertEquals(TtsLimits.MAX_CHARS_CEILING, TtsLimits.clampCap(999_999))
        assertEquals(400, TtsLimits.clampCap(400))
        // A nonsense zero would make every request fail, so it falls back rather than refusing all.
        assertEquals(TtsLimits.MAX_CHARS, TtsLimits.clampCap(0))
        assertEquals(TtsLimits.MAX_CHARS, TtsLimits.clampCap(-5))
    }

    @Test
    fun `rate and pitch are clamped into the engine's own range`() {
        assertEquals(TtsLimits.MAX_RATE, TtsLimits.clampRate(99f))
        assertEquals(TtsLimits.MIN_RATE, TtsLimits.clampRate(0.01f))
        assertEquals(1.0f, TtsLimits.clampRate(1.0f))
        assertEquals(1.0f, TtsLimits.clampRate(Float.NaN))

        assertEquals(TtsLimits.MAX_PITCH, TtsLimits.clampPitch(99f))
        assertEquals(TtsLimits.MIN_PITCH, TtsLimits.clampPitch(0f))
        assertEquals(1.0f, TtsLimits.clampPitch(Float.NaN))
    }

    @Test
    fun `queue depth is never zero`() {
        // 0 would mean "refuse unless idle", which is a route that answers busy almost always.
        assertEquals(1, TtsLimits.clampQueueDepth(0))
        assertEquals(1, TtsLimits.clampQueueDepth(-3))
        assertEquals(TtsLimits.MAX_QUEUE_DEPTH, TtsLimits.clampQueueDepth(1_000))
    }

    @Test
    fun `the timeout grows with the cap and is never zero`() {
        val small = TtsLimits.timeoutMsFor(1)
        val large = TtsLimits.timeoutMsFor(400)
        assertTrue("a longer text needs a longer deadline", large > small)
        assertTrue("a deadline must exist", small > 0)
        // The ceiling is the bound that matters: a configured cap above it cannot stretch the
        // deadline, because [clampCap] would have refused that value anyway.
        assertEquals(
            TtsLimits.timeoutMsFor(TtsLimits.MAX_CHARS_CEILING),
            TtsLimits.timeoutMsFor(TtsLimits.MAX_CHARS_CEILING * 10)
        )
    }
}
