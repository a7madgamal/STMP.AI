package com.mido.phonebridge.kit

import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.tokens.Dimens
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyButtonArmedTest : KitTest() {

    @Test
    fun `unarmed ignores clicks and says when it will be available`() {
        val a = host()
        var clicks = 0
        val b = KeyButton.create(a, "Deny", KeyButton.Kind.OUTLINE, onClick = { clicks++ })
        KeyButton.setArmed(b, armed = false, secondsUntilArmed = 3)
        assertEquals(0.38f, b.alpha, 0.001f)
        b.performClick()
        assertEquals(0, clicks)
        assertTrue(b.contentDescription.toString().endsWith("available in 3 seconds"))
        KeyButton.setArmed(b, armed = true, secondsUntilArmed = 0)
        assertEquals(1f, b.alpha, 0f)
        b.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `small key keeps its side padding at rest and after a press`() {
        val a = host()
        val padH = a.resources.displayMetrics.density * 12
        val b = KeyButton.create(a, "Copy", KeyButton.Kind.PANEL, KeyButton.Size.SMALL, onClick = {})
        assertEquals(padH.toInt(), b.paddingLeft)
        assertEquals(padH.toInt(), b.paddingRight)
        val inset = (a.resources.displayMetrics.density * ((Dimens.MIN_TARGET - Dimens.KEY_SMALL_VISUAL) / 2)).toInt()
        assertEquals(inset, b.paddingTop)
        assertEquals(inset, b.paddingBottom)
        val restTop = b.paddingTop
        val restBottom = b.paddingBottom
        b.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        assertEquals(padH.toInt(), b.paddingLeft)
        assertEquals(padH.toInt(), b.paddingRight)
        b.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 5f, 5f, 0))
        assertEquals(padH.toInt(), b.paddingLeft)
        assertEquals(padH.toInt(), b.paddingRight)
        assertEquals(restTop, b.paddingTop)
        assertEquals(restBottom, b.paddingBottom)
    }
}
