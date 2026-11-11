package com.mido.phonebridge

import com.mido.phonebridge.ui.PaperMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperMotionTest {
    @Test
    fun `perspective geometry makes the top about nine percent wider than the bottom`() {
        assertEquals(
            PaperMotion.PRESENT_TOP_WIDTH_FACTOR.toDouble(),
            PaperMotion.projectedTopWidthRatio(),
            0.005,
        )
    }

    @Test
    fun `a request is presented once across recreation and reshow`() {
        val registry = PaperMotion.EntranceRegistry()
        assertTrue(registry.claim(41L))
        assertFalse(registry.claim(41L))
        assertTrue(registry.claim(42L))
        assertTrue(PaperMotion.entrance(reduced = false, alreadyPresented = true).durationMs == 0L)
        assertTrue(PaperMotion.entrance(reduced = false, alreadyPresented = true).initialAlpha == 1f)
        assertFalse(PaperMotion.entrance(reduced = true, alreadyPresented = false).perspective)
        assertEquals(PaperMotion.REDUCED_FADE_MS, PaperMotion.entrance(true, false).durationMs)
    }
}
