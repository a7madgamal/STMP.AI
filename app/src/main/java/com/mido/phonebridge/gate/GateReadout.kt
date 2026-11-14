package com.mido.phonebridge.gate

import com.mido.phonebridge.ApprovalGate

/**
 * Which value is the gate's headline (D10): the verbatim command or primary payload, promoted to
 * the dark readout as the largest text on the screen. Pure.
 */
object GateReadout {
    data class Headline(
        /** The etched label above the readout (WP03a section 7). */
        val label: String,
        /** The verbatim value, or the request line. */
        val text: String,
        /** The row style was CODE. */
        val isCommand: Boolean,
        /** Muted, non-selectable line(s) above the text. */
        val prompt: String?,
        /** Index in the input rows that was promoted (excluded from the rows panel). */
        val rowIndex: Int?,
    )

    /** Route key → the row labels to promote, in preference order (WP03a section 11). */
    val HEADLINES: Map<String, List<String>> = mapOf(
        "POST /ssh/{name}" to listOf("COMMAND"),
        "POST /ssh/{name}/detach" to listOf("COMMAND"),
        "POST /gmail/messages/send" to listOf("TO"),
        "POST /gmail/messages/{id}/trash" to listOf("SUBJECT", "MESSAGE ID"),
        "POST /gmail/messages/{id}/modify" to listOf("ADD LABELS", "REMOVE LABELS"),
        "GET /drive/files/{id}/content" to listOf("DOWNLOAD", "SAVE TO PHONE"),
        "POST /drive/files/{id}/trash" to listOf("TRASH (recoverable 30 days)"),
        "POST /drive/files" to listOf("CREATE FOLDER"),
        "POST /drive/files/{id}/rename" to listOf("TO"),
        "POST /drive/files/{id}/move" to listOf("TO"),
        "POST /drive/files/upload" to listOf("UPLOAD"),
        "POST /calendar/events" to listOf("TITLE"),
        "POST /calendar/events/{id}" to listOf("TITLE", "EVENT ID"),
        "DELETE /calendar/events/{id}" to listOf("TITLE", "EVENT ID"),
        "POST /calendar/events/{id}/move" to listOf("MOVE TO"),
        "POST /calendar/quickadd" to listOf("TEXT"),
        "GET /contacts" to listOf("SEARCH"),
        "POST /notifications/{id}/action" to listOf("REPLY", "ACTION"),
        "POST /notifications/{id}/dismiss" to listOf("TITLE", "DISMISS"),
    )

    private val SECRETS_CALL = Regex("""^POST /secrets/[^/]+/call$""")
    private val SECRETS_LABELS = listOf("TARGET")
    private val USER_AT_HOST = Regex("""^(.+@[^:]+)(?::\d+)?$""")

    fun headline(routeKey: String, requestLine: String, rows: List<ApprovalGate.Row>): Headline {
        val labels = HEADLINES[routeKey] ?: if (SECRETS_CALL.matches(routeKey)) SECRETS_LABELS else emptyList()
        val index = labels.asSequence()
            .map { label -> rows.indexOfFirst { it.label == label } }
            .firstOrNull { it >= 0 }
            ?: rows.indexOfFirst { it.style == ApprovalGate.Row.Style.CODE }.takeIf { it >= 0 }
            ?: return Headline(GateCopy.READOUT_REQUEST, requestLine, isCommand = false, prompt = null, rowIndex = null)
        val row = rows[index]
        val isCommand = row.style == ApprovalGate.Row.Style.CODE
        val userHost = if (isCommand) {
            rows.firstOrNull { it.label == "HOST" }?.let { USER_AT_HOST.matchEntire(it.value)?.groupValues?.get(1) }
        } else null
        val prompt = if (userHost != null) "$requestLine\n$userHost $" else requestLine
        return Headline(GateCopy.readoutLabel(row.label), row.value, isCommand, prompt, index)
    }
}
