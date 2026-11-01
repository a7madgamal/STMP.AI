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
    val rules: List<Rule> = emptyList(),
    /**
     * Does a `SECURE` approval require a fingerprint?
     *
     * **A preference, not a security decision, and it is stored here anyway** (Mido,
     * 2026-10-20: *"single file for now"*). The distinction matters for PARSING, not for
     * location: this field defaults rather than throwing, so a garbled value can never stop
     * the bridge the way a garbled `global` does.
     *
     * **Default OFF** (Mido, 2026-10-20). A fresh install approves a `SECURE` modal with a
     * plain tap. The vault is unaffected — its key is `setUserAuthenticationRequired(true)`, so
     * no setting can remove its fingerprint, and `lockOnLoad` still unlocks it at app start. The
     * only thing this switches is whether the MODAL binds APPROVE to a biometric.
     *
     * `NOTIFY` already works this way and always has: it never touched biometrics or the vault,
     * which is why this needs no new consent model invented for it.
     */
    val fingerprintForModals: Boolean = false,
    /**
     * Which summariser to use, if any.
     *
     * **Default OFF** (Mido, 2026-10-20: *"off means every damn AI feature completely off... free
     * memory and just bypass any AI related function"*). With OFF, [AiGateway] never loads, never
     * provisions and never summarises, and readiness is satisfied unconditionally, so no route
     * answers `503 model_not_ready`.
     *
     * **Three values, not a boolean** — see [AiBackend]. EXTERNAL is a different trust boundary
     * and a different failure mode from LOCAL, and a boolean cannot say which is meant.
     *
     * Parsed leniently for the same reason as [fingerprintForModals]: a preference must never be
     * able to refuse to start the server. An unreadable value falls back to OFF, which is the
     * safe direction — it frees memory rather than silently reaching out to a network box.
     */
    val aiBackend: AiBackend = AiBackend.OFF,
    /**
     * Where the EXTERNAL model server lives, e.g. `http://192.168.1.9:11434`.
     *
     * **A runtime preference, not a build constant.** It was `BuildConfig.EXTERNAL_AI_URL`
     * only, which meant choosing EXTERNAL in Options produced a backend that could never be
     * pointed anywhere — the setting said "use a server" and then refused because no server
     * had been compiled in. A toggle whose ON state cannot be configured is a dead control.
     *
     * Empty means "not configured", which is a real state: `AiGateway` refuses requests and
     * names the cause rather than silently reaching for a default. There is deliberately no
     * fallback address — a compiled-in default would mean "not set up" quietly sent requests
     * to whoever built the APK.
     *
     * Lenient parse like the other preferences: a malformed value is dropped and logged, never
     * a refusal to start. Stored with its scheme so the caller cannot guess one wrong.
     */
    val aiExternalUrl: String = "",
    /**
     * Does the bridge start itself — after a reboot, after an app update, and on app load?
     *
     * **Default ON** (Mido, 2026-10-25). The whole point of the app is a server that is
     * reachable; a bridge that is only up because someone opened the app is not a bridge, it
     * is a manual daemon. The phone rebooted on 2026-10-25 and nothing restarted it, which is
     * exactly the state this default prevents.
     *
     * **One toggle, one meaning: "the server starts itself."** It covers BOTH entry points —
     * the boot receiver and `MainActivity.maybeAutoStartServer()` — deliberately. A toggle
     * that stopped the boot start but still started on every app load would be a setting whose
     * OFF state does not mean off.
     *
     * Lenient like the other preferences: a garbled value falls back to ON and is logged, never
     * a refusal to start. The failure mode of the fallback is a server that runs when the user
     * did not ask for it — visible and harmless. The failure mode of throwing would be a dead
     * bridge over a preference.
     */
    val autoStartOnBoot: Boolean = true,
    /**
     * Preferences that were present but unreadable, for the caller to LOG.
     *
     * **Carried rather than logged here, and that is a purity constraint, not tidiness.**
     * `Policy` must be JVM-testable: the suite has no `android.util.Log`, so a `Debug.log` call
     * inside `parse` turns every preference test into `RuntimeException: Method i in
     * android.util.Log not mocked` — which is how this shipped broken for one build. Reporting the
     * problem as DATA keeps the decision pure and puts the side effect where Android actually
     * exists, in [PolicyStore].
     *
     * Not serialized: it describes one read of one file, not a stored fact.
     */
    val preferenceWarnings: List<String> = emptyList()
) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("global", global.name)
        // Preferences, written with the security fields so one file holds the whole choice.
        // Their ABSENCE is meaningful: a policy written before these existed parses with the
        // defaults, which is why VERSION does not move (see [parse]).
        .put("fingerprintForModals", fingerprintForModals)
        .put("aiBackend", aiBackend.name)
        .put("aiExternalUrl", aiExternalUrl)
        .put("autoStartOnBoot", autoStartOnBoot)
        .put(
            "rules",
            JSONArray().apply {
                for (r in rules) {
                    put(
                        JSONObject()
                            .put("plugin", r.plugin)
                            // An ANY rule stores the literal token, so the file says what
                            // it means and a missing key is never a silent wildcard.
                            .put("verb", r.verb?.name ?: Rule.ANY)
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
        Policy(
            global = global,
            rules = rules.filterNot { it.key == rule.key } + rule,
            // EVERY preference carried across EXPLICITLY, and always by name. `Policy(global,
            // rules)` alone compiles fine and resets every sibling field to its default — that
            // exact mistake is already recorded in this project (`applyGate` wiped the rule table
            // the same way), and here it would silently turn the AI back off, or a fingerprint
            // requirement back on, or stop the bridge from restarting after a reboot, every time
            // someone added a rule.
            fingerprintForModals = fingerprintForModals,
            aiBackend = aiBackend,
            aiExternalUrl = aiExternalUrl,
            autoStartOnBoot = autoStartOnBoot
        )

    /** A new policy with the pair's rule removed. [verb] null removes the ANY rule. */
    fun withoutRule(plugin: String, verb: Verb?): Policy =
        Policy(
            global = global,
            rules = rules.filterNot { it.plugin == plugin && it.verb == verb },
            fingerprintForModals = fingerprintForModals,
            aiBackend = aiBackend,
            aiExternalUrl = aiExternalUrl,
            autoStartOnBoot = autoStartOnBoot
        )

    /** A new policy with the gate switch changed, preserving rules and preferences. */
    fun withGlobal(mode: GlobalMode): Policy =
        Policy(
            global = mode,
            rules = rules,
            fingerprintForModals = fingerprintForModals,
            aiBackend = aiBackend,
            aiExternalUrl = aiExternalUrl,
            autoStartOnBoot = autoStartOnBoot
        )

    /** A new policy with the modal-fingerprint preference changed. */
    fun withFingerprintForModals(enabled: Boolean): Policy =
        Policy(
            global = global,
            rules = rules,
            fingerprintForModals = enabled,
            aiBackend = aiBackend,
            aiExternalUrl = aiExternalUrl,
            autoStartOnBoot = autoStartOnBoot
        )

    /** A new policy with the AI backend changed. */
    fun withAiBackend(backend: AiBackend): Policy =
        Policy(
            global = global,
            rules = rules,
            fingerprintForModals = fingerprintForModals,
            aiBackend = backend,
            aiExternalUrl = aiExternalUrl,
            autoStartOnBoot = autoStartOnBoot
        )

    /** A new policy with the external AI endpoint changed. Empty means "not configured". */
    fun withAiExternalUrl(url: String): Policy =
        Policy(
            global = global,
            rules = rules,
            fingerprintForModals = fingerprintForModals,
            aiBackend = aiBackend,
            aiExternalUrl = url,
            autoStartOnBoot = autoStartOnBoot
        )

    /** A new policy with the boot auto-start preference changed. */
    fun withAutoStartOnBoot(enabled: Boolean): Policy =
        Policy(
            global = global,
            rules = rules,
            fingerprintForModals = fingerprintForModals,
            aiBackend = aiBackend,
            aiExternalUrl = aiExternalUrl,
            autoStartOnBoot = enabled
        )

    companion object {
        const val VERSION = 1

        /**
         * Strict parse. Every failure throws [PolicyException]; nothing falls back.
         *
         * The cases worth naming:
         *  - an unknown `global` value is a parse error, NOT a silent `SECURE`.
         *    Quietly re-enabling a gate the user turned off is a behaviour change
         *    they never asked for.
         *  - a rule this build cannot honour (bad plugin, bad verb, an unknown mode)
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
            // No migration, no special case: `OFF` was a <=0.11.0 switch value and is now
            // just another unrecognised string. The one-way "OFF" -> NOTIFY load that
            // 0.12.0 carried was itself removed once we agreed there is no backward
            // compatibility while the app is not live. An unknown global stays a parse
            // error, exactly like every other unreadable value in this file.
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
            val warnings = ArrayList<String>(3)
            val fingerprint = lenientFlag(o, "fingerprintForModals", false, warnings)
            val backend = lenientBackend(o, "aiBackend", AiBackend.OFF, warnings)
            val externalUrl = lenientString(o, "aiExternalUrl", warnings)
            // Absent means ON. A file written before this preference existed therefore gets the
            // new behaviour without a migration step — which is why VERSION does not move.
            val autoStart = lenientFlag(o, "autoStartOnBoot", true, warnings)
            return Policy(
                global = global,
                rules = rules,
                fingerprintForModals = fingerprint,
                aiBackend = backend,
                aiExternalUrl = externalUrl,
                autoStartOnBoot = autoStart,
                preferenceWarnings = warnings
            )
        }

        /**
         * Read a PREFERENCE flag: absent means [default], garbage means [default] plus a log.
         *
         * **Deliberately not strict, and deliberately not silent either.** These two fields do
         * not decide whether a human is asked, so an unreadable value must not refuse to start
         * the bridge the way a bad `global` does — a preference is not worth a dead server. But
         * a silent fallback would leave someone believing a toggle took effect when it did not,
         * so a bad value is recorded loudly once, in the same ring `/debug` reports.
         *
         * `has()` + `isBoolean()` rather than `optBoolean`: `optBoolean` coerces a string
         * `"yes"` to false without telling anyone, which is exactly the silent fallback this
         * project forbids. A wrong TYPE is as much a bug as a wrong value.
         */
        private fun lenientFlag(
            o: JSONObject,
            key: String,
            default: Boolean,
            warnings: MutableList<String>,
        ): Boolean {
            if (!o.has(key)) return default
            val value = o.opt(key)
            if (value is Boolean) return value
            // Pure: record the problem, do not log it. `PolicyStore` logs what it finds in
            // `preferenceWarnings`, which is the only side effect this needs and the only place
            // it can legally happen — `android.util.Log` does not exist on the JVM.
            warnings.add(
                "'$key' is not a boolean (${value?.javaClass?.simpleName ?: "null"}); " +
                    "using default $default"
            )
            return default
        }

        /**
         * Read a PREFERENCE string: absent or garbage means "" plus a log.
         *
         * Same contract as [lenientFlag] and [lenientBackend] — a preference must never be able
         * to refuse to start the server, and a silent fallback would leave someone believing a
         * value took effect when it did not. Garbage becomes EMPTY rather than a guess: for an
         * endpoint, "" means "not configured", which refuses and says so, while any guessed
         * address would send requests somewhere nobody chose.
         */
        private fun lenientString(
            o: JSONObject,
            key: String,
            warnings: MutableList<String>,
        ): String {
            if (!o.has(key)) return ""
            val raw = o.opt(key)
            if (raw !is String) {
                warnings.add(
                    "'$key' is not a string (${raw?.javaClass?.simpleName ?: "null"}); using empty"
                )
                return ""
            }
            return raw.trim()
        }

        private fun lenientBackend(
            o: JSONObject,
            key: String,
            default: AiBackend,
            warnings: MutableList<String>,
        ): AiBackend {
            if (!o.has(key)) return default
            val raw = o.opt(key)
            if (raw !is String) {
                warnings.add(
                    "'$key' is not a string (${raw?.javaClass?.simpleName ?: "null"}); " +
                        "using default ${default.name}"
                )
                return default
            }
            // An unknown NAME falls back rather than throwing, and the fallback is OFF: the
            // direction that frees memory and never reaches a network box. A typo must not be
            // able to silently select EXTERNAL, so this is a whitelist, not a guess.
            return AiBackend.values().firstOrNull { it.name == raw } ?: run {
                warnings.add(
                    "'$key' has unknown value '$raw'; this build understands " +
                        AiBackend.values().joinToString(", ") + " — using ${default.name}"
                )
                default
            }
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
            // "ANY" is the any-verb token; a real Verb name is an exact rule. Anything
            // else is a parse error, and a MISSING verb is an error too — never a
            // silent wildcard, which would turn a truncated file into broad rules.
            val verb: Verb? = when {
                rawVerb == Rule.ANY -> null
                else -> Verb.values().firstOrNull { it.name == rawVerb }
                    ?: throw PolicyException(
                        "$where has unknown verb '$rawVerb'; this build understands " +
                            (Verb.values().joinToString(", ") + ", or " + Rule.ANY + " for any verb")
                    )
            }
            val rawMode = o.optString("mode", "")
            val mode = Mode.values().firstOrNull { it.name == rawMode }
                ?: throw PolicyException(
                    "$where has unknown mode '$rawMode'; a rule may carry " +
                        Rule.ALLOWED_MODES.joinToString(", ")
                )
            if (mode !in Rule.ALLOWED_MODES) {
                // Unreachable while ALLOWED_MODES covers every Mode value, and kept as
                // the guard for the day one is added without being classified here.
                throw PolicyException(
                    "$where sets mode '$rawMode'; a rule may carry " +
                        Rule.ALLOWED_MODES.joinToString(", ")
                )
            }
            return Rule(plugin, verb, mode)
        }
    }
}
