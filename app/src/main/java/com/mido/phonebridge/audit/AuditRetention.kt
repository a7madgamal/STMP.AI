package com.mido.phonebridge.audit

/**
 * How long the log keeps itself.
 *
 * Two independent bounds because they fail differently: the age cap stops a log
 * from growing forever at a steady rate, and the size cap stops a burst (or a
 * pathological route) from filling the phone before the age cap ever fires.
 *
 * Both are applied to whole DAY FILES. There is no partial trimming, because the
 * file name IS the date and a half-deleted day would break the retention rule that
 * makes the name meaningful.
 */
data class AuditRetention(
    val maxAgeDays: Int = 7,
    /** 20 MiB. The audit is a debugging aid, not a data store. */
    val maxTotalBytes: Long = 20L * 1024L * 1024L,
)
