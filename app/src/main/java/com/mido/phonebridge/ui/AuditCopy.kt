package com.mido.phonebridge.ui

import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.core.Verb
import java.util.Locale

/**
 * The only place a stored audit string becomes display text (D21). Stored values never change;
 * what a person reads does.
 */
object AuditCopy {
    enum class Tone { OK, NEUTRAL, CAUTION, NOISE }

    data class OutcomeLook(val word: String, val tone: Tone, val allowsChip: Boolean)

    fun outcome(o: AuditOutcome): OutcomeLook = when (o) {
        AuditOutcome.APPROVED -> OutcomeLook("Approved", Tone.OK, true)
        AuditOutcome.NOTIFY_APPROVED -> OutcomeLook("Approved by silence", Tone.OK, true)
        AuditOutcome.DENIED -> OutcomeLook("Denied", Tone.NEUTRAL, true)
        AuditOutcome.NOTIFY_REJECTED -> OutcomeLook("Denied from the card", Tone.NEUTRAL, true)
        AuditOutcome.TIMEOUT -> OutcomeLook("Timed out", Tone.CAUTION, true)
        AuditOutcome.DENIED_UNATTENDED -> OutcomeLook("Denied, phone unattended", Tone.NEUTRAL, true)
        AuditOutcome.HANDLER_FAILED -> OutcomeLook("Failed", Tone.CAUTION, true)
        AuditOutcome.CLIENT_GONE -> OutcomeLook("Caller left", Tone.NEUTRAL, true)
        AuditOutcome.POLICY_CHANGED -> OutcomeLook("Gate changed", Tone.NEUTRAL, false)
        AuditOutcome.REJECTED_INVALID -> OutcomeLook("Bad request", Tone.NOISE, false)
        AuditOutcome.UNROUTED -> OutcomeLook("Unknown path", Tone.NOISE, false)
    }

    /** The outcomes the log's "Denied" filter covers: every way a request was refused (D50). */
    fun isDenial(o: AuditOutcome): Boolean =
        o == AuditOutcome.DENIED || o == AuditOutcome.NOTIFY_REJECTED || o == AuditOutcome.DENIED_UNATTENDED

    /**
     * The one summary line for a record, shared by Home's tape and the request log. A gate change's
     * stored text is turned into words; any other summary is shown as stored; a blank one falls back
     * to [label].
     */
    fun summary(outcome: AuditOutcome, raw: String, label: String = ""): String {
        val text = if (outcome == AuditOutcome.POLICY_CHANGED) policySummary(raw) else raw
        return text.ifBlank { label }
    }

    fun modeWords(raw: String): String = when (raw.trim().uppercase()) {
        "SECURE", "PARANOID" -> "Paranoid"
        "NOTIFY", "DONT_DISTURB" -> "Don't disturb"
        "WHITELIST" -> "Whitelist"
        "" -> "Unknown"
        else -> readable(raw)
    }

    fun sourceWords(s: String): String = when (s.trim().uppercase()) {
        "DEFAULT" -> "No exception applied, so the default (ask for your stamp)"
        "GLOBAL" -> "The gate mode decided"
        "RULE" -> "An exception decided"
        else -> modeWords(s)
    }

    /** `gmail/EDIT` to `Gmail · edit`; `gmail/ANY` to `Gmail · any action`. */
    fun ruleWords(key: String): String {
        val slash = key.indexOf('/')
        if (slash < 0) return PluginLabel.of(key)
        val plugin = PluginLabel.of(key.substring(0, slash))
        val verb = verbOf(key.substring(slash + 1))
        return "$plugin · ${verbWords(verb)}"
    }

    fun verbWords(v: Verb?): String = when (v) {
        Verb.READ -> "read"
        Verb.WRITE -> "write"
        Verb.EDIT -> "edit"
        Verb.DELETE -> "delete"
        Verb.EXECUTE -> "execute"
        null -> "any action"
    }

    fun verbOf(raw: String): Verb? = Verb.values().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    fun originWords(raw: String): String? = when (raw.trim().uppercase()) {
        "" -> null
        "LOCAL" -> "This phone"
        "TAILNET" -> "Tailnet"
        "UNKNOWN" -> "Unknown address"
        else -> readable(raw)
    }

    fun duration(ms: Long, locale: Locale): String? = when {
        ms <= 0 -> null
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> String.format(locale, "%.1f s", ms / 1000.0)
        else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
    }

    fun bytes(n: Int, locale: Locale): String = String.format(locale, "%,d bytes", n)

    private val FINGERPRINT = Regex("""^fingerprint: (on|off) \((.+)\)$""")
    private val SWITCH = Regex("""^switch: (\S+) → (\S+) \((.+)\)$""")
    private val RULE = Regex("""^rule: ([^/\s]+)/(\S+) (\S+) → (\S+) \((.+?); switch (\S+)\)$""")

    /** Display text for a POLICY_CHANGED record's stored summary; anything unrecognised is unchanged. */
    fun policySummary(raw: String): String {
        FINGERPRINT.matchEntire(raw)?.let { m ->
            return "Fingerprint for approvals: ${m.groupValues[1]}"
        }
        SWITCH.matchEntire(raw)?.let { m ->
            val (from, to, src) = m.destructured
            val by = sourceWords2(src)
            // A switch record whose two sides agree is a settings save that left the mode alone.
            return if (from == to) "Settings saved, gate mode stays ${modeWords(from)}"
            else "Gate mode ${modeWords(from)} → ${modeWords(to)}, changed in $by"
        }
        RULE.matchEntire(raw)?.let { m ->
            val (plugin, verb, from, to, src) = m.destructured
            return "Exception ${ruleWords("$plugin/$verb")}: ${ruleMode(from)} → ${ruleMode(to)}, " +
                "changed in ${sourceWords2(src)}"
        }
        return raw
    }

    private fun ruleMode(raw: String) = if (raw == "default") "Default (ask)" else modeWords(raw)

    private fun sourceWords2(src: String): String = when (src.trim().lowercase()) {
        "settings", "options" -> "Settings"
        "modal" -> "the approval screen"
        "home" -> "Home"
        else -> src
    }

    private fun readable(raw: String): String =
        raw.trim().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
