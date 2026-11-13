package com.mido.phonebridge

import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.ui.PluginCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `?` copy must be readable by someone who is not the person who wrote the plugin.
 *
 * **This replaced a test that pinned the OLD format.** That version asserted the dialog listed
 * routes and permission ids — accurate, and unreadable. Mido, 2026-11-10: *"friendly not a fucking
 * api documentation. a non developer simple description of the features and example prompts"*.
 * So the assertions here are about the opposite property: no HTTP verbs, no route paths, and at
 * least one example written the way a person speaks.
 */
class PluginCopyTest {

    /** Plugins that a human actually sees a row for. */
    private val known = listOf(
        "notifications", "google", "gmail", "calendar", "drive",
        "contacts", "ssh", "secrets", "tts", "audio", "logs", "debug", "help", "probe",
    )

    @Test
    fun `every plugin a human can see has copy`() {
        for (id in known) {
            val info = PluginCopy.forId(id, id)
            assertTrue("no blurb for '$id'", info.blurb.isNotBlank())
        }
    }

    @Test
    fun `the copy never leaks API vocabulary`() {
        // The exact failure being fixed: a description built from routes reads like swagger.
        val forbidden = listOf("GET ", "POST ", "DELETE ", "HTTP ", "/gmail", "/ssh", "/drive", "{id}")
        for (id in known) {
            val info = PluginCopy.forId(id, id)
            val text = info.blurb + " " + info.examples.joinToString(" ")
            for (token in forbidden) {
                assertFalse(
                    "'$id' copy contains API vocabulary '$token':\n$text",
                    text.contains(token),
                )
            }
        }
    }

    @Test
    fun `most plugins give at least one example of what to ask`() {
        // The examples are the part that makes a feature click; a bare description is a manual.
        val withExamples = known.count { PluginCopy.forId(it, it).examples.isNotEmpty() }
        assertTrue(
            "expected examples for most plugins, got $withExamples of ${known.size}",
            withExamples >= known.size - 2,
        )
    }

    @Test
    fun `examples read like a person talking, not like a call`() {
        for (id in known) {
            for (example in PluginCopy.forId(id, id).examples) {
                assertFalse(
                    "'$id' example looks like a request: $example",
                    example.contains("{") || example.contains("http"),
                )
                assertTrue("'$id' example is empty", example.isNotBlank())
            }
        }
    }

    @Test
    fun `an unknown plugin still gets honest copy instead of nothing`() {
        // A newly registered plugin must not open an empty dialog. The fallback says only what it
        // can honestly say — the name — rather than inventing a purpose.
        val info = PluginCopy.forId("brand-new", "Brand New")
        assertTrue(info.blurb.isNotBlank())
        assertTrue(info.blurb.contains("Brand New"))
    }

    @Test
    fun `the off notice explains that nothing is broken`() {
        // The sentence a human needs when a plugin reads off: it is not an error, and its routes
        // still exist. Both halves are asserted because dropping either makes it alarming.
        assertTrue(PluginCopy.OFF_NOTE.contains("Switched off", ignoreCase = true))
        assertTrue(PluginCopy.OFF_NOTE.contains("404", ignoreCase = true))
    }
}

class PluginCopyDisplayTest {
    private val ok = PluginStatus.State.OK
    private val attention = PluginStatus.State.NEEDS_ATTENTION
    private val err = PluginStatus.State.ERROR
    private val unconfigured = PluginStatus.State.OFF

    private fun line(enabled: Boolean, state: PluginStatus.State, detail: String = "", id: String = "contacts") =
        PluginCopy.statusLine(id, enabled, PluginStatus("x", state, detail))

    @Test
    fun `only a healthy plugin says On and ready`() {
        assertEquals("On, and ready.", line(true, ok).text)
        for (s in listOf(attention, err, unconfigured)) {
            assertFalse(line(true, s, "something").text.contains("On, and ready"))
        }
        assertFalse(line(false, ok).text.contains("On, and ready"))
    }

    @Test
    fun `status line covers all five rows`() {
        assertEquals(PluginCopy.OFF_NOTE, line(false, err).text)
        assertEquals(PluginCopy.LineTone.NEUTRAL, line(false, ok).tone)
        val a = line(true, attention, "READ_CONTACTS not granted - 503 contacts_unavailable")
        assertEquals("On, but it needs action. Contacts permission isn't granted. Open this plugin to allow it.", a.text)
        assertEquals(PluginCopy.LineTone.CAUTION, a.tone)
        val e = line(true, err, "Status unavailable: boom", id = "debug")
        assertEquals("On, but it hit an error. Status unavailable: boom.", e.text)
        val n = line(true, unconfigured, "no OAuth client id configured", id = "gmail")
        assertEquals("On, but not set up yet. " + PluginCopy.NO_GOOGLE, n.text)
        assertEquals(PluginCopy.LineTone.NEUTRAL, n.tone)
    }

