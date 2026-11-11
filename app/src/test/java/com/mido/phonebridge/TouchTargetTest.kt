package com.mido.phonebridge

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.chip.ChipGroup
import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.screenshots.GateFixtures
import com.mido.phonebridge.screenshots.KitFixtures
import com.mido.phonebridge.ui.kit.HoldKey
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.RuleLinearLayout
import com.mido.phonebridge.ui.kit.TabBar
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every clickable view of every enrolled fixture is at least 48dp both ways, and two targets that
 * do not overlap are at least 8dp apart (TESTING 6.4). `ENROLLED` is every kit fixture; each screen
 * WP appends its screens in a block headed `// WPnn`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class TouchTargetTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font {0}")
        fun scales() = listOf(arrayOf<Any>(1f), arrayOf<Any>(1.35f))

        private const val MIN_DP = 48f
        private const val MIN_GAP_DP = 8f
        private const val TOLERANCE_DP = 0.5f
    }

    /** WP02: every kit fixture. */
    private val enrolled get() = KitFixtures.all

    @Test
    fun `every enrolled fixture meets the touch target rules`() {
        RuntimeEnvironment.setFontScale(fontScale)
        val failures = ArrayList<String>()
        for (f in enrolled) {
            val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
            val view = f.build(a)
            a.column.addView(
                view,
                view.layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            shadowOf(Looper.getMainLooper()).idle()
            check(a.column, "${f.screen}/${f.state}", failures)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // WP03a: the approval gate in its main gestures and states.
    private val approvalFixtures: List<Pair<String, () -> android.app.Activity>> get() = listOf(
        "approval/hold_ssh" to { GateFixtures.launch(GateFixtures.ssh("ls -la"), "POST /ssh/kvm-box") },
        "approval/tap_probe" to {
            GateFixtures.launch(GateFixtures.view(com.mido.phonebridge.core.Verb.READ, "GET /probe"), "GET /probe")
        },
        "approval/exception_offer" to {
            GateFixtures.launch(
                GateFixtures.contacts(
                    offer = com.mido.phonebridge.ApprovalGate.ExceptionOffer(
                        com.mido.phonebridge.policy.Rule("contacts", com.mido.phonebridge.core.Verb.READ, com.mido.phonebridge.policy.Mode.DONT_DISTURB),
                        "ALWAYS NOTIFY",
                    ),
                ),
                "GET /contacts",
            )
        },
        "approval/password" to { GateFixtures.launch(GateFixtures.ssh("sudo -S true", sudo = true), "POST /ssh/kvm-box") },
    )

    @Test
    fun `every enrolled approval fixture meets the touch target rules`() {
        RuntimeEnvironment.setFontScale(fontScale)
        val failures = ArrayList<String>()
        try {
            for ((name, launch) in approvalFixtures) {
                val a = launch()
                shadowOf(Looper.getMainLooper()).idle()
                check(a.window.decorView as ViewGroup, name, failures)
                GateFixtures.reset()
            }
        } finally {
            GateFixtures.reset()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // WP03b: the Don't-disturb card (the ✕ is 48 x 48; the card itself is at least 48 tall).
    private val notifyVerbs = listOf(com.mido.phonebridge.core.Verb.READ, com.mido.phonebridge.core.Verb.EXECUTE)

    @Test
    fun `every enrolled notify card meets the touch target rules`() {
        RuntimeEnvironment.setFontScale(fontScale)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val wm = app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val failures = ArrayList<String>()
        try {
            for ((i, verb) in notifyVerbs.withIndex()) {
                com.mido.phonebridge.notify.NotifyOverlay.show(
                    ctx = app, id = 9_300L + i, summary = "Run uptime on kvm-box and report the load",
                    label = "RUN", verb = verb, windowMs = 60_000L, facts = null, needsAi = false,
                    fetchCapMs = 0L, onOutcome = { },
                )
                shadowOf(Looper.getMainLooper()).idle()
                val card = org.robolectric.shadow.api.Shadow
                    .extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views.last() as ViewGroup
                val name = "notify_card/${verb.name.lowercase()}"
                check(card, name, failures)
                val d = card.resources.displayMetrics.density
                val close = card.allViews().firstOrNull { it.contentDescription?.startsWith("Deny: ") == true }
                if (close == null || !close.isShown) failures += "$name: no visible ✕"
                else if (close.width / d < MIN_DP - TOLERANCE_DP || close.height / d < MIN_DP - TOLERANCE_DP) {
                    failures += "$name: ✕ is ${close.width / d}x${close.height / d}dp"
                }
                if (card.height / d < MIN_DP - TOLERANCE_DP) failures += "$name: card is ${card.height / d}dp tall"
                com.mido.phonebridge.notify.NotifyOverlay.dismissAll()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
            }
        } finally {
            com.mido.phonebridge.notify.NotifyOverlay.dismissAll()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // WP07: the Settings tab in the states that add controls.
    private val settingsStates: List<Pair<String, () -> Unit>> get() = listOf(
        "settings/default" to { },
        "settings/ai_local_ready" to {
            Fixtures.aiBackend(com.mido.phonebridge.policy.AiBackend.LOCAL)
            Fixtures.modelFile(com.mido.phonebridge.ai.ModelRegistry.EXPECTED_SIZE)
        },
        "settings/ai_local_failed" to {
            Fixtures.aiBackend(com.mido.phonebridge.policy.AiBackend.LOCAL)
            Fixtures.provisioner(false, null, "HTTP 503 from huggingface.co")
        },
        "settings/ai_remote_ready" to {
            Fixtures.externalModel("http://192.168.1.9:8888", "qwen3:8b", com.mido.phonebridge.policy.ExternalDialect.entries.first())
        },
        "settings/callers_and_log" to {
            Fixtures.callers(
                "192.168.1.25" to com.mido.phonebridge.callers.CallerState.Verified("home laptop"),
                "192.168.1.77" to com.mido.phonebridge.callers.CallerState.Denied,
            )
            Fixtures.debugLog(listOf("09:40:00  server started", "09:40:03  GET /help -> 200"))
        },
    )

    @Test
    fun `every enrolled settings state meets the touch target rules`() {
        RuntimeEnvironment.setFontScale(fontScale)
        val failures = ArrayList<String>()
        try {
            for ((name, setup) in settingsStates) {
                Fixtures.dropSingletons()
                setup()
                val intent = android.content.Intent(Fixtures.app, com.mido.phonebridge.debug.ScreenshotHostActivity::class.java)
                    .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_FRAGMENT, OptionsFragment::class.java.name)
                    .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_TAB, com.mido.phonebridge.ui.Theme.Tab.SETTINGS.name)
                val a = Robolectric.buildActivity(com.mido.phonebridge.debug.ScreenshotHostActivity::class.java, intent).setup().get()
                shadowOf(Looper.getMainLooper()).idle()
                // The fragment only: the host's tab bar is the shell's (WP04), not this screen's.
                val screen = a.supportFragmentManager.fragments.filterIsInstance<OptionsFragment>().first().view as ViewGroup
                check(screen, name, failures)
                Fixtures.reset()
            }
        } finally {
            Fixtures.reset()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // WP04: Home states, inside the shell (the 64 dp tab bar is part of every capture).
    private val homeStates: List<Pair<String, () -> Unit>> get() = listOf(
        "home/stopped" to { Fixtures.server(Fixtures.Server.STOPPED) },
        "home/running_healthy" to { Fixtures.server(Fixtures.Server.RUNNING_HEALTHY) },
        "home/running_attention_both" to { Fixtures.serverAttention(listenerOk = false, notifOk = false) },
        "home/failed" to { Fixtures.server(Fixtures.Server.FAILED) },
        "home/audit_degraded" to { Fixtures.auditDegraded() },
        "home/gate_paranoid" to { Fixtures.gate(com.mido.phonebridge.policy.GlobalMode.PARANOID) },
        "home/gate_dont_disturb" to { Fixtures.gate(com.mido.phonebridge.policy.GlobalMode.DONT_DISTURB) },
        "home/gate_unreadable" to { Fixtures.gateUnreadable() },
        "home/tape_three" to { Fixtures.auditTape() },
        "home/updates_available" to { Fixtures.updatesAvailable() },
        "home/updates_installing" to { Fixtures.updatesInstalling() },
        "home/updates_checking" to { Fixtures.updatesChecking() },
        "home/updates_unavailable" to { Fixtures.updatesUnavailable() },
    )

    @Test
    fun `every enrolled home state meets the touch target rules`() {
        RuntimeEnvironment.setFontScale(fontScale)
        val failures = ArrayList<String>()
        try {
            for ((name, setup) in homeStates) {
                Fixtures.dropSingletons()
                setup()
                val intent = android.content.Intent(Fixtures.app, com.mido.phonebridge.debug.ScreenshotHostActivity::class.java)
                    .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_FRAGMENT, HomeFragment::class.java.name)
                    .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_TAB, com.mido.phonebridge.ui.Theme.Tab.HOME.name)
                val a = Robolectric.buildActivity(com.mido.phonebridge.debug.ScreenshotHostActivity::class.java, intent).setup().get()
                shadowOf(Looper.getMainLooper()).idle()
                // The scrolling body and the bar are checked apart: a scroll view clips its content
                // at the bar, so a key near the clip edge is not "close to" a tab cell.
                val body = com.mido.phonebridge.screenshots.firstScroll(a.window.decorView)!!.getChildAt(0) as ViewGroup
                check(body, name, failures)
                check(a.window.decorView.allViews().filterIsInstance<TabBar>().first(), "$name/tabbar", failures)
                Fixtures.reset()
            }
        } finally {
            Fixtures.reset()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun isTarget(v: View): Boolean {
        if (!v.isShown) return false
        if (v is ScrollView || v is NestedScrollView) return false
        // Selectable text is clickable only because the text machinery makes it so (copy handles);
        // its size is the text's, not a button's.
        if (v is TextView && v.isTextSelectable) return false
        return v is HoldKey || v.isClickable || v.isLongClickable || v.hasOnClickListeners()
    }

    private fun check(root: ViewGroup, name: String, failures: MutableList<String>) {
        val d = root.resources.displayMetrics.density
        val targets = root.allViews().filter(::isTarget)
        for (t in targets) {
            val w = t.width / d
            val h = t.height / d
            if (w < MIN_DP - TOLERANCE_DP || h < MIN_DP - TOLERANCE_DP) {
                failures += "$name: ${t.javaClass.simpleName} is ${w}x${h}dp"
            }
        }
        val rects = targets.associateWith { v ->
            val loc = IntArray(2); v.getLocationInWindow(loc)
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        for (i in targets.indices) for (j in i + 1 until targets.size) {
            val x = targets[i]; val y = targets[j]
            if (isAncestor(x, y) || isAncestor(y, x)) continue
            if (sameGroup(x, y)) continue
            val r1 = rects.getValue(x); val r2 = rects.getValue(y)
            val dx = maxOf(r2.left - r1.right, r1.left - r2.right)
            val dy = maxOf(r2.top - r1.bottom, r1.top - r2.bottom)
            if (dx < 0 && dy < 0) continue   // overlap or containment is not a gap problem
            if (maxOf(dx, dy) / d < MIN_GAP_DP - TOLERANCE_DP) {
                failures += "$name: ${x.javaClass.simpleName} and ${y.javaClass.simpleName} are ${maxOf(dx, dy) / d}dp apart"
            }
        }
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p = b.parent
        while (p is View) { if (p === a) return true; p = p.parent }
        return false
    }

    /**
     * Segments of a knob and cells of a tab bar are one control, and chip frames carry their own
     * 8dp of breathing top and bottom (the pill is 32dp inside a 48dp frame).
     */
    private fun sameGroup(a: View, b: View): Boolean {
        val pa = a.parent; val pb = b.parent
        if (pa !== pb) return false
        // Rows of one list (status rows, tape rows) sit edge to edge, separated by their dashed rule.
        val listRows = a is RuleLinearLayout && b is RuleLinearLayout
        return pa is KnobSelector || pa is TabBar || pa is ChipGroup || listRows
    }
}
