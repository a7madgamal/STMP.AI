package com.mido.phonebridge.kit

import android.widget.Toast
import com.mido.phonebridge.ui.kit.Toasts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToastsTest : KitTest() {

    @Test
    fun `shows the text for a short time`() {
        Toasts.show(host(), "Copied")
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
    }

    @Test
    fun `only the legacy long duration is passed through`() {
        Toasts.show(host(), "Saved", Toast.LENGTH_LONG)
        assertEquals(Toast.LENGTH_LONG, ShadowToast.getLatestToast().duration)
        Toasts.show(host(), "Saved", 99)
        assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
    }
}
