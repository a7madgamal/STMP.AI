package com.mido.phonebridge

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.OnboardingPrefs
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.screenshots.OnboardingFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Robolectric 4.14 has no switch for the API 34 full-screen-intent grant, so this one reports it denied. */
@org.robolectric.annotation.Implements(android.app.NotificationManager::class)
class FullScreenIntentDeniedShadow : org.robolectric.shadows.ShadowNotificationManager() {
    @org.robolectric.annotation.Implementation
    fun canUseFullScreenIntent(): Boolean = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingActivityTest {

    @Before fun setUp() = Fixtures.dropSingletons()
    @After fun tearDown() = Fixtures.reset()

    private fun open() = Robolectric.buildActivity(OnboardingActivity::class.java).setup().get()

    private fun texts(a: OnboardingActivity): List<String> =
        a.window.decorView.allViews().filterIsInstance<TextView>().map { it.text.toString() }

    private fun key(a: OnboardingActivity, label: String): View? =
        a.window.decorView.allViews().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }

    private fun tap(a: OnboardingActivity, label: String) {
        val k = key(a, label)
        assertNotNull("no key $label", k)
        k!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickableParent(v: View): View = generateSequence(v) { it.parent as? View }.first { it.isClickable }

    private fun paneTitle(a: OnboardingActivity): CharSequence? =
        a.window.decorView.allViews().filterIsInstance<android.widget.ScrollView>().first()
            .let { ViewCompat.getAccessibilityPaneTitle(it) }

    @Test fun `step 1 has skip and next but no back, and the tour is marked seen`() {
        val a = open()
        assertNotNull(key(a, "SKIP")); assertNotNull(key(a, "NEXT")); assertNull(key(a, "BACK"))
        assertTrue(OnboardingPrefs.seen(a))
    }

    @Test fun `step 5 has back and done and no close`() {
        val a = open()
        repeat(4) { tap(a, "NEXT") }
        assertNotNull(key(a, "BACK")); assertNotNull(key(a, "DONE"))
        assertNull(key(a, "CLOSE")); assertNull(key(a, "SKIP"))
        tap(a, "BACK")
        assertTrue(texts(a).contains("STEP 4 OF 5"))
    }

    @Test fun `skip and done finish`() {
        val a = open()
        tap(a, "SKIP")
        assertTrue(a.isFinishing)
        val b = open()
        repeat(4) { tap(b, "NEXT") }
        tap(b, "DONE")
        assertTrue(b.isFinishing)
    }

    @Test fun `pane title changes per step`() {
        val a = open()
        assertEquals("Step 1 of 5. Your phone is the server", paneTitle(a))
        tap(a, "NEXT")
        assertEquals("Step 2 of 5. What it can do", paneTitle(a))
    }

    private val permissionLabels =
        listOf("Notification access", "Draw over apps", "Lock-screen popup", "Battery optimization")

    /** The row StatusRow returned for [label]: the nearest clickable ancestor of its label text. */
    private fun permissionRow(a: OnboardingActivity, label: String): View =
        generateSequence(checkNotNull(key(a, label)) { "no row $label" }) { it.parent as? View }
            .first { it.isClickable }

    private fun ledStates(row: View): List<Led.State> =
        row.allViews().filterIsInstance<Led>().map { it.state }

    private fun openStep3(): OnboardingActivity {
        val a = open()
        repeat(2) { tap(a, "NEXT") }
        return a
    }

    private fun assertRow(a: OnboardingActivity, label: String, value: String, led: Led.State) {
        val row = permissionRow(a, label)
        assertTrue("$label clickable", row.isClickable)
        assertEquals(label, listOf(led), ledStates(row))
        assertTrue(
            "$label description '${row.contentDescription}'",
            row.contentDescription.toString().startsWith("$label, $value"),
        )
    }

    @Test fun `step 3 mixed grants show ON, TURN ON and the optional row`() {
        OnboardingFixtures.permissions(listener = true, overlay = false, battery = true)
        val a = openStep3()
        val t = texts(a)
        permissionLabels.forEach { assertTrue(it, t.contains(it)) }
        // sdk 33: the full-screen intent is not revocable, so Lock-screen popup reads granted.
        assertEquals(3, t.count { it == "ON" })
        assertEquals(1, t.count { it == "TURN ON" })
        assertEquals(0, t.count { it == "OPTIONAL" })
        assertRow(a, "Notification access", "ON", Led.State.OK)
        assertRow(a, "Draw over apps", "TURN ON", Led.State.CAUTION)
        assertRow(a, "Lock-screen popup", "ON", Led.State.OK)
        assertRow(a, "Battery optimization", "ON", Led.State.OK)
        assertTrue(t.contains("You can change these later in the Settings tab, under Permissions."))
    }

    @Test fun `step 3 with nothing granted asks for the three required rows`() {
        OnboardingFixtures.permissions(listener = false, overlay = false, battery = false)
        val a = openStep3()
        val t = texts(a)
        assertEquals(3, t.count { it == "TURN ON" })
        assertEquals(1, t.count { it == "ON" })
        assertRow(a, "Notification access", "TURN ON", Led.State.CAUTION)
        assertRow(a, "Draw over apps", "TURN ON", Led.State.CAUTION)
        assertRow(a, "Battery optimization", "TURN ON", Led.State.CAUTION)
    }

    @Test fun `step 3 with everything granted shows four ON and no ask`() {
        OnboardingFixtures.permissions(listener = true, overlay = true, battery = true)
        val a = openStep3()
        val t = texts(a)
        assertEquals(4, t.count { it == "ON" })
        assertEquals(0, t.count { it == "TURN ON" })
        permissionLabels.forEach { assertRow(a, it, "ON", Led.State.OK) }
    }

    @Test @Config(sdk = [34], shadows = [FullScreenIntentDeniedShadow::class]) fun `step 3 on sdk 34 shows the lock-screen popup as optional when denied`() {
        OnboardingFixtures.permissions(listener = true, overlay = true, battery = true)
        val a = openStep3()
        assertEquals(1, texts(a).count { it == "OPTIONAL" })
        assertRow(a, "Lock-screen popup", "OPTIONAL", Led.State.OFF)
        assertRow(a, "Notification access", "ON", Led.State.OK)
    }

    @Test fun `tapping a permission row opens its settings screen`() {
        OnboardingFixtures.permissions(listener = false, overlay = false, battery = false)
        val a = openStep3()
        val row = permissionRow(a, "Notification access")
        assertTrue(row.isClickable)
        row.performClick()
        val started = shadowOf(a).nextStartedActivity
        assertNotNull("no settings screen started", started)
        assertEquals("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", started.action)

        permissionRow(a, "Draw over apps").performClick()
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(a).nextStartedActivity.action)
    }

