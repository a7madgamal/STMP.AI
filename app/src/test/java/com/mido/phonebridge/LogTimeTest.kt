package com.mido.phonebridge

import com.mido.phonebridge.ui.LogTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.Locale

/**
 * The request log's clock, pinned.
 *
 * Every assertion passes its zone, locale and 12/24 choice explicitly — the whole point of the
 * object is that those are arguments rather than ambient state, so a test can put the phone in
 * Kuala Lumpur at 10 PM and check the string without one.
 */
@RunWith(RobolectricTestRunner::class)
class LogTimeTest {

    // 2026-11-10T14:41:25Z — chosen so the same instant is a different wall clock in two zones.
    private val ts = 1_762_785_685_000L
    private val kualaLumpur = ZoneId.of("Asia/Kuala_Lumpur")
    private val london = ZoneId.of("Europe/London")
    private val us = Locale.US

    @Test
    fun `a 24-hour locale renders 24-hour time`() {
        val s = LogTime.clock(ts, kualaLumpur, us, is24 = true)
        assertTrue("expected a 22-hour or later clock, got $s", s.contains("22:41:25"))
    }

    @Test
    fun `a 12-hour device renders a 12-hour clock with a marker`() {
        val s = LogTime.clock(ts, kualaLumpur, us, is24 = false)
        assertTrue(
            "a 12-hour device must not get 22:41, got $s",
            !s.contains("22:41"),
        )
        assertTrue("expects an AM/PM marker, got $s", s.contains("10:41:25") && s.contains("PM"))
    }

    @Test
    fun `the same instant is a different wall clock in a different zone`() {
        val my = LogTime.clock(ts, kualaLumpur, us, is24 = true)
        val uk = LogTime.clock(ts, london, us, is24 = true)
        assertTrue("Malaysia must read 22:xx, got $my", my.contains("22:"))
        assertTrue("London must read 14:xx in November, got $uk", uk.contains("14:"))
    }

    @Test
    fun `today and yesterday are named, not dated`() {
        val noon = 1_762_785_685_000L
        assertEquals("Today", LogTime.dayHeading(noon, noon + 3_600_000L, kualaLumpur, us))
        assertEquals(
            "Yesterday",
            LogTime.dayHeading(noon, noon + 24 * 3_600_000L + 60_000L, kualaLumpur, us),
        )
    }

    @Test
    fun `an older day shows the date and omits a same-year year`() {
        val old = ts - 40L * 24 * 3_600_000L
        val heading = LogTime.dayHeading(old, ts, kualaLumpur, us)
        assertTrue("must name a weekday and day, got $heading", heading.contains("Oct"))
        assertTrue(
            "a same-year heading must not repeat the year, got $heading",
            !heading.contains("2026"),
        )
    }

    @Test
    fun `a zone with no short name falls back to an offset`() {
        // A fixed offset zone has no abbreviation, so the fallback path is the one that runs.
        val name = LogTime.zoneName(ts, ZoneId.of("+08:00"), us)
        assertEquals("GMT+8", name)
    }

    @Test
    fun `a zone with a real short name uses it`() {
        val name = LogTime.zoneName(ts, kualaLumpur, us)
        assertTrue("expected MYT, got $name", name == "MYT" || name.startsWith("GMT"))
    }
}
