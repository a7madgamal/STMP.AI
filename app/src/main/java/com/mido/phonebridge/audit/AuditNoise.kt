package com.mido.phonebridge.audit

/**
 * Which records are noise — hidden from the log's default view, never from the log.
 *
 * The two noise outcomes are noise by DEFINITION, not by path: an unrouted path or
 * bad input is a caller's typo, and the human was never asked. Everything else is a
 * real event on some path, so it stays visible.
 *
 * [PROBE_PATHS] exists for a route that is deliberately called as a liveness probe.
 * It is empty today because no route earned an exemption: a probe that is also a real
 * request still deserves to be seen.
 */
object AuditNoise {

    private val PROBE_PATHS: Set<String> = emptySet()

    fun isNoise(outcome: AuditOutcome, path: String): Boolean = when (outcome) {
        // Bad input and an unknown path never reached a human, so they can only be
        // the caller's mistake. Noise.
        AuditOutcome.UNROUTED, AuditOutcome.REJECTED_INVALID -> true

        // Everything else happened to a real request. A 5xx in particular must never
        // be hidden — hiding a crash is how a broken bridge looks healthy.
        AuditOutcome.APPROVED,
        AuditOutcome.DENIED,
        AuditOutcome.TIMEOUT,
        AuditOutcome.DENIED_UNATTENDED,
        AuditOutcome.NOTIFY_APPROVED,
        AuditOutcome.NOTIFY_REJECTED,
        AuditOutcome.POLICY_CHANGED,
        AuditOutcome.HANDLER_FAILED,
        AuditOutcome.CLIENT_GONE -> PROBE_PATHS.contains(path)
    }
}
