package com.mido.phonebridge.core

import java.time.ZoneId

/**
 * Validation for a caller-supplied time zone id.
 *
 * Why this is its own type: the Calendar routes must never let a request run against an
 * implicit zone. A `+02:00` window silently reinterpreted in the calendar's own zone
 * (`Asia/Kuala_Lumpur`) is how a "one day" read picks up a neighbouring day's event —
 * observed live on 2026-10-18, where an Oct 1–5 window matched a block starting Oct 6
 * because the window's end crossed midnight in the calendar's zone.
 *
 * So the zone is REQUIRED, and a required value that is merely non-blank is not validated
 * at all. This is the check. It accepts exactly what `java.time.ZoneId` accepts — IANA
 * region ids (`Europe/Berlin`), fixed offsets (`+02:00`), and `UTC` — and rejects
 * everything else, so a typo fails as a 400 before any approval tap rather than becoming
 * an implicit default downstream.
 *
 * Pure and JVM-testable, like [ErrorCodes]: the rule lives here, the enforcement lives in
 * the Calendar plugin, and the test covers the rule.
 */
object TimeZones {

    /** True when [id] names a real zone the platform understands. */
    fun isValid(id: String): Boolean = try {
        ZoneId.of(id)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * True when two zone ids denote the SAME zone, with aliases resolved by the platform
     * (`UTC` vs `Z`, `GMT+2` vs `+02:00`). Used where a zone must match rather than merely
     * be valid — e.g. `quickAdd`, which Google parses in the calendar's own zone and
     * cannot be told otherwise. An unparseable id is never "the same"; it is not a zone.
     *
     * Compared via [ZoneId.normalized]: raw `equals` is false for `UTC` vs `Z` (one is a
     * region, one an offset), which would reject a caller who meant exactly the same zone.
     * Normalization collapses fixed-offset aliases and leaves DST regions intact, so
     * `Europe/Berlin` and `+02:00` stay DIFFERENT — an offset cannot express the winter
     * rules, and treating them as equal would create an event wrong half the year.
     */
    fun same(a: String, b: String): Boolean = try {
        ZoneId.of(a).normalized() == ZoneId.of(b).normalized()
    } catch (e: Exception) {
        false
    }
}
