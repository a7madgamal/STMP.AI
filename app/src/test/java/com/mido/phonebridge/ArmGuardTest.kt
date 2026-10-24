package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The misclick guard.
 *
 * Mido, 2026-10-21: *"when AI is disabled, we need the 3 seconds protection again to avoid
 * misclicking the dialog"*. With no summary to fetch, the gate armed the buttons at `shownAtMs` —
 * the instant the modal appeared — so a tap already in flight when the card arrives lands on
 * APPROVE.
 *
 * The rule is deliberately ONE function used by BOTH arm paths (with AI and without), because
 * applying it per-branch is how the two drift apart. These tests pin the boundaries, including the
 * one that matters most: the guard may only ever DELAY arming, never bring it forward.
 */
class ArmGuardTest {

    @Test
    fun `with no AI the buttons wait the full guard`() {
        val shown = 1_000_000L
        // The no-AI path passes `shownAtMs` as readyAt: decidable immediately, still not tappable.
        assertEquals(shown + ArmGuard.MISCLICK_GUARD_MS, ArmGuard.armAt(shown, shown))
        assertFalse(ArmGuard.isArmed(shown + ArmGuard.MISCLICK_GUARD_MS - 1, shown))
        // ...and they are NOT armed at the instant the modal appears, which was the bug.
        assertFalse(ArmGuard.isArmed(ArmGuard.armAt(shown, shown), shown))
    }

    @Test
    fun `a fast summary still waits for the guard`() {
        // ~1.2s is the measured summary latency. The guard must win, or the AI path would be
        // immune to the protection simply by being quick.
        val shown = 5_000L
        val ready = shown + 1_200L
        assertEquals(shown + ArmGuard.MISCLICK_GUARD_MS, ArmGuard.armAt(shown, ready))
    }

    @Test
    fun `a slow summary is not delayed further`() {
        // The guard only pushes LATER. A summary that took 6s is already past it, so the arm time
        // is the moment it landed — adding 3s on top would punish a slow model twice.
        val shown = 5_000L
        val ready = shown + 6_000L
        assertEquals(ready, ArmGuard.armAt(shown, ready))
    }

    @Test
    fun `the boundary is inclusive`() {
        // At exactly the arm time the buttons work. An off-by-one here means a countdown reaching
        // zero while the button is still dead — the same "0s but nothing happens" confusion.
        val arm = ArmGuard.armAt(0L, 0L)
        // Arguments are (armAtMs, nowMs) — the arm time stays fixed and `now` walks up to it.
        assertFalse(ArmGuard.isArmed(arm, arm - 1))
        assertTrue(ArmGuard.isArmed(arm, arm))
    }

    @Test
    fun `remaining time counts down and floors at zero`() {
        val arm = 10_000L
        assertEquals(10_000L, ArmGuard.remainingMs(arm, 0L))
        assertEquals(0L, ArmGuard.remainingMs(arm, arm))
        // Never negative, so a caller can render it unconditionally.
        assertEquals(0L, ArmGuard.remainingMs(arm, arm + 5_000L))
    }

    @Test
    fun `remaining seconds round UP so zero means armed`() {
        // A countdown that says "0s" while the buttons are still disabled is the failure this
        // rounding exists to prevent.
        val arm = 3_000L
        assertEquals(3L, ArmGuard.remainingSeconds(arm, 0L))
        // 1ms left is still "1s", not "0s".
        assertEquals(1L, ArmGuard.remainingSeconds(arm, arm - 1))
        assertEquals(0L, ArmGuard.remainingSeconds(arm, arm))
    }

    @Test
    fun `the guard is the agreed three seconds`() {
        assertEquals(3_000L, ArmGuard.MISCLICK_GUARD_MS)
    }
}
