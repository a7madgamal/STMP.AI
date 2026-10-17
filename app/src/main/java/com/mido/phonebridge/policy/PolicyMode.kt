package com.mido.phonebridge.policy

import com.mido.phonebridge.core.Verb

/**
 * The one switch on the home screen.
 *
 * `AUTOMATIC` is the only value that reads the rule table. The other two force a
 * single mode on every request.
 *
 * There is deliberately no `OFF` (auto-approve) value. It existed from R2 and was
 * removed in 0.12.0: `NOTIFY` is the floor. The quietest thing a request can do is
 * show a 5-second overlay, and nothing in this app can run a request without asking
 * a human first. `SECURE` is stricter than `NOTIFY`, so the switch can only move
 * *toward* asking more — never away from it.
 */
enum class GlobalMode { AUTOMATIC, SECURE, NOTIFY }

/**
 * The mode a request is actually gated with. Distinct from [GlobalMode] because
 * `AUTOMATIC` is a switch value that is not a mode: it resolves to one.
 */
enum class Mode { SECURE, NOTIFY }

/**
 * Why a mode was chosen. Deliberately a policy-local enum rather than the audit's
 * `DecisionSource`: the audit already depends on this package (PolicyAudit reads
 * GlobalMode), and a shared enum would make the dependency circular. BridgeService
 * maps one to the other at the one place that writes a record.
 */
enum class ModeSource { GLOBAL, RULE, DEFAULT }

/**
 * A resolved decision: the mode, why, and the exact rule if one fired.
 *
 * [rule] is non-null only when [source] is [ModeSource.RULE]. That pairing is the
 * whole explanation a reader needs — "NOTIFY because gmail/DELETE says so" — and it
 * is what lets the log answer "why did this run unapproved?" without inference.
 */
class ModeResolution(
    val mode: Mode,
    val source: ModeSource,
    val rule: Rule?
) {
    /** `plugin/VERB` of the rule that fired, or null. What the record stores. */
    val ruleKey: String? get() = rule?.key
}

/**
 * The entire policy engine: `find` on a list.
 *
 * Total and exhaustive over [GlobalMode] with **no `else` branch** — adding a value
 * is a compile error here rather than a silent default, which matters because the
 * default would be a security decision nobody made.
 *
 * `AUTOMATIC` with no matching rule → `SECURE`: silence means "ask me". Nothing
 * auto-approves by accident, which is the invariant every other choice leans on.
 */
fun resolve(
    global: GlobalMode,
    rules: List<Rule>,
    plugin: String,
    verb: Verb
): ModeResolution = when (global) {
    GlobalMode.AUTOMATIC -> {
        val rule = rules.find { it.plugin == plugin && it.verb == verb }
        if (rule == null) ModeResolution(Mode.SECURE, ModeSource.DEFAULT, null)
        else ModeResolution(rule.mode, ModeSource.RULE, rule)
    }
    GlobalMode.SECURE -> ModeResolution(Mode.SECURE, ModeSource.GLOBAL, null)
    GlobalMode.NOTIFY -> ModeResolution(Mode.NOTIFY, ModeSource.GLOBAL, null)
}

/** Thin wrapper for callers that only need the mode. One decision path, not two. */
fun resolveMode(
    global: GlobalMode,
    rules: List<Rule>,
    plugin: String,
    verb: Verb
): Mode = resolve(global, rules, plugin, verb).mode
