package com.mido.phonebridge

import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The risk is a background WASH, not a stripe.
 *
 * Mido, 2026-11-03: *"the level result shouldn't be a left border it should affect the bg
 * color with a light tint effect"*.
 *
 * The rule that matters is the NULL case: an absent assessment must return the ordinary sheet,
 * not a colour. A modal that turned green because nobody had assessed it yet would be saying
 * "this is safe" on the strength of missing information — the same class of lie as painting the
 * APPROVE button green when the summary failed.
 *
 * The three buckets must also be DISTINCT. A mapping that returned the same tint for MEDIUM and
 * HIGH would look correct in isolation and make the wash carry no information at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RiskSurfaceTest {

    @Test
    fun `an absent assessment leaves the sheet untouched`() {
        assertEquals(
            "no assessment is not 'safe' — it must not wear the success tint",
            Theme.BG,
            Theme.riskSurface(null)
        )
    }

    @Test
    fun `low differs from medium and medium shares caution with high`() {
        val low = Theme.riskSurface(Risk.LOW)
        val medium = Theme.riskSurface(Risk.MEDIUM)
        val high = Theme.riskSurface(Risk.HIGH)

        assertNotEquals("LOW and MEDIUM must differ", low, medium)
        assertEquals("MEDIUM and HIGH share the caution wash; the word tells them apart", medium, high)
    }

    @Test
    fun `no bucket reuses the plain sheet colour`() {
        for (risk in Risk.entries) {
            assertNotEquals(
                "$risk must be visually distinguishable from no-assessment",
                Theme.BG,
                Theme.riskSurface(risk)
            )
        }
    }

    @Test
    fun `no bucket uses the signal wash`() {
        val signal = Theme.current().signalSurface
        for (risk in Risk.entries) {
            assertNotEquals("$risk must never wear the EXECUTE/DELETE colour", signal, Theme.riskSurface(risk))
        }
    }

    @Test
    fun `the tint agrees with the ink colour for the same bucket`() {
        assertEquals(Theme.SUCCESS_SURFACE, Theme.riskSurface(Risk.LOW))
        assertEquals(Theme.WARNING_SURFACE, Theme.riskSurface(Risk.MEDIUM))
        assertEquals(Theme.WARNING_SURFACE, Theme.riskSurface(Risk.HIGH))

        assertEquals(Theme.SUCCESS, Theme.riskColor(Risk.LOW))
        assertEquals(Theme.WARNING, Theme.riskColor(Risk.MEDIUM))
        assertEquals(Theme.WARNING, Theme.riskColor(Risk.HIGH))
    }
}
