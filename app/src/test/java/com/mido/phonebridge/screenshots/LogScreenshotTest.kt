package com.mido.phonebridge.screenshots

import android.app.Activity
import android.view.View
import com.mido.phonebridge.LogActivity
import org.junit.Test

/** Request log (WP08 section 12). */
class LogScreenshotTest(variant: Variant) : ShotTest(variant) {

    /** The chip frame that holds a pill reading [label]. */
    private fun tap(a: Activity, label: String) {
        (findByText(a.window.decorView, label)!!.parent as View).performClick()
    }

    @Test fun log_empty_no_records() {
        Fixtures.pinLogClock()
        capture(launch(LogActivity::class.java), "log", "empty_no_records")
    }

    @Test fun log_list_default() {
        Fixtures.logList()
        capture(launch(LogActivity::class.java), "log", "list_default")
    }

    @Test fun log_list_with_noise() {
        Fixtures.logList()
        Fixtures.logNoise()
        val a = launch(LogActivity::class.java)
        tap(a, "Show noise")
        capture(a, "log", "list_with_noise")
    }

    @Test fun log_only_noise() {
        Fixtures.pinLogClock()
        Fixtures.auditStore()
        Fixtures.logNoise()
        capture(launch(LogActivity::class.java), "log", "only_noise")
    }

    @Test fun log_filter_denied() {
        Fixtures.logList()
        val a = launch(LogActivity::class.java)
        tap(a, "Denied")
        capture(a, "log", "filter_denied")
    }

    @Test fun log_filter_empty() {
        Fixtures.logList(withFailure = false)
        val a = launch(LogActivity::class.java)
        tap(a, "Failed")
        capture(a, "log", "filter_empty")
    }

    @Test fun log_unreadable_lines() {
        Fixtures.logList()
        Fixtures.logCorruptLines(2)
        capture(launch(LogActivity::class.java), "log", "unreadable_lines")
    }
}
