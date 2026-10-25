package com.mido.phonebridge.audit

/**
 * Whether the log is currently able to record, and why not if it cannot.
 *
 * A failed write is a REQUEST failure, not a swallowed error: nothing executes
 * unrecorded. That decision needs a reason the UI can show, and it has to be
 * readable from anywhere in the process — hence a single object rather than a
 * field threaded through call sites.
 *
 * `reason == null` means healthy. It is cleared on the next successful write, so a
 * transient failure does not leave a permanent red banner.
 */
object AuditHealth {

    @Volatile
    var reason: String? = null
        private set

    fun markDegraded(r: String) {
        reason = r
    }

    fun clear() {
        reason = null
    }
}
