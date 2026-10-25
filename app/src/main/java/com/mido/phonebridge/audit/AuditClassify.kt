package com.mido.phonebridge.audit

/**
 * Decides which [AuditOutcome] a failed request gets.
 *
 * Pure and total so it can be tested without a server, a socket, or a gate. This
 * exists because the rule used to be written inline in the catch block and was
 * wrong in a way only the device log revealed: `?count=-1` was recorded as
 * `HANDLER_FAILED` — the loudest badge in the log — for what was a typo in a URL.
 * The cause was validating query parameters inside the handler, i.e. after the
 * gate, so the failure genuinely looked post-approval.
 *
 * Two independent inputs, and both matter:
 *
 *  - [status]: what the caller is being told. A 4xx is by definition the caller's
 *    mistake; the server did what it should.
 *  - [gated]: whether a human already decided. A 4xx that somehow arrives after
 *    an approval is not noise: the human was made to approve something that then
 *    failed, and that wasted a decision. It stays signal.
 *
 * A 5xx is a fault either way. "Our side broke" is never the caller's fault and
 * must never be filed as noise, because noise is the default-hidden view.
 */
object AuditClassify {

    fun forError(status: Int, gated: Boolean): AuditOutcome =
        if (!gated && status < 500) AuditOutcome.REJECTED_INVALID else AuditOutcome.HANDLER_FAILED

    /** A throw out of dispatch is always our fault, whatever the status would have been. */
    fun forCrash(): AuditOutcome = AuditOutcome.HANDLER_FAILED
}
