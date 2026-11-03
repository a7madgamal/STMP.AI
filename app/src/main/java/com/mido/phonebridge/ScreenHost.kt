package com.mido.phonebridge

import android.Manifest
import android.content.Intent
import android.widget.Toast

/**
 * What a screen needs from whatever is hosting it.
 *
 * **Why this exists.** Every screen called `Toast.makeText(this, …)`, `startActivity(…)`,
 * `runOnUiThread { … }` directly. That welded each screen to `Activity`, and the cost showed up
 * twice:
 *
 *  1. **A screen could not be converted to anything else.** Swapping the host for a Fragment
 *     changed the meaning of `this` at every one of those call sites — 136 compile errors, in the
 *     first attempt at the migration.
 *  2. **A screen could not be tested.** Constructing one meant constructing an Activity.
 *
 * An interface with six methods removes both. A screen says `toast(…)` and no longer knows or
 * cares whether an Activity, a Fragment, or a test double is answering.
 *
 * **Deliberately small.** Only what the screens actually called, counted rather than guessed:
 * `startActivity` ×8, `Toast` ×8, `runOnUiThread` ×5, `AlertDialog` ×2, plus one each of
 * `requestPermissions` and `checkSelfPermission`. Anything a screen can do without a host —
 * building views, reading stores, calling the registry — stays out, because a needlessly wide
 * interface is just the Activity API with extra steps.
 *
 * **Service control is NOT here.** `startBridge`/`stopBridge` belong to [BridgeControl], which
 * only Home implements. Putting them here would force the Plugins and Settings screens to write
 * empty overrides for buttons they do not have.
 */
interface ScreenHost {

    /**
     * Navigate.
     *
     * Named `go` rather than `startActivity` because the host may not be an Activity at all, and
     * a method that promises an Activity by name would be lying in exactly the case this
     * interface exists to allow.
     */
    fun go(intent: Intent)

    /** A transient message. LONG by default, matching every existing call site. */
    fun toast(message: String, duration: Int = Toast.LENGTH_LONG)

    /** Run on the main thread. The caller is usually already on a worker. */
    fun ui(block: () -> Unit)

    /**
     * A yes/no dialog. `onPositive` runs on the main thread after the tap.
     *
     * `onNegative` is optional because most callers want nothing on cancel — but not all: the
     * model re-download dialog logs the refusal, and a screen that needs to observe a "no" must
     * be able to. Defaulting it to null would silently drop that line.
     */
    fun confirm(
        title: String,
        message: String,
        positive: String,
        negative: String,
        onNegative: (() -> Unit)? = null,
        onPositive: () -> Unit,
    )

    /** Whether a runtime permission is currently granted. */
    fun hasPermission(permission: String): Boolean

    /**
     * Ask for a runtime permission.
     *
     * The result does NOT come back here: it arrives at the host's `onRequestPermissionsResult`,
     * which is a host-shaped detail a screen must not have to model.
     */
    fun askPermission(permission: String, requestCode: Int)

    companion object {
        /**
         * The only runtime permission any screen asks for today.
         *
         * **On the `InlinedApi` suppression.** `Manifest.permission.POST_NOTIFICATIONS` is
         * annotated API 33 while `minSdk` is 26, so lint flags the reference. The reference is
         * safe because the field is a compile-time `String` constant: the compiler inlines the
         * literal `"android.permission.POST_NOTIFICATIONS"` into this file, so on an API 26
         * device the code never loads the API-33 field it appears to name.
         *
         * Suppressed HERE rather than at the call sites because the value is inert at this
         * level; the call sites are what must guard on `SDK_INT`, and they already do. A
         * suppression over the whole file would hide a real API misuse elsewhere.
         */
        @Suppress("InlinedApi")
        const val POST_NOTIFICATIONS: String = Manifest.permission.POST_NOTIFICATIONS
    }
}

/**
 * Starting and stopping the bridge.
 *
 * Separate from [ScreenHost] because exactly one screen has these buttons, and an interface every
 * implementer must stub is an interface that teaches nothing. Home implements this; Settings and
 * Plugins do not, and are not obliged to pretend.
 */
interface BridgeControl {
    fun startBridge()
    fun stopBridge()
}
