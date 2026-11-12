package com.mido.phonebridge

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.callers.CallerStore
import com.mido.phonebridge.debug.ScreenshotHostActivity
import com.mido.phonebridge.policy.AiBackend
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.KnobSelector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsFragmentTest {

    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock() }

    @After fun after() = Fixtures.reset()

    private fun open(): FragmentActivity {
        val intent = Intent(Fixtures.app, ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, OptionsFragment::class.java.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, Theme.Tab.SETTINGS.name)
        return Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup().get()
    }

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun texts(a: FragmentActivity) =
        a.window.decorView.all().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descriptions(a: FragmentActivity) =
        a.window.decorView.all().mapNotNull { it.contentDescription?.toString() }.toList()

    @Test
    fun `the page is called Settings and never OPTIONS`() {
        val t = texts(open())
        assertTrue(t.any { it == "Settings" })
        assertTrue(t.none { it.contains("OPTIONS") || it.contains("Options") })
    }

    @Test
    fun `notification access shows its three states`() {
        Fixtures.notificationListener(granted = false, connected = false)
        assertTrue(descriptions(open()).any { it.startsWith("Notification access, Needed") })
        Fixtures.notificationListener(granted = true, connected = false)
        assertTrue(descriptions(open()).any { it.startsWith("Notification access, Not connected") })
        Fixtures.notificationListener(granted = true, connected = true)
        assertTrue(descriptions(open()).any { it.startsWith("Notification access, Granted") })
    }

    @Test
    fun `forget asks first and removes the caller only on confirm`() {
        Fixtures.callers("192.168.1.25" to CallerState.Verified("home laptop"))
        val a = open()
        val key = a.window.decorView.all().first { it.contentDescription?.toString() == "Forget home laptop" }
        key.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(1, CallerStore.from(Fixtures.app).all().size)
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(0, CallerStore.from(Fixtures.app).all().size)
    }

    @Test
    fun `an unreadable policy keeps Permissions, Tools, Debug and Callers`() {
        Fixtures.gateUnreadable()
        val t = texts(open()).map { it.lowercase() }
        assertTrue(t.contains("settings can't be read"))
        for (h in listOf("permissions", "tools", "debug", "callers")) assertTrue("missing $h", t.contains(h))
        assertFalse(t.contains("bridge"))
        assertFalse(t.contains("ai"))
    }

    @Test
    fun `choosing Off on the knob stores OFF`() {
        Fixtures.aiBackend(AiBackend.LOCAL)
        val a = open()
        val knob = a.window.decorView.all().filterIsInstance<KnobSelector>().first()
        knob.onSelect!!.invoke(0)
        ShadowLooper.idleMainLooper()
        assertEquals(AiBackend.OFF, PolicyStore.from(Fixtures.app).get().aiBackend)
    }

    @Test
    fun `fingerprint for approvals starts on for a fresh install`() {
        val a = open()
        assertTrue(PolicyStore.from(Fixtures.app).get().fingerprintForModals)
        assertTrue(descriptions(a).any { it.startsWith("Fingerprint for approvals, In Paranoid mode, approving a request asks") })
    }

    @Test
    fun `every clickable view is at least 48dp`() {
        Fixtures.callers("192.168.1.25" to CallerState.Verified("home laptop"))
        val a = open()
        val d = a.resources.displayMetrics.density
        val small = a.window.decorView.all()
            .filter { it.isClickable && it.isShown && it.height > 0 }
            .filter { it.height / d < 47.5f }
            .map { "${it.javaClass.simpleName} ${it.contentDescription} ${it.height / d}dp" }.toList()
        assertTrue(small.joinToString("\n"), small.isEmpty())
        assertNotNull(a)
    }

    @Test
    fun `Save checks the address that was typed, not the one drawn`() {
        Fixtures.aiBackend(AiBackend.EXTERNAL, "")
        val a = open()
        val url = a.window.decorView.all().filterIsInstance<android.widget.EditText>()
            .first { it.hint?.toString() == "http://host:port" }
        url.setText("http://host:80/")
        ShadowLooper.idleMainLooper()
        a.window.decorView.all().first { it is TextView && it.text.toString().equals("Save", ignoreCase = true) }
            .let { v -> generateSequence(v) { it.parent as? View }.first { it.isClickable } }.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(texts(a).any { it == "Drop the trailing slash." })
        assertEquals("", PolicyStore.from(Fixtures.app).get().aiExternalUrl)
    }
}
