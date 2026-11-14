package com.mido.phonebridge.kit

import android.os.SystemClock
import android.widget.TextView
import com.mido.phonebridge.ui.kit.CountdownTicks
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CountdownTicksTest : KitTest() {

    private fun text(c: CountdownTicks) = c.allViews().filterIsInstance<TextView>().first().text.toString()

    @Test
    fun `lit segments equal the whole seconds left`() {
        val a = host()
        val c = a.place(CountdownTicks(a, 60))
        c.deadlineUptimeMs = SystemClock.uptimeMillis() + 47_000
        assertEquals(47, c.litSegments)
        assertEquals("auto-deny in 0:47", text(c))
        advance(1000)
        assertEquals(46, c.litSegments)
        assertEquals("auto-deny in 0:46", text(c))
    }

    @Test
    fun `last ten seconds tick once each`() {
        val a = host()
        val c = a.place(CountdownTicks(a, 60))
        c.deadlineUptimeMs = SystemClock.uptimeMillis() + 15_000
        advance(4_999)
        assertEquals("nothing above 10 s", 0, haptics.size)
        advance(10_001)
        assertEquals(10, haptics.size)
        assertEquals(Haptics.constantFor(HapticEvent.COUNTDOWN_TICK), haptics.first())
    }

    @Test
    fun `unarmed shows the guard text and lights nothing`() {
        val a = host()
        val c = a.place(CountdownTicks(a, 60))
        c.deadlineUptimeMs = SystemClock.uptimeMillis() + 30_000
        c.armed = false
        assertEquals("ready in 1 s", text(c))
        assertEquals(0, c.litSegments)
        c.unarmedText = "waiting for the AI summary"
        assertEquals("waiting for the AI summary", text(c))
        c.armed = true
        assertEquals(30, c.litSegments)
    }
}
