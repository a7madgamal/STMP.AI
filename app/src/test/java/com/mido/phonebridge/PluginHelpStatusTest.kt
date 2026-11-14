package com.mido.phonebridge

import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.PluginCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The help dialog must not claim a plugin is fine when its own status says otherwise.
 *
 * It printed "On, and ready." whenever the stored switch was on — it never asked the plugin. So
 * the one screen a person opens to ask "why is this not working" answered "it is working", for a
 * plugin with no secret saved or one missing the permission its `status()` reports.
 */
class PluginHelpStatusTest {

    private fun status(state: PluginStatus.State, detail: String = "") =
        PluginStatus("X", state, detail)

    @Test
    fun `a healthy plugin reads as ready`() {
        val line = PluginCopy.statusLine(enabled = true, status = status(PluginStatus.State.OK))
        assertEquals(PluginCopy.LineTone.OK, line.tone)
        assertTrue(line.text.startsWith("On, and ready."))
    }

    @Test
    fun `a plugin needing attention is not called ready`() {
        val line = PluginCopy.statusLine(
            enabled = true,
            status = status(PluginStatus.State.NEEDS_ATTENTION, "No secret saved"),
        )
        assertNotEquals(PluginCopy.LineTone.OK, line.tone)
        assertFalse("must not claim readiness", line.text.contains("and ready"))
        assertTrue("the plugin's own reason must reach the reader", line.text.contains("No secret saved"))
    }

    @Test
    fun `a plugin that errored is not called ready`() {
        val line = PluginCopy.statusLine(
            enabled = true,
            status = status(PluginStatus.State.ERROR, "Keystore unavailable"),
        )
        assertEquals(PluginCopy.LineTone.CAUTION, line.tone)
        assertTrue(line.text.contains("Keystore unavailable"))
    }

    @Test
    fun `on but internally off is not set up, which is not the same as switched off`() {
        val line = PluginCopy.statusLine(
            enabled = true,
            status = status(PluginStatus.State.OFF),
        )
        assertTrue(line.text.contains("not set up"))
        // The distinct case: the human did NOT switch this off, so it must not use OFF_NOTE.
        assertNotEquals(PluginCopy.OFF_NOTE, line.text)
    }

    @Test
    fun `switched off keeps the shared off wording`() {
        val line = PluginCopy.statusLine(enabled = false, status = status(PluginStatus.State.OK))
        assertEquals(PluginCopy.OFF_NOTE, line.text)
        assertEquals(PluginCopy.LineTone.NEUTRAL, line.tone)
    }

    @Test
    fun `an unread status says so instead of guessing`() {
        val line = PluginCopy.statusLine(enabled = true, status = null)
        assertNotEquals(PluginCopy.LineTone.OK, line.tone)
        assertFalse("never invent readiness", line.text.contains("ready"))
        assertTrue(line.text.contains("not read"))
    }

    @Test
    fun `a detail that already ends in a full stop is not given two`() {
        val line = PluginCopy.statusLine(
            enabled = true,
            status = status(PluginStatus.State.NEEDS_ATTENTION, "Sign in again."),
        )
        assertFalse("no double period", line.text.endsWith(".."))
    }
}
