package com.mido.phonebridge.policy

import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject

/** A policy that cannot be read is not a policy. Never repaired, never guessed. */
class PolicyException(message: String) : Exception(message)

/**
 * The stored policy: one switch, plus exact `plugin × verb` rules.
 *
 * Read policy is fail-hard, and every failure here is a refusal to start rather
 * than a fallback: an unknown switch value, an unknown verb, a mode a rule may not
 * carry, a duplicate pair. The alternative to each is a silent behaviour change on
 * the one file that decides whether a human is asked at all.
 */
class Policy(
    val global: GlobalMode,
    val rules: List<Rule> = emptyList()
) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("global", global.name)
        .put(
            "rules",
            JSONArray().apply {
                for (r in rules) {
                    put(
                        JSONObject()
                            .put("plugin", r.plugin)
                            .put("verb", r.verb.name)
                            .put("mode", r.mode.name)
                    )
                }
            }
        )

    /**
     * A new policy with one pair set. `SECURE` is stored like any other rule rather
     * than deleted, so the settings list reads back as decisions instead of
     * exceptions — and so the two states stay distinguishable in the UI even though
     * they behave identically (see [Rule] and the plan's §7.5).
     */
    fun withRule(rule: Rule): Policy =
        Policy(global, rules.filterNot { it.key == rule.key } + rule)

    /** A new policy with the pair's rule removed. */
    fun withoutRule(plugin: String, verb: Verb): Policy =
        Policy(global, rules.filterNot { it.plugin == plugin && it.verb == verb })

    companion object {
        const val VERSION = 1

        /**
         * Strict parse. Every failure throws [PolicyException]; nothing falls back.
         *
         * The cases worth naming:
         *  - an unknown `global` value is a parse error, NOT a silent `SECURE`.
         *    Quietly re-enabling a gate the user turned off is a behaviour change
         *    they never asked for.
         *  - a rule this build cannot honour (bad plugin, bad verb, `OFF` as a mode)
         *    is a parse error, not a dropped rule: running while ignoring a decision
         *    that sits in the file is the same lie in a different shape.
         *  - a duplicate `(plugin, verb)` pair is an error, never last-wins. The same
         *    file must not resolve differently depending on write order.
         */
        fun parse(raw: String): Policy {
            val o = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw PolicyException("policy is not valid JSON: ${e.message}")
            }
            val version = o.optInt("version", -1)
            if (version != VERSION) {
                throw PolicyException("policy version $version is not $VERSION")
            }
            val rawGlobal = o.optString("global", "")
            val global = GlobalMode.values().firstOrNull { it.name == rawGlobal }
                ?: throw PolicyException(
                    "unknown global '$rawGlobal'; this build understands " +
                        GlobalMode.values().joinToString(", ")
                )
            val arr = o.optJSONArray("rules")
                ?: throw PolicyException("policy has no 'rules' array")
            val rules = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) rules.add(parseRule(arr.getJSONObject(i), i))
            val dupes = rules.groupBy { it.key }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) {
                throw PolicyException(
                    "duplicate rule(s) for ${dupes.joinToString(", ")}; a pair must have " +
                        "exactly one rule, and last-wins would make the file order-dependent"
                )
            }
            return Policy(global, rules)
        }

        private fun parseRule(o: JSONObject, index: Int): Rule {
            val where = "rule[$index]"
            val plugin = o.optString("plugin", "")
            if (plugin.isBlank()) throw PolicyException("$where has no 'plugin'")
            // A wildcard is not a plugin id, and the rule engine has no cascade to
            // give it meaning. Naming the failure keeps a hand-edited file honest.
            if (plugin == "*") {
                throw PolicyException("$where uses '*'; rules are exact, there is no cascade")
            }
            val rawVerb = o.optString("verb", "")
            val verb = Verb.values().firstOrNull { it.name == rawVerb }
                ?: throw PolicyException(
                    "$where has unknown verb '$rawVerb'; this build understands " +
                        Verb.values().joinToString(", ")
                )
            val rawMode = o.optString("mode", "")
            val mode = Mode.values().firstOrNull { it.name == rawMode }
                ?: throw PolicyException(
                    "$where has unknown mode '$rawMode'; a rule may carry " +
                        Rule.ALLOWED_MODES.joinToString(", ")
                )
            if (mode !in Rule.ALLOWED_MODES) {
                throw PolicyException(
                    "$where sets mode '$rawMode'; a rule may carry " +
                        Rule.ALLOWED_MODES.joinToString(", ") +
                        " — OFF is app-wide only (the global switch)"
                )
            }
            return Rule(plugin, verb, mode)
        }
    }
}
