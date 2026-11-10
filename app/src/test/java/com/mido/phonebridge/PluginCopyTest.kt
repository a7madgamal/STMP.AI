package com.mido.phonebridge

import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.ui.PluginCopy
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
