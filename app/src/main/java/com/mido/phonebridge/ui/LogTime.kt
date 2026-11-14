package com.mido.phonebridge.ui

import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every time the request log shows a person: local, with the zone named (WP08 4.2). */
object LogTime {
    /**
     * The zone the phone is in NOW. The bridge process lives for days and Android caches
     * `TimeZone.getDefault()` at process start, so the cache is reset first.
     */
    fun zone(): ZoneId {
        java.util.TimeZone.setDefault(null)
        return ZoneId.systemDefault()
    }

    /** "22:41:25" or "10:41:25 PM". */
    fun clock(ts: Long, zone: ZoneId, locale: Locale, is24: Boolean): String =
        format(ts, zone, locale, if (is24) "Hms" else "hms")

    /** "10 Nov 2026, 22:41:25 MYT". */
    fun dateTime(ts: Long, zone: ZoneId, locale: Locale, is24: Boolean): String =
        format(ts, zone, locale, (if (is24) "yMMMdHms" else "yMMMdhms")) + " " + zoneName(ts, zone, locale)

    /** "Today", "Yesterday", "Mon 9 Nov", or "Mon 9 Nov 2025" when not this year. */
    fun dayHeading(ts: Long, now: Long, zone: ZoneId, locale: Locale): String {
        val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            else -> format(ts, zone, locale, if (day.year == today.year) "EEEMMMd" else "EEEMMMdy")
        }
    }

    /** "MYT" when the platform has a short name, else "GMT+8". */
    fun zoneName(ts: Long, zone: ZoneId, locale: Locale): String {
        val z = Instant.ofEpochMilli(ts).atZone(zone)
        val short = java.time.format.DateTimeFormatter.ofPattern("z", locale).format(z)
        if (short.isNotBlank() && !short.startsWith("GMT") && short.none { it.isDigit() }) return short
        val seconds = z.offset.totalSeconds
        if (seconds == 0) return "GMT"
        val sign = if (seconds < 0) "-" else "+"
        val abs = kotlin.math.abs(seconds)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return "GMT$sign$h" + if (m != 0) ":%02d".format(m) else ""
    }

    private fun format(ts: Long, zone: ZoneId, locale: Locale, skeleton: String): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(ts).atZone(zone))
    }
}