    @Test fun `step 5 cards switch content and the copy key copies the exact string`() {
        OnboardingFixtures.bridgeAddress("100.101.7.22")
        val a = open()
        repeat(4) { tap(a, "NEXT") }
        assertNotNull(key(a, "COPY PROMPT"))
        tap(a, "COPY PROMPT")
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            com.mido.phonebridge.ui.OnboardingCopy.buildPrompt("http://100.101.7.22:8787"),
            cm.primaryClip!!.getItemAt(0).text.toString(),
        )
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())

        clickableParent(key(a, "Hermes")!!).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(key(a, "COPY COMMANDS")); assertNull(key(a, "COPY PROMPT"))
        // title -> text column -> row -> card
        val card = generateSequence(key(a, "Hermes")!!) { it.parent as View }.drop(3).first()
        val info = card.createAccessibilityNodeInfo()
        assertTrue(info.isChecked)
        assertTrue("selected card must be focusable", info.isFocusable || ViewCompat.isScreenReaderFocusable(card))
        assertEquals("android.widget.RadioButton", info.className.toString())
        val other = generateSequence(key(a, "Another agent")!!) { it.parent as View }.drop(3).first()
        assertFalse(other.createAccessibilityNodeInfo().isChecked)
    }

    @Test fun `server off shows the placeholder note`() {
        OnboardingFixtures.bridgeAddress("")
        val a = open()
        repeat(4) { tap(a, "NEXT") }
        assertTrue(texts(a).any { it.startsWith("STMP isn't on yet") })
        assertTrue(texts(a).any { it.contains("<start STMP first>") })
    }
}
