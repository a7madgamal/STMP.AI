package com.mido.phonebridge.gate

import com.mido.phonebridge.ApprovalGate.ApproveGesture
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every user-visible string of the approval gate (WP03a section 7), in one place. */
object GateCopy {

    // --- caller ----------------------------------------------------------------------------------

    /** Who is asking, as far as the caller registry knows. */
    enum class CallerKind { VERIFIED, THIS_PHONE, UNVERIFIED, BLOCKED }

    fun callerState(kind: CallerKind): String = when (kind) {
        CallerKind.VERIFIED -> "From · verified caller"
        CallerKind.THIS_PHONE -> "From · this phone"
        CallerKind.UNVERIFIED -> "From · unverified caller"
        CallerKind.BLOCKED -> "From · blocked caller"
    }

    fun isLoopback(remote: String): Boolean = remote == "127.0.0.1" || remote == "::1" || remote == "localhost"

    /** `<remote>` + ` · as <account>` when there is an account worth naming. */
    fun addressLine(remote: String, account: String): String =
        if (account.isNotBlank() && account != "—") "$remote · as $account" else remote

    fun windowTitle(requester: String): String = "Approve request from $requester"

    // --- readout ---------------------------------------------------------------------------------

    const val READOUT_REQUEST = "Request"
    fun readoutLabel(rowLabel: String): String = "$rowLabel · verbatim"

    // --- AI --------------------------------------------------------------------------------------

    const val AI_LABEL = "AI summary · may be wrong"
    const val AI_UNAVAILABLE = "AI summary · unavailable"
    const val AI_LOADING_REDUCED = "summarising…"
    fun aiReadyLabel(risk: Risk): String = "$AI_LABEL · ${risk.name}"
    fun aiFailed(detail: String): String = "Could not summarise — $detail. You can still decide."
    fun aiAnnounceReady(description: String): String = "AI summary: $description"
    const val AI_ANNOUNCE_FAILED = "AI summary unavailable"

    /**
     * Words cycled while the model works, so the wait reads as thinking rather than hanging
     * (Mido, 2026-10-19). Honest about being indeterminate: there is no token stream to measure.
     */
    val LOADING_WORDS = listOf("thinking", "wondering", "considering", "reading", "summarising", "weighing it up")
    const val DOTS_MAX = 3
    fun loading(wordIndex: Int, dots: Int): String =
        LOADING_WORDS[wordIndex % LOADING_WORDS.size] + ".".repeat(dots.coerceIn(0, DOTS_MAX))

    // --- arming and timer ------------------------------------------------------------------------

    const val ARM_SUMMARY_PENDING = "waiting for the AI summary"
    fun armGuard(seconds: Long): String = "ready in ${seconds.coerceAtLeast(1)} s"
    fun armArmed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "auto-deny in %d:%02d".format(Locale.US, s / 60, s % 60)
    }
    fun trailing(requestId: Long): String = "REQ $requestId"
    fun queue(behind: Int): String = "$behind waiting behind this one"

    // --- rows ------------------------------------------------------------------------------------

    const val ROWS_TITLE = "Request details"
    const val NOTIFICATION_TITLE = "On this notification"
    const val FILTER = "FILTER"
    const val REPLY = "REPLY"
    const val OTHER_ACTIONS = "OTHER ACTIONS"
    const val MESSAGES = "MESSAGES"
    fun messages(n: Int): String = "$n in this conversation"
    const val FALLBACK_SOURCE = "SOURCE"
    const val FALLBACK_ENDPOINT = "ENDPOINT"
    const val FALLBACK_SUMMARY = "SUMMARY"

    // --- exception -------------------------------------------------------------------------------

    fun exceptionKey(label: String, on: Boolean): String = if (on) "$label · ON" else label

    /** D21: a plugin is named by its display name, never its id. */
    fun pluginName(id: String): String = PluginRegistry.all().firstOrNull { it.id == id }?.displayName ?: id

    fun modeName(mode: Mode): String = when (mode) {
        Mode.DONT_DISTURB -> "Don't disturb"
        Mode.PARANOID -> "Paranoid"
    }

    /** D64: user-facing copy says "exception", never "rule". */
    fun exceptionCaption(gesture: ApproveGesture, on: Boolean, rule: Rule): String {
        val plugin = pluginName(rule.plugin)
        val verb = rule.verb?.name?.lowercase() ?: "any verb"
        val mode = modeName(rule.mode)
        return when {
            gesture != ApproveGesture.HOLD -> "Approves this request and sets $plugin · $verb to $mode from now on."
            on -> "The exception is saved when you hold the key: $plugin · $verb → $mode."
            else -> "Tap to also save this exception, then hold the key: $plugin · $verb → $mode."
        }
    }

    // --- password --------------------------------------------------------------------------------

    const val PASSWORD_LABEL = "Sudo password"
    const val PASSWORD_HINT = "sudo password"
    const val PASSWORD_CAPTION = "Runs as root. The password goes to sudo's stdin on this phone, never over the network."

    // --- biometric -------------------------------------------------------------------------------

    const val BIO_UNAVAILABLE_TITLE = "Approve is unavailable"
    const val BIO_NOT_ENROLLED = "No fingerprint is enrolled. Add one in Android Settings, then come back — Deny still works."
    const val BIO_LOCKED_TITLE = "Fingerprint locked for now"
    const val BIO_LOCKED = "Too many attempts. It clears by itself; this screen checks again and Approve comes back on its own."
    const val BIO_VAULT_BROKEN = "The vault key is invalid or was reset (a new fingerprint can do this). " +
        "Reset the vault in Settings → Secret vault and add the secrets again. Deny still works."
    const val BIO_SUBTITLE_FALLBACK = "STMP"
    const val APPROVE_WITH_FINGERPRINT = "Approve, then confirm with your fingerprint"

    // --- keys and expander -----------------------------------------------------------------------

    const val DENY = "DENY"
    const val APPROVE = "APPROVE"
    const val HOLD_CAPTION = "TO APPROVE"
    const val RAW_REQUEST = "Raw request"
    const val SHOW = "SHOW"
    const val HIDE = "HIDE"
    fun raw(endpoint: String, detail: String?): String = "endpoint: $endpoint\n\n${detail ?: "(no detail)"}"

    // --- punch -----------------------------------------------------------------------------------

    fun punchMeta(requestId: Long, decidedAtMs: Long): String =
        "№ $requestId · ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(decidedAtMs))}"

    // --- contacts permission ---------------------------------------------------------------------

    const val CONTACTS_TITLE = "Contacts permission needed"
    const val CONTACTS_CAN_ASK = "Without it this request cannot run: the contacts search would tell your agent " +
        "that contacts are unavailable.\n\nTap Approve to be asked again."
    const val CONTACTS_CANNOT_ASK = "Android will not show that prompt again.\n\nTurn on Contacts in Android " +
        "Settings → Apps → STMP → Permissions, then approve this request again."
    const val CONTACTS_OK = "OK"
    const val CONTACTS_OPEN_SETTINGS = "Open Settings"
    const val CONTACTS_CANCEL = "Cancel"
}
