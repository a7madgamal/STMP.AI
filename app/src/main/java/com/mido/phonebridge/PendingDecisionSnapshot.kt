package com.mido.phonebridge

/**
 * An immutable, display-only request waiting for a decision.
 *
 * No latch, mutable queue entry, password, request body, or resolver crosses this boundary. The
 * Activity tab can explain what is waiting but cannot approve, deny, replay or change policy.
 */
data class PendingDecisionSnapshot(
    val id: String,
    val caller: String,
    val action: String,
    val endpoint: String,
    val verb: String,
    val state: String,
    val enqueuedAtMs: Long,
    val awaitingSinceMs: Long,
) {
    val waitingMs: Long
        get() = (System.currentTimeMillis() - awaitingSinceMs).coerceAtLeast(0L)
}
