package com.mido.phonebridge.kit

import android.view.MotionEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.RockerSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RockerSwitchTest : KitTest() {

    @Test
    fun `setCheckedImmediately sets checked with no animation`() {
        val a = host()
        val r = a.place(RockerSwitch(a))
        r.setCheckedImmediately(true)
        assertTrue(r.checked)
        assertEquals(1f, r.fraction, 0f)
        r.setCheckedImmediately(false)
        assertEquals(0f, r.fraction, 0f)
    }

    @Test
    fun `tap sends the live state even after repaints`() {
        val a = host()
        val sent = ArrayList<Boolean>()
        val r = a.place(Theme.rocker(a, on = false, label = "Auto-start") { sent.add(it) })
        r.performClick()
        r.setCheckedImmediately(true)   // the screen repaints from stored truth
        r.performClick()
        r.setCheckedImmediately(false)
        r.performClick()
        assertEquals(listOf(true, false, true), sent)
    }

    @Test
    fun `pending keeps the cap where it is`() {
        val a = host()
        val r = a.place(RockerSwitch(a))
        r.setCheckedImmediately(false)
        r.pending = true
        assertFalse(r.checked)
        assertEquals(0f, r.fraction, 0f)
    }

    @Test
    fun `node is a checkable switch with the right state`() {
        val a = host()
        val r = a.place(Theme.rocker(a, on = true, label = "Auto-start") {})
        val info = AccessibilityNodeInfoCompat.wrap(r.createAccessibilityNodeInfo())
        assertEquals("android.widget.Switch", info.className.toString())
        assertTrue(info.isCheckable)
        assertTrue(info.isChecked)
        assertEquals("On", info.stateDescription.toString())
        r.pending = true
        assertEquals("Working", AccessibilityNodeInfoCompat.wrap(r.createAccessibilityNodeInfo()).stateDescription.toString())
    }

    @Test
    fun `disabled ignores taps`() {
        val a = host()
        var taps = 0
        val r = a.place(Theme.rocker(a, on = false, label = "x") { taps++ })
        r.isEnabled = false
        val t = android.os.SystemClock.uptimeMillis()
        touch(r, MotionEvent.ACTION_DOWN, downTime = t)
        touch(r, MotionEvent.ACTION_UP, downTime = t)
        idle()
        assertEquals(0, taps)
        r.isEnabled = true
        touch(r, MotionEvent.ACTION_DOWN, downTime = t)
        touch(r, MotionEvent.ACTION_UP, downTime = t)
        idle()
        assertEquals(1, taps)
    }

    @Test
    fun `tap plays the toggle haptic, programmatic changes do not`() {
        val a = host()
        val r = a.place(Theme.rocker(a, on = false, label = "x") {})
        r.setCheckedImmediately(true)
        assertEquals(0, haptics.size)
        r.performClick()
        assertEquals(1, haptics.size)
    }
}
