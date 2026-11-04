package com.mido.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.mido.phonebridge.core.ApkInstaller
import com.mido.phonebridge.core.InstallOutcome
import com.mido.phonebridge.core.UpdatePrefs
import com.mido.phonebridge.core.Updater

/**
 * Where Android reports the outcome of an install session.
 *
 * **Why this exists rather than an inline callback.** `PackageInstaller.commit` takes a
 * `PendingIntent`, which is delivered by the system to a manifest-declared receiver — a lambda
 * cannot cross that boundary. The result therefore arrives here, in a fresh instance, and is handed
 * back to whatever the app registered before starting the install.
 *
 * **Why it is `exported="false"` in the manifest.** Nothing outside this app should be able to
 * claim that an install succeeded. The system delivers the session result through the
 * PendingIntent, which already carries this app's identity.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants to show its own confirmation dialog. Tapping it is the one
                // step Android will not let an app skip, so launch it and say so honestly.
                // API 33+ has a typed overload; the untyped one is deprecated and trips -Werror.
                // minSdk is 26, so the branch is required rather than stylistic.
                val confirm: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                        Debug.log("update: android confirmation shown")
                        InstallOutcome.deliver(ApkInstaller.Result.ConfirmationShown)
                    } catch (e: Exception) {
                        Debug.log("update: cannot show installer dialog: ${e.message}")
                        InstallOutcome.deliver(
                            ApkInstaller.Result.Refused("could not open the installer: ${e.message}")
                        )
                    }
                } else {
                    Debug.log("update: PENDING_USER_ACTION with no confirmation intent")
                    InstallOutcome.deliver(
                        ApkInstaller.Result.Refused("the installer opened nothing to confirm")
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Debug.log("update: installed")
                // A successful install restarts the app itself, so anything after this is a
                // formality. Clearing the skip is what stops the new build being hidden by the
                // old build's "later".
                UpdatePrefs.setSkippedVersion(context, null)
                Updater.lastOutcome = null
                InstallOutcome.deliver(ApkInstaller.Result.ConfirmationShown)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The human dismissed the dialog. That is a legitimate "no", not an error.
                Debug.log("update: install aborted by the user")
                InstallOutcome.deliver(
                    ApkInstaller.Result.Refused("install cancelled")
                )
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Debug.log("update: install blocked (${message ?: "no detail"})")
                InstallOutcome.deliver(
                    ApkInstaller.Result.Refused(
                        "Android blocked the install — allow this app to install unknown apps " +
                            "in settings, then try again"
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Debug.log("update: install incompatible (${message ?: "no detail"})")
                InstallOutcome.deliver(
                    ApkInstaller.Result.Refused(
                        "this update cannot install over the current one — it may be signed " +
                            "with a different key"
                    )
                )
            }

            else -> {
                Debug.log("update: install failed status=$status (${message ?: "no detail"})")
                InstallOutcome.deliver(
                    ApkInstaller.Result.Refused(message ?: "install failed (status $status)")
                )
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.mido.phonebridge.INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
