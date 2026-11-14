package com.mido.phonebridge.audit

import org.json.JSONObject

/**
 * Reading an optional field out of a stored audit line.
 *
 * **`optString` alone is wrong on Android.** A key written as `JSONObject.NULL` — which is how
 * `AuditRecord.toJson` stores "no error", "no rule", "nothing enqueued" — comes back from
 * `optString` as the four-character STRING "null", not as empty and not as null. The record
 * detail screen therefore printed `ERROR null` on every successful request and `RULE null` on
 * every decision the gate made itself. Android's behaviour is the one that matters and it is
 * asserted by `AndroidJsonProofTest`, so this is not a difference between two JVMs; it is the
 * device reading a real value as text.
 *
 * Absence has to be read as ABSENCE: a missing key, a JSON null and an empty string all mean the
 * same thing here, and none of them is a word to show somebody.
 */
object AuditField {

    /** The field's text, or null when it is missing, JSON null, or blank. */
    fun text(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The field's text verbatim, trimmed only of nothing — for a body, where indentation is the
     * data. Null when absent; a body that is present but empty stays an empty string.
     */
    fun body(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key)
    }

    /** An optional epoch-millisecond field; null when absent rather than 0. */
    fun millis(o: JSONObject, key: String): Long? =
        if (!o.has(key) || o.isNull(key)) null else o.optLong(key)
}
