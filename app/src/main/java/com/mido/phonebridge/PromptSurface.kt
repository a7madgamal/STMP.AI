package com.mido.phonebridge

/**
 * What this phone can actually DO with an approval prompt — decided from two facts, before
 * anything is enqueued.
 *
 * **Why this is a pure object.** The failure it prevents is silent by nature: the request waits
 * out its 60-second window and answers `approval_timeout`, which is indistinguishable from a
 * human who chose not to answer. Reported 2026-11-11: *"i didn't get the modal!"* — with
 * `fullScreenIntent: false` and the phone inside another app, the card was never drawn at all.
 * A rule this important must be testable on the JVM, and `NotificationManager`, `Settings` and
 * an Activity are all unavailable there.
 */
object PromptSurface {

    /**
     * [CAN_RAISE] the modal over whatever is on screen.
     *
     * [FULL_SCREEN_ONLY] can raise it from another app, but a locked screen may still swallow
     * it — the prompt is presented, so this is NOT a refusal case.
     */
    enum class Capability { CAN_RAISE, FULL_SCREEN_ONLY }

    /**
     * The strongest thing this phone can do, with `NONE` meaning "refuse the request".
     *
     * Both false is the only unreachable case, and it is deliberately the one that fails:
     * without an overlay window a background Activity launch is illegal, and without
     * full-screen intent the card cannot replace another app's window. Presenting is then a
     * promise the platform will not keep.
     */
    val NONE: Capability? = null

    fun of(fullScreenIntent: Boolean, overlayGranted: Boolean): Capability? = when {
        overlayGranted || fullScreenIntent -> Capability.CAN_RAISE
        else -> NONE
    }

    /** The single sentence a human reads when a request was refused for this reason. */
    fun remedy(): String =
        "Preferences → Lock-screen popup (allow full-screen notifications), or " +
            "Preferences → Draw over apps."
}
