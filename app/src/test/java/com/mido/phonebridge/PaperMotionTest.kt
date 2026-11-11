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
        // Already on screen: no entrance, so no second announcement for something nobody did.
        assertEquals(0L, PaperMotion.entrance(alreadyPresented = true).durationMs)
        assertEquals(1f, PaperMotion.entrance(alreadyPresented = true).initialAlpha)
        // First presentation always animates, in full. There is no reduced branch: the stamp and
        // the entrance are the app's receipt for a decision, and deleting them was the bug
        // reported on 2026-11-11.
        assertTrue(PaperMotion.entrance(alreadyPresented = false).perspective)
        assertEquals(PaperMotion.PRESENT_MS, PaperMotion.entrance(alreadyPresented = false).durationMs)
    }
}
