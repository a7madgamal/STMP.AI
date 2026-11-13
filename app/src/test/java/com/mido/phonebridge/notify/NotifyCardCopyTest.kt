package com.mido.phonebridge.notify

import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Don't-disturb card names who asks, which plugin, and the risk as a word (R1). */
class NotifyCardCopyTest {

    @Test
    fun `the card names the requester and the plugin`() {
        assertEquals("Work laptop · Gmail", NotifyCardCopy.requesterLine("Work laptop", "Gmail"))
    }

    @Test
    fun `an unnamed requester is named unknown, never left out`() {
        assertEquals("unknown requester · Gmail", NotifyCardCopy.requesterLine("", "Gmail"))
        assertEquals("unknown requester · Gmail", NotifyCardCopy.requesterLine("   ", "Gmail"))
    }

    @Test
    fun `the production call site passes the caller name as requester`() {
        val src = java.io.File("src/main/java/com/mido/phonebridge/BridgeService.kt").readText()
        val call = src.substringAfter("NotifyGate.await(").substringBefore("when (decision)")
        assertTrue(call.contains("requester = callerLabelFor(remote)"))
    }

    @Test
    fun `the plugin id comes from the facts, else from the label`() {
        assertEquals("gmail", NotifyCardCopy.pluginId("tts.read", "gmail"))
        assertEquals("tts", NotifyCardCopy.pluginId("tts.read", "?"))
        assertEquals("tts", NotifyCardCopy.pluginId("tts.read", null))
    }

    @Test
    fun `a plugin is named by display name, an unknown id stays as it is`() {
        assertEquals("nope", NotifyCardCopy.pluginName("nope"))
    }

    @Test
    fun `every risk is a different word`() {
        val words = Risk.values().map { NotifyCardCopy.riskWord(it) }
        assertEquals(words.size, words.toSet().size)
        assertEquals("HIGH risk", NotifyCardCopy.riskWord(Risk.HIGH))
        assertNotEquals(NotifyCardCopy.riskWord(Risk.HIGH), NotifyCardCopy.riskWord(Risk.MEDIUM))
    }

    @Test
    fun `the spoken description carries who, what and how to decide`() {
        val d = NotifyCardCopy.description(Verb.DELETE, "Work laptop · Notifications", "Delete the notification from Signal")
        assertTrue(d.contains("Work laptop"))
        assertTrue(d.contains("Delete the notification from Signal"))
        assertTrue(d.contains("silence approves"))
    }
}
