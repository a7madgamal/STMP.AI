package com.mido.phonebridge.kit

import com.mido.phonebridge.ui.kit.KeyButton
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
}
