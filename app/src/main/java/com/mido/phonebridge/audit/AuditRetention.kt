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
    /**
     * Third bound: total stored records, enforced in whole day files.
     *
     * Age alone lets a burst pack an enormous amount into seven days; size alone lets
     * an absurd number of tiny records survive under 20 MiB (a rejection record is a
     * few hundred bytes, so 60k of them fit). This bound exists so the log cannot grow
     * *unbounded in item count* while staying small in bytes.
     *
     * **It is a whole-file bound, so it can be exceeded by one day's volume.** The file
     * name IS the date; trimming a day in half would break the rule that makes the name
     * meaningful. So the oldest whole days are dropped until the total is under, and the
     * newest file is never deleted — which means a single day that alone exceeds
     * [maxLines] stays, at [maxLines] + that day. Pick 5000 so that day is far away.
     */
    val maxLines: Int = 5_000,
)
