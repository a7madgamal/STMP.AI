package com.mido.phonebridge

import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.SecretVault

/**
 * Unlocking the secret vault, in one place.
 *
 * **Why this is not a private method on a screen any more.** It was `MainActivity.lockOnLoad`,
 * which owned four things that only make sense together: the once-per-process prompt guard, the
 * two unrecoverable states, the reset dialog, and the unlock itself. The vault row moved to
 * Settings, so the logic had to either move with it or be duplicated — and a duplicated copy of a
 * biometric flow is the kind of thing that drifts silently, because the second copy is the one
 * nobody exercises.
 *
 * The states it distinguishes are not cosmetic:
 *
 *  - **Unrecoverable (0.14.x)** — the data key was wrapped without storing the IV, and AES-GCM
 *    cannot be unwrapped without it. The secrets are gone.
 *  - **Orphaned** — the prefs survived a reinstall but the Keystore key did not. Calling the
 *    normal unlock path here is a trap: `vaultKey()` mints a fresh key when the alias is missing,
 *    so the prompt appears, accepts a fingerprint, and then fails to unwrap every single time.
 *
 * Both dead ends get a reset offer rather than a cipher error on every attempt.
 */
object VaultUnlock {

    /** Why the last unlock or create attempt failed, or null. Shown by Settings. */
    @Volatile
    var lastFailure: String? = null

    /**
     * Prompt to unlock (or create) the vault, once.
     *
     * @param force skip the once-per-process guard: the human tapped the row asking for it.
     * @param onChanged called after the state changed, so the caller can repaint.
     */
    fun ensureUnlocked(
        activity: FragmentActivity,
        force: Boolean = false,
        onChanged: () -> Unit,
    ) {
        if (SecretVault.isUnlocked()) return
        if (PromptGuard.attempted && !force) return
        PromptGuard.attempted = true

        if (SecretVault.isUnrecoverable(activity)) {
            offerReset(activity, iVMissing = true, onChanged)
            return
        }
        if (SecretVault.isOrphaned(activity)) {
            offerReset(activity, iVMissing = false, onChanged)
            return
        }

        if (VaultGate.hasVault(activity)) {
            VaultGate.unlock(
                activity,
                onUnlocked = { lastFailure = null; Debug.log("vault unlocked (session open)"); onChanged() },
                onFailed = { lastFailure = it; Debug.log("vault unlock not completed: $it"); onChanged() },
            )
        } else {
            // First run: the same prompt authorises creating the vault.
            VaultGate.initialize(
                activity,
                onReady = { lastFailure = null; Debug.log("vault created (session open)"); onChanged() },
                onFailed = { lastFailure = it; Debug.log("vault creation not completed: $it"); onChanged() },
            )
        }
    }

    /**
     * The once-per-process guard.
     *
     * Deliberately NOT per-activity: `onResume` runs on every screen switch, and a prompt that
     * reappears each time the human navigates is a prompt they learn to dismiss without reading —
     * which is exactly how a biometric gate stops being a gate.
     */
    private object PromptGuard {
        var attempted = false
    }

    /** Tests and a fresh process: forget that a prompt was attempted. */
    fun resetGuardForTest() {
        PromptGuard.attempted = false
    }

    private fun offerReset(
        activity: FragmentActivity,
        iVMissing: Boolean,
        onChanged: () -> Unit,
    ) {
        if (ResetGuard.offered) return
        ResetGuard.offered = true

        // Two causes, one remedy, and the copy has to name the right one. Telling someone an old
        // build destroyed their vault when the truth is "you reinstalled and kept the config" is
        // a lie that makes them distrust the app instead of understanding it.
        val message = if (iVMissing) {
            "This vault was created by an earlier build that saved the key without the IV it " +
                "needs to be reopened. The secrets inside it cannot be recovered — this is a bug " +
                "in that build, not something you did.\n\n" +
                "Resetting clears the vault so you can create a fresh one and re-add your " +
                "secrets. Nothing else on the phone is affected."
        } else {
            "Your config was kept across the reinstall, but the vault's encryption key could not " +
                "come with it — Android deletes app keys on uninstall, always.\n\n" +
                "So the secrets themselves are gone. Your exceptions, request log and account are " +
                "all intact. Resetting clears the dead vault so you can create a fresh one and " +
                "re-add your secrets; nothing else is touched."
        }

        com.mido.phonebridge.ui.kit.StmpDialog.confirmDelete(
            activity,
            title = "Secret vault needs a reset",
            message = message,
            deleteLabel = "Reset vault",
            keepLabel = "Not now",
        ) {
            SecretVault.destroy(activity)
            Debug.log("vault reset (${if (iVMissing) "0.14.x" else "orphaned keystore"})")
            lastFailure = null
            PromptGuard.attempted = false
            ResetGuard.offered = false
            ensureUnlocked(activity, force = true, onChanged = onChanged)
        }.show()
    }

    private object ResetGuard {
        var offered = false
    }
}
