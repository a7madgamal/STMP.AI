package com.mido.phonebridge

import com.mido.phonebridge.core.SpecialAccess
import com.mido.phonebridge.ui.PluginDetailCopy
import com.mido.phonebridge.ui.PluginRowModel
import com.mido.phonebridge.ui.kit.Led
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words on the plugin screen: permission states, access reasons, the switch sentence. */
class PluginDetailCopyTest {

    private val banned = listOf("route", "plugin_disabled", "404", "`", "/")

    @Test fun `granted permission reads GRANTED with a green light`() {
        val s = PluginDetailCopy.permissionState(granted = true, optional = false)
        assertEquals("GRANTED", s.word)
        assertEquals(PluginRowModel.Tone.OK, s.tone)
        assertEquals(Led.State.OK, s.led)
    }

    @Test fun `granted wins over optional`() {
        assertEquals("GRANTED", PluginDetailCopy.permissionState(granted = true, optional = true).word)
    }

    @Test fun `ungranted optional permission reads OPTIONAL with the light off`() {
        val s = PluginDetailCopy.permissionState(granted = false, optional = true)
        assertEquals("OPTIONAL", s.word)
        assertEquals(PluginRowModel.Tone.NEUTRAL, s.tone)
        assertEquals(Led.State.OFF, s.led)
    }

    @Test fun `ungranted required permission reads NEEDED with a caution light`() {
        val s = PluginDetailCopy.permissionState(granted = false, optional = false)
        assertEquals("NEEDED", s.word)
        assertEquals(PluginRowModel.Tone.CAUTION, s.tone)
        assertEquals(Led.State.CAUTION, s.led)
    }

    @Test fun `access state is GRANTED or NEEDED`() {
        assertEquals("GRANTED", PluginDetailCopy.accessState(true).word)
        val needed = PluginDetailCopy.accessState(false)
        assertEquals("NEEDED", needed.word)
        assertEquals(Led.State.CAUTION, needed.led)
    }

    @Test fun `every special access has its own plain reason`() {
        val reasons = SpecialAccess.entries.map { PluginDetailCopy.accessReason(it) }
        reasons.forEach { r ->
            assertTrue(r.isNotBlank())
            banned.forEach { assertFalse("'$r' contains '$it'", r.lowercase().contains(it)) }
        }
        assertEquals(reasons.size, reasons.toSet().size)
        SpecialAccess.entries.forEach { a ->
            assertFalse(PluginDetailCopy.accessReason(a).contains(a.name, ignoreCase = true))
        }
    }

    @Test fun `switch sentence covers on, cannot serve and off`() {
        assertEquals(
            "On. Its requests are served and gated like any other.",
            PluginDetailCopy.switchSentence(on = true, showsOn = true),
        )
        assertTrue(PluginDetailCopy.switchSentence(on = true, showsOn = false).startsWith("Switched on, but it can't serve"))
        assertEquals(
            "Off. Agents that call it are told it is off.",
            PluginDetailCopy.switchSentence(on = false, showsOn = false),
        )
    }

    @Test fun `switch sentences leak no route or error code`() {
        listOf(true to true, true to false, false to false).forEach { (on, shows) ->
            val s = PluginDetailCopy.switchSentence(on, shows)
            listOf("route", "plugin_disabled", "404", "`").forEach { assertFalse("'$s' has '$it'", s.contains(it)) }
        }
    }

    @Test fun `only the cannot-serve sentence is a caution`() {
        assertTrue(PluginDetailCopy.isCaution(on = true, showsOn = false))
        assertFalse(PluginDetailCopy.isCaution(on = true, showsOn = true))
        assertFalse(PluginDetailCopy.isCaution(on = false, showsOn = false))
    }

    @Test fun `a text value that is empty reads Not set`() {
        assertEquals("Not set", PluginDetailCopy.textValue(""))
        assertEquals("rate 1.0", PluginDetailCopy.textValue("rate 1.0"))
    }

    @Test fun `sign-in status words never say email unknown`() {
        assertEquals("Signed in as a@b.c", PluginDetailCopy.signedIn("a@b.c"))
        assertEquals("Signed in", PluginDetailCopy.signedIn(null))
        assertEquals("Signed in", PluginDetailCopy.signedIn(""))
    }

    @Test fun `a refused setting names the reason`() {
        assertEquals("Couldn't save: too big", PluginDetailCopy.refusal("too big"))
    }
}
