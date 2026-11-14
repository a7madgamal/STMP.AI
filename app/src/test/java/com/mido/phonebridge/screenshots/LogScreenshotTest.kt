package com.mido.phonebridge.screenshots

import com.mido.phonebridge.LogActivity
import org.junit.Test

/** Audit log screen. */
class LogScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun log_empty() = capture(launch(LogActivity::class.java), "log", "empty")

    @Test fun log_records() {
        Fixtures.auditRecords(8)
        capture(launch(LogActivity::class.java), "log", "records")
    }

    @Test fun log_filtered_empty() {
        Fixtures.auditRecords(2)
        val a = launch(LogActivity::class.java)
        findByText(a.window.decorView, "failed")?.performClick()
        capture(a, "log", "filtered_empty")
    }
}
