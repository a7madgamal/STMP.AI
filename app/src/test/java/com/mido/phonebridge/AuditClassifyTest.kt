package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditClassify
import com.mido.phonebridge.audit.AuditNoise
import com.mido.phonebridge.audit.AuditOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the classification that the device log got wrong on 2026-10-17.
 *
 * `?count=-1` was recorded as HANDLER_FAILED — a screaming server fault — for a
 * typo in a URL. That put a fake 500 in the signal stream every time a caller got
 * a parameter wrong, and, because HANDLER_FAILED is deliberately not noise, the
 * typo also escaped the default "noise hidden" view.
 *
 * These tests describe the contract, not the implementation: bad input is the
 * caller's fault and is noise; a failure after a human approved is a real fault
 * and is signal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditClassifyTest {

    @Test
    fun `a 4xx before the gate is the caller's mistake and is noise`() {
        val outcome = AuditClassify.forError(status = 400, gated = false)
        assertEquals(AuditOutcome.REJECTED_INVALID, outcome)
        assertTrue("bad input must stay out of the default view", AuditNoise.isNoise(outcome, "/notifications"))
    }

    @Test
    fun `every pre-gate 4xx narrows to REJECTED_INVALID, not just 400`() {
        for (status in listOf(400, 404, 409, 410, 422)) {
            assertEquals(
                "status $status before the gate",
                AuditOutcome.REJECTED_INVALID,
                AuditClassify.forError(status, gated = false)
            )
        }
    }

    @Test
    fun `a 5xx is a fault even before the gate`() {
        val outcome = AuditClassify.forError(status = 500, gated = false)
        assertEquals(AuditOutcome.HANDLER_FAILED, outcome)
        assertFalse("a crash must never be hidden as noise", AuditNoise.isNoise(outcome, "/notifications"))
    }

    @Test
    fun `a 4xx AFTER approval is signal, because it wasted a human decision`() {
        // The human approved something that then failed on bad input. That is not
        // noise: the log has to explain why a decision produced nothing.
        val outcome = AuditClassify.forError(status = 404, gated = true)
        assertEquals(AuditOutcome.HANDLER_FAILED, outcome)
        assertFalse(AuditNoise.isNoise(outcome, "/gmail/messages/{id}"))
    }

    @Test
    fun `a crash is always HANDLER_FAILED`() {
        assertEquals(AuditOutcome.HANDLER_FAILED, AuditClassify.forCrash())
    }

    @Test
    fun `unrouted is noise but a handler failure is not`() {
        assertTrue(AuditNoise.isNoise(AuditOutcome.UNROUTED, "/status"))
        assertFalse(AuditNoise.isNoise(AuditOutcome.HANDLER_FAILED, "/notifications"))
    }

    @Test
    fun `an approved request is never noise, whatever the path`() {
        assertFalse(AuditNoise.isNoise(AuditOutcome.APPROVED, "/status"))
        assertFalse(AuditNoise.isNoise(AuditOutcome.APPROVED, "/notifications"))
    }
}
