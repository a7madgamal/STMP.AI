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
enum class GlobalMode { WHITELIST, PARANOID, DONT_DISTURB }

/**
 * What a mode is CALLED and what it DOES.
 *
 * **One source, because this text now appears twice.** The Home gate card states each mode's
 * consequence, and the onboarding flow explains the same three choices. Copied strings would
 * drift, and the drift would be invisible: nobody re-reads an onboarding paragraph when they
 * tweak a card label, so the flow would quietly start describing a tradeoff the app no longer
 * offered. Both screens read from here instead.
 *
 * The wording states the COST of the mode, not its name. "Paranoid" is a label; "every request
 * needs your stamp" is the thing the human is choosing between.
 */
val GlobalMode.title: String
    get() = when (this) {
        GlobalMode.WHITELIST -> "Whitelist"
        GlobalMode.PARANOID -> "Paranoid"
        GlobalMode.DONT_DISTURB -> "Don't disturb"
    }

val GlobalMode.blurb: String
    get() = when (this) {
        GlobalMode.WHITELIST ->
            "Exceptions decide. Anything without one asks for your stamp."
        GlobalMode.PARANOID ->
            "Every request needs your stamp. Nothing is quiet."
        GlobalMode.DONT_DISTURB ->
            "Small overlay. Doing nothing approves it. Tap \u2715 to deny."
    }

/**
 * The mode a request is actually gated with. Distinct from [GlobalMode] because
 * `AUTOMATIC` is a switch value that is not a mode: it resolves to one.
 */
enum class Mode { PARANOID, DONT_DISTURB }

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
 * The entire policy engine: `find` on a list, twice.
 *
 * Total and exhaustive over [GlobalMode] with **no `else` branch** — adding a value
 * is a compile error here rather than a silent default, which matters because the
 * default would be a security decision nobody made.
 *
 * `AUTOMATIC` resolves in two steps, and the order is the whole precedence rule: an
 * **exact** `plugin × verb` rule wins, and only if none exists does the plugin's
 * **ANY** rule apply. Two rules can therefore match one request, which is the one
 * thing the pre-ANY design forbade — so the order is stated here rather than
 * computed, and `PolicyTest` asserts it. Still exactly one winner, still nothing to
 * rank by specificity.
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
    GlobalMode.WHITELIST -> {
        // Exact first, then the plugin's ANY fallback. Not `firstOrNull { covers() }`,
        // which would let list ORDER decide between an exact rule and an ANY rule.
        val rule = rules.find { it.plugin == plugin && it.verb == verb }
            ?: rules.find { it.plugin == plugin && it.verb == null }
        if (rule == null) ModeResolution(Mode.PARANOID, ModeSource.DEFAULT, null)
        else ModeResolution(rule.mode, ModeSource.RULE, rule)
    }
    GlobalMode.PARANOID -> ModeResolution(Mode.PARANOID, ModeSource.GLOBAL, null)
    GlobalMode.DONT_DISTURB -> ModeResolution(Mode.DONT_DISTURB, ModeSource.GLOBAL, null)
}

/** Thin wrapper for callers that only need the mode. One decision path, not two. */
fun resolveMode(
    global: GlobalMode,
    rules: List<Rule>,
    plugin: String,
    verb: Verb
): Mode = resolve(global, rules, plugin, verb).mode

/**
 * The mode an EXCEPTION for a pair should set, given the mode the request resolved to.
 *
 * The exception is the OTHER mode: a request that was about to demand a fingerprint
 * becomes a 5-second overlay, and a quiet pair can be made loud. That is what "an
 * exception to how this pair behaves" means, and it is why the modal's third button
 * needs no mode picker — there is exactly one sensible value, and this computes it.
 *
 * Total and exhaustive with **no `else`**: adding a third [Mode] becomes a compile
 * error here rather than a silent default, which matters because this decides what a
 * one-tap policy write records.
 *
 * It can never return an auto-approve value, because no such [Mode] exists — `NOTIFY`
 * is the floor. So an exception can only choose *how* a pair is asked about, never
 * whether it is asked at all. That invariant is the whole reason the button is safe.
 */
fun exceptionModeFor(resolved: Mode): Mode = when (resolved) {
    Mode.PARANOID -> Mode.DONT_DISTURB
    Mode.DONT_DISTURB -> Mode.PARANOID
}
