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
 * [mode] is never an auto-approve value, because no such mode exists in this build.
 * `NOTIFY` is the floor: the quietest a request can be is a 5-second overlay, and
 * 0.12.0 removed every path that skipped a human. A rule can therefore only choose
 * *how* the question is asked — never whether it is asked at all.
 */
data class Rule(
    val plugin: String,
    val verb: Verb,
    val mode: Mode
) {
    /** Stable key, for duplicate detection and for the log's `rule` field. */
    val key: String get() = "$plugin/${verb.name}"

    companion object {
        /**
         * Modes a rule may carry. Since 0.12.0 this is every value [Mode] has — there
         * is no auto-approve mode to exclude. Kept as a list so the parser, the picker
         * and the tests agree on one source, and so a future mode has to be added here
         * deliberately rather than becoming rule-settable by default.
         */
        val ALLOWED_MODES: List<Mode> = listOf(Mode.NOTIFY, Mode.SECURE)
    }
}
