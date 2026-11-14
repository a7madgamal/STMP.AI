package com.mido.phonebridge.kit

import android.provider.Settings
import com.mido.phonebridge.ui.kit.Receipt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ReceiptTest : KitTest() {

    private val lines = listOf("STMP RECEIPT 413", "laptop  EXECUTE  /ssh/run", "stamped 14:03:27.201")

    @Test
    fun `four steps 60 ms apart then 1500 ms on screen`() {
        val a = host()
        val done = intArrayOf(0)
        val r = a.place(Receipt(a, lines, "413", "waiting"), widthDp = 331, heightDp = 123)
        r.onDone = { done[0]++ }
        r.feed()
        advance(59); assertEquals(0, r.stepsDone)
        advance(2); assertEquals(1, r.stepsDone)
        advance(60); assertEquals(2, r.stepsDone)
        advance(60); assertEquals(3, r.stepsDone)
        advance(60); assertEquals(4, r.stepsDone)
        assertEquals(0f, r.slipTranslation, 0f)
        assertEquals(4, haptics.size)
        advance(1498); assertEquals(0, done[0])
        advance(3); assertEquals(1, done[0])
    }

    @Test
    fun `a tap closes it at once, once`() {
        val a = host()
        val done = intArrayOf(0)
        val r = a.place(Receipt(a, lines, "413", "waiting"), widthDp = 331, heightDp = 123)
        r.onDone = { done[0]++ }
        r.feed()
        advance(100)
        r.performClick()
        assertEquals(1, done[0])
        advance(3000)
        assertEquals(1, done[0])
    }

    @Test
    fun `reduced motion shows the slip at once and still holds 1500 ms`() {
        val a = host()
        Settings.Global.putFloat(a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val done = intArrayOf(0)
        val r = a.place(Receipt(a, lines, "413", "waiting"), widthDp = 331, heightDp = 123)
        r.onDone = { done[0]++ }
        r.feed()
        assertEquals(0f, r.slipTranslation, 0f)
        assertEquals(1, haptics.size)
        advance(1499); assertEquals(0, done[0])
        advance(2); assertEquals(1, done[0])
    }
}
