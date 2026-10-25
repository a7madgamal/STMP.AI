package com.mido.phonebridge.audit

/**
 * The terminal state of one request. Exactly one per audit record.
 *
 * [DENIED] and [DENIED_UNATTENDED] are never merged: "he tapped no" and "nobody
 * was there" are different facts, and the second is how a mis-scoped NOTIFY rule
 * announces itself.
 *
 * Every value below is producible today; the gate has no auto-approve mode, so
 * the quietest outcome a request can have is [NOTIFY_APPROVED].
 */
enum class AuditOutcome {
    /** Human tapped APPROVE (and the biometric passed, when it was required). */
    APPROVED,

    /** Human tapped DENY. */
    DENIED,

    /** The modal expired with no tap. */
    TIMEOUT,

    /**
     * Nobody could see the request: screen off or device locked.
     *
     * Kept apart from [DENIED] on purpose — a denial is a decision, and this is the
     * absence of one. Reading them as the same fact hides the case where a schedule
     * quietly stopped working because the phone was in a pocket.
     */
    DENIED_UNATTENDED,

    /** The 5-second overlay window elapsed in silence, which approves. */
    NOTIFY_APPROVED,

    /** The overlay's `✕` was tapped, which denies. */
    NOTIFY_REJECTED,

    /** A gate rule changed. Its own record, never folded into the approval it enabled. */
    POLICY_CHANGED,

    /** Bad input, refused before the gate. The caller's mistake, and noise. */
    REJECTED_INVALID,

    /** No route matched the path. Noise. */
    UNROUTED,

    /** A fault on our side. Never noise, whatever the status. */
    HANDLER_FAILED,

    /** The client went away before a decision landed. */
    CLIENT_GONE,
}
