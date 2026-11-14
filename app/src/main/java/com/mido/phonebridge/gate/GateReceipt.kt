package com.mido.phonebridge.gate

import com.mido.phonebridge.core.Verb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The receipt's lines after the punch (D16). "Live status" is the gate's state at the moment of
 * the stamp (D57); the handler's later result lives in the request log. Pure.
 */
object GateReceipt {

    fun lines(
        id: Long,
        requester: String,
        verb: Verb,
        path: String,
        decidedAtMs: Long,
        approved: Boolean,
        by: String,
    ): List<String> = listOf(
        "STMP RECEIPT ········ $id",
        "$requester  ${verb.name}  $path",
        "stamped ${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(decidedAtMs))}",
        "status ${status(approved)}",
        "by this phone · $by",
    )

    fun status(approved: Boolean): String = if (approved) "approved · running now" else "denied · caller told 403"

    /** The path part of a request line (`"GET /probe"` → `"/probe"`); the whole line if it has no space. */
    fun pathOf(requestLine: String): String = requestLine.substringAfter(' ', requestLine)
}
