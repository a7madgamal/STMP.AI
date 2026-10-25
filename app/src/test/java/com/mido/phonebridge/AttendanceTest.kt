package com.mido.phonebridge

import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.isAttended
import com.mido.phonebridge.policy.unattendedAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unattended rule (Mido, 2026-10-25): *"for notification level calls it shouldn't matter if
 * the screen was off or the phone locked or not, as long as the app was unlocked once for storage
 * unlock"*.
 *
 * **This is the most consequential rule in the app, so it is the one that most needs a test.** It
 * decides whether a request runs with nobody looking. `NOTIFY` approves on silence, and a dark
 * screen guarantees silence — so returning `true` here is unattended approval, not a card that was
 * ignored. That is the accepted trade, and these cases pin its boundary so a future edit cannot
 * widen it by accident.
 */
class AttendanceTest {

    @Test
    fun `unattended means screen off or locked, and both deny attendance`() {
        assertTrue(isAttended(screenInteractive = true, keyguardLocked = false))
        // Screen on but locked: nobody is reading a card behind a lockscreen.
        assertFalse(isAttended(screenInteractive = true, keyguardLocked = true))
        // Unlocked but dark: same.
        assertFalse(isAttended(screenInteractive = false, keyguardLocked = false))
        assertFalse(isAttended(screenInteractive = false, keyguardLocked = true))
    }

    @Test
    fun `a NOTIFY request may proceed unattended once the vault is unlocked`() {
        // The rule itself.
        assertTrue(unattendedAllowed(Mode.NOTIFY, vaultUnlocked = true))
    }

    @Test
    fun `a NOTIFY request is still denied unattended when the vault is locked`() {
        // The bound: no session, no trust. This is what stops a cold-booted phone from
        // approving things it has never been unlocked for.
        assertFalse(unattendedAllowed(Mode.NOTIFY, vaultUnlocked = false))
    }

    @Test
    fun `SECURE never proceeds unattended, whatever the vault says`() {
        // The line that must never blur. A SECURE modal exists to require a present human, and
        // its fingerprint is bound to the vault — but a vault session is NOT a substitute for a
        // human. If this ever returns true, the fingerprint gate is silently off.
        assertFalse(unattendedAllowed(Mode.SECURE, vaultUnlocked = true))
        assertFalse(unattendedAllowed(Mode.SECURE, vaultUnlocked = false))
    }

    @Test
    fun `every mode is classified`() {
        // Exhaustive by construction, but stated as a test so adding a Mode forces a decision
        // here rather than inheriting whichever branch the compiler happens to allow.
        for (mode in Mode.entries) {
            val allowed = unattendedAllowed(mode, vaultUnlocked = true)
            assertEquals(
                "mode=$mode",
                mode == Mode.NOTIFY,
                allowed,
            )
        }
    }

    @Test
    fun `the rule is verb-agnostic by design`() {
        // Mido's explicit call: "regardless of verb". Stated here because the absence of a Verb
        // parameter is deliberate and easy to "fix" later. The blast radius is bounded by POLICY
        // instead: only a route someone marked NOTIFY behaves this way.
        //
        // This test exists so that adding a Verb parameter would fail loudly and force the
        // conversation rather than quietly narrowing the rule.
        assertTrue(unattendedAllowed(Mode.NOTIFY, vaultUnlocked = true))
    }
}
