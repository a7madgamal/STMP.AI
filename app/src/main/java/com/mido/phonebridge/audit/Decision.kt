package com.mido.phonebridge.audit

/**
 * How the gate reached its answer, recorded alongside the outcome.
 *
 * The outcome says WHAT happened; this says how the machine got there. They are
 * separate fields because "approved" is not an explanation — an approval under a
 * rule, an approval under a forced `SECURE`, and an approval with no policy at all
 * are three different facts about the system, and the log has to be able to tell
 * them apart after the fact.
 *
 * The three timestamps are nullable on purpose. `enqueuedAt` is absent when nothing
 * waited, `shownAt` is absent when nothing was drawn (an unattended approval
 * deliberately draws no card), and `decidedAt` is absent when no decision landed.
 * A zero would claim an event at the epoch; null says the event did not happen.
 */
data class Decision(
    /** The mode the request was actually gated with: `SECURE` or `NOTIFY`. */
    val mode: String,
    val source: DecisionSource,
    /** The global switch at decision time, when it was consulted. */
    val globalMode: String? = null,
    /** The exact `plugin/VERB` rule that decided, when one did. */
    val rule: String? = null,
    val enqueuedAt: Long? = null,
    val shownAt: Long? = null,
    val decidedAt: Long? = null,
    /** How many requests were waiting behind this one when it was decided. */
    val queueDepth: Int = 0,
    /**
     * The text the human typed when denying, or null for a plain deny.
     *
     * Stored because a reason is a decision the human made and the log is the only place it
     * survives — the caller gets it in the 403 body, but a request that already gave up on the
     * other side leaves no other trace. Additive: absent on every record written before this
     * existed, so a reader must treat its absence as "none given" rather than as a defect.
     *
     * The 8 KiB value cap applies like any other field; the sheet caps its own input well under
     * that, so a truncated reason is not a case this can reach.
     */
    val reason: String? = null,
)
