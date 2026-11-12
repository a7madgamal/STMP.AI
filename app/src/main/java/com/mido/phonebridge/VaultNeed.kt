package com.mido.phonebridge

import com.mido.phonebridge.core.VaultState
import com.mido.phonebridge.policy.Mode

/**
 * Whether a request may proceed, must ask for the vault to be opened, or is refused outright.
 *
 * **Why this is a pure function.** The refusal it replaces is invisible from outside: a route
 * that signs with a vault-held key threw `secret_locked` while its approval rows were being
 * built, so the request died in milliseconds with **no prompt** and no way to fix it from the
 * caller's side. Reported 2026-11-11 as *"why don't you fucking use stmp bridge for gh?"* —
 * the bridge was used, four times, and answered `secret_locked` each time.
 *
 * The decision depends on four facts and none of them need a device, so it lives here where a
 * JVM test can pin every branch. `SecretVault` and `BiometricPrompt` cannot be touched off-device.
 */
object VaultNeed {

    enum class Action {
        /** Nothing about the vault stands in the way. */
        PROCEED,

        /** Locked, openable, and a human is present: raise the unlock prompt and wait. */
        UNLOCK,

        /** The vault can never be opened (orphaned, unrecoverable, or absent). Refuse. */
        REFUSE_DEAD_END,

        /** The gating mode has no surface that can show a fingerprint. Refuse. */
        REFUSE_NO_SURFACE,
    }

    /**
     * The decision, from facts only.
     *
     * Order matters and is deliberate: a dead end outranks the mode, because telling someone to
     * change their gate mode cannot fix a vault whose encryption key is gone. And `!needsVault`
     * comes first so a route that never touches the vault is unaffected by its state — the vault
     * being locked has never blocked a plain read and must not start doing so.
     */
    fun decide(
        needsVault: Boolean,
        unlocked: Boolean,
        state: VaultState.State,
        mode: Mode,
        /** Whether a prompt could actually be drawn (overlay or full-screen intent). */
        canPresent: Boolean = true,
    ): Action = when {
        !needsVault -> Action.PROCEED
        unlocked -> Action.PROCEED
        // ABSENT is a first run with no vault at all: there is nothing to unlock, and creating
        // one from inside a request is not a thing a caller may cause.
        state == VaultState.State.ABSENT -> Action.REFUSE_DEAD_END
        state == VaultState.State.UNRECOVERABLE -> Action.REFUSE_DEAD_END
        state == VaultState.State.ORPHANED -> Action.REFUSE_DEAD_END
        // DONT_DISTURB gates through a 5-second overlay that has no Activity and therefore no
        // fingerprint. Pretending to unlock there would produce a card that waits for something
        // it cannot collect.
        mode == Mode.DONT_DISTURB -> Action.REFUSE_NO_SURFACE
        // Same rule as `prompt_unreachable` on the approval prompt: with no overlay and no
        // full-screen intent, the unlock screen cannot be raised, so waiting 65 seconds would
        // report "nobody answered" for a prompt that never appeared.
        !canPresent -> Action.REFUSE_NO_SURFACE
        else -> Action.UNLOCK
    }

    /** What the caller is told. Each sentence names the one thing that would actually change it. */
    fun detail(action: Action, state: VaultState.State): String = when (action) {
        Action.PROCEED -> ""
        Action.UNLOCK ->
            "the vault was not unlocked, so this request was refused. Nothing ran."
        Action.REFUSE_NO_SURFACE ->
            "the vault is locked and no prompt can be shown to unlock it. Switch the gate to " +
                "Whitelist or Paranoid and allow draw-over-apps, or unlock the vault on the phone."
        Action.REFUSE_DEAD_END -> when (state) {
            VaultState.State.ABSENT ->
                "no vault exists on this phone yet. Create it in Preferences → Secret vault."
            VaultState.State.UNRECOVERABLE ->
                "the vault was written by an old build and can never be opened. " +
                    "Reset it in Preferences → Secret vault."
            VaultState.State.ORPHANED ->
                "the vault survived a reinstall but its encryption key did not. " +
                    "Reset it in Preferences → Secret vault."
            else -> "the vault cannot be opened."
        }
    }
}
