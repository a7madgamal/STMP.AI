package com.mido.phonebridge

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.screenshots.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LogScreensTest {

    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock() }

    @After fun after() = Fixtures.reset()

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun texts(a: android.app.Activity) =
        a.window.decorView.all().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun chip(a: android.app.Activity, label: String) =
        a.window.decorView.all().filterIsInstance<TextView>().first { it.text.toString() == label }.parent as View

    private fun summary(a: android.app.Activity) = texts(a).first { it.contains(" of ") && it.contains("records") }

    private fun clip(): String? =
        (Fixtures.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    private fun log() = Robolectric.buildActivity(LogActivity::class.java).setup().get()

    private fun rows(a: android.app.Activity) =
        a.window.decorView.all().filter { it.isLongClickable && it.isClickable }.toList()

    @Test fun `Denied filters to denied rows, checks the chip, and toggles off`() {
        Fixtures.logList()
        val a = log()
        assertEquals(8, rows(a).size)
        chip(a, "Denied").performClick()
        assertEquals(1, rows(a).size)
        assertTrue(texts(a).contains("Denied"))
        assertEquals("1 of 8 records", summary(a).substringBefore(" ·"))
        chip(a, "Denied").performClick()
        assertEquals(8, rows(a).size)
    }

    @Test fun `All clears a filter and Show noise changes the count`() {
        Fixtures.logList()
        Fixtures.logNoise()
        val a = log()
        assertTrue(summary(a), summary(a).startsWith("8 of 10 records") && summary(a).contains("2 noise hidden"))
        chip(a, "Timed out").performClick()
        assertTrue(summary(a).startsWith("1 of 10"))
        chip(a, "All").performClick()
        assertTrue(summary(a).startsWith("8 of 10"))
        chip(a, "Show noise").performClick()
        assertTrue(summary(a), summary(a).startsWith("10 of 10"))
    }

    @Test fun `long press puts the stored line on the clipboard under the STMP label`() {
        Fixtures.logList()
        val a = log()
        val row = rows(a).first()
        row.performLongClick()
        val cb = Fixtures.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("STMP request log", cb.primaryClipDescription?.label)
        assertTrue(clip()!!.startsWith("{") && clip()!!.contains("\"id\":\"wp08-7\""))
    }

    @Test fun `no clickable view is shorter than 48dp`() {
        Fixtures.logList()
        val a = log()
        val d = a.resources.displayMetrics.density
        val small = a.window.decorView.all().filter { it.isClickable && it.height / d < 47.5f }.toList()
        assertTrue(small.joinToString { it.javaClass.simpleName + it.height / d }, small.isEmpty())
    }

    @Test fun `the log reads in words with no emoji and no enum names`() {
        Fixtures.logList()
        val all = texts(log()).joinToString("\n")
        assertTrue(all.contains("Approved by silence") || all.contains("Approved"))
        assertTrue(all.contains("Gate mode Whitelist → Paranoid, changed in Settings"))
        assertFalse(all.contains("POLICY_CHANGED") || all.contains("NOTIFY_APPROVED") || all.contains("WHITELIST"))
    }

    @Test fun `Copy raw record copies the stored line, not the formatted text`() {
        val id = Fixtures.detailRecord("approved")
        val raw = AuditLog.from(Fixtures.app).line(id)!!
        val a = Robolectric.buildActivity(
            LogDetailActivity::class.java,
            Intent(Fixtures.app, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_ID, id),
        ).setup().get()
        a.window.decorView.all().first { it.contentDescription == "Copy the stored record to the clipboard" }.performClick()
        assertEquals(raw, clip())
    }

    @Test fun `no detail screen shows the text null`() {
        for (kind in listOf("approved", "policy_changed", "unrouted", "failed_with_error", "truncated", "bodies_absent", "unattended")) {
            Fixtures.dropSingletons()
            val id = Fixtures.detailRecord(kind)
            val a = Robolectric.buildActivity(
                LogDetailActivity::class.java,
                Intent(Fixtures.app, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_ID, id),
            ).setup().get()
            // The raw record is the stored line and may legitimately hold a JSON null.
            val shown = texts(a).filterNot { it.startsWith("{") }
            for (t in shown) {
                assertFalse("$kind: \"$t\"", t.trim().equals("null", true) || t.trim().endsWith("—"))
            }
        }
    }

    @Test fun `exceptions banner names the paused mode in words`() {
        Fixtures.registerNamedPlugins()
        Fixtures.exceptionsOnly(Rule("gmail", Verb.EDIT, Mode.DONT_DISTURB))
        Fixtures.gate(GlobalMode.PARANOID)
        val t = texts(Robolectric.buildActivity(ExceptionsActivity::class.java).setup().get()).joinToString("\n")
        assertTrue(t.contains("Exceptions are paused"))
        assertTrue(t.contains("The gate mode is Paranoid"))
        assertFalse(t.contains("AUTOMATIC") || t.contains("dont_disturb") || t.contains("SECURE") || t.contains("NOTIFY"))
        assertTrue(t.contains("Gmail · edit"))
        assertTrue(t.contains("Don't disturb · 5 s card, doing nothing approves it"))
    }

    @Test fun `Remove clears the exception and records a Gate changed row`() {
        Fixtures.registerNamedPlugins()
        Fixtures.exceptionsOnly(Rule("gmail", Verb.EDIT, Mode.PARANOID))
        val a = Robolectric.buildActivity(ExceptionsActivity::class.java).setup().get()
        a.window.decorView.all().first { it.contentDescription == "Remove the exception for Gmail · edit" }.performClick()
        assertTrue(texts(a).toString(), PolicyStore.from(Fixtures.app).get().rules.isEmpty())
        val newest = AuditLog.from(Fixtures.app).headers().first()
        assertEquals(AuditOutcome.POLICY_CHANGED, newest.outcome)
        assertTrue(texts(a).contains("No exceptions yet"))
    }

    @Test fun `the plugin dialog lists display names and has a Cancel`() {
        Fixtures.registerNamedPlugins()
        val a = Robolectric.buildActivity(ExceptionsActivity::class.java).setup().get()
        a.window.decorView.all().first { it.contentDescription == "Add an exception" }.performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val labels = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
        assertEquals(listOf("Gmail", "Calendar", "Contacts", "Speak", "SSH"), labels)
        assertTrue(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).text.toString().equals("Cancel", true))
    }

    @Test fun `a policy file that cannot be saved shows the error inline`() {
        Fixtures.registerNamedPlugins()
        Fixtures.exceptionsOnly(Rule("gmail", Verb.EDIT, Mode.PARANOID))
        val a = Robolectric.buildActivity(ExceptionsActivity::class.java).setup().get()
        val dir = File(Fixtures.app.filesDir, "policy")
        // Make the write fail: the policy file's directory is replaced by a plain file.
        val backup = File(Fixtures.app.filesDir, "policy-away")
        dir.renameTo(backup)
        File(Fixtures.app.filesDir, "policy").writeText("in the way")
        a.window.decorView.all().first { it.contentDescription == "Change Gmail · edit to Don't disturb" }.performClick()
        val t = texts(a)
        assertTrue(t.toString(), t.contains("Could not save the exception"))
    }
}
