package com.mido.phonebridge.core

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log

/**
 * How the vault key (VK) is specified and generated, kept apart from [SecretVault] so the
 * decisions can be tested without an `AndroidKeyStore`.
 *
 * **Each attempt gets its own spec.** The StrongBox attempt and the TEE fallback used to share
 * one `KeyGenParameterSpec.Builder`, and the StrongBox attempt set `setIsStrongBoxBacked(true)`
 * on it. The builder kept that flag, so the "TEE" retry asked for StrongBox again, threw
 * `StrongBoxUnavailableException` again, and every device without StrongBox (OnePlus 7 Pro,
 * Android 12) ended in "could not create the vault key". [spec] builds a fresh spec per call.
 */
internal object VaultKeySpec {
    private const val TAG = "SecretVault"
    private const val KEY_BITS = 256

    /**
     * The VK spec. Auth-per-use (timeout 0) with a strong biometric, invalidated by a new
     * enrolment, and usable only while the device is unlocked. [strongBox] is the only thing
     * that differs between attempts.
     */
    fun spec(alias: String, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            // The whole point: no cipher init without a fresh finger.
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        // setUnlockedDeviceRequired is API 28+; minSdk is 26, so it is added only where
        // the platform has it. Below 28 the auth-per-use requirement above is the
        // guarantee — this is belt, not the only brace.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            builder.setIsStrongBoxBacked(strongBox)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // timeout 0 => auth-per-use (every single use needs authentication).
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder.build()
    }

    /**
     * Generate VK: StrongBox first where the platform has it (API 28+), then the TEE with an
     * otherwise identical spec. That is a capability fallback, not a downgrade — the key is
     * hardware-backed either way and carries the same auth requirements.
     *
     * When both attempts fail, the error names the real exception of each, so the debug log
     * says why instead of a generic "could not create the vault key".
     */
    fun <K> generate(alias: String, generator: (KeyGenParameterSpec) -> K): K {
        val strongBoxFailure: Exception? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generator(spec(alias, strongBox = true)).also {
                    Log.i(TAG, "vault key created (strongBox=true)")
                }
            } catch (e: Exception) {
                Log.i(TAG, "StrongBox key failed (${describe(e)}); falling back to TEE")
                e
            }
        } else {
            null
        }

        return try {
            generator(spec(alias, strongBox = false)).also {
                Log.i(TAG, "vault key created (strongBox=false)")
            }
        } catch (e: Exception) {
            val strongBoxPart = strongBoxFailure?.let { "; StrongBox attempt: ${describe(it)}" }.orEmpty()
            Log.w(TAG, "vault key generation failed", e)
            throw PluginError(
                "secure_storage_error",
                "could not create the vault key: ${describe(e)}$strongBoxPart"
            )
        }
    }

    private fun describe(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        return if (msg == null) e.javaClass.simpleName else "${e.javaClass.simpleName}: $msg"
    }
}
