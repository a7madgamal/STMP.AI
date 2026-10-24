package com.mido.phonebridge

/**
 * The misclick guard: how long after a modal appears its decision buttons may go live.
 *
 * **Why this exists.** With the AI switched off there is no summary to fetch, so `ApprovalGate`
 * armed the buttons at `shownAtMs` — the instant the modal appeared. That is not a window a human
 * can read in; a tap already in flight when the modal arrives lands on APPROVE. Mido,
 * 2026-10-21: *"when AI is disabled, we need the 3 seconds protection again to avoid misclicking
 * the dialog"*.
 *
 * **One rule for both paths, deliberately.** It would be easy to apply this only to the no-AI
 * branch, since that is the one that regressed. But "how soon can a human accidentally agree to
 * something" is a property of the MODAL, not of whether a summary happened to be written. Applying
 * it per-branch is how the two paths drift apart — the exact failure this project has paid for
 * repeatedly (the audit flag honoured on one dispatch path and ignored on the other).
 *
 * So the rule is a MINIMUM time since shown: whichever comes later, the guard or the summary.
 *
 * **The AI path is barely affected in practice.** A summary takes ~1.2s to land and the model is
 * only consulted when it is enabled, so the guard adds at most ~1.8s there — and a request that
 * took longer than 3s to summarise is unchanged.
 *
 * Pure and Context-free so [ArmGuardTest] can pin the boundaries; the gate and the Activity both
 * read the same function rather than each computing "is it armed yet".
 */
object ArmGuard {

    /**
     * The minimum time between a modal appearing and its buttons accepting a tap.
     *
     * Three seconds: long enough to see that SOMETHING appeared and stop a tap already travelling,
     * short enough not to feel like a hang. Chosen by Mido, who had this behaviour before the AI
     * arm-on-fetch change replaced it.
     */
    const val MISCLICK_GUARD_MS = 3_000L

    /**
     * When the buttons may go live.
     *
     * @param shownAtMs when the modal appeared — the clock this guard is measured from.
     * @param readyAtMs when the request itself became decidable (the summary landed, or
     *   immediately for a route that needs none). The guard can only ever push this LATER, never
     *   earlier: a slow summary is still the binding constraint.
     */
    fun armAt(shownAtMs: Long, readyAtMs: Long): Long =
        maxOf(readyAtMs, shownAtMs + MISCLICK_GUARD_MS)

    /** True once [armAt] has passed. The ONE predicate the UI uses to enable the buttons. */
    fun isArmed(armAtMs: Long, nowMs: Long): Boolean = nowMs >= armAtMs

    /**
     * Whether a touch may DECIDE anything (approve, or dismiss) right now.
     *
     * **The NOTIFY card used to approve on any tap, with no guard at all.** Mido, 2026-10-24:
     * *"add the same 3 seconds waiting period before ANY touch event on the notification works so
     * that I don't approve or cancel by mistake"*. The overlay is a floating card that can appear
     * under a finger already in motion, and a tap means APPROVE — so the same minimum window the
     * modal uses has to apply here.
     *
     * **Dragging is deliberately still allowed while unarmed.** Moving the card is not a decision,
     * and blocking it would make the guard feel like a frozen UI rather than a careful one. Only the
     * two things that answer the request are gated.
     *
     * A pure function so the boundary is pinned by [ArmGuardTest] rather than living inside a touch
     * listener where only a device could check it.
     *
     * @param shownAtMs when the card appeared. The guard is measured from here — never from the
     *   fetch, so a slow summary cannot shorten the wait.
     */
    fun decisionsAllowed(shownAtMs: Long, nowMs: Long): Boolean {
        // `0` means "never attached" (see `Entry.shownAt`). Treating it as a timestamp would make
        // every comparison true, since a real clock reading is far past 0 + 3000 — so a card that
        // somehow received a touch before it was stamped would be fully armed. Refuse instead: an
        // unattached card has no decision to make yet.
        if (shownAtMs <= 0L) return false
        return isArmed(shownAtMs + MISCLICK_GUARD_MS, nowMs)
    }

    /**
     * How much of the guard is left, for the countdown line. Zero once armed, so a caller can
     * render it unconditionally.
     */
    fun remainingMs(armAtMs: Long, nowMs: Long): Long =
        (armAtMs - nowMs).coerceAtLeast(0L)

    /**
     * Whole seconds left, rounded UP.
     *
     * Rounded up so a card never says "0s" while the buttons are still disabled — the countdown
     * reaching zero must mean the buttons work.
     */
    fun remainingSeconds(armAtMs: Long, nowMs: Long): Long {
        val ms = remainingMs(armAtMs, nowMs)
        return (ms + 999L) / 1000L
    }
}
