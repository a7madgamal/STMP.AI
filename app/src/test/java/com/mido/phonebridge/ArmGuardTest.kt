package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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

    // --- the NOTIFY overlay's decision gate ---

    @Test
    fun `a notify card refuses decisions for the first three seconds`() {
        // Mido, 2026-10-24: "add the same 3 seconds waiting period before ANY touch event on the
        // notification works so that I don't approve or cancel by mistake". The overlay approves on
        // a plain tap and had NO guard, unlike the modal — so a card arriving under a moving finger
        // could approve a request nobody read.
        val shown = 5_000_000L
        assertFalse("a tap at t=0 must not decide", ArmGuard.decisionsAllowed(shown, shown))
        assertFalse(ArmGuard.decisionsAllowed(shown, shown + 2_999L))
        assertTrue("the guard expires exactly at 3s", ArmGuard.decisionsAllowed(shown, shown + 3_000L))
        assertTrue(ArmGuard.decisionsAllowed(shown, shown + 10_000L))
    }

    @Test
    fun `an unattached card allows no decision`() {
        // `Entry.shownAt` is 0 until the view is attached. A real clock reading is far past
        // 0 + 3000, so treating 0 as a timestamp would leave an unattached card fully armed.
        assertFalse(ArmGuard.decisionsAllowed(0L, System.currentTimeMillis()))
        assertFalse(ArmGuard.decisionsAllowed(-1L, 10_000L))
    }

    @Test
    fun `the overlay guard and the modal guard are the same duration`() {
        // The two surfaces must not drift: "how soon can a human accidentally agree" is a property
        // of the gate, not of which UI happened to show.
        val shown = 1_000L
        val modalArmedAt = ArmGuard.armAt(shown, shown)
        val overlayAllowsAt = shown + ArmGuard.MISCLICK_GUARD_MS
        assertEquals(modalArmedAt, overlayAllowsAt)
    }
}
