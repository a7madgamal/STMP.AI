package com.mido.phonebridge.screenshots

import android.content.Intent
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

    private fun caller(state: String) {
        val a = launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, "100.64.0.9"))
        capture(a, "caller", state)
    }
}
