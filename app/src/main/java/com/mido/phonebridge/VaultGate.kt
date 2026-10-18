package com.mido.phonebridge

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.SecretVault

/**
 * The one place the biometric prompt is bound to the vault (plan D9).
 *
 * App lock on load IS the vault unlock: the same prompt carries a `CryptoObject`
 * bound to the vault key, so the finger that authenticates the app also unwraps the
 * data key. One scan, both jobs — and it cannot be faked, because the cipher will
 * not initialise without a fresh finger.
 *
 * Fingerprint-only by construction: a crypto-bound prompt cannot accept
 * `DEVICE_CREDENTIAL`, so there is no PIN/pattern escape hatch. That is the
 * deliberate trade recorded in the plan, not an oversight — so this asks for
 * `BIOMETRIC_STRONG` alone and fails closed when it is unavailable.
 *
 * Two entry points, one per state:
 *  - [unlock] when a vault already exists;
 *  - [initialize] on first run, where the same prompt authorises creating it.
 */
object VaultGate {

    private const val TAG = "VaultGate"

    /** Callbacks are always on the main thread (BiometricPrompt's executor). */
    fun unlock(activity: FragmentActivity, onUnlocked: () -> Unit, onFailed: (String) -> Unit) {
        val cipher = try {
            SecretVault.prepareUnlockCipher(activity)
        } catch (e: Exception) {
            // Could not even prepare the cipher: a corrupted/invalidated key. Fail loud
            // rather than show a prompt that could never succeed.
            onFailed("could not prepare the vault cipher: ${e.message}")
            return
        }
        prompt(activity, cipher, "Unlock PhoneBridge", "Confirm it's you", { authorised ->
            try {
                SecretVault.unlock(activity, authorised)
                onUnlocked()
            } catch (e: Exception) {
                onFailed(e.message ?: "unlock failed")
            }
        }, onFailed)
    }

    fun initialize(activity: FragmentActivity, onReady: () -> Unit, onFailed: (String) -> Unit) {
        val cipher = try {
            SecretVault.prepareInitCipher(activity)
        } catch (e: Exception) {
            onFailed("could not prepare the vault cipher: ${e.message}")
            return
        }
        prompt(activity, cipher, "Create the vault", "Set up encrypted secret storage", {
            try {
                SecretVault.initialize(activity, it)
                onReady()
            } catch (e: Exception) {
                onFailed(e.message ?: "vault init failed")
            }
        }, onFailed)
    }

    /**
     * [onAuthed] receives the AUTHORISED cipher, not a boolean: the caller must do the
     * crypto with exactly the object the finger authorised, or the guarantee is gone.
     */
    private fun prompt(
        activity: FragmentActivity,
        cipher: javax.crypto.Cipher,
        title: String,
        subtitle: String,
        onAuthed: (javax.crypto.Cipher) -> Unit,
        onFailed: (String) -> Unit
    ) {
        // Same authenticator set as the crypto binding: STRONG biometric only. The
        // button state and the prompt can therefore never disagree.
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val can = BiometricManager.from(activity).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fails closed: no prompt, no unlock. Named so the UI can say why.
            Log.w(TAG, "biometric unavailable (code=$can)")
            onFailed("fingerprint unavailable (code $can) — enrol a fingerprint in system settings")
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorised = result.cryptoObject?.cipher
                    if (authorised == null) {
                        // Should be impossible: we passed a CryptoObject. If it happens,
                        // refuse rather than fall back to "authenticated, so allow" —
                        // the whole point is that the cipher was authorised.
                        onFailed("biometric succeeded but returned no authorised cipher")
                        return
                    }
                    onAuthed(authorised)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.i(TAG, "biometric error $errorCode: $errString")
                    onFailed(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // One bad read: the prompt stays up for a retry. Nothing to do.
                    Log.i(TAG, "biometric failed (retryable)")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Is a vault key already present (i.e. has the vault ever been created)? */
    fun hasVault(ctx: Context): Boolean = SecretVault.exists(ctx)
}
