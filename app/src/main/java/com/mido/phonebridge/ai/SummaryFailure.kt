package com.mido.phonebridge.ai

/**
 * Why the last summary attempt produced nothing.
 *
 * **This exists because the old answer was a lie.** `ApprovalGate` used
 * `LiteRtLm.lastEngineError.ifBlank { "the model did not answer within ${cap}ms" }`. But
 * `lastEngineError` is only set by ENGINE faults — a guard rejection, a budget overrun, an
 * unparseable reply and a contradiction all return `null` without touching it. So every one of
 * them fell through to the blank-branch fallback and told the human "the model did not answer
 * within 10000ms" — a cause that never happened, about a fetch that finished in 1.4 seconds.
 *
 * A fallback that invents a cause is worse than no detail at all: it sends the next debugging
 * session looking at timeouts when the real fault was the model's own sentence. Mido,
 * 2026-11-03: *"I got couldn't summarize in 10000ms but it was never full 10 seconds"*.
 *
 * **So every null-return path now STATES its reason.** There is no fallback here — a caller that
 * forgets to set one gets [UNSTATED], which names the defect instead of guessing at a cause.
 * That is the difference this whole change is about: an absent answer and a wrong answer are
 * different failures, and only one of them wastes a human's time.
 */
object SummaryFailure {

    /** Set by [record] at each failure point, read by the gate and the NOTIFY card. */
    @Volatile
    private var reason: String = ""

    /**
     * The reason the last attempt failed, from a path that stated one.
     *
     * Says so plainly when nobody did, rather than manufacturing a plausible cause.
     */
    fun take(): String = reason.ifBlank { UNSTATED }

    /** Record why an attempt produced no summary. Called on every null path. */
    fun record(why: String) {
        reason = why
    }

    /** Clear before each attempt, so a stale reason cannot describe a fresh failure. */
    fun clear() {
        reason = ""
    }

    /** A caller failed to state a reason. Named as a defect, never replaced with a guess. */
    const val UNSTATED: String =
        "the summariser failed without stating a reason — this is a defect in the app, not in the model"

    /**
     * A duration in the form a human reads, not raw milliseconds.
     *
     * Mido, 2026-11-03: *"write the message in seconds"*. `10000ms` reads as a measurement of
     * something you cannot see; `10s` reads as a wait you can judge. Sub-second values keep one
     * decimal so a fast failure is not rounded to "0s".
     */
    fun humanMs(ms: Long): String {
        if (ms < 1_000L) return "${ms}ms"
        val seconds = ms / 1_000.0
        // One decimal only when it is not a whole number: "10s", not "10.0s".
        return if (seconds % 1.0 == 0.0) "${seconds.toInt()}s" else String.format("%.1fs", seconds)
    }
}
