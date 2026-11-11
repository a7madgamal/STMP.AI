package com.mido.phonebridge.kit

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ObscuredTouchGuardTest : KitTest() {

    private class Rig(val clicks: IntArray, val obscured: IntArray, val clear: IntArray, val guard: ObscuredTouchGuard)

    private fun rig(): Pair<Rig, com.mido.phonebridge.debug.KitHostActivity> {
        val a = host()
        val clicks = intArrayOf(0); val obscured = intArrayOf(0); val clear = intArrayOf(0)
        val guard = ObscuredTouchGuard(a)
        val child = View(a).apply { isClickable = true; setOnClickListener { clicks[0]++ } }
        guard.addView(child, FrameLayout.LayoutParams(200, 200))
        guard.onObscuredTouch = { obscured[0]++ }
        guard.onClearTouch = { clear[0]++ }
        a.place(guard, widthDp = 100, heightDp = 100)
        return Rig(clicks, obscured, clear, guard) to a
    }

    private fun tap(g: View, flags: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        touch(g, MotionEvent.ACTION_DOWN, 10f, 10f, t, flags)
        touch(g, MotionEvent.ACTION_UP, 10f, 10f, t, flags)
        idle()
    }

    @Test
    fun `an obscured gesture never reaches the child and reports once`() {
        val (r, _) = rig()
        tap(r.guard, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, r.clicks[0])
        assertEquals(1, r.obscured[0])
        assertEquals(0, r.clear[0])
    }

    @Test
    fun `a clear gesture clicks and reports clear`() {
        val (r, _) = rig()
        tap(r.guard, 0)
        assertEquals(1, r.clicks[0])
        assertEquals(1, r.clear[0])
        assertEquals(0, r.obscured[0])
    }

    @Test
    fun `partial cover is ignored`() {
        val (r, _) = rig()
        tap(r.guard, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(1, r.clicks[0])
        fun event(flags: Int): MotionEvent {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
            val coords = arrayOf(MotionEvent.PointerCoords())
            return MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
        }
        assertFalse(ObscuredTouchGuard.isObscured(event(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)))
        assertTrue(ObscuredTouchGuard.isObscured(event(MotionEvent.FLAG_WINDOW_IS_OBSCURED)))
    }
}
