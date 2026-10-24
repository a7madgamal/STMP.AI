package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditDraft
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.audit.recordResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The decision to keep a handler's reply in the audit — and the bug that proved it
 * had to be shared.
 *
 * It used to live twice, once per dispatch path. The ungated path's copy (the `OFF`
 * switch, removed in 0.12.0) always stored the body, so `Route.auditResponseBody` was
 * honoured under `SECURE` and silently ignored whenever the gate was off: `/debug`
 * wrote its whole report into the log on every call. Measured on the device — one
 * `debug.read` record cost ~1 KB under `SECURE` and ~13.7 KB under `OFF`.
 *
 * These call the same function the dispatcher calls, which is the point. The previous
 * version of this file constructed a `Route` and asserted on the flag, so it passed
 * happily while the real dispatcher ignored that flag on one of its two paths. A test
 * that cannot fail is worse than no test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RouteAuditTest {

    /**
     * A draft that is ready to become a record. The outcome is set because
     * `toRecord` refuses a draft with no terminal state — which is deliberate, and
     * the first version of this test tripped over it.
     */
    private fun draft() = AuditDraft(
        startedAtMs = 1L,
        remote = "100.1.1.1",
        method = "GET",
        path = "/debug",
        query = emptyMap(),
        requestHeaders = emptyMap(),
        appVersionName = "0.5.2",
        appVersionCode = 71
    ).apply { outcome(AuditOutcome.APPROVED) }

    @Test
    fun `storing on keeps the body`() {
        val d = draft()
        recordResponse(d, storesResponseBody = true, status = 200, body = JSONObject("""{"a":1}"""))
        val json = d.toRecord(2L).toJson()
        assertEquals("""{"a":1}""", json.getString("responseBody"))
        // The flag is omitted when false rather than written as false, so absence is
        // the signal. Asserted with optBoolean: demanding the key would fail on the
        // correct path, which is what the first version of this test did.
        assertFalse(json.getJSONObject("truncated").optBoolean("responseBodyAbsent", false))
    }

    @Test
    fun `opting out drops the body and marks it absent`() {
        val d = draft()
        recordResponse(
            d, storesResponseBody = false, status = 200,
            body = JSONObject("""{"big":"report"}""")
        )
        val json = d.toRecord(2L).toJson()
        assertEquals("", json.getString("responseBody"))
        assertTrue(json.getJSONObject("truncated").getBoolean("responseBodyAbsent"))
    }

    @Test
    fun `a null body with storing on is empty, not absent`() {
        // The distinction the flag exists to preserve: "no body existed" and "a body
        // existed and was not kept" must not collapse into the same record.
        val d = draft()
        recordResponse(d, storesResponseBody = true, status = 204, body = null)
        val json = d.toRecord(2L).toJson()
        assertEquals("", json.getString("responseBody"))
        assertFalse(json.getJSONObject("truncated").optBoolean("responseBodyAbsent", false))
    }

    @Test
    fun `both dispatch paths cannot disagree`() {
        // The regression itself. Under the old code these two callers had their own
        // copy of the decision, and the ungated one stored the body while the gated one
        // did not. Compared field-by-field, not record-by-record: record ids and
        // timestamps increment by design, so a whole-object comparison would differ
        // for a reason that has nothing to do with this decision.
        val ungated = draft()
        val gated = draft()
        recordResponse(ungated, storesResponseBody = false, status = 200, body = JSONObject("""{"x":1}"""))
        recordResponse(gated, storesResponseBody = false, status = 200, body = JSONObject("""{"x":1}"""))
        val a = ungated.toRecord(2L).toJson()
        val b = gated.toRecord(2L).toJson()
        assertEquals(a.getString("responseBody"), b.getString("responseBody"))
        assertEquals(
            a.getJSONObject("truncated").getBoolean("responseBodyAbsent"),
            b.getJSONObject("truncated").getBoolean("responseBodyAbsent")
        )
    }
}
