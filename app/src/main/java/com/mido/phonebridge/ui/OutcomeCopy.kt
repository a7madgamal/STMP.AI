package com.mido.phonebridge.ui

import com.mido.phonebridge.audit.AuditOutcome

/**
 * What an [AuditOutcome] is CALLED on screen.
 *
 * **Why this exists.** The request log, the activity list and the record detail all printed
 * `outcome.name`, so a person read `NOTIFY_APPROVED` and `DENIED_UNATTENDED` — the enum's
 * identifier, not a sentence. Three screens that must agree about one word is three places to
 * drift, so the word lives here once.
 *
 * The stored value never changes: only what a human reads. A rename of an enum constant is a
 * stored-format change and must not be done to improve a label.
 */
object OutcomeCopy {

    fun words(o: AuditOutcome): String = when (o) {
        AuditOutcome.APPROVED -> "Approved"
        AuditOutcome.DENIED -> "Denied"
        AuditOutcome.TIMEOUT -> "Timed out"
        AuditOutcome.DENIED_UNATTENDED -> "Denied, phone unattended"
        AuditOutcome.NOTIFY_APPROVED -> "Approved by silence"
        AuditOutcome.NOTIFY_REJECTED -> "Denied from the card"
        AuditOutcome.POLICY_CHANGED -> "Gate changed"
        AuditOutcome.REJECTED_INVALID -> "Bad request"
        AuditOutcome.UNROUTED -> "Unknown path"
        AuditOutcome.HANDLER_FAILED -> "Failed"
        AuditOutcome.CLIENT_GONE -> "Caller left"
    }

    /**
     * True when the outcome is a fault on OUR side rather than a decision or a call.
     *
     * Stated here because "which outcomes are worth a red row" is the same question as "what is
     * this outcome called", and answering it at each call site is how one screen ends up treating
     * a failed handler as noise.
     */
    fun isFault(o: AuditOutcome): Boolean = o == AuditOutcome.HANDLER_FAILED
}
