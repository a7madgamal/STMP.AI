package com.mido.phonebridge.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.mido.phonebridge.Debug
import com.mido.phonebridge.InstallResultReceiver
import java.io.File

/**
 * Hands a downloaded APK to Android's installer.
 *
 * **Why `PackageInstaller.Session` rather than `ACTION_VIEW` on a file.** The file-intent route
 * needs a `FileProvider` and a read grant, and it fails in a way that looks like a bug: no
 * activity can handle `application/vnd.android.package-archive` on some OEM builds, and the
 * result is a toast about no app being found rather than an install. The session API is what Play
 * itself uses, it takes the bytes directly, and it reports a definite outcome through a
 * `PendingIntent`.
 *
 * **It cannot be silent, and this class does not pretend otherwise.** Outside a device-owner
 * setup, Android shows the system install dialog and the human taps it. That tap is the floor this
 * whole feature is designed around — everything here exists to make sure the tap installs the
 * RIGHT bytes and nothing else.
 */
object ApkInstaller {

    /** What happened, so the screen can say something specific. */
    sealed interface Result {
        /** Android accepted the session and is showing its own dialog. Not yet installed. */
        data object ConfirmationShown : Result

        /** The install was refused with a reason worth showing. */
        data class Refused(val reason: String) : Result
    }

    /**
     * Whether this app may prompt to install packages at all.
     *
     * `REQUEST_INSTALL_PACKAGES` is declared but the USER still has to allow it per-app. When it
     * is off, `PackageInstaller` accepts the session and then fails with
     * `STATUS_FAILURE_BLOCKED`, which is a confusing dead end unless the UI warns first.
     */
    fun canInstall(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /**
     * Start an install.
     *
     * The verification callback deliberately does not re-check the digest: by this point the file
     * on disk has already been compared against the published SHA-256 by the caller, and doing it
     * twice would mean two sources of truth for the same question.
     */
    fun install(ctx: Context, apk: File, onResult: (Result) -> Unit) {
        if (!apk.isFile || apk.length() == 0L) {
            onResult(Result.Refused("the downloaded file is missing or empty"))
            return
        }
        if (!canInstall(ctx)) {
            onResult(
                Result.Refused(
                    "installing updates is not allowed for this app yet — allow it once in " +
                        "Android settings and try again"
                )
            )
            return
        }

        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // The size makes the system reserve space up front, so a full disk fails
                // here with a clear error rather than halfway through the write.
                setAppPackageName(ctx.packageName)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("stmp-update", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                // `commit` takes an IntentSender, not a PendingIntent: the PendingIntent
                // has to be unwrapped, and unwrapping is where the identity and flags are
                // settled. Passing the PendingIntent would not compile, which is the good case.
                session.commit(commitIntent(ctx, sessionId, onResult).intentSender)
            }
        } catch (e: SecurityException) {
            // The usual cause: REQUEST_INSTALL_PACKAGES granted but the user revoked it, or a
            // managed device forbids it. Say which, rather than "install failed".
            onResult(Result.Refused("Android refused the install session: ${e.message}"))
        } catch (e: Exception) {
            Debug.log("update: install session failed: ${e.javaClass.simpleName}: ${e.message}")
            onResult(Result.Refused("could not start the installer: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /**
     * The `PendingIntent` the installer reports back through.
     *
     * The receiver is registered in the manifest and matched by ACTION. The session id travels in
     * the intent so the receiver can tell an update it started from anything else — an explicit
     * id beats inferring one from a global.
     */
    private fun commitIntent(
        ctx: Context,
        sessionId: Int,
        onResult: (Result) -> Unit,
    ): PendingIntent {
        // The callback cannot be a lambda across a process boundary, so the receiver reports into
        // a process-wide holder. This is the one mutable global here, and it is cleared as soon as
        // it fires.
        InstallOutcome.callback = onResult

        val intent = Intent(ctx, InstallResultReceiver::class.java).apply {
            action = InstallResultReceiver.ACTION_INSTALL_RESULT
            putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
    }
}

/**
 * Where the install result lands when the system answers.
 *
 * A single-slot holder rather than a list: only one install can be in flight from this app, and a
 * queue would keep a dead callback alive across a configuration change.
 */
object InstallOutcome {
    @Volatile
    var callback: ((ApkInstaller.Result) -> Unit)? = null

    fun deliver(result: ApkInstaller.Result) {
        val cb = callback
        callback = null
        cb?.invoke(result)
    }
}
