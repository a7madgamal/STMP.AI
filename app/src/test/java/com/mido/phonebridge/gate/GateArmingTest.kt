package com.mido.phonebridge.gate

import com.mido.phonebridge.ArmGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP03a 8.1 and the D19 agreement check: the guard, the timer text and the latch agree. */
class GateArmingTest {

    private val shown = 50_000L
    private val enabledAt = ArmGuard.armAt(shown, shown) // AI off: armed at shown + guard

    @Test
    fun `no arm time is summary pending`() {
        assertEquals(ArmPhase.SummaryPending, GateArming.phase(0L, shown, 60))
        assertEquals("waiting for the AI summary", GateArming.text(ArmPhase.SummaryPending))
    }

    @Test
    fun `the agreement check with AI off`() {
        assertEquals(shown + ArmGuard.MISCLICK_GUARD_MS, enabledAt)
        val at0 = GateArming.phase(enabledAt, shown, 60)
        assertEquals(ArmPhase.Guard(1), at0)
        assertEquals("ready in 1 s", GateArming.text(at0))
        assertEquals(ArmPhase.Guard(1), GateArming.phase(enabledAt, shown + 999, 60))
        val armed = GateArming.phase(enabledAt, shown + 1000, 60)
        assertEquals(ArmPhase.Armed(60), armed)
        assertEquals("auto-deny in 1:00", GateArming.text(armed))
    }

    @Test
    fun `armed is inclusive at the arm time`() {
        assertTrue(GateArming.phase(enabledAt, enabledAt, 60) is ArmPhase.Armed)
        assertTrue(GateArming.phase(enabledAt, enabledAt - 1, 60) is ArmPhase.Guard)
    }

    @Test
    fun `timer text`() {
        assertEquals("auto-deny in 0:47", GateArming.text(GateArming.phase(enabledAt, enabledAt + 13_000, 60)))
        assertEquals("auto-deny in 0:00", GateArming.text(GateArming.phase(enabledAt, enabledAt + 60_000, 60)))
    }

    @Test
    fun `seconds left are never negative`() {
        val late = GateArming.phase(enabledAt, enabledAt + 600_000, 60)
        assertEquals(ArmPhase.Armed(0), late)
        for (t in listOf(shown - 5_000, shown, shown + 500)) {
            val p = GateArming.phase(enabledAt, t, 60)
            assertTrue("$p", p is ArmPhase.Guard && p.secondsLeft >= 1)
        }
    }
}
