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
)
