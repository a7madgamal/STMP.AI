package com.mido.phonebridge.audit

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one id format for every line in the audit log.
 *
 * Extracted so a `POLICY_CHANGED` record and a request record cannot drift into
 * two different shapes. `line(id)` matches on the id prefix, so two formats means
 * a lookup that silently finds nothing — or, worse, finds the wrong line.
 */
object AuditId {

    private val FMT = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS", Locale.US)

    /**
     * Monotonic within the process. A RANDOM suffix was the first attempt and it
     * was wrong: two records in the same millisecond can draw the same value, and
     * `line(id)` matches on the id prefix, so a collision silently returns the
     * WRONG record. In a log whose only job is to be trustworthy, "unlikely" is
     * not a guarantee. A counter is.
     *
     * Wraps at 65535 so the suffix stays four hex digits wide. Wrapping can only
     * collide with an id from the same millisecond after 65 536 records, which no
     * real request rate reaches.
     */
    private val seq = AtomicInteger(0)

    fun next(ts: Long): String {
        val n = seq.getAndUpdate { if (it >= 65535) 0 else it + 1 }
        return FMT.format(Date(ts)) + "-" + Integer.toHexString(n).padStart(4, '0')
    }
}
