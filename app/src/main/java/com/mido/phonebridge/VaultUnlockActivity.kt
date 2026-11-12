package com.mido.phonebridge

import android.os.Bundle
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.ui.Theme

/**
 * Asks for one fingerprint so a blocked request can continue.
 *
 * **This screen approves nothing.** It opens the vault and closes; the request then proceeds to
 * its normal approval modal, where the human reads the real target and decides. Keeping those two
 * acts apart is the reason this exists as its own Activity rather than a button on the approval
 * card: an approval card that a scan could satisfy would mean consent by fingerprint, which is
 * exactly what this app refuses to offer.
 *
 * It is deliberately small. No request details are shown because there is nothing to decide yet —
 * showing a host or a command here would invite approving something the caller has not yet been
 * asked about, and would duplicate the modal that follows.
 */
class VaultUnlockActivity : FragmentActivity() {

    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SecretVault.isUnlocked()) { close(true); return }
        setContentView(Theme.inset(buildShell()))
        prompt()
    }

    private fun buildShell(): android.view.View {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
            gravity = android.view.Gravity.CENTER
            setPadding(Theme.dp(this@VaultUnlockActivity, 24), 0,
                Theme.dp(this@VaultUnlockActivity, 24), 0)
        }
        root.addView(Theme.text(this, "Vault locked", Theme.TEXT_TITLE_SP).apply {
            typeface = Theme.handFont(this@VaultUnlockActivity)
        })
        root.addView(Theme.text(
            this,
            "A request needs a stored credential. Unlock the vault to review it — you will " +
                "still approve the request itself on the next screen.",
            Theme.TEXT_DETAIL_SP, Theme.ON_SURFACE_VARIANT,
        ).apply { setPadding(0, Theme.dp(this@VaultUnlockActivity, 8), 0, Theme.dp(this@VaultUnlockActivity, 16)) })
        root.addView(Theme.button(this, "UNLOCK", Theme.ButtonStyle.SUCCESS) { prompt() })
        root.addView(Theme.button(this, "CANCEL", Theme.ButtonStyle.GHOST) { close(false) }.apply {
            setPadding(0, Theme.dp(this@VaultUnlockActivity, 12), 0, 0)
        })
        return root
    }

    /**
     * The prompt, carrying the vault cipher so one pass both authenticates AND unwraps.
     *
     * Failure paths all leave the request blocked rather than guessing: a dismissed prompt, a
     * lockout, or a device with nothing enrolled closes the screen with `false`, and the caller
     * answers `secret_locked` with the reason. Nothing here can approve.
     */
    private fun prompt() {
        val cipher = try {
            SecretVault.prepareUnlockCipher(this)
        } catch (e: Exception) {
            Log.w(TAG, "cannot prepare vault cipher: ${e.message}")
            close(false)
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "biometric unavailable (code=$can)")
            close(false)
            return
        }
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorised = result.cryptoObject?.cipher
                    if (authorised == null) {
                        // Only a cipher-authorised result may unlock. Treating "authenticated" as
                        // sufficient would accept a prompt that never bound the key.
                        Log.w(TAG, "no authorised cipher returned")
                        close(false)
                        return
                    }
                    try {
                        SecretVault.unlock(this@VaultUnlockActivity, authorised)
                    } catch (e: Exception) {
                        Log.w(TAG, "unlock failed: ${e.message}")
                        close(false)
                        return
                    }
                    close(SecretVault.isUnlocked())
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.i(TAG, "biometric error $errorCode: $errString")
                    close(false)
                }

                override fun onAuthenticationFailed() {
                    // One bad read: the system prompt stays up for a retry. Nothing to do.
                    Log.i(TAG, "biometric failed (retryable)")
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock the vault")
                .setSubtitle("Needed to review the pending request")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Cancel")
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun close(unlocked: Boolean) {
        if (resolved) return
        resolved = true
        val id = intent.getLongExtra(VaultUnlockGate.EXTRA_ID, -1L)
        if (id > 0) VaultUnlockGate.resolve(id, unlocked)
        finish()
    }

    // No `onBackPressed` override.
    //
    // Back is a cancel, and `onDestroy` already reports the truth to the waiting request, so an
    // override would only duplicate that with a deprecated hook. One exit path, one place to get
    // the resolution right.

    override fun onDestroy() {
        // Removal by the system (rotation, task dismissal) must not leave the caller waiting:
        // report whatever the vault actually says, which is usually still "locked".
        if (!resolved) {
            resolved = true
            val id = intent.getLongExtra(VaultUnlockGate.EXTRA_ID, -1L)
            if (id > 0) VaultUnlockGate.resolve(id, SecretVault.isUnlocked())
        }
        super.onDestroy()
    }

    private companion object { const val TAG = "VaultUnlockActivity" }
}
