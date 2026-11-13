package com.mido.phonebridge

import com.mido.phonebridge.core.VaultState
import com.mido.phonebridge.policy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A locked vault must ASK, not refuse — and asking must never be consent.
 *
 * Reported 2026-11-12: the bridge was used four times to run `gh` on the host, and every call
 * answered `secret_locked` in under 20ms with no prompt on the phone. The cause was structural:
 * SSH reads its key while building the approval card, before the gate exists, so there was nowhere
 * to put a fingerprint. From the outside it looked like nobody was paying attention.
 *
 * The rule this pins: a route that needs the vault gets a prompt when a prompt is possible, a
 * named refusal when it is not, and never a silent wait.
 */
class VaultNeedTest {

    @Test
    fun `a route that does not touch the vault is unaffected by its state`() {
        // The vault being shut has never blocked a plain read and must not start doing so.
        assertEquals(
            VaultNeed.Action.PROCEED,
            VaultNeed.decide(
                needsVault = false,
                unlocked = false,
                state = VaultState.State.UNLOCKABLE,
                mode = Mode.PARANOID,
            ),
        )
    }

    @Test
    fun `an open vault proceeds without a prompt`() {
        assertEquals(
            VaultNeed.Action.PROCEED,
            VaultNeed.decide(
                needsVault = true,
                unlocked = true,
                state = VaultState.State.UNLOCKABLE,
                mode = Mode.PARANOID,
            ),
        )
    }

    @Test
    fun `locked and openable asks for the fingerprint`() {
        assertEquals(
            VaultNeed.Action.UNLOCK,
            VaultNeed.decide(
                needsVault = true,
                unlocked = false,
                state = VaultState.State.UNLOCKABLE,
                mode = Mode.PARANOID,
            ),
        )
    }

    @Test
    fun `every dead end refuses instead of prompting`() {
        // A fingerprint that can never succeed is a lie dressed as a prompt. Each of these needs
        // a human to do something else entirely, and the message must name it.
        for (state in listOf(
            VaultState.State.ABSENT,
            VaultState.State.UNRECOVERABLE,
            VaultState.State.ORPHANED,
        )) {
            assertEquals(
                "state $state must refuse, not prompt",
                VaultNeed.Action.REFUSE_DEAD_END,
                VaultNeed.decide(
                    needsVault = true,
                    unlocked = false,
                    state = state,
                    mode = Mode.PARANOID,
                ),
            )
            assertEquals(
                "state $state must explain itself",
                true,
                VaultNeed.detail(VaultNeed.Action.REFUSE_DEAD_END, state).isNotEmpty(),
            )
        }
    }

    @Test
    fun `dont disturb has no surface for a fingerprint`() {
        // The overlay card is a 5-second window with no Activity, so it cannot collect a scan.
        assertEquals(
            VaultNeed.Action.REFUSE_NO_SURFACE,
            VaultNeed.decide(
                needsVault = true,
                unlocked = false,
                state = VaultState.State.UNLOCKABLE,
                mode = Mode.DONT_DISTURB,
            ),
        )
    }

    @Test
    fun `a prompt that cannot be displayed refuses rather than waiting`() {
        // Same rule as `prompt_unreachable`: with no overlay and no full-screen intent the unlock
        // screen cannot be raised, so a 65-second wait would report "nobody answered" for a
        // prompt nobody ever saw.
        assertEquals(
            VaultNeed.Action.REFUSE_NO_SURFACE,
            VaultNeed.decide(
                needsVault = true,
                unlocked = false,
                state = VaultState.State.UNLOCKABLE,
                mode = Mode.PARANOID,
                canPresent = false,
            ),
        )
    }

    @Test
    fun `a dead end outranks the gate mode`() {
        // Telling someone to change their mode cannot fix a vault whose key is gone, so the mode
        // must not be the message they receive.
        assertEquals(
            VaultNeed.Action.REFUSE_DEAD_END,
            VaultNeed.decide(
                needsVault = true,
                unlocked = false,
                state = VaultState.State.ORPHANED,
                mode = Mode.DONT_DISTURB,
            ),
        )
    }
}
