package com.mido.phonebridge

import com.mido.phonebridge.core.VaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guard for [VaultState] — the rule that decides whether a vault can ever open.
 *
 * **Why this exists.** `SecretVault` cannot be tested: `AndroidKeyStore` does not exist on the
 * JVM, so the suite cannot construct a wrapped key or unwrap one. That is how the 0.14.x bug
 * (wrapping the data key without storing its IV) shipped with a green build and left every
 * vault it created permanently unopenable.
 *
 * This release adds a second dead state to the same class of problem: `hasFragileUserData`
 * keeps prefs across an uninstall, the Keystore does not follow, and the result is a vault whose
 * key is gone. Without [VaultState.ORPHANED] the app would prompt for a fingerprint forever and
 * never offer a reset — because `vaultKey()` silently mints a replacement key and the old blob
 * then fails its GCM tag.
 *
 * Every reachable combination is pinned below, and the ORPHANED case is the one that only
 * becomes reachable because of this release, so it is tested first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VaultStateTest {

    @Test
    fun `prefs survived but the keystore key did not is ORPHANED`() {
        // The reinstall case this release creates: wrapped key + IV restored, Keystore wiped.
        assertEquals(
            VaultState.State.ORPHANED,
            VaultState.of(wrappedExists = true, ivExists = true, keyExists = false),
        )
    }

    @Test
    fun `both dead states are DEAD, so the UI offers a reset`() {
        assertTrue(VaultState.isDead(VaultState.State.ORPHANED))
        assertTrue(VaultState.isDead(VaultState.State.UNRECOVERABLE))
    }

    @Test
    fun `a healthy vault is unlockable and not dead`() {
        val state = VaultState.of(wrappedExists = true, ivExists = true, keyExists = true)
        assertEquals(VaultState.State.UNLOCKABLE, state)
        assertFalse(VaultState.isDead(state))
    }

    @Test
    fun `first run is ABSENT, not a failure`() {
        val state = VaultState.of(wrappedExists = false, ivExists = false, keyExists = false)
        assertEquals(VaultState.State.ABSENT, state)
        assertFalse(VaultState.isDead(state))
    }

    @Test
    fun `a missing IV is UNRECOVERABLE regardless of the key`() {
        // The 0.14.x shape. The key may or may not be present; the missing IV alone is fatal,
        // and it must win over ORPHANED so the message blames the right cause.
        assertEquals(
            VaultState.State.UNRECOVERABLE,
            VaultState.of(wrappedExists = true, ivExists = false, keyExists = true),
        )
        assertEquals(
            VaultState.State.UNRECOVERABLE,
            VaultState.of(wrappedExists = true, ivExists = false, keyExists = false),
        )
    }

    @Test
    fun `an absent vault is never reported as dead`() {
        // Guarding against the classic inversion: "no wrap IV" is also true on first run, so
        // checking the IV without first checking that a vault EXISTS would offer a reset dialog
        // to a brand-new user.
        assertFalse(VaultState.isDead(VaultState.of(false, false, false)))
        assertFalse(VaultState.isDead(VaultState.of(false, false, true)))
    }

    @Test
    fun `only UNLOCKABLE permits an unlock attempt`() {
        // The practical contract: every other state either creates a vault (ABSENT) or resets it.
        // If a state were added without updating this expectation, the assert below is what
        // catches someone routing it to the ordinary prompt.
        val attempts = VaultState.State.entries.filter {
            it != VaultState.State.ABSENT && !VaultState.isDead(it)
        }
        assertEquals(listOf(VaultState.State.UNLOCKABLE), attempts)
    }
}
