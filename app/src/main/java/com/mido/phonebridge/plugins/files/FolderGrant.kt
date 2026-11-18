package com.mido.phonebridge.plugins.files

import android.content.Context
import android.content.Intent

/**
 * Ask the human to choose the folder — by raising the modal directly, not by posting a notification.
 *
 * **The notification was the wrong instrument.** It was chosen on the theory that a background
 * route cannot raise a window, but this app already answers that question another way: `VaultUnlock`
 * and the approval card both reach the human with a direct `startActivity` from the service. A
 * notification is a second door that can be disabled by a channel setting, swallowed by
 * `POST_NOTIFICATIONS`, or simply not looked at — and it gave no way to say *why* the folder is
 * wanted. The modal is the same surface a human already trusts for a decision this app needs.
 *
 * **De-duplicated in-process, not by the platform.** A polling caller would otherwise stack identical
 * windows; one flag means a second request while the prompt is already up does not raise another.
 * The flag is cleared by the Activity whichever way the human leaves it, so a cancelled prompt can
 * be asked for again on the next request.
 */
object FolderGrant {

    /** True while a grant prompt is on screen. Guarded by the object's own monitor. */
    private var showing = false

    /** When the last prompt was raised, so a retrying caller cannot reopen it every second. */
    private var lastAskedAtMs = 0L

    /**
     * Minimum gap between prompts, after one was raised and dismissed without a grant.
     *
     * **SAF has no "don't ask again"**, which is right — a human who backs out should be able to
     * change their mind. But the *caller* is a script, and a script retries. Without this, a client
     * polling in a loop would stack a fresh sheet on the human every few seconds, which is worse
     * than a notification because it takes the whole screen.
     */
    private const val COOLDOWN_MS = 60_000L

    /**
     * Raise the folder prompt, unless one is already up.
     *
     * Called from the pre-gate check, so the human is asked at the exact moment a request needs the
     * grant — never at setup, never on app launch.
     */
    @Synchronized
    fun askOnce(ctx: Context) {
        if (showing) {
            // Already asking. Raising a second window would be two pickers over each other.
            com.mido.phonebridge.Debug.log("files: grant prompt already showing")
            return
        }
        val since = System.currentTimeMillis() - lastAskedAtMs
        if (lastAskedAtMs != 0L && since < COOLDOWN_MS) {
            // Asked recently and dismissed. The refusal the caller already has is the answer; the
            // human is not to be interrupted again this minute.
            com.mido.phonebridge.Debug.log("files: grant prompt in cooldown (${since}ms)")
            return
        }
        showing = true
        lastAskedAtMs = System.currentTimeMillis()
        try {
            ctx.startActivity(
                Intent(ctx, FilesGrantActivity::class.java).apply {
                    // A separate task, because this is launched from a service and must not be
                    // stacked onto whatever the human was doing.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            // `startActivity` from the background is refused on some Android versions and OEM
            // builds, with `BackgroundActivityStartDenied` in the log. Clearing the flag is the
            // important half: a failed raise must not block every later attempt.
            showing = false
            com.mido.phonebridge.Debug.log(
                "files: could not raise the grant prompt (${e.javaClass.simpleName}): ${e.message}"
            )
        }
    }

    /**
     * Called by [FilesGrantActivity] when it goes away, however that happened.
     *
     * Deliberately not "on success": a human who backs out must be able to be asked again, and a
     * flag that only cleared on success would leave the prompt permanently suppressed after one
     * cancel.
     */
    @Synchronized
    fun finished() {
        showing = false
    }
}
