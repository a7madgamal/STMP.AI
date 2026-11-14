package com.mido.phonebridge.ui

import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Every time a screen shows a moment to a person: local, in the device's own format, with the zone
 * named when it matters.
 *
 * **Why this is not a `SimpleDateFormat` at each call site.** The log used
 * `SimpleDateFormat("HH:mm:ss", Locale.US)`, which forces 24-hour American formatting on a phone
 * that may be set to 12-hour, in a locale that may write dates in a different order, in a zone the
 * reader may not be in. Every one of those is a small lie about when something happened, and the
 * request log's whole job is to say when something happened.
 *
 * Pure: every function takes its zone, locale and 12/24 choice explicitly, so the behaviour is
 * testable without an `Activity` and a device in another timezone.
 */
object LogTime {

    /**
     * The zone the phone is in NOW.
     *
     * `TimeZone.getDefault()` is cached in the process at startup, and the bridge runs for days —
     * so a phone carried across a timezone would keep reporting the old one until restart. Clearing
     * the cache first makes each read honest.
     */
    fun zone(): ZoneId {
        TimeZone.setDefault(null)
        return ZoneId.systemDefault()
    }

    /**
     * Whether the device is set to 24-hour time.
     *
     * Takes a Context because `DateFormat.is24HourFormat(null)` throws a NullPointerException
     * inside the platform (`context.getUserId()` on null) rather than falling back to a default.
     * There is no context-free way to ask this question.
     */
    fun is24Hour(context: android.content.Context): Boolean =
        DateFormat.is24HourFormat(context)

    /** "22:41:25", or "10:41:25 PM" on a 12-hour device. */
    fun clock(ts: Long, zone: ZoneId, locale: Locale, is24: Boolean): String =
        format(ts, zone, locale, if (is24) "Hms" else "hms")

    /** "10 Nov 2026, 22:41:25 MYT" — the form used where a single row must stand alone. */
    fun dateTime(ts: Long, zone: ZoneId, locale: Locale, is24: Boolean): String =
        format(ts, zone, locale, if (is24) "yMMMdHms" else "yMMMdhms") +
            " " + zoneName(ts, zone, locale)

    /**
     * "Today", "Yesterday", "Mon 9 Nov", or "Mon 9 Nov 2025" once the year differs.
     *
     * Grouping a log by day heading is what turns a scroll of timestamps into a list of sessions.
     * The year appears only when it is not this one, because repeating the current year on every
     * heading is noise that pushes the useful word off the line.
     */
    fun dayHeading(ts: Long, now: Long, zone: ZoneId, locale: Locale): String {
        val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            else -> format(
                ts, zone, locale,
                if (day.year == today.year) "EEEMMMd" else "EEEMMMdy",
            )
        }
    }

    /**
     * "MYT" when the platform has a name for the zone, else "GMT+8".
     *
     * The fallback exists because a bare offset is still information while an empty string is not:
     * a zone name is a courtesy, the offset is the fact.
     */
    fun zoneName(ts: Long, zone: ZoneId, locale: Locale): String {
        val z = Instant.ofEpochMilli(ts).atZone(zone)
        val short = DateTimeFormatter.ofPattern("z", locale).format(z)
        if (short.isNotBlank() && !short.startsWith("GMT") && short.none { it.isDigit() }) {
            return short
        }
        val seconds = z.offset.totalSeconds
        if (seconds == 0) return "GMT"
        val sign = if (seconds < 0) "-" else "+"
        val abs = kotlin.math.abs(seconds)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return "GMT$sign$h" + if (m != 0) ":%02d".format(m) else ""
    }

    /**
     * One format path, from an Android skeleton.
     *
     * A skeleton like "Hms" describes the FIELDS to show and lets the platform pick the order and
     * separators for the locale; a hardcoded pattern like "HH:mm:ss" is a guess about half the
     * world. This is the whole reason the object exists.
     */
    private fun format(ts: Long, zone: ZoneId, locale: Locale, skeleton: String): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return DateTimeFormatter.ofPattern(pattern, locale)
            .format(Instant.ofEpochMilli(ts).atZone(zone))
    }
}
