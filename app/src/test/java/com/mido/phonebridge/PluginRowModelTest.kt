package com.mido.phonebridge

import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.ui.PluginRowModel
import com.mido.phonebridge.ui.PluginRowModel.Tone
import com.mido.phonebridge.ui.kit.Led
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRowModelTest {

    private class Fake(
        override val id: String,
        override val displayName: String = id,
        private val state: PluginStatus.State = PluginStatus.State.OK,
        private val detail: String = "",
        private val boom: Boolean = false,
    ) : Plugin {
        override fun routes() = emptyList<Route>()
        override fun status(): PluginStatus {
            if (boom) throw IllegalStateException("vault closed")
            return PluginStatus(displayName, state, detail)
        }
    }

    private fun model(p: Plugin, enabled: Boolean = true, google: Boolean = true) =
        PluginRowModel.of(p, enabled, hasGoogleClient = google)

    @Test
    fun `stored off is OFF whatever the plugin says`() {
        val m = model(Fake("tts", "Speak", PluginStatus.State.ERROR), enabled = false)
        assertEquals("OFF", m.word); assertEquals(Tone.NEUTRAL, m.tone); assertEquals(Led.State.OFF, m.led)
        assertFalse(m.showsOn); assertEquals(4, m.rank)
        assertEquals("Switched off. Agents that call it are told it is off.", m.detail)
    }

    @Test
    fun `healthy is ON`() {
        val m = model(Fake("tts", "Speak", detail = "POST /tts - cap 500"))
        assertEquals("ON", m.word); assertEquals(Tone.OK, m.tone); assertEquals(Led.State.OK, m.led)
        assertTrue(m.showsOn); assertEquals(3, m.rank)
        assertEquals("Speaks text aloud on this phone.", m.detail)
    }

    @Test
    fun `needs attention is NEEDS ACTION`() {
        val m = model(Fake("contacts", "Contacts", PluginStatus.State.NEEDS_ATTENTION, "READ_CONTACTS not granted - 503 contacts_unavailable"))
        assertEquals("NEEDS ACTION", m.word); assertEquals(Tone.CAUTION, m.tone); assertEquals(Led.State.CAUTION, m.led)
        assertFalse(m.showsOn); assertEquals(1, m.rank)
        assertEquals("Contacts permission isn't granted. Open this plugin to allow it.", m.detail)
    }

    @Test
    fun `error is ERROR`() {
        val m = model(Fake("secrets", "Secrets (metadata)", PluginStatus.State.ERROR, "index unreadable: bad key"))
        assertEquals("ERROR", m.word); assertEquals(Tone.CAUTION, m.tone); assertEquals(Led.State.CAUTION, m.led)
        assertFalse(m.showsOn); assertEquals(0, m.rank)
        assertEquals("The secret list can't be read: bad key", m.detail)
    }

    @Test
    fun `on but unconfigured is NOT SET UP`() {
        val m = model(Fake("ssh", "SSH", PluginStatus.State.OFF, "no connections configured"))
        assertEquals("NOT SET UP", m.word); assertEquals(Tone.NEUTRAL, m.tone); assertEquals(Led.State.OFF, m.led)
        assertFalse(m.showsOn); assertEquals(2, m.rank)
        assertEquals("No connections yet — add one below.", m.detail)
    }

    @Test
    fun `secrets with none stored is NOT SET UP not ACTION NEEDED`() {
        val m = model(Fake("secrets", "Secrets (metadata)", PluginStatus.State.NEEDS_ATTENTION, "0 secret(s) · GET /secrets"))
        assertEquals("NOT SET UP", m.word); assertEquals(2, m.rank); assertFalse(m.showsOn)
        assertEquals("No secrets yet — add one below.", m.detail)
        assertEquals("Secrets", m.title)
    }

    @Test
    fun `google rows say not in this build without a client and win over every state`() {
        for (id in listOf("google", "gmail", "calendar", "drive")) {
            val on = model(Fake(id, id, PluginStatus.State.OFF, "no OAuth client id configured"), enabled = true, google = false)
            assertEquals("NOT IN THIS BUILD", on.word); assertEquals(5, on.rank); assertFalse("rocker is OFF whatever was stored", on.showsOn)
            assertEquals(Led.State.OFF, on.led); assertEquals(Tone.NEUTRAL, on.tone)
            assertEquals("This build has no Google sign-in. Install the official release to use it.", on.detail)
            val off = model(Fake(id, id), enabled = false, google = false)
            assertEquals("NOT IN THIS BUILD", off.word); assertFalse(off.showsOn)
        }
        assertEquals("ON", model(Fake("gmail"), google = true).word)
    }

    @Test
    fun `a plugin not in this build is not counted on and its detail is the D70 sentence`() {
        val rows = listOf(
            model(Fake("gmail"), enabled = true, google = false),
            model(Fake("notifications"), enabled = true, google = false),
        )
        assertTrue(PluginRowModel.isNotInBuild(rows[0]))
        assertEquals("2 plugins · 1 on", PluginRowModel.summary(rows))
    }

    @Test
    fun `inside the plugin the detail never says open this plugin`() {
        val p = Fake("contacts", "Contacts", PluginStatus.State.NEEDS_ATTENTION, "no permission")
        val inside = PluginRowModel.of(p, true, hasGoogleClient = true, insidePlugin = true)
        assertFalse(inside.detail, inside.detail.contains("Open this plugin"))
        val onTab = PluginRowModel.of(p, true, hasGoogleClient = true)
        assertTrue(onTab.detail.contains("Open this plugin"))
    }

    @Test
    fun `a throwing status becomes ERROR with the message`() {
        val p = Fake("debug", "Debug", boom = true)
        val s = PluginRowModel.statusOf(p)
        assertEquals(PluginStatus.State.ERROR, s.state)
        assertEquals("Status unavailable: vault closed", s.detail)
        val m = model(p)
        assertEquals("ERROR", m.word); assertEquals(0, m.rank)
    }

    @Test
    fun `sorted puts error then action then not set up then on then off then not in build`() {
        val models = listOf(
            model(Fake("a", "Zed")), // on
            model(Fake("b", "Bee", PluginStatus.State.OFF)), // not set up
            model(Fake("c", "Cee", boom = true)), // error
            model(Fake("d", "Dee"), enabled = false), // off
            model(Fake("e", "Eee", PluginStatus.State.NEEDS_ATTENTION)), // action
            model(Fake("gmail", "Gmail"), google = false), // not in build
            model(Fake("f", "alpha")), // on, ties by lowercase title
        )
        assertEquals(
            listOf("Cee", "Eee", "Bee", "alpha", "Zed", "Dee", "Gmail"),
            PluginRowModel.sorted(models).map { it.title },
        )
    }

    @Test
    fun `google account is not pinned first`() {
        val models = listOf(
            model(Fake("google", "Google account")),
            model(Fake("calendar", "Calendar", PluginStatus.State.NEEDS_ATTENTION)),
        )
        assertEquals(listOf("Calendar", "Google account"), PluginRowModel.sorted(models).map { it.title })
    }

    @Test
    fun `title maps secrets and keeps other display names`() {
        assertEquals("Secrets", PluginRowModel.title(Fake("secrets", "Secrets (metadata)")))
        assertEquals("Speak", PluginRowModel.title(Fake("tts", "Speak")))
    }

    @Test
    fun `summary counts plugins on and attention`() {
        fun on(n: Int) = List(n) { model(Fake("o$it")) }
        fun action(n: Int) = List(n) { model(Fake("a$it", state = PluginStatus.State.NEEDS_ATTENTION)) }
        fun off(n: Int) = List(n) { model(Fake("f$it"), enabled = false) }
        assertEquals("12 plugins · 6 on · 2 need action", PluginRowModel.summary(on(6) + action(2) + off(4)))
        assertEquals("1 plugin · 1 on", PluginRowModel.summary(on(1)))
        assertEquals("3 plugins · 0 on · 1 needs action", PluginRowModel.summary(action(1) + off(2)))
        assertEquals("0 plugins · 0 on", PluginRowModel.summary(emptyList()))
    }

    @Test
    fun `not in build rows count as neither on nor attention`() {
        val rows = List(4) { model(Fake("gmail"), google = false) }
        assertEquals("4 plugins · 0 on", PluginRowModel.summary(rows))
    }
}
