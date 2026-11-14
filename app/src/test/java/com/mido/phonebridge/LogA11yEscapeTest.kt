package com.mido.phonebridge

import android.view.View
import android.view.ViewGroup
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.screenshots.Fixtures
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The spoken description of a log row must never carry the raw plugin id (U+202E reorders text). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LogA11yEscapeTest {
    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock() }

    @After fun after() = Fixtures.reset()

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    @Test fun `a plugin id with U+202E is spoken as a token on the log row`() {
        Fixtures.pinLogClock()
        val record = Fixtures.auditRecord("rlo-0", Fixtures.NOW_MS, AuditOutcome.APPROVED, 0)
            .copy(plugin = "evil‮plugin")
        Fixtures.auditStore().append(record)
        val a = Robolectric.buildActivity(LogActivity::class.java).setup().get()
        val descriptions = a.window.decorView.all()
            .filter { it.isClickable && it.isLongClickable }.mapNotNull { it.contentDescription?.toString() }.toList()
        assertTrue(descriptions.toString(), descriptions.any { it.contains("\\u{202E}") })
        assertFalse(descriptions.toString(), descriptions.any { it.contains('‮') })
    }
}
