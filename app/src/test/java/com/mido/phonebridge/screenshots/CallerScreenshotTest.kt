package com.mido.phonebridge.screenshots

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import com.mido.phonebridge.CallerActivity
import org.junit.Test

/** Caller registration screen. */
class CallerScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun caller_default() = caller("default")

    @Test fun caller_name_required() {
        val a = launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, "100.64.0.9"))
        // Confirming with an empty name is what raises the "name required" state.
        findByText(a.window.decorView, "allow")?.performClick()
        capture(a, "caller", "name_required")
    }

    /** A tap while another window fully covers the screen (D17): the banner shows, nothing decides. */
    @Test fun caller_obscured_banner() {
        val a = launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, "100.64.0.9"))
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 200f; y = 400f; pressure = 1f; size = 1f })
            val ev = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            a.window.decorView.dispatchTouchEvent(ev)
            ev.recycle()
        }
        capture(a, "caller", "obscured_banner")
    }

    private fun caller(state: String) {
        val a = launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, "100.64.0.9"))
        capture(a, "caller", state)
    }
}
