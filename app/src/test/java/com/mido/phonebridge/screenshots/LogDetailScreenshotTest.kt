package com.mido.phonebridge.screenshots

import android.content.Intent
import com.mido.phonebridge.LogDetailActivity
import org.junit.Test

/** Request detail (WP08 section 12). */
class LogDetailScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun shoot(id: String, state: String) {
        val a = launch(LogDetailActivity::class.java, Intent().putExtra(LogDetailActivity.EXTRA_ID, id))
        capture(a, "log_detail", state)
    }

    private fun shootKind(kind: String) = shoot(Fixtures.detailRecord(kind), kind)

    @Test fun log_detail_approved() = shootKind("approved")
    @Test fun log_detail_policy_changed() = shootKind("policy_changed")
    @Test fun log_detail_unrouted() = shootKind("unrouted")
    @Test fun log_detail_failed_with_error() = shootKind("failed_with_error")
    @Test fun log_detail_truncated() = shootKind("truncated")
    @Test fun log_detail_bodies_absent() = shootKind("bodies_absent")
    @Test fun log_detail_unattended() = shootKind("unattended")

    @Test fun log_detail_not_found() {
        Fixtures.pinLogClock()
        Fixtures.auditStore()
        shoot("no-such-record", "not_found")
    }

    @Test fun log_detail_unreadable() {
        Fixtures.logUnreadableLine("wp08-bad")
        shoot("wp08-bad", "unreadable")
    }
}
