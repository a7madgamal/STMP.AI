package com.mido.phonebridge.audit

/**
 * What the log is currently using, for the diagnostics report.
 *
 * [days] is newest first, because that is the order a reader cares about and the
 * order the retention rule deletes in reverse.
 */
data class AuditStorage(
    val days: List<AuditDay>,
    val totalBytes: Long,
    val retention: AuditRetention,
)
