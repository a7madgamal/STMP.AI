package com.mido.phonebridge

import com.mido.phonebridge.ai.SummaryFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failure must state its REAL cause, in a unit a human can read.
 *
 * WHY THIS EXISTS
 *   The modal used to say *"the model did not answer within 10000ms"* for every failure,
 *   including ones that finished in 1.4 seconds. `LiteRtLm.lastEngineError` is only set by
 *   ENGINE faults, so a guard rejection, a budget overrun and an unparseable reply all fell
 *   through to `.ifBlank { ... }` and reported a timeout that never happened.
 *
 *   Mido, 2026-11-03: *"I got couldn't summarize in 10000ms but it was never full 10 seconds.
 *   and write the message in seconds"*.
 *
 * WHAT THIS PINS
 *   1. A recorded reason is returned verbatim — never replaced by a plausible guess.
 *   2. An UNRECORDED failure says so, and names itself as a defect rather than inventing a cause.
 *   3. Durations read as seconds, not raw milliseconds, and a whole number keeps no decimal.
 */
class SummaryFailureTest {

    @After
    fun clear() {
        SummaryFailure.clear()
    }

    @Test
    fun `a stated reason is returned unchanged`() {
        SummaryFailure.record("the model described a READ as \"Creating contacts\"")
        assertEquals(
            "the caller must see what actually failed, not a substitute",
            "the model described a READ as \"Creating contacts\"",
            SummaryFailure.take()
        )
    }

    @Test
    fun `an unstated reason names itself as a defect instead of inventing a cause`() {
        SummaryFailure.clear()
        val reason = SummaryFailure.take()

        assertEquals(SummaryFailure.UNSTATED, reason)
        assertTrue(
            "it must say this is the app's fault, so nobody hunts the model or a timeout",
            reason.contains("defect")
        )
        // The specific lie this replaced. If this string ever comes back from an unstated
        // failure, the old fallback has been reintroduced somewhere.
        assertFalse(
            "an unstated failure must NEVER claim a timeout — that was the original bug",
            reason.contains("did not answer within")
        )
    }

    @Test
    fun `clearing between attempts stops a stale cause describing a new failure`() {
        SummaryFailure.record("an old reason from a previous attempt")
        SummaryFailure.clear()
        assertEquals(SummaryFailure.UNSTATED, SummaryFailure.take())
    }

    @Test
    fun `durations are phrased in seconds`() {
        assertEquals("10s", SummaryFailure.humanMs(10_000L))
        assertEquals("60s", SummaryFailure.humanMs(60_000L))
        assertEquals("2s", SummaryFailure.humanMs(2_000L))
    }

    @Test
    fun `a whole number of seconds carries no decimal point`() {
        // "10.0s" reads like a measurement nobody took; "10s" reads like a budget.
        assertFalse(SummaryFailure.humanMs(10_000L).contains('.'))
    }

    @Test
    fun `a fractional duration keeps one decimal`() {
        assertEquals("1.5s", SummaryFailure.humanMs(1_500L))
        assertEquals("2.3s", SummaryFailure.humanMs(2_300L))
    }

    @Test
    fun `a sub-second duration keeps milliseconds rather than rounding to zero`() {
        // "0s" would say nothing happened. The whole point of this change is that a fast
        // failure must not look like a timeout or like nothing at all.
        assertEquals("250ms", SummaryFailure.humanMs(250L))
        assertEquals("1ms", SummaryFailure.humanMs(1L))
    }
}
