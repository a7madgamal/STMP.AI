package com.mido.phonebridge.ui

import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.core.Verb
import org.json.JSONException
import org.json.JSONObject

/** The decision block of one stored record; every optional fact is null when absent (R-ABSENT). */
data class DecisionModel(
    val mode: String?,
    val source: String?,
    val globalMode: String?,
    val rule: String?,
    val enqueuedAt: Long?,
    val shownAt: Long?,
    val decidedAt: Long?,
    val queueDepth: Int,
)

/** One stored audit line, parsed so that no screen ever sees the text "null" (WP08 4.1). */
data class LogDetailModel(
    val id: String,
    val ts: Long,
    val outcome: AuditOutcome?,
    val outcomeRaw: String,
    val label: String?,
    val plugin: String?,
    val verb: Verb?,
    val origin: String?,
    val remote: String?,
    val requester: String?,
    val route: String?,
    val path: String?,
    val query: String?,
    val summary: String?,
    val headers: String?,
    val requestBody: String?,
    val requestAbsent: Boolean,
    val requestOriginal: Int?,
    val responseStatus: Int,
    val responseBody: String?,
    val responseAbsent: Boolean,
    val responseOriginal: Int?,
    val error: String?,
    val durationMs: Long,
    val noise: Boolean,
    val decision: DecisionModel?,
) {
    val isPolicyChange: Boolean get() = outcome == AuditOutcome.POLICY_CHANGED

    companion object {
        fun parse(raw: String?): LogDetail = LogDetailParser.parse(raw)
    }
}

sealed class LogDetail {
    object Missing : LogDetail()
    data class Unreadable(val raw: String) : LogDetail()
    data class Ok(val model: LogDetailModel) : LogDetail()
}

object LogDetailParser {
    /** R-ABSENT: missing, JSON null and blank are all absent. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.body(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.time(key: String): Long? = if (isNull(key)) null else optLong(key)

    fun parse(raw: String?): LogDetail {
        if (raw == null) return LogDetail.Missing
        val o = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return LogDetail.Unreadable(raw)
        }
        val outcomeRaw = o.text("outcome").orEmpty()
        val trunc = o.optJSONObject("truncated")
        val query = o.optJSONObject("query")?.takeIf { it.length() > 0 }?.toString()
        val headers = o.optJSONObject("requestHeaders")?.takeIf { it.length() > 0 }?.let { JsonCode.present(it.toString()) }
        return LogDetail.Ok(
            LogDetailModel(
                id = o.text("id").orEmpty(),
                ts = o.optLong("ts"),
                outcome = AuditOutcome.values().firstOrNull { it.name == outcomeRaw },
                outcomeRaw = outcomeRaw,
                label = o.text("label"),
                plugin = o.text("plugin"),
                verb = o.text("verb")?.let { AuditCopy.verbOf(it) },
                origin = o.text("origin"),
                remote = o.text("remote"),
                requester = o.text("requester"),
                route = o.text("route"),
                path = o.text("path"),
                query = query,
                summary = o.text("summary"),
                headers = headers,
                requestBody = o.body("requestBody"),
                requestAbsent = trunc?.optBoolean("requestBodyAbsent") ?: false,
                requestOriginal = trunc?.optInt("requestBody", 0)?.takeIf { it > 0 },
                responseStatus = o.optInt("responseStatus"),
                responseBody = o.body("responseBody"),
                responseAbsent = trunc?.optBoolean("responseBodyAbsent") ?: false,
                responseOriginal = trunc?.optInt("responseBody", 0)?.takeIf { it > 0 },
                error = o.text("error"),
                durationMs = o.optLong("durationMs"),
                noise = o.optBoolean("noise"),
                decision = o.optJSONObject("decision")?.let { d ->
                    DecisionModel(
                        mode = d.text("mode"),
                        source = d.text("source"),
                        globalMode = d.text("globalMode"),
                        rule = d.text("rule"),
                        enqueuedAt = d.time("enqueuedAt"),
                        shownAt = d.time("shownAt"),
                        decidedAt = d.time("decidedAt"),
                        queueDepth = d.optInt("queueDepth"),
                    )
                },
            )
        )
    }
}
