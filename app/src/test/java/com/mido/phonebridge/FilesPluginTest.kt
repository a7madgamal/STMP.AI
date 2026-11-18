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

    /**
     * The card states the size it was told, and says so plainly when it was told nothing.
     *
     * **This is a regression test for a defect that reached the phone.** Every upload card said
     * "0 B" for a 43 MB APK, because the `Req` built for describing rows dropped `bodyLength` and
     * the row coerced -1 to 0. A card that under-states a transfer by 43 megabytes is worse than
     * one that omits the size: it reads as small and safe.
     */
    @Test
    fun `the upload card states the declared size, and never invents zero`() {
        val plugin = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication())

        val known = card {
            com.mido.phonebridge.core.Req(
                method = "POST", path = "/files/upload",
                query = mapOf("path" to "big.apk", "type" to "application/octet-stream"),
                pathParams = emptyMap(), body = org.json.JSONObject(), remote = "test",
                bodyLength = 43L * 1024L * 1024L,
            )
        }
        val sizeRow = known.first { it.label == "SIZE" }
        assertTrue("a known 43 MiB must render as MiB, not 0 B: '${sizeRow.value}'", sizeRow.value.contains("MiB"))

        val unknown = card {
            com.mido.phonebridge.core.Req(
                method = "POST", path = "/files/upload",
                query = mapOf("path" to "big.apk", "type" to "application/octet-stream"),
                pathParams = emptyMap(), body = org.json.JSONObject(), remote = "test",
                bodyLength = -1L,
            )
        }
        val unknownSize = unknown.first { it.label == "SIZE" }
        assertFalse("an undeclared size must not render as a number", unknownSize.value.contains("0 B"))
        assertTrue("it must say so", unknownSize.value.contains("not declared"))
    }

    private fun card(block: () -> com.mido.phonebridge.core.Req) =
        FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication()).describeRows(block())

    /**
     * Every route refuses BEFORE the card when no folder is granted.
     *
     * On device the listing card was approved and *then* answered `503 folder_not_granted` — a
     * wasted tap on a request that could never have worked, and the kind of thing that teaches a
     * human to stop reading the modal. The check is on `validate`, which runs pre-gate, so this
     * asserts the wiring rather than the behaviour of a handler nobody should reach.
     */
    @Test
    fun `every route checks the grant before the card`() {
        val routes = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication()).routes()
        for (route in routes) {
            assertTrue(
                "${route.method} ${route.path} has no pre-gate check, so an ungranted folder " +
                    "would cost a tap",
                route.validate != null,
            )
        }
    }

    /**
     * The listing card must NAME the folder it is about to read.
     *
     * "LIST FILES" with no rows asks someone to approve a read of the phone's storage without
     * saying where. Reported on device as a card that was "almost empty".
     */
    @Test
    fun `the list card names the folder`() {
        val rows = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication()).describeRows(
            com.mido.phonebridge.core.Req(
                method = "GET", path = "/files", query = emptyMap(),
                pathParams = emptyMap(), body = org.json.JSONObject(), remote = "test",
            )
        )
        assertTrue("the card must have rows at all, not an empty modal", rows.isNotEmpty())
        assertTrue(
            "the folder must be named: ${rows.map { it.label + "=" + it.value }}",
            rows.any { it.value.contains("STMP") },
        )
        // With no grant it still renders, and says so rather than showing an empty list.
        assertTrue("an ungranted folder must be stated", rows.any { it.value.contains("not granted") })
    }

    /**
     * A grant whose folder has been deleted must read as "not granted", not as a broken read.
     *
     * The persisted permission outlives the folder, so the system still says it is granted while
     * every query against it fails. Returning that state as an odd provider error makes a normal
     * situation look like a bug — and gives the human nothing to act on.
     */
    @Test
    fun `an unreachable folder is reported as ungranted`() {
        val store = store()
        // No grant at all is the pre-condition this shares with the real case: both must surface as
        // NotGranted so the same 503-and-ask path handles them.
        assertTrue(
            runCatching { store.root() }.exceptionOrNull() is FilesStore.NotGranted,
        )
    }

    /**
     * The refusal must describe the thing that actually appears.
     *
     * It said "tap the notification" for two releases after the notification became a sheet, so a
     * human following the instruction looked for something that was no longer posted. Copy that
     * names a mechanism is copy that has to move when the mechanism does.
     */
    @Test
    fun `the grant refusal names the sheet, not a notification`() {
        // Read from the classification test's own source of truth rather than the private map:
        // asking the app what it thinks the words are is not a test of the words.
        val source = java.io.File("src/main/java/com/mido/phonebridge/core/ErrorCodes.kt").readText()
        val line = source.lines().first { it.contains("\"folder_not_granted\" to \"") }
        assertTrue("must not point at a notification any more: $line", !line.contains("notification"))
        assertTrue("must name what the human will see: $line", line.contains("sheet"))
    }

    /**
     * The grant Activity must NOT be declared `noHistory`.
     *
     * **This is a regression test for a defect that ate the user's answer.** `noHistory="true"`
     * finishes an Activity as soon as it is covered — and the folder picker covers it — so the
     * picker's result was delivered to an Activity that no longer existed, `onActivityResult` never
     * ran, and the folder the human picked was silently discarded. Nothing in the code could show
     * this; only the manifest attribute could, which is why it is asserted here.
     */
    @Test
    fun `the grant activity keeps its state across the picker`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val start = manifest.indexOf(".plugins.files.FilesGrantActivity")
        assertTrue("FilesGrantActivity is not declared", start >= 0)
        val element = manifest.substring(manifest.lastIndexOf("<activity", start), manifest.indexOf("/>", start))
        assertTrue(
            "noHistory finishes the activity when the picker covers it, so the result is lost:\n$element",
            !element.contains("noHistory"),
        )
    }

    /**
     * The card speaks UPLOAD and DOWNLOAD, not the internal READ/WRITE verbs.
     *
     * `Verb` is the app's taxonomy and its direction depends on which side you stand on — the phone
     * writes, the caller receives. A human reading a card has no such context, and "READ" is
     * genuinely ambiguous about whether anything leaves the device. Reported on device as a
     * question about what the word meant, which is the test.
     */
    @Test
    fun `the cards use upload and download, not read and write`() {
        val routes = FilesPlugin(org.robolectric.RuntimeEnvironment.getApplication()).routes()
        val labels = routes.joinToString(" | ") { it.describe }
        assertTrue("a route still says READ: $labels", !routes.any { it.describe.startsWith("READ") })
        assertTrue("the download route must say so: $labels", labels.contains("DOWNLOAD"))
        assertTrue("the upload route must say so: $labels", labels.contains("UPLOAD"))
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
