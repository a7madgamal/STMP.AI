package com.mido.phonebridge

import com.mido.phonebridge.plugins.drive.DriveRouteFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit-leak guard for routes that carry FILE BYTES.
 *
 * Mido, 2026-10-21: streaming is acceptable *"as long as it doesn't leak secrets"*. That is a
 * property, so this is the test that holds it.
 *
 * The audit stores request and response bodies VERBATIM, capped at 32 KiB. Without the opt-outs:
 *
 *  - an upload writes the first 32 KB of the user's document permanently into an append-only log;
 *  - a download writes the first 32 KB of the file being fetched.
 *
 * Both would land in a place nobody thinks to look, looking like ordinary traffic.
 *
 * **This tests `DriveRouteFlags`, the table `DrivePlugin.routes()` actually builds from** — not a
 * copy. A test that re-declares the routes and asserts against its own declaration passes no
 * matter what the plugin does, and this project has shipped that mistake twice.
 */
class DriveAuditFlagsTest {

    @Test
    fun `the download route does not store the response body`() {
        val f = DriveRouteFlags.forPath("GET", "/drive/files/{id}/content")
        assertTrue("download route missing from the table", f != null)
        assertFalse(
            "downloading a file must not copy its bytes into the audit log",
            f!!.auditResponseBody
        )
    }

    @Test
    fun `the upload route does not store the request body`() {
        val f = DriveRouteFlags.forPath("POST", "/drive/files/upload")
        assertTrue("upload route missing from the table", f != null)
        assertFalse(
            "uploading a file must not copy its bytes into the audit log",
            f!!.auditRequestBody
        )
    }

    @Test
    fun `the upload route streams rather than buffering`() {
        // Independent of the audit flag, and the reason both exist: `readRawBody` builds the whole
        // body into a String, which is fatal for a large file regardless of what the log keeps.
        val f = DriveRouteFlags.forPath("POST", "/drive/files/upload")
        assertTrue("upload must not buffer the body into a String", f!!.streamsRequest)
    }

    @Test
    fun `every OTHER drive route keeps full auditing`() {
        // The opt-outs must stay surgical. A metadata route that skipped the audit would be a
        // request that ran with no record.
        val bytePaths = setOf("/drive/files/upload", "/drive/files/{id}/content")
        for (f in DriveRouteFlags.ALL) {
            if (f.path in bytePaths) continue
            assertTrue("${f.method} ${f.path} must record its request body", f.auditRequestBody)
            assertTrue("${f.method} ${f.path} must record its response body", f.auditResponseBody)
            assertFalse("${f.method} ${f.path} must not stream its request", f.streamsRequest)
        }
    }

    @Test
    fun `exactly two routes carry bytes, and they are the expected pair`() {
        // A count guard: adding a third byte-carrying route without thinking about the log is
        // exactly how the leak would come back.
        val carriers = DriveRouteFlags.byteCarriers().map { it.path }.sorted()
        assertEquals(
            listOf("/drive/files/upload", "/drive/files/{id}/content"),
            carriers
        )
    }

    @Test
    fun `an unknown route resolves to null rather than a permissive default`() {
        // `DrivePlugin.route()` throws on a null here, so a new route whose audit behaviour nobody
        // decided fails at registration instead of inheriting a default that looks safe.
        assertNull(DriveRouteFlags.forPath("GET", "/drive/not-a-route"))
        // Method matters: the same path under a different verb is a different route.
        assertNull(DriveRouteFlags.forPath("DELETE", "/drive/files"))
    }
}
