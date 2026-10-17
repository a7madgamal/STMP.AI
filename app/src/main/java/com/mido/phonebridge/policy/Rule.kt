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
 * [mode] is never [Mode.OFF]: auto-approve is app-wide only, reachable only through
 * the global switch with its confirmation and its red banner. A per-pair `OFF` would
 * make "no human, no fingerprint" settable twelve times, and setting it by accident
 * once is enough.
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
         * Modes a rule may carry. `OFF` is excluded on purpose — see the class doc.
         * Kept as a list so the parser, the picker and the tests agree on one source.
         */
        val ALLOWED_MODES: List<Mode> = listOf(Mode.NOTIFY, Mode.SECURE)
    }
}
