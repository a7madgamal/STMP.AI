package com.mido.phonebridge

import com.mido.phonebridge.plugins.files.FilesPlugin
import com.mido.phonebridge.plugins.files.FilesStore
import com.mido.phonebridge.plugins.files.UploadRollback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules that make `files` safe are mostly REFUSALS, so this pins the refusals.
 *
 * A plugin that can add and read files has three ways to go wrong, and none of them is visible in a
 * happy-path test: it can accept a name that is really a path, it can accept a body whose length it
 * never checked, and it can claim a failed transfer left nothing behind when a partial file is still
 * on disk. Each has an assertion here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FilesPluginTest {

    private fun store() = FilesStore(org.robolectric.RuntimeEnvironment.getApplication())

    // --- names --------------------------------------------------------------------------------

    /**
     * A name containing a separator is refused, not sanitised.
     *
     * This is the one input that becomes a different FILE if it is rewritten instead of refused, and
     * on the MediaStore path the same string was a directory traversal. Rewriting would mean the
     * file the human approved and the file on disk could differ, which a gate must never allow.
     */
    @Test
    fun `a path separator in a name is refused`() {
        for (bad in listOf("a/b.txt", "../escape.txt", "a\\\\b.txt", ".", "..")) {
            val e = runCatching { store().requireName(bad) }.exceptionOrNull()
            assertTrue("'$bad' must be refused", e is com.mido.phonebridge.core.PluginError)
        }
    }

    /** An ordinary name is accepted unchanged — including unicode and spaces. */
    @Test
    fun `an ordinary name is accepted`() {
        for (good in listOf("report.pdf", "taxes 2024.pdf", "naïve—résumé.txt", "🦀.png")) {
            assertEquals(good, store().requireName(good))
        }
    }

    /** An empty name has nothing to open, so it is its own refusal rather than a 404 later. */
    @Test
    fun `an empty name is refused`() {
        assertTrue(runCatching { store().requireName("   ") }.exceptionOrNull() is com.mido.phonebridge.core.PluginError)
    }

    // --- the cap ------------------------------------------------------------------------------

    /**
     * The cap is a constant, and its unit is the thing that goes wrong.
     *
     * 200 MiB, not 200 MB: a binary unit is what a transfer reports and what a human sees in Files,
     * and a decimal reading would make the limit quietly wrong by 4.8%.
     */
    @Test
    fun `the cap is 200 MiB`() {
        assertEquals(200L * 1024L * 1024L, FilesPlugin.MAX_BYTES)
    }

    // --- rollback honesty ---------------------------------------------------------------------

    /**
     * The failure sentence may only claim "nothing was kept" when the removal actually happened.
     *
     * Two different facts, and conflating them is how a report becomes a lie: the user would go
     * looking for a partial file the notification told them was gone, or trust a file that is
     * truncated. The message is generated from the rollback's own result, not from an assumption.
     */
    @Test
    fun `the failure message states what actually happened`() {
        val removed = UploadRollback.failureMessage(written = 100, declared = 200, removed = true)
        assertTrue("removed says nothing was kept", removed.contains("nothing was kept"))

        val kept = UploadRollback.failureMessage(written = 100, declared = 200, removed = false)
        assertFalse("a failed rollback must not claim the file is gone", kept.contains("nothing was kept"))
        assertTrue("and it must tell the human to clean up", kept.contains("partial file may remain"))
        assertTrue("both name the byte counts", kept.contains("100") && kept.contains("200"))
    }

    // --- no delete ----------------------------------------------------------------------------

    /**
     * **There is no DELETE route, and this is the guard for that decision.**
     *
     * The route was cut because its card could not honestly describe a permanent, unrecoverable
     * action on a folder with no trash. Adding it back should require deleting this test, which
     * makes it a decision rather than a drive-by.
     */
    @Test
    fun `the plugin exposes no delete route`() {
        val routes = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication()).routes()
        assertTrue(
            "files must expose no DELETE: ${routes.map { it.method + " " + it.path }}",
            routes.none { it.method.equals("DELETE", ignoreCase = true) },
        )
        // And the verbs it does use are only the two the review allowed.
        val verbs = routes.map { it.verb.name }.toSet()
        assertEquals(setOf("READ", "WRITE"), verbs)
    }

    /** An upload reads its body as a stream and neither audits it nor buffers it. */
    @Test
    fun `the upload route streams and does not audit its body`() {
        val upload = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication())
            .routes()
            .first { it.path == "/files/upload" }
        assertTrue("an upload must stream", upload.streamsRequest)
        assertFalse("a file must never be copied into the log", upload.auditRequestBody)
        assertFalse("nor its echoed reply", upload.auditResponseBody)
        assertTrue("and it must validate before the card", upload.validate != null)
    }

    /** A download never records the file's bytes in the audit either. */
    @Test
    fun `the download route does not audit its response`() {
        val download = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication())
            .routes()
            .first { it.path == "/files/content" }
        assertFalse("a downloaded document must not land in the log", download.auditResponseBody)
    }
}
