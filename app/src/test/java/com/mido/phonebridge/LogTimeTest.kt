package com.mido.phonebridge

import com.mido.phonebridge.ui.LogTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogTimeTest {
    private val ts = 1_794_346_882_505L
    private val utc = ZoneId.of("UTC")
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")

    @Test fun `clock in 24 and 12 hour form`() {
        assertEquals("21:41:22", LogTime.clock(ts, utc, Locale.US, true))
        assertEquals("9:41:22 PM", LogTime.clock(ts, utc, Locale.US, false).replace(' ', ' '))
    }

    @Test fun `dateTime names the zone and uses the local clock`() {
        val s = LogTime.dateTime(ts, kl, Locale.US, true)
        assertTrue(s, s.endsWith("MYT") || s.endsWith("GMT+8"))
        assertTrue(s, s.contains("05:41:22"))
    }

    @Test fun `day headings`() {
        val day = 86_400_000L
        assertEquals("Today", LogTime.dayHeading(ts, ts, utc, Locale.US))
        assertEquals("Yesterday", LogTime.dayHeading(ts - day, ts, utc, Locale.US))
        val older = LogTime.dayHeading(ts - 3 * day, ts, utc, Locale.US)
        assertTrue(older, older.contains("Nov") && !older.contains("2026"))
        val lastYear = LogTime.dayHeading(ts - 400 * day, ts, utc, Locale.US)
        assertTrue(lastYear, lastYear.contains("2025"))
    }

    @Test fun `zone is never null and resets the cached default`() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
        assertNotNull(LogTime.zone())
        assertTrue(LogTime.zoneName(ts, kl, Locale.US).isNotBlank())
    }
}
