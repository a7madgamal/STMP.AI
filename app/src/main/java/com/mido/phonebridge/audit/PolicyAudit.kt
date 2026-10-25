package com.mido.phonebridge.audit

import com.mido.phonebridge.BuildConfig
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Rule

/**
 * Writes the records for a POLICY change — its own record, never folded into the
 * approval it enabled.
 *
 * Reading an approval as the whole story hides that the gate moved. When the human
 * taps "always notify" on a modal, two things happened: this request was approved,
 * and the rule table changed for every future request. Those are different facts
 * with different blast radii, and the second is the one someone will want six days
 * later when they wonder why a plugin stopped prompting.
 *
 * [source] names the control that made the change (`settings`, `options`, `modal`,
 * the exceptions screen). It is a free string rather than an enum because the set of
 * screens is a UI detail, and the log should not need a release to name a new one.
 */
object PolicyAudit {

    const val PLUGIN = "policy"
    const val LABEL = "policy.changed"

    fun record(before: GlobalMode, after: GlobalMode, source: String): AuditRecord =
        build("switch: ${before.name} → ${after.name} ($source)", after.name)

    /**
     * A rule was added, replaced or removed.
     *
     * Both sides are nullable because both real cases exist: adding a rule has no
     * `before`, removing one has no `after`. The summary names whichever side carries
     * the identity, so a removal still says WHICH rule went away.
     */
    fun recordRule(before: Rule?, after: Rule?, global: GlobalMode, source: String): AuditRecord {
        val pair = (after?.plugin ?: before?.plugin) + "/" +
            (after?.verb?.name ?: before?.verb?.name ?: Rule.ANY)
        val from = before?.mode?.name ?: "default"
        val to = after?.mode?.name ?: "default"
        return build(
            "rule: $pair $from → $to ($source; switch ${global.name})",
            global.name,
        )
    }

    private fun build(summary: String, modeAfter: String): AuditRecord {
        val now = System.currentTimeMillis()
        return AuditRecord(
            id = AuditId.next(now),
            ts = now,
            outcome = AuditOutcome.POLICY_CHANGED,
            label = LABEL,
            plugin = PLUGIN,
            // EXECUTE because a policy change IS an act, and the log's verb column has
            // to classify it like any other. It is not a READ of anything.
            verb = "EXECUTE",
            // No remote and no route: this did not arrive over HTTP. Empty rather than
            // "unknown", because there is no request to have an origin.
            origin = "",
            remote = "",
            requester = "the phone",
            route = "",
            method = "",
            path = "",
            query = emptyMap(),
            summary = summary,
            requestHeaders = emptyMap(),
            requestBody = "",
            responseStatus = 0,
            responseBody = "",
            error = null,
            // GLOBAL, not DEFAULT: this record exists because the switch or a rule
            // moved, and the mode it lands in is the global one.
            decision = Decision(
                mode = modeAfter,
                source = DecisionSource.GLOBAL,
                globalMode = modeAfter,
                rule = null,
                enqueuedAt = null,
                shownAt = null,
                decidedAt = now,
                queueDepth = 0,
            ),
            durationMs = 0L,
            truncated = Truncation(0, 0, false, false),
            noise = false,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
        )
    }
}
