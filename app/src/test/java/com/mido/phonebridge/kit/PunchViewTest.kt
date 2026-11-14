package com.mido.phonebridge.kit

import android.provider.Settings
import com.mido.phonebridge.ui.kit.PunchView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class PunchViewTest : KitTest() {

    private fun punch(held: Boolean): Triple<PunchView, IntArray, IntArray> {
        val a = host()
        val sounds = intArrayOf(0)
        val finished = intArrayOf(0)
        val v = a.place(PunchView(a), widthDp = 411, heightDp = 800)
        v.soundHook = { sounds[0]++ }
        v.holdAfterSettle = held
        v.onFinished = { finished[0]++ }
        v.stamp(PunchView.Verdict.APPROVED)
        return Triple(v, sounds, finished)
    }

    @Test
    fun `not held finishes once at 880 ms and the thud lands at 180 ms`() {
        val (_, sounds, finished) = punch(held = false)
        advance(179); assertEquals(0, sounds[0])
        advance(2); assertEquals(1, sounds[0])
        advance(698); assertEquals(0, finished[0])   // 879
        advance(2); assertEquals(1, finished[0])
        advance(2000); assertEquals(1, finished[0])
        assertEquals(1, sounds[0])
    }

    @Test
    fun `held finishes once at 720 ms`() {
        val (_, _, finished) = punch(held = true)
        advance(719); assertEquals(0, finished[0])
        advance(2); assertEquals(1, finished[0])
        advance(2000); assertEquals(1, finished[0])
    }

    @Test
    fun `reduced motion thuds at once and still finishes at 720 ms`() {
        val a = host()
        Settings.Global.putFloat(a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val sounds = intArrayOf(0); val finished = intArrayOf(0)
        val v = a.place(PunchView(a), widthDp = 411, heightDp = 800)
        v.soundHook = { sounds[0]++ }
        v.onFinished = { finished[0]++ }
        v.stamp(PunchView.Verdict.DENIED)
        assertEquals(1, sounds[0])
        advance(719); assertEquals(0, finished[0])
        advance(2); assertEquals(1, finished[0])
    }

    @Test
    fun `jolt keyframes follow the spec`() {
        val (v, _, _) = punch(held = false)
        assertEquals(0f, v.joltDp(100), 0f)
        assertEquals(3f, v.joltDp(180 + (220 * 0.35f).toLong()), 0.05f)
        assertEquals(-1f, v.joltDp(180 + (220 * 0.65f).toLong()), 0.05f)
        assertEquals(0f, v.joltDp(180 + 220), 0f)
    }
}
