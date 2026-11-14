package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.audit.AuditRecord
import com.mido.phonebridge.audit.Decision
import com.mido.phonebridge.audit.DecisionSource
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.audit.Truncation
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.ui.LogDetail
import com.mido.phonebridge.ui.LogDetailModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogDetailModelTest {

    private fun record(
        outcome: AuditOutcome = AuditOutcome.HANDLER_FAILED,
        plugin: String = "gmail",
        error: String? = null,
        decision: Decision = Decision("PARANOID", DecisionSource.GLOBAL, "WHITELIST", null, null, null, null, 0),
        truncated: Truncation = Truncation(0, 0),
    ) = AuditRecord(
        id = "r1", ts = 1_794_346_882_505L, outcome = outcome,
        label = if (plugin.isEmpty()) "" else "$plugin.edit", plugin = plugin, verb = if (plugin.isEmpty()) "" else "EDIT",
        origin = "TAILNET", remote = "100.64.0.7", requester = "Work laptop",
        route = "POST /gmail/x", method = "POST", path = "/gmail/x", query = emptyMap(),
        summary = "Edit a thing", requestHeaders = emptyMap(), requestBody = "", responseStatus = 0,
        responseBody = "", error = error, decision = decision, durationMs = 0L, truncated = truncated,
        noise = false, appVersionName = "0.49.8", appVersionCode = 304,
    )

    private fun LogDetailModel.d() = checkNotNull(decision)

    private fun ok(r: AuditRecord) = (LogDetailModel.parse(r.toJson().toString()) as LogDetail.Ok).model

    /** Every String in the model, flattened, so one assertion covers every field. */
    private fun strings(m: LogDetailModel): List<String> {
        val d = m.decision
        return listOfNotNull(
            m.id, m.outcomeRaw, m.label, m.plugin, m.origin, m.remote, m.requester, m.route, m.path,
            m.query, m.summary, m.headers, m.requestBody, m.responseBody, m.error,
            d?.mode, d?.source, d?.globalMode, d?.rule,
        )
    }

    private fun assertNoNullText(m: LogDetailModel) =
        strings(m).forEach { assertFalse("field reads \"$it\"", it.trim().equals("null", true) || it.contains("null")) }

    @Test fun `a JSON null error is absent, not the text null`() {
        val m = ok(record(error = null))
        assertNull(m.error)
        assertNoNullText(m)
    }

    @Test fun `a real error is kept`() = assertEquals("upstream_timeout", ok(record(error = "upstream_timeout")).error)

    @Test fun `a decision with every time and the rule null`() {
        val m = ok(record())
        assertNull(m.d().rule)
        assertNull(m.d().enqueuedAt)
        assertNull(m.d().shownAt)
        assertNull(m.d().decidedAt)
        assertNoNullText(m)
    }

    @Test fun `times that exist are numbers`() {
        val m = ok(record(decision = Decision("PARANOID", DecisionSource.RULE, "WHITELIST", "gmail/EDIT", 1, 2, 3, 2)))
        assertEquals(1L, m.d().enqueuedAt)
        assertEquals(3L, m.d().decidedAt)
        assertEquals("gmail/EDIT", m.d().rule)
        assertEquals(2, m.d().queueDepth)
    }

    @Test fun `absent flags are not a truncation`() {
        val m = ok(record(truncated = Truncation(0, 0, responseBodyAbsent = true, requestBodyAbsent = true)))
        assertNull(m.requestOriginal)
        assertNull(m.responseOriginal)
        assertTrue(m.requestAbsent)
        assertTrue(m.responseAbsent)
    }

    @Test fun `a real truncation keeps the original size`() {
        val m = ok(record(truncated = Truncation(40_112, 51_200)))
        assertEquals(40_112, m.requestOriginal)
        assertEquals(51_200, m.responseOriginal)
        assertFalse(m.requestAbsent)
    }

    @Test fun `a policy change record has a chip-less outcome and no plugin facts to show`() {
        val m = ok(PolicyAudit.record(GlobalMode.WHITELIST, GlobalMode.PARANOID, "settings"))
        assertTrue(m.isPolicyChange)
        assertNull(m.query)
        assertNull(m.origin)
        assertNull(m.remote)
        assertNull(m.error)
        assertNoNullText(m)
    }

    @Test fun `an unrouted record has no plugin`() {
        val m = ok(record(outcome = AuditOutcome.UNROUTED, plugin = ""))
        assertNull(m.plugin)
        assertNull(m.label)
        assertNull(m.verb)
    }

    @Test fun `garbage is unreadable and a missing line is missing`() {
        assertTrue(LogDetailModel.parse("not json {{") is LogDetail.Unreadable)
        assertTrue(LogDetailModel.parse(null) is LogDetail.Missing)
    }
}
