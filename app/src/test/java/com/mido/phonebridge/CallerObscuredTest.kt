package com.mido.phonebridge

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The new-device screen ignores touches while another window covers it (D17, D60). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class CallerObscuredTest {

    private fun launch(): CallerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CallerActivity::class.java)
            .putExtra(CallerActivity.EXTRA_IP, "100.64.0.9")
        return Robolectric.buildActivity(CallerActivity::class.java, intent).setup().visible().get()
    }

    /** A DOWN/UP pair on the window, at the centre of [target]. */
    private fun tapOn(a: CallerActivity, target: View, flags: Int) {
        val root = a.window.decorView
        val loc = IntArray(2).also { target.getLocationInWindow(it) }
        val x = loc[0] + target.width / 2f
        val y = loc[1] + target.height / 2f
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
            val ev = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
            root.dispatchTouchEvent(ev)
            ev.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun View.findText(text: String, ignoreCase: Boolean = false): TextView? {
        if (this is TextView && this.text?.toString().equals(text, ignoreCase)) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findText(text, ignoreCase)?.let { return it }
        return null
    }

    @Test
    fun `an obscured tap on allow shows the banner and decides nothing`() {
        val a = launch()
        val root = a.window.decorView
        val allow = root.findText("allow", ignoreCase = true)
        assertNotNull(allow)
        val bannerText = root.findText(ObscuredTouchGuard.BANNER_TEXT)
        assertNotNull("the cover banner must be on the screen, hidden", bannerText)
        assertTrue("the banner starts hidden", !bannerText!!.isShown)

        tapOn(a, allow!!, MotionEvent.FLAG_WINDOW_IS_OBSCURED)

        assertTrue("the banner shows while covered", bannerText.isShown)
        assertNull(
            "the obscured tap must not reach allow (no name-required message, no biometric attempt)",
            root.findText("A name is required to allow this device."),
        )
    }

    @Test
    fun `a clear tap on allow with no name reaches the existing check`() {
        val a = launch()
        val root = a.window.decorView
        val allow = root.findText("allow", ignoreCase = true)!!

        tapOn(a, allow, 0)

        assertNotNull(root.findText("A name is required to allow this device."))
        val bannerText = root.findText(ObscuredTouchGuard.BANNER_TEXT)!!
        assertEquals(false, bannerText.isShown)
    }
}
