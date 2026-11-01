package com.mido.phonebridge.core

/**
 * What the "bridge status" notification says.
 *
 * **Pure and separate on purpose.** The copy is the part a human acts on, and it used to be
 * inline string literals in `BridgeService` — which meant a state could be added without anyone
 * noticing the notification still described the old one. Keeping it here lets the suite assert
 * that every state says something distinct and that the dismissible/ongoing split matches what
 * the service is actually doing. Same extraction rule as `HostAllowlist`, `ResponseBody` and
 * `ServerStatus`: a decision a human reads belongs where a test can fail on it.
 *
 * **Three states, and the third one exists because of a real gap.** `WAITING_FOR_ADDRESS` is new
 * (2026-10-25): the service used to stop itself once the retry ladder was spent, so turning
 * Tailscale back on left the bridge down until someone opened the app. It now stays alive and
 * watches for the tailnet instead, and this state is what tells the human that waiting is the
 * correct thing to do rather than something they must fix.
 */
object BridgeStatusNotice {

    enum class Kind {
        /** The retry ladder is still running. Transient; expect it to change on its own. */
        RETRYING,

        /**
         * The ladder is spent and the service is now waiting for a tailnet address.
         *
         * Distinct from [STOPPED] because the required action is different: nothing. It starts
         * by itself the moment Tailscale connects.
         */
        WAITING_FOR_ADDRESS,

        /**
         * Not running, and nothing is watching. The human must act.
         *
         * The only state whose notification is dismissible: it describes a finished outcome, not
         * work in progress, so leaving it pinned would be a permanent alarm for something the
         * user may have already dealt with.
         */
        STOPPED,
    }

    fun title(kind: Kind): String = when (kind) {
        Kind.RETRYING -> "PhoneBridge can't start yet"
        Kind.WAITING_FOR_ADDRESS -> "PhoneBridge is waiting for a network"
        Kind.STOPPED -> "PhoneBridge is not running"
    }

    /**
     * @param attempt 1-based retry number; ignored by every kind except [Kind.RETRYING].
     * @param maxAttempts total attempts in the ladder, so the copy cannot drift from the schedule.
     */
    fun text(kind: Kind, attempt: Int, maxAttempts: Int): String = when (kind) {
        Kind.RETRYING -> "No network address yet — retrying ($attempt/$maxAttempts)."
        Kind.WAITING_FOR_ADDRESS ->
            "It will start by itself as soon as the phone is on a network."
        Kind.STOPPED -> "No network address. Check Wi-Fi or Tailscale, then open the app."
    }

    /** Ongoing = the service is still working on it, so the human cannot dismiss it away. */
    fun ongoing(kind: Kind): Boolean = when (kind) {
        Kind.RETRYING, Kind.WAITING_FOR_ADDRESS -> true
        Kind.STOPPED -> false
    }

    /** Derived, never stored separately: the two are one decision and must not disagree. */
    fun autoCancel(kind: Kind): Boolean = !ongoing(kind)
}
