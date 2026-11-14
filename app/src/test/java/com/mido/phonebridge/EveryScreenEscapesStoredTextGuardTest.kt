package com.mido.phonebridge

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import org.robolectric.android.controller.ActivityController
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

    /** A plugin id no other stored string starts like, so its views are told apart. */
    private val badPlugin = "plug‮in​x"

    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock(); Fixtures.pinLogClock() }

    @After fun after() = Fixtures.reset()

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun hostFragment(fragment: Class<*>, tab: Theme.Tab): ActivityController<out Activity> {
        val intent = android.content.Intent(Fixtures.app, ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, fragment.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, tab.name)
        return Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup()
    }

    private fun seed(): String {
        val base = Fixtures.auditRecord("evil-0", Fixtures.NOW_MS, AuditOutcome.APPROVED, 0)
        val record = base.copy(
            id = "evil-0", label = bad, plugin = badPlugin, verb = bad, origin = bad, remote = bad, requester = bad,
            route = bad, method = bad, path = bad, query = mapOf(bad to bad), summary = bad,
            requestHeaders = mapOf(bad to bad), requestBody = bad, responseBody = bad, error = bad,
        )
        Fixtures.auditStore().append(record)
        // The Home tape lists only records with a known verb, so a second record carries one.
        Fixtures.auditStore().append(record.copy(id = "evil-1", verb = Verb.EDIT.name, ts = record.ts + 1))
        Fixtures.exceptions(Rule(badPlugin, Verb.EDIT, Mode.PARANOID), Rule(badPlugin, null, Mode.DONT_DISTURB))
        Fixtures.callers(bad to CallerState.Verified(bad), "10.0.0.$bad" to CallerState.Denied)
        return record.id
    }

    private fun screens(id: String): Map<String, () -> ActivityController<out Activity>> = mapOf(
        "log" to { Robolectric.buildActivity(LogActivity::class.java).setup() },
        "log detail" to {
            Robolectric.buildActivity(
                LogDetailActivity::class.java,
                android.content.Intent(Fixtures.app, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_ID, id),
            ).setup()
        },
        "home tape" to { hostFragment(HomeFragment::class.java, Theme.Tab.HOME) },
        "exceptions" to { Robolectric.buildActivity(ExceptionsActivity::class.java).setup() },
        "callers list" to { hostFragment(OptionsFragment::class.java, Theme.Tab.SETTINGS) },
    )

    /** Screens whose plugin name is drawn with spans, and how many views must carry the caution colour. */
    private val cautionViews = mapOf("log" to 1, "log detail" to 2)

    /** Only the plugin name starts with "Plug" plus the token. */
    private fun TextView.hasCautionToken(caution: Int): Boolean {
        val t = text as? Spanned ?: return false
        val token = UntrustedText.token(0x202E)
        if (!t.startsWith("Plug$token")) return false
        var from = t.indexOf(token)
        while (from >= 0) {
            val hit = t.getSpans(from, from + token.length, ForegroundColorSpan::class.java)
                .any { it.foregroundColor == caution && t.getSpanStart(it) <= from && t.getSpanEnd(it) >= from + token.length }
            if (hit) return true
            from = t.indexOf(token, from + 1)
        }
        return false
    }

    private fun raw(s: CharSequence?): Boolean = s != null && UntrustedText.hasHidden(s)

    @Test fun `no screen draws or speaks a raw hidden character from stored text`() {
        val id = seed()
        val failures = mutableListOf<String>()
        for ((name, launch) in screens(id)) {
            val controller = launch()
            val activity = controller.get()
            ShadowLooper.idleMainLooper()
            val views = activity.window.decorView.all().toList()
            assertTrue("$name rendered nothing", views.size > 3)
            val token = UntrustedText.token(0x202E)
            assertTrue(
                "$name never rendered the stored text (no view holds $token)",
                views.any { v ->
                    (v is TextView && v.text.contains(token)) || v.contentDescription?.contains(token) == true
                },
            )
            val caution = Theme.palette(activity).cautionText
            val needed = cautionViews[name] ?: 0
            val marked = views.count { it is TextView && it.hasCautionToken(caution) }
            assertTrue("$name: $marked views draw the token in the caution colour, expected at least $needed", marked >= needed)
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
            controller.pause().stop().destroy()
        }
        assertTrue("raw hidden characters reach a view:\n" + failures.distinct().joinToString("\n"), failures.isEmpty())
    }
}
