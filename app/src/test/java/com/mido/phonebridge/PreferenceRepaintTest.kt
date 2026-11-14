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
    private val inventory = File("src/main/java/com/mido/phonebridge/ui/PluginInventoryView.kt")

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
        assertTrue(source.contains("force = true) { renderPosted() }"))
        assertFalse(
            "no click callback may call render() inline",
            source.contains("onProgress = { ui { render() } }")
        )
    }

    /**
     * A progress callback that repaints unconditionally destroys the form it is repainting.
     *
     * `ModelProvisioner` reports every 250 ms. Each report rebuilt the whole screen, so with the
     * AI knob on Remote the URL and token fields were torn down and recreated four times a
     * second and the keyboard lost focus mid-word. Only the "On this phone" card draws a percent,
     * so a tick that changes nothing visible must not repaint — and a tick that IS visible must.
     */
    @Test
    fun `model progress repaints only when the visible percent moves`() {
        val source = options.readText()

        assertTrue(
            "the download callback must go through the visibility check",
            source.contains("onProgress = { ui { repaintIfVisibleProgressChanged() } }")
        )
        assertFalse(
            "the download callback must not repaint on every chunk",
            source.contains("onProgress = { ui { renderPosted() } }")
        )
        assertTrue(
            "the check must compare against what was drawn, and give up when nothing is drawn",
            source.contains("private fun repaintIfVisibleProgressChanged()") &&
                source.substringAfter("private fun repaintIfVisibleProgressChanged()")
                    .substringBefore("\n    }")
                    .let { it.contains("drawnDownloadPercent ?: return") && it.contains("!= drawn") }
        )
        assertTrue(
            "render() must record what it drew, per backend",
            source.contains("drawnDownloadPercent = if (policy.aiBackend == ") &&
                source.contains("com.mido.phonebridge.policy.AiBackend.LOCAL) {")
        )
    }

    @Test
    fun `the plugin toggle defers its row rebuild out of click dispatch`() {
        assertTrue("PluginInventoryView.kt missing", inventory.isFile)
        val source = inventory.readText()

        assertTrue(
            "PluginInventoryView.resume() must post and coalesce its rebuild",
            source.contains("private var renderPosted = false") &&
                source.substringAfter("fun resume()").contains("renderPosted = true")
        )
        assertFalse(
            "the plugin toggle must not rebuild the list inside its own click",
            source.contains("enabled=\$want\")\n                render()")
        )
    }
}
