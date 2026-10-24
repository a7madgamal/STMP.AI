package com.mido.phonebridge.core

/**
 * What state the secret vault is in, decided from three facts that are all readable without
 * a fingerprint or a Keystore unwrap.
 *
 * **Why this has to exist NOW, when it did not before.** The app was about to gain
 * `android:hasFragileUserData="true"` so a reinstall can keep the user's config. That keeps
 * `filesDir` and SharedPreferences — but the **Keystore is deleted on uninstall, always**, and
 * the vault's wrapping key (`pb_vault_key`) lives there. So the clearest possible restore
 * produces a half-state: the wrapped data key and its IV are still on disk, while the key that
 * unwraps them is gone.
 *
 * That half-state used to be invisible, and worse than invisible — actively misleading:
 *
 *  - `SecretVault.vaultKey()` **creates a fresh key when the alias is missing**. So the very act
 *    of trying to unlock would generate a brand-new VK, then fail to unwrap the old blob with
 *    it (GCM tag mismatch), and throw `vault_unwrap_failed` — an error that reads like corruption.
 *  - `isUnrecoverable()` only checks for a missing wrap IV (the 0.14.x bug), so it answered
 *    `false` and the UI went down the ordinary unlock path, which can never succeed.
 *
 * The result would be a vault that fails every unlock attempt forever, with no reset offered and
 * no honest explanation. Exactly the class of dead end Mido already lived through once with the
 * 0.14.x vault.
 *
 * **So the decision is a pure function over three booleans.** `SecretVault` cannot be
 * unit-tested — `AndroidKeyStore` does not exist on the JVM — which is how the 0.14.x IV bug
 * shipped green. `VaultStateTest` pins every combination instead, including the orphaned one
 * that only becomes reachable once data survives an uninstall.
 */
object VaultState {

    enum class State {
        /** Nothing stored yet: first run, create a vault with the biometric prompt. */
        ABSENT,

        /** Wrapped key, IV and the Keystore key are all present — an ordinary unlock. */
        UNLOCKABLE,

        /**
         * Wrapped key present but **no wrap IV** — a vault written by 0.14.x, whose data key
         * can never be unwrapped. Data-loss state; only a reset is honest.
         */
        UNRECOVERABLE,

        /**
         * Wrapped key and IV present but **the Keystore key is gone** — the vault was restored
         * onto an install whose Keystore no longer holds `pb_vault_key`. The secrets are
         * equally unrecoverable; the only difference from [UNRECOVERABLE] is the cause, and the
         * message should say so rather than blame a build bug.
         *
         * This state is UNREACHABLE without `hasFragileUserData`, because a full uninstall
         * wipes the prefs along with the key. It becomes reachable the moment data survives an
         * uninstall — which is exactly what this release turns on.
         */
        ORPHANED,
    }

    /**
     * Resolve the state from three facts, each of which must be read WITHOUT unwrapping:
     *
     * @param wrappedExists `F_DK_WRAPPED` is present (a vault was created).
     * @param ivExists `F_DK_IV` is present (the wrap IV was stored — the 0.14.x fix).
     * @param keyExists the Keystore alias `pb_vault_key` exists **right now**. This must be
     *   checked with `KeyStore.containsAlias`, never by calling `vaultKey()`, because that
     *   getter creates the key as a side effect and would erase the very evidence being read.
     */
    fun of(wrappedExists: Boolean, ivExists: Boolean, keyExists: Boolean): State = when {
        !wrappedExists -> State.ABSENT
        !ivExists -> State.UNRECOVERABLE
        !keyExists -> State.ORPHANED
        else -> State.UNLOCKABLE
    }

    /**
     * True when no unlock attempt can ever succeed, so the UI must offer a reset instead of a
     * prompt. Both dead states answer here, because the caller's action is identical and only
     * the explanatory copy differs.
     */
    fun isDead(state: State): Boolean =
        state == State.UNRECOVERABLE || state == State.ORPHANED
}
