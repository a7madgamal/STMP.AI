package com.mido.phonebridge

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The fingerprint the approval modal asks for — availability, and the prompt itself.
 *
 * **Why this is its own object.** Three places need to know whether a fingerprint can be asked
 * for: this screen (to enable or disable the switch), the modal (to approve), and the service (to
 * decide whether a SECURE request can demand one at all). Each was doing its own
 * `canAuthenticate` call with its own reading of the error codes, which is how two screens end up
 * disagreeing about whether the phone has a fingerprint.
 *
 * **`BIOMETRIC_STRONG` only, and that is deliberate.** This is the same authenticator set the vault
 * and the approval modal use, so the three cannot disagree. It also means a device with only a
 * weak (Class 2) sensor reports unavailable rather than offering a prompt the vault would reject.
 *
 * **Device credential is NOT accepted.** A PIN is a thing someone standing next to an unlocked
 * phone may know; a fingerprint is not. Offering the PIN here would quietly weaken the setting it
 * is meant to strengthen. Enrolling a fingerprint is the remedy, and [Status.reason] says so.
 */
object Fingerprint {

    private const val TAG = "Fingerprint"

    /** Whether a usable fingerprint exists, and if not, the sentence a human can act on. */
    sealed interface Status {
        /** A strong biometric is enrolled and the hardware is answering. */
        data object Usable : Status

        /**
         * A state a human can fix on this phone, with the reason phrased as an instruction.
         *
         * `null` never: a disabled control with no explanation is the thing this codebase keeps
         * removing, and "fingerprint unavailable" on its own tells nobody what to do.
         */
        data class Unusable(val reason: String) : Status
    }

    /** Read the current capability. Cheap enough to call from `render()`. */
    fun status(activity: FragmentActivity): Status {
        val can = try {
            BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } catch (e: Exception) {
            // A missing biometric provider throws on some OEM builds rather than returning a code.
            Log.w(TAG, "canAuthenticate threw: ${e.message}")
            return Status.Unusable(
                "Fingerprint is unavailable on this phone, so \"Fingerprint for approvals\" cannot be turned on. " +
                    "Approvals use a plain tap."
            )
        }

        return when (can) {
            BiometricManager.BIOMETRIC_SUCCESS -> Status.Usable

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Status.Unusable(
                "No fingerprint is set up on this phone. Add one in Android settings, then " +
                    "this switch becomes usable. Until then approvals use a plain tap."
            )

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Status.Unusable(
                "This phone has no fingerprint sensor, so \"Fingerprint for approvals\" cannot be turned on. " +
                    "Approvals use a plain tap."
            )

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Status.Unusable(
                "The fingerprint sensor is unavailable right now — usually a hardware fault or " +
                    "a temporary sensor error. Approvals use a plain tap until it returns."
            )

            // 7 == BIOMETRIC_ERROR_LOCKOUT: too many attempts. It clears by itself.
            7 -> Status.Unusable(
                "Too many failed fingerprint attempts, so the sensor is locked for a moment. " +
                    "It clears by itself. Approvals use a plain tap meanwhile."
            )

            else -> Status.Unusable(
                "Fingerprint cannot be used on this phone right now (code $can), so " +
                    "approvals use a plain tap."
            )
        }
    }

    /**
     * Ask for the fingerprint, and only run [onConfirmed] when one was actually read.
     *
     * **No `CryptoObject` here, unlike the vault.** This prompt authorises a SETTING rather than
     * unwrapping a key, so there is no cipher to bind. That is what makes it safe to also allow
     * turning the setting OFF: the same prompt answers both directions.
     */
    fun confirm(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onConfirmed: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val can = BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fails closed, and never shows a prompt that could not succeed.
            Log.w(TAG, "confirm: biometric unavailable (code=$can)")
            onFailed(
                (status(activity) as? Status.Unusable)?.reason
                    ?: "fingerprint unavailable (code $can)"
            )
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    onConfirmed()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // A cancelled prompt is a decision, not a fault: the setting simply does not
                    // change, and saying anything would be nagging about something they cancelled.
                    Log.i(TAG, "confirm error $errorCode: $errString")
                    onFailed(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // One bad read: the prompt stays up for a retry, so there is nothing to say.
                    Log.i(TAG, "confirm: read not recognised, retry available")
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }
}
