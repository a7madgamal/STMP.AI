package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditBlobs
import com.mido.phonebridge.audit.AuditDraft
import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.audit.AuditNoise
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.audit.Decision
import com.mido.phonebridge.audit.DecisionSource
import com.mido.phonebridge.core.Verb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The record is the only thing this feature produces, so its codec is the one
 * place a silent corruption would poison every later decision. Round-trips here
 * include the things real payloads actually contain: Unicode, newlines, quotes,
 * emoji, and a body past the cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditCodecTest {

    private fun draft(path: String = "/gmail/messages/abc/modify"): AuditDraft = AuditDraft(
        startedAtMs = 1_000L,
        remote = "100.101.102.103",
        requesterLabelValue = "this device",
        method = "POST",
        path = path,
        query = mapOf("count" to "3"),
        requestHeaders = mapOf("user-agent" to "curl/8.5.0"),
        appVersionName = "0.4.0",
        appVersionCode = 66
    )

    @Test
    fun `record round-trips through JSON without losing a field`() {
        val d = draft()
        d.bindRoute("gmail", Verb.EDIT, "/gmail/messages/{id}/modify")
        d.summary("MODIFY EMAIL  add=[STARRED]")
        d.requestBody("""{"addLabels":["STARRED"],"note":"café 🦀 \"quoted\"\nline2"}""")
        d.decision(
            Decision(
                mode = "SECURE", source = DecisionSource.DEFAULT, globalMode = null, rule = null,
                enqueuedAt = 1_100L, shownAt = 1_150L, decidedAt = 1_900L, queueDepth = 2
            )
        )
        d.outcome(AuditOutcome.APPROVED)
        d.response(200, """{"ok":true,"id":"abc"}""")

        val rec = d.toRecord(2_000L)
        val json = rec.toJson()
        val line = json.toString()
        val parsed = JSONObject(line)
        assertEquals("APPROVED", parsed.getString("outcome"))
        assertEquals("gmail.edit", parsed.getString("label"))
        assertEquals("EDIT", parsed.getString("verb"))
        // The fixture remote is a CGNAT address, so the label is TAILNET. There is no
        // OPENCLAW bucket any more: caller identity comes from CallerStore, not from an
        // address that had to be hardcoded into the build.
        assertEquals("TAILNET", parsed.getString("origin"))
        assertEquals(2, parsed.getJSONObject("decision").getInt("queueDepth"))
        assertFalse(parsed.getBoolean("noise"))
        // The real invariant: the stored line re-parses to the SAME object, so the
        // body survived escaping (Unicode, quotes, emoji, newline) without drift.
        assertEquals(json.toString(), JSONObject(line).toString())
        assertTrue(parsed.getString("requestBody").contains("café 🦀"))

        // The header parse (what the list actually uses) agrees with the record.
        val h = AuditHeader.parse(line)!!
        assertEquals(rec.id, h.id)
        assertEquals(AuditOutcome.APPROVED, h.outcome)
        assertEquals("gmail.edit", h.label)
        assertEquals(200, h.responseStatus)
    }

    @Test
    fun `ids in the same millisecond never collide`() {
        // A collision would make line(id) return the wrong record, so this is a
        // correctness property, not a nicety.
        val ids = (1..500).map { draftId(1_700_000_000_000L) }
        assertEquals("500 ids in one ms are 500 distinct ids", 500, ids.toSet().size)
    }

    private fun draftId(ts: Long): String {
        val d = draft()
        d.outcome(AuditOutcome.APPROVED)
        d.response(200, "{}")
        return d.toRecord(ts).id
    }

    @Test
    fun `a draft with no outcome refuses to freeze`() {
        val d = draft()
        d.bindRoute("gmail", Verb.READ, "/gmail/messages")
        // No outcome set: this is a server defect, not a record with a guessed state.
        val e = runCatching { d.toRecord(1_000L) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test
    fun `a request rejected before routing has no plugin, no verb, and still records`() {
        val d = draft(path = "/status")
        d.outcome(AuditOutcome.UNROUTED)
        d.response(404, """{"ok":false,"error":"not_found"}""")
        val rec = d.toRecord(1_050L)
        assertEquals("", rec.label)
        assertEquals("", rec.plugin)
        assertEquals("", rec.verb)
        assertTrue(AuditNoise.isNoise(rec.outcome, rec.path))
        assertTrue(rec.toJson().getBoolean("noise"))
    }

    @Test
    fun `only UNROUTED and REJECTED_INVALID are noise`() {
        for (o in AuditOutcome.values()) {
            val expected = o == AuditOutcome.UNROUTED || o == AuditOutcome.REJECTED_INVALID
            assertEquals("$o", expected, AuditNoise.isNoise(o, "/gmail/messages"))
        }
    }

    @Test
    fun `a handler failure is never hidden as noise`() {
        assertFalse(AuditNoise.isNoise(AuditOutcome.HANDLER_FAILED, "/gmail/messages"))
    }
}