    @Test
    fun `without a Google client the help line is the D70 sentence`() {
        val l = PluginCopy.statusLine("gmail", true, PluginStatus("Gmail", ok, "ready"), hasGoogleClient = false)
        assertEquals(PluginCopy.NO_GOOGLE, l.text)
        assertEquals(PluginCopy.LineTone.NEUTRAL, l.tone)
    }

    @Test
    fun `humanDetail rules`() {
        assertEquals("Notification access is off. Open this plugin to turn it on.", PluginCopy.humanDetail("notifications", attention, "listener not connected"))
        assertEquals("No secrets yet", PluginCopy.humanDetail("secrets", ok, "0 secret(s) · GET /secrets"))
        assertEquals("1 secret", PluginCopy.humanDetail("secrets", ok, "1 secret(s) · GET /secrets"))
        assertEquals("3 secrets", PluginCopy.humanDetail("secrets", ok, "3 secret(s) · GET /secrets"))
        assertEquals("No connections yet — add one below.", PluginCopy.humanDetail("ssh", unconfigured, "no connections configured"))
        assertEquals(PluginCopy.NO_GOOGLE, PluginCopy.humanDetail("drive", unconfigured, "no OAuth client id configured"))
        assertEquals("Sign in to your Google account first.", PluginCopy.humanDetail("gmail", attention, "not logged in"))
        assertEquals("Sign in to your Google account first.", PluginCopy.humanDetail("google", attention, "not signed in — Gmail, Calendar and Drive cannot serve until you are"))
        assertEquals("Sign in again to allow Drive access.", PluginCopy.humanDetail("drive", attention, "re-login needed to grant Drive access"))
        assertEquals("The secret list can't be read: bad key", PluginCopy.humanDetail("secrets", err, "index unreadable: bad key"))
        assertEquals("Search your contacts.", PluginCopy.humanDetail("contacts", ok, "GET /contacts?query= — names, numbers and emails, up to 20 people"))
        assertEquals("Tells agents what this phone can do.", PluginCopy.humanDetail("help", ok, "GET /help — 14 plugin(s), contract v4"))
        assertEquals("a\u200B@b.c", PluginCopy.humanDetail("google", ok, "a@b.c"))
        assertEquals("Something unusual", PluginCopy.humanDetail("tts", err, "Something unusual"))
    }

    @Test
    fun `no humanDetail output leaks routes or codes`() {
        val inputs = listOf(
            "contacts" to (ok to "GET /contacts?query= — names"),
            "contacts" to (attention to "READ_CONTACTS not granted — searching answers 503 contacts_unavailable"),
            "secrets" to (ok to "2 secret(s) · GET /secrets"),
            "help" to (ok to "GET /help — 14 plugin(s), contract v4"),
            "tts" to (ok to "POST /tts — cap 500 chars"),
            "audio" to (ok to "POST /audio/{cue} — chime"),
            "logs" to (ok to "GET /logs?lines=100"),
            "debug" to (ok to "GET /debug"),
            "ssh" to (ok to "2 connection(s)"),
            "gmail" to (unconfigured to "no OAuth client id configured"),
            "gmail" to (ok to "ready · token 42m"),
        )
        val forbidden = listOf("GET ", "POST ", "DELETE ", "contract v", "503", "_unavailable", "secret(s)", "OAuth", "404", "connection(s)")
        for ((id, sd) in inputs) {
            val out = PluginCopy.humanDetail(id, sd.first, sd.second)
            for (f in forbidden) assertFalse("'$out' contains '$f'", out.contains(f))
        }
    }

    @Test
    fun `summary has a clean entry for every route-style plugin`() {
        for (id in listOf("contacts", "tts", "audio", "help", "debug", "logs", "probe")) {
            val s = PluginCopy.summary(id)
            assertTrue(id, s.isNotBlank())
            assertFalse("$id: $s", Regex("[a-z]+_[a-z]+").containsMatchIn(s))
            assertFalse(s.contains("`"))
        }
        assertTrue(PluginCopy.summary("ssh").isNotBlank())
    }
}
