package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditField
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Absence must read as absence, on the runtime the phone actually runs.
 *
 * `AuditRecord.toJson` stores "no error" and "no rule" as `JSONObject.NULL` — a present key whose
 * value is null. Android's `optString` answers the STRING "null" for that, so the record detail
 * screen printed `ERROR null` on every successful request and `RULE null` on every decision the
 * gate made itself. A missing key, a JSON null and a blank string all have to mean the same
 * thing, and none of them is a word to show somebody.
 *
 * Robolectric, not a bare JVM: the reference `org.json` returns "" here, so a plain-JVM test would
 * be green against a behaviour the phone does not have. `AndroidJsonProofTest` pins the difference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS")
class AuditFieldTest {

    private fun record() = JSONObject().apply {
        put("error", JSONObject.NULL)
        put("label", "gmail.edit")
        put("summary", "  padded  ")
        put("emptyBody", "")
        put("requestBody", "{\n  \"a\": 1\n}")
        put(
            "decision",
            JSONObject().apply {
                put("mode", "SECURE")
                put("rule", JSONObject.NULL)
                put("enqueuedAt", JSONObject.NULL)
                put("shownAt", 1_150L)
            },
        )
    }

    @Test
    fun `a JSON null is absent, not the word null`() {
        val o = record()
        // The failure this exists to stop: Android's optString really does answer "null".
        assertEquals("null", o.optString("error"))
        assertNull("a JSON null must read as absent", AuditField.text(o, "error"))
    }

    @Test
    fun `a missing key is absent`() {
        assertNull(AuditField.text(JSONObject(), "error"))
        assertNull(AuditField.text(JSONObject(), "rule"))
    }

    @Test
    fun `blank is absent so no screen prints an empty labelled row`() {
        assertNull(AuditField.text(JSONObject().put("x", "   "), "x"))
        assertNull(AuditField.text(JSONObject().put("x", ""), "x"))
    }

    @Test
    fun `a real value survives, trimmed for display`() {
        val o = record()
        assertEquals("gmail.edit", AuditField.text(o, "label"))
        assertEquals("padded", AuditField.text(o, "summary"))
    }

    @Test
    fun `a body keeps its formatting, and an empty body stays empty`() {
        val o = record()
        // Indentation is the data on a body: trimming it would make JSON unreadable.
        assertEquals("{\n  \"a\": 1\n}", AuditField.body(o, "requestBody"))
        assertEquals("", AuditField.body(o, "emptyBody"))
        assertNull(AuditField.body(o, "absent"))
    }

    /**
     * The end-to-end case: a REAL record, written by the code that runs on the phone.
     *
     * This is the one that matters, because it uses `AuditRecord.toJson` rather than a hand-built
     * object — so if the writer ever changes how it stores absence, this fails here instead of on
     * a screen.
     */
    @Test
    fun `a real record with no error and no rule shows neither as the word null`() {
        val rec = com.mido.phonebridge.audit.AuditRecord(
            id = "x", ts = 1_000L, outcome = com.mido.phonebridge.audit.AuditOutcome.APPROVED,
            label = "contacts.read", plugin = "contacts", verb = "READ",
            origin = "TAILNET", remote = "100.64.0.7", requester = "Work laptop",
            route = "GET /contacts/search", method = "GET", path = "/contacts/search",
            query = mapOf("q" to "sara"), summary = "Search contacts",
            requestHeaders = mapOf("Host" to "h:8787"), requestBody = "",
            responseStatus = 200, responseBody = "{}", error = null,
            decision = com.mido.phonebridge.audit.Decision(
                mode = "SECURE", source = com.mido.phonebridge.audit.DecisionSource.GLOBAL,
                globalMode = "PARANOID", enqueuedAt = 1_000L, shownAt = 1_100L, decidedAt = 3_500L,
            ),
            durationMs = 2_600L, truncated = com.mido.phonebridge.audit.Truncation(0, 0),
            noise = false, appVersionName = "0.0.0", appVersionCode = 1,
        )
        val o = org.json.JSONObject(rec.toJson().toString())

        assertNull("a clean record must not print an ERROR row", AuditField.text(o, "error"))
        val d = o.getJSONObject("decision")
        assertNull("a global decision has no rule, and must not say 'null'", AuditField.text(d, "rule"))
        // The three moments are numbers on the wire and must arrive as numbers here.
        assertEquals(1_000L, AuditField.millis(d, "enqueuedAt"))
        assertEquals(1_100L, AuditField.millis(d, "shownAt"))
        assertEquals(3_500L, AuditField.millis(d, "decidedAt"))
    }

    @Test
    fun `a decision's absent timestamp is null, and a present one is a number`() {
        val d = record().getJSONObject("decision")
        assertNull(AuditField.millis(d, "enqueuedAt"))
        assertEquals(1_150L, AuditField.millis(d, "shownAt"))
        assertNull(AuditField.millis(d, "decidedAt"))
    }
}
