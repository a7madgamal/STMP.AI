package com.mido.phonebridge

import com.mido.phonebridge.core.TimeZones
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the rule behind the Calendar routes' REQUIRED `timeZone`.
 *
 * Live on 2026-10-18 an Oct 1–5 window (`+02:00`) matched an event starting Oct 6,
 * because a missing `timeZone` made Google interpret the window in the CALENDAR's zone
 * (`Asia/Kuala_Lumpur`). The fix is to require the zone — and a required value that is
 * never validated is not required, so this is the validation rule, tested on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TimeZonesTest {

    @Test
    fun `real IANA zones are accepted`() {
        for (z in listOf("Europe/Berlin", "Asia/Kuala_Lumpur", "America/New_York", "UTC")) {
            assertTrue("$z should be valid", TimeZones.isValid(z))
        }
    }

    @Test
    fun `fixed offsets are accepted`() {
        // RFC3339 offsets are real zones; a caller sending +02:00 means exactly that.
        for (z in listOf("+02:00", "-05:00", "Z")) {
            assertTrue("$z should be valid", TimeZones.isValid(z))
        }
    }

    @Test
    fun `typos and non-zones are refused`() {
        // Verified against the platform's own parser, not assumed: `GMT+2` is a REAL zone
        // (it normalizes to +02:00) and is deliberately absent from this list.
        for (z in listOf("", " ", "Berlin", "Europe/Berlin ", "Mars/Phobos", "02:00")) {
            assertFalse("'$z' must be refused", TimeZones.isValid(z))
        }
    }

    @Test
    fun `platform-style aliases are accepted because the platform accepts them`() {
        // GMT+2 and UTC are valid ZoneIds; a validator stricter than the platform would
        // reject zones Google itself would accept, which is a self-inflicted 400.
        assertTrue(TimeZones.isValid("GMT+2"))
        assertTrue(TimeZones.isValid("GMT"))
        assertTrue(TimeZones.isValid("Etc/GMT+2"))
    }

    @Test
    fun `same resolves aliases but never invents equivalence`() {
        assertTrue(TimeZones.same("UTC", "Z"))
        assertTrue(TimeZones.same("Europe/Berlin", "Europe/Berlin"))
        // A region and its current offset are NOT the same zone: the offset cannot
        // express the region's winter rules, so treating them as equal would create an
        // event that is wrong half the year.
        assertFalse(TimeZones.same("Europe/Berlin", "+02:00"))
        assertFalse(TimeZones.same("Asia/Kuala_Lumpur", "Europe/Berlin"))
        // An unparseable id is never "the same".
        assertFalse(TimeZones.same("nonsense", "nonsense"))
    }
}
