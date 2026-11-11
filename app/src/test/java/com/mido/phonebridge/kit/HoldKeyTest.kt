package com.mido.phonebridge.kit

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ui.kit.HoldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HoldKeyTest : KitTest() {

    private fun key(onHeld: () -> Unit = {}, onCancel: () -> Unit = {}): Pair<HoldKey, com.mido.phonebridge.debug.KitHostActivity> {
        val a = host()
        val k = a.place(HoldKey(a))
        k.onHeld = onHeld
        k.onCancelled = onCancel
        return k to a
    }

    @Test
    fun `release before 1200 ms cancels and never approves`() {
        var held = 0; var cancelled = 0
        val (k, _) = key({ held++ }, { cancelled++ })
        touch(k, MotionEvent.ACTION_DOWN)
        advance(1199)
        touch(k, MotionEvent.ACTION_UP)
        advance(500)
        assertEquals(0, held)
        assertEquals(1, cancelled)
    }

    @Test
    fun `holding for 1200 ms approves exactly once`() {
        var held = 0
        val (k, _) = key({ held++ })
        touch(k, MotionEvent.ACTION_DOWN)
        advance(1200)
        assertEquals(1, held)
        touch(k, MotionEvent.ACTION_UP)
        advance(2000)
        assertEquals(1, held)
        assertEquals(1f, k.progress, 0f)
    }

    @Test
    fun `ticks at 300 600 and 900 ms then a confirm`() {
        val (k, _) = key()
        touch(k, MotionEvent.ACTION_DOWN)
        advance(299); assertEquals(0, haptics.size)
        advance(2); assertEquals(1, haptics.size)
        advance(300); assertEquals(2, haptics.size)
        advance(300); assertEquals(3, haptics.size)
        advance(300); assertEquals(4, haptics.size)   // the confirm at 1200
    }

    @Test
    fun `unarmed ignores input`() {
        var held = 0
        val (k, _) = key({ held++ })
        k.armed = false
        touch(k, MotionEvent.ACTION_DOWN)
        advance(1500)
        assertEquals(0, held)
        assertTrue(k.contentDescription.toString().isNotEmpty())
    }

    @Test
    fun `accessibility long click approves and click does nothing`() {
        var held = 0
        val (k, _) = key({ held++ })
        val info = AccessibilityNodeInfoCompat.wrap(k.createAccessibilityNodeInfo())
        val ids = info.actionList.map { it.id }
        assertFalse(ids.contains(AccessibilityNodeInfoCompat.ACTION_CLICK))
        val long = info.actionList.first { it.id == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK }
        assertEquals("Approve", long.label.toString())
        assertFalse(k.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        assertEquals(0, held)
        assertTrue(k.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, null))
        assertEquals(1, held)
    }

    @Test
    fun `enter and space behave like a touch hold`() {
        var held = 0; var cancelled = 0
        val (k, _) = key({ held++ }, { cancelled++ })
        k.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        advance(600)
        k.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals(1, cancelled)
        k.onKeyDown(KeyEvent.KEYCODE_SPACE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        advance(1200)
        assertEquals(1, held)
    }

    @Test
    fun `disabling mid-hold never approves`() {
        var held = 0
        val (k, _) = key({ held++ })
        touch(k, MotionEvent.ACTION_DOWN)
        advance(600)
        k.isEnabled = false
        touch(k, MotionEvent.ACTION_UP)
        advance(2000)
        assertEquals(0, held)
        assertEquals(0f, k.progress, 0f)
    }

    @Test
    fun `disabled key ignores keyboard holds`() {
        var held = 0
        val (k, _) = key({ held++ })
        k.isEnabled = false
        k.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        advance(1500)
        assertEquals(0, held)
    }

    @Test
    fun `unarming mid-hold returns the key to idle and it can be held again`() {
        var held = 0
        val (k, _) = key({ held++ })
        touch(k, MotionEvent.ACTION_DOWN)
        advance(600)
        k.armed = false
        advance(2000)
        assertEquals(0, held)
        assertEquals(0f, k.progress, 0f)
        k.armed = true
        touch(k, MotionEvent.ACTION_DOWN)
        advance(1200)
        assertEquals(1, held)
    }
}
