package com.mido.phonebridge.screenshots

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import com.mido.phonebridge.CallerActivity
import org.junit.Test
import org.robolectric.shadows.ShadowLooper

/** The new-device screen (WP09 section 5.2). */
class CallerScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun open(ip: String = "100.101.7.22"): CallerActivity =
        launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, ip))

    @Test fun caller_initial() = capture(open(), "caller", "initial")

    @Test fun caller_name_required() {
        val a = open()
        // Allowing with an empty name is what raises the "name required" state.
        findByText(a.window.decorView, "ALLOW")?.performClick()
        capture(a, "caller", "name_required")
    }

    @Test fun caller_cannot_confirm_not_enrolled() {
        val a = open()
        a.showStatusForTest(com.mido.phonebridge.ui.CallerCopy.cannotConfirm(11), isProblem = true)
        capture(a, "caller", "cannot_confirm_not_enrolled")
    }

    @Test fun caller_cannot_confirm_unknown_code() {
        val a = open()
        a.showStatusForTest(com.mido.phonebridge.ui.CallerCopy.cannotConfirm(99), isProblem = true)
        capture(a, "caller", "cannot_confirm_unknown_code")
    }

    @Test fun caller_not_confirmed() {
        val a = open()
        // The platform's own "Authentication canceled" is mapped to our words and shown in caution.
        a.showStatusForTest(com.mido.phonebridge.ui.CallerCopy.notConfirmed(10), isProblem = true)
        capture(a, "caller", "not_confirmed")
    }

    @Test fun caller_save_failed() {
        val a = open()
        a.showStatusForTest("Could not save: disk full", isProblem = true)
        capture(a, "caller", "save_failed")
    }

    /** A full-length IPv6 address: wraps with the continuation glyph, never inside a hextet. */
    @Test fun caller_full_ipv6() = capture(open("fd7a:115c:a1e0:ab12:4843:cd96:6258:2f01"), "caller", "full_ipv6")

    @Test fun caller_long_ip_wrap() = capture(open("2001:db8:85a3::8a2e:370:7334"), "caller", "long_ip_wrap")

    /** A tap while another window fully covers the screen (D17): the banner shows, nothing decides. */
    @Test fun caller_obscured_banner() {
        val a = open("100.64.0.9")
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 200f; y = 400f; pressure = 1f; size = 1f })
            val ev = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            a.window.decorView.dispatchTouchEvent(ev)
            ev.recycle()
        }
        ShadowLooper.idleMainLooper()
        capture(a, "caller", "obscured_banner")
    }
}
