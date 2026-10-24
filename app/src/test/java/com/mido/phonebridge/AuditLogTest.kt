package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.audit.AuditRetention
import com.mido.phonebridge.core.Verb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store's job is to never lose a record silently and never resurrect a deleted
 * one. Retention and corruption handling are the two paths that decide whether the
 * log can be trusted six days from now, so both are tested against a real
 * directory rather than mocked.
 *
 * AuditLog takes a [File], not a Context, precisely so this can run on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun log(
        dir: File = tmp.newFolder(),
        retention: AuditRetention = AuditRetention()
    ) = AuditLog(dir, retention, "0.4.0", 66)

    private fun record(log: AuditLog, outcome: AuditOutcome, path: String = "/gmail/messages") {
        val d = com.mido.phonebridge.audit.AuditDraft(
            startedAtMs = 1_000L,
            remote = "100.64.0.10",
            method = "POST",
            path = path,
            query = emptyMap(),
            requestHeaders = emptyMap(),
            appVersionName = "0.4.0",
            appVersionCode = 66
        )
        d.bindRoute("gmail", Verb.READ, "/gmail/messages")
        d.outcome(outcome)
        d.response(200, """{"ok":true}""")
        log.append(d.toRecord(System.currentTimeMillis()))
    }

    @Test
    fun `written records survive a reopen`() {
        val dir = tmp.newFolder()
        val a = log(dir)
        record(a, AuditOutcome.APPROVED)
        record(a, AuditOutcome.DENIED)
        record(a, AuditOutcome.TIMEOUT)
        val before = a.headers()
        assertEquals(3, before.size)

        // A fresh instance over the same directory is what an app restart looks like.
        val b = log(dir)
        val after = b.headers()
        assertEquals(3, after.size)
        // Newest first, and the ids are stable across the reopen.
        assertEquals(before.map { it.id }, after.map { it.id })
        assertNotNull(b.line(after.first().id))
    }

    @Test
    fun `outcomes and labels are read back intact`() {
        val a = log()
        record(a, AuditOutcome.DENIED)
        val h = a.headers().first()
        assertEquals(AuditOutcome.DENIED, h.outcome)
        assertEquals("gmail.read", h.label)
        assertEquals(200, h.responseStatus)
    }

    @Test
    fun `a corrupt line is counted, not fatal, and does not hide its neighbours`() {
        val dir = tmp.newFolder()
        val a = log(dir)
        record(a, AuditOutcome.APPROVED)
        // Simulate external damage: append junk to the day file.
        val day = dir.listFiles()!!.first()
        day.appendText("this is not json\n")
        day.appendText("{\"id\":\"x\",\"outcome\":\"NOT_A_REAL_OUTCOME\"}\n")
        record(a, AuditOutcome.DENIED)

        val b = log(dir)
        assertEquals("two good records must still load", 2, b.headers().size)
        assertEquals("junk and a bad outcome are both counted", 2, b.unreadableCount())
    }

    @Test
    fun `retention deletes a day-file older than the window`() {
        val dir = tmp.newFolder()
        // A file named well in the past cannot be produced by the clock, so write it
        // directly — the name IS the date, which is the whole retention mechanism.
        val old = File(dir, "2020-01-01.jsonl")
        old.writeText(
            JSONObject().put("id", "old").put("outcome", "APPROVED").toString() + "\n"
        )
        assertTrue("precondition: the stale file exists", old.exists())

        log(dir) // constructor runs prune()

        assertTrue("stale day-file must be deleted", !old.exists())
    }

    @Test
    fun `the size cap drops whole oldest days, never a partial file`() {
        val dir = tmp.newFolder()
        // Dates must be INSIDE the age window or the age rule deletes them and this
        // stops testing the cap at all (an earlier version hardcoded 2026-10-01,
        // which passed on the day it was written and rotted from there). Derived
        // from the clock, so it cannot drift.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val today = fmt.format(java.util.Date())
        val yesterday = fmt.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        File(dir, "$yesterday.jsonl").writeText("x".repeat(2000) + "\n")
        File(dir, "$today.jsonl").writeText("y".repeat(2000) + "\n")

        log(dir, AuditRetention(maxAgeDays = 7, maxTotalBytes = 2500))

        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals("only the newest day survives the cap", listOf("$today.jsonl"), left)
    }

    @Test
    fun `headers are newest first`() {
        val a = log()
        // Two records in different milliseconds so ordering is unambiguous.
        val d1 = com.mido.phonebridge.audit.AuditDraft(
            1L, "1.1.1.1", "GET", "/notifications", emptyMap(), emptyMap(), "0.4.0", 66
        ).apply { bindRoute("notifications", Verb.READ, "/notifications"); outcome(AuditOutcome.APPROVED); response(200, "{}") }
        val d2 = com.mido.phonebridge.audit.AuditDraft(
            1L, "1.1.1.1", "GET", "/notifications", emptyMap(), emptyMap(), "0.4.0", 66
        ).apply { bindRoute("notifications", Verb.READ, "/notifications"); outcome(AuditOutcome.DENIED); response(403, "{}") }
        a.append(d1.toRecord(5_000L))
        a.append(d2.toRecord(6_000L))
        val hs = a.headers()
        assertEquals(AuditOutcome.DENIED, hs[0].outcome)
        assertEquals(AuditOutcome.APPROVED, hs[1].outcome)
    }

    @Test
    fun `line returns the exact stored JSON for an id, and null when it is gone`() {
        val a = log()
        record(a, AuditOutcome.APPROVED)
        val id = a.headers().first().id
        val raw = a.line(id)
        assertNotNull(raw)
        assertEquals(id, JSONObject(raw!!).getString("id"))
        assertNull(a.line("20200101T000000.000-dead"))
    }

    @Test
    fun `a body that cannot be parsed is still stored verbatim`() {
        // The dead end this exists to prevent: answering 400 "invalid JSON body"
        // while keeping not one byte of what the caller actually sent.
        val d = com.mido.phonebridge.audit.AuditDraft(
            1L, "1.1.1.1", "POST", "/notifications/abc/action", emptyMap(), emptyMap(), "0.5.0", 69
        )
        d.bindRoute("notifications", Verb.EXECUTE, "/notifications/{id}/action")
        d.requestBody("{\"action\": broken")
        d.outcome(AuditOutcome.REJECTED_INVALID)
        d.error("bad_request")
        d.response(400, null)
        val rec = d.toRecord(2L)
        assertEquals("{\"action\": broken", rec.requestBody)
        // Survives the codec, so the detail screen can show it back.
        assertEquals("{\"action\": broken", JSONObject(rec.toJson().toString()).getString("requestBody"))
    }

    @Test
    fun `an unrecorded response body is marked absent, never silently empty`() {
        // /debug returns the log itself and opts out of storing its response. An empty
        // responseBody must not read as "the handler returned nothing".
        val d = com.mido.phonebridge.audit.AuditDraft(
            1L, "1.1.1.1", "GET", "/debug", emptyMap(), emptyMap(), "0.5.0", 69
        )
        d.bindRoute("debug", Verb.READ, "/debug")
        d.outcome(AuditOutcome.APPROVED)
        d.responseNotStored(200)
        val json = d.toRecord(2L).toJson()
        assertEquals("", json.getString("responseBody"))
        assertTrue(json.getJSONObject("truncated").getBoolean("responseBodyAbsent"))
    }

    @Test
    fun `clear removes every record`() {
        val dir = tmp.newFolder()
        val a = log(dir)
        record(a, AuditOutcome.APPROVED)
        record(a, AuditOutcome.DENIED)
        a.clear()
        assertEquals(0, a.headers().size)
        assertEquals("fresh instance also sees nothing", 0, log(dir).headers().size)
    }
}
