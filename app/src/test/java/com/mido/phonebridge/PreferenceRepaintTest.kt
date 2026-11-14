package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tap must never rebuild the screen it is still being dispatched on.
 *
 * Reported 2026-11-11: *"hangs on preference click"*. The cause was a synchronous `render()`
 * at the end of the toggle/save path: the whole Preferences screen — a fresh
 * `PluginInventoryView` plus every plugin's `status()` read — was torn down and rebuilt while
 * the click was still being dispatched, so the press never completed. The same defect class is
 * documented in `UpdatesSection.refresh()` ("removeAllViews() mid-dispatch").
 *
 * The rule: repaint requests from click callbacks are POSTED to the next loop and coalesced.
 * Source-scanned because this compiles perfectly while being wrong.
 */
class PreferenceRepaintTest {

    private val options = File("src/main/java/com/mido/phonebridge/OptionsFragment.kt")
    private val inventory = File("src/main/java/com/mido/phonebridge/PluginsFragment.kt")

    @Test
    fun `preference repaints are posted and coalesced, never run inside the click`() {
        assertTrue("OptionsFragment.kt missing", options.isFile)
        val source = options.readText()

        assertTrue(
            "OptionsFragment must own a posted, coalesced repaint",
            source.contains("private fun renderPosted()")
        )
        // The write path's repaint goes through it.
        val saveBody = source.substringAfter("private fun save(")
        assertTrue(
            "save() must repaint via renderPosted(), not a synchronous render()",
            saveBody.substringBefore("\n    private fun").contains("renderPosted()")
        )
        // The two known click-path callers: the vault row and the model download progress.
        assertTrue(source.contains("force = true) { uiSafe { renderPosted() } }"))
        assertTrue(source.contains("onProgress = { uiSafe { if (percentChangedOnScreen()) renderPosted() } }"))
        assertFalse(
            "no click callback may call render() inline",
            source.contains("onProgress = { uiSafe { if (percentChangedOnScreen()) render() } }")
        )
    }

    @Test
    fun `the plugin toggle defers its row rebuild out of click dispatch`() {
        assertTrue("PluginsFragment.kt missing", inventory.isFile)
        val source = inventory.readText()

        assertTrue(
            "the plugin toggle must post its rebuild, not render inside its own click",
            source.contains("list.post { render() }")
        )
    }
}
