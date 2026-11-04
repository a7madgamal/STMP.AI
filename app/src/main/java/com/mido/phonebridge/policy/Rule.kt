package com.mido.phonebridge.policy

import com.mido.phonebridge.core.Verb

/**
 * One exact decision: this plugin's verb behaves this way.
 *
 * Exact keys only — `plugin` is a real plugin id and `verb` is a real verb. There is
 * deliberately no `*` and no `scope`: a wildcard cascade was designed and then
 * removed, because with exact keys two rules can never both match one request, so
 * there is nothing to rank, no specificity to compute and no tie to break. The
 * whole rule engine is `List.find` (see [resolveMode]).
 *
 * **[verb] may be null, which means ANY** (Mido, 2026-10-19: *"in the rules verb
 * selection allow ANY"*). This is a BOUNDED reintroduction of precedence, and it is
 * worth being precise about what changed: the deleted design was an unbounded
 * plugin×verb wildcard matrix with a specificity ranking. This is one axis, two
 * levels — an exact verb rule wins over the plugin's ANY rule, and there is still
 * exactly one winner. The plugin axis stays exact; `"*"` as a plugin is still a
 * parse error. So a rule set can now express "gmail: 5s overlay, except DELETE which
 * needs a fingerprint" without a rule per verb, and the resolution order is a
 * documented fact rather than a computed one.
 *
 * [mode] is never an auto-approve value, because no such mode exists in this build.
 * `NOTIFY` is the floor: the quietest a request can be is a 5-second overlay, and
 * 0.12.0 removed every path that skipped a human. A rule can therefore only choose
 * *how* the question is asked — never whether it is asked at all. That is unchanged
 * by ANY: an ANY rule carries the same two modes an exact one may.
 */
data class Rule(
    val plugin: String,
    /** null means ANY — this rule covers every verb this plugin exposes. */
    val verb: Verb?,
    val mode: Mode
) {
    /** Stable key, for duplicate detection and for the log's `rule` field. */
    val key: String get() = "$plugin/${verb?.name ?: ANY}"

    /**
     * Does this rule cover one concrete request? The ONLY place ANY's meaning lives,
     * so no caller has to remember that null is a wildcard.
     */
    fun covers(plugin: String, verb: Verb): Boolean =
        this.plugin == plugin && (this.verb == null || this.verb == verb)

    companion object {
        /** The stored token for an any-verb rule. Not a [Verb]; it is a rule-axis value. */
        const val ANY = "ANY"

        /**
         * Modes a rule may carry. Since 0.12.0 this is every value [Mode] has — there
         * is no auto-approve mode to exclude. Kept as a list so the parser, the picker
         * and the tests agree on one source, and so a future mode has to be added here
         * deliberately rather than becoming rule-settable by default.
         */
        val ALLOWED_MODES: List<Mode> = listOf(Mode.DONT_DISTURB, Mode.PARANOID)
    }
}
