package com.mido.phonebridge.audit

import com.mido.phonebridge.core.Verb
import org.json.JSONObject

/**
 * The audit record, and the small header the list view reads.
 *
 * Both live here because they are one format: the header is a projection of the
 * record, and [AuditHeader.parse] must agree with [AuditRecord.toJson] field for
 * field or the list shows one thing and the detail screen another.
 */

/**
 * One line of the log, in full.
 *
 * Every field is a `String` rather than a nullable type where the value is
 * "usually absent", because the on-disk format is JSON and a missing key and a
 * null key read the same to `optString`. The one genuine null is [error]: "no error
 * occurred" is different from "an error occurred and its code was empty".
 */
data class AuditRecord(
    val id: String,
    val ts: Long,
    val outcome: AuditOutcome,
    /** `plugin.verb`, e.g. `gmail.edit`. Empty when the request never routed. */
    val label: String,
    val plugin: String,
    val verb: String,
    /** Where the request came from, derived from the address. Never used to gate. */
    val origin: String,
    val remote: String,
    val requester: String,
    /** `METHOD /route/{template}`. The template, never the concrete path. */
    val route: String,
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val summary: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val responseStatus: Int,
    val responseBody: String,
    val error: String?,
    val decision: Decision,
    val durationMs: Long,
    val truncated: Truncation,
    val noise: Boolean,
    val appVersionName: String,
    val appVersionCode: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ts", ts)
        put("outcome", outcome.name)
        put("label", label)
        put("plugin", plugin)
        put("verb", verb)
        put("origin", origin)
        put("remote", remote)
        put("requester", requester)
        put("route", route)
        put("method", method)
        put("path", path)
        put("query", JSONObject(query))
        put("summary", summary)
        put("requestHeaders", JSONObject(requestHeaders))
        put("requestBody", requestBody)
        put("responseStatus", responseStatus)
        put("responseBody", responseBody)
        put("error", error ?: JSONObject.NULL)
        put(
            "decision",
            JSONObject().apply {
                put("mode", decision.mode)
                put("source", decision.source.name)
                put("globalMode", decision.globalMode ?: JSONObject.NULL)
                put("rule", decision.rule ?: JSONObject.NULL)
                put("enqueuedAt", decision.enqueuedAt ?: JSONObject.NULL)
                put("shownAt", decision.shownAt ?: JSONObject.NULL)
                put("decidedAt", decision.decidedAt ?: JSONObject.NULL)
                put("queueDepth", decision.queueDepth)
            }
        )
        put("durationMs", durationMs)
        // Absence is written as a PRESENT key, and zero/empty as an ABSENT one. That
        // asymmetry is deliberate: `responseBodyAbsent: true` is a fact worth stating,
        // while `responseBody: 0` would be noise on every ordinary record.
        put(
            "truncated",
            JSONObject().apply {
                if (truncated.requestBody > 0) put("requestBody", truncated.requestBody)
                if (truncated.requestBodyAbsent) put("requestBodyAbsent", true)
                if (truncated.responseBody > 0) put("responseBody", truncated.responseBody)
                if (truncated.responseBodyAbsent) put("responseBodyAbsent", true)
            }
        )
        // Fixed at false: redaction happens before the record is built (a secret is
        // scrubbed by the broker, not by the log). The field is written so a reader
        // knows the question was asked rather than forgotten.
        put("redacted", false)
        put("noise", noise)
        put(
            "app",
            JSONObject().apply {
                put("versionName", appVersionName)
                put("versionCode", appVersionCode)
            }
        )
    }
}

/**
 * The projection the list view reads, parsed back out of a stored line.
 *
 * Separate from [AuditRecord] because the list shows 50 rows and must not pay to
 * parse 50 full bodies. [parse] returns null on anything it cannot trust — a
 * corrupt line is counted, not guessed at.
 */
data class AuditHeader(
    val id: String,
    val ts: Long,
    val outcome: AuditOutcome,
    val label: String,
    val plugin: String,
    val verb: String,
    val summary: String,
    val durationMs: Long,
    val responseStatus: Int,
    val noise: Boolean,
) {
    companion object {
        fun parse(line: String): AuditHeader? {
            val o = try {
                JSONObject(line)
            } catch (e: org.json.JSONException) {
                return null
            }
            // A record without an id cannot be looked up, so it is unreadable rather
            // than a header with an empty key.
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            // An unknown outcome means the format drifted; refusing is safer than
            // inventing a state the rest of the app will then act on.
            val outcome = AuditOutcome.values().firstOrNull { it.name == o.optString("outcome") }
                ?: return null
            return AuditHeader(
                id = id,
                ts = o.optLong("ts"),
                outcome = outcome,
                label = o.optString("label"),
                plugin = o.optString("plugin"),
                verb = o.optString("verb"),
                summary = o.optString("summary"),
                durationMs = o.optLong("durationMs"),
                responseStatus = o.optInt("responseStatus"),
                noise = o.optBoolean("noise", false),
            )
        }
    }
}
