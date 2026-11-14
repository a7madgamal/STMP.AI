package com.mido.phonebridge

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.debug.ScreenshotHostActivity
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.UntrustedText
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The guard for every screen, present and future: a stored audit record, policy exception and caller
 * whose every string holds U+202E and U+200B is rendered on each screen that shows stored text, and
 * the whole view tree is walked. No text, hint, contentDescription, stateDescription or accessibility
 * event may carry a raw hidden code point ([UntrustedText.needsEscape]). A new screen that shows
 * stored text belongs in [screens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class EveryScreenEscapesStoredTextGuardTest {
    private val bad = "x‮y​z"

    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock(); Fixtures.pinLogClock() }

    @After fun after() = Fixtures.reset()

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun hostFragment(fragment: Class<*>, tab: Theme.Tab): Activity {
        val intent = android.content.Intent(Fixtures.app, ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, fragment.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, tab.name)
        return Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup().get()
    }

    private fun seed(): String {
        val base = Fixtures.auditRecord("evil-0", Fixtures.NOW_MS, AuditOutcome.APPROVED, 0)
        val record = base.copy(
            id = "evil-0", label = bad, plugin = bad, verb = bad, origin = bad, remote = bad, requester = bad,
            route = bad, method = bad, path = bad, query = mapOf(bad to bad), summary = bad,
            requestHeaders = mapOf(bad to bad), requestBody = bad, responseBody = bad, error = bad,
        )
        Fixtures.auditStore().append(record)
        Fixtures.exceptions(Rule(bad, Verb.EDIT, Mode.PARANOID), Rule(bad, null, Mode.DONT_DISTURB))
        Fixtures.callers(bad to CallerState.Verified(bad), "10.0.0.$bad" to CallerState.Denied)
        return record.id
    }

    private fun screens(id: String): Map<String, () -> Activity> = mapOf(
        "log" to { Robolectric.buildActivity(LogActivity::class.java).setup().get() },
        "log detail" to {
            Robolectric.buildActivity(
                LogDetailActivity::class.java,
                android.content.Intent(Fixtures.app, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_ID, id),
            ).setup().get()
        },
        "home tape" to { hostFragment(HomeFragment::class.java, Theme.Tab.HOME) },
        "exceptions" to { Robolectric.buildActivity(ExceptionsActivity::class.java).setup().get() },
        "callers list" to { hostFragment(OptionsFragment::class.java, Theme.Tab.SETTINGS) },
    )

    private fun raw(s: CharSequence?): Boolean = s != null && UntrustedText.hasHidden(s)

    @Test fun `no screen draws or speaks a raw hidden character from stored text`() {
        val id = seed()
        val failures = mutableListOf<String>()
        for ((name, launch) in screens(id)) {
            val activity = launch()
            ShadowLooper.idleMainLooper()
            val views = activity.window.decorView.all().toList()
            assertTrue("$name rendered nothing", views.size > 3)
            for (v in views) {
                fun check(kind: String, s: CharSequence?) {
                    if (raw(s)) failures += "$name: ${v.javaClass.simpleName} $kind = ${UntrustedText.escape(s!!)}"
                }
                if (v is TextView) { check("text", v.text); check("hint", v.hint) }
                check("contentDescription", v.contentDescription)
                check("stateDescription", v.stateDescription)
                val info = AccessibilityNodeInfo()
                v.onInitializeAccessibilityNodeInfo(info)
                check("info.text", info.text); check("info.hintText", info.hintText)
                check("info.contentDescription", info.contentDescription)
                val event = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
                v.onInitializeAccessibilityEvent(event)
                v.onPopulateAccessibilityEvent(event)
                event.text.forEach { check("event.text", it) }
                check("event.contentDescription", event.contentDescription)
            }
        }
        assertTrue("raw hidden characters reach a view:\n" + failures.distinct().joinToString("\n"), failures.isEmpty())
    }
}
