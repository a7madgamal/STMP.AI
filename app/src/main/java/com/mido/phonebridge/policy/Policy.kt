package com.mido.phonebridge.policy

import org.json.JSONArray
import org.json.JSONObject

/** A policy that cannot be read is not a policy. Never repaired, never guessed. */
class PolicyException(message: String) : Exception(message)

/**
 * The stored policy: one switch, and a `rules` array that R2 always writes empty.
 *
 * The array exists now on purpose. R3 fills it, and a file written by R2 has to be
 * readable by R3 without a migration step — a format change on a security file is
 * a chance to silently reinterpret someone's saved decision.
 */
class Policy(val global: GlobalMode) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("global", global.name)
        .put("rules", JSONArray())

    companion object {
        const val VERSION = 1

        /**
         * Strict parse. Every failure throws [PolicyException]; nothing falls back.
         *
         * The two cases worth naming:
         *  - an unknown `global` (e.g. `"AUTOMATIC"` written by a future build) is a
         *    parse error, NOT a silent `SECURE`. Downgrading the app must fail loudly,
         *    because quietly re-enabling a gate the user turned off is a behaviour
         *    change they never asked for.
         *  - a non-empty `rules` array is refused by this build. It cannot honour a
         *    rule, so running on the file would mean ignoring a decision that is
         *    sitting right there in the file.
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
            val rules = o.optJSONArray("rules")
                ?: throw PolicyException("policy has no 'rules' array")
            if (rules.length() != 0) {
                throw PolicyException(
                    "policy has ${rules.length()} rule(s) but this build has no rule support; " +
                        "refusing to run on a policy it cannot honour"
                )
            }
            return Policy(global)
        }
    }
}
