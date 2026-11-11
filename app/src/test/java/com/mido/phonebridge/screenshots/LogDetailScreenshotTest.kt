package com.mido.phonebridge.screenshots

import android.content.Intent
import com.mido.phonebridge.LogDetailActivity
import org.junit.Test

/** Audit log detail screen. */
class LogDetailScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun log_detail_denied_probe() {
        val ids = Fixtures.auditRecords(2)
        val a = launch(LogDetailActivity::class.java, Intent().putExtra(LogDetailActivity.EXTRA_ID, ids[1]))
        capture(a, "log_detail", "denied_probe")
    }
}
