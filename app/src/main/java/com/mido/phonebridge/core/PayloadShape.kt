package com.mido.phonebridge.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The SHAPE of a JSON payload, in a few words.
 *
 * This exists because a brokered call's summary was useless: writing 8 todos showed the
 * human `todoist: /secrets/todoist/call` — a path. The human approves the CONTENTS, so the
 * summary has to describe the contents (Mido, 2026-10-19: *"the payload is the whole reason
 * I added AI, so it's critical to describe it too"*).
 *
 * Pure and Context-free on purpose: this string is the difference between a useful summary
 * and a route path, so the rule belongs somewhere a JVM test can reach it — the same split
 * as `isAttended` and `ModelReadiness`.
 *
 * **Deliberately a real object, not a copy in a test.** An earlier version inlined this in
 * `BridgeService` and duplicated it in the test, which asserted against its own copy and
 * could never fail. This is the extraction that makes the test real.
 */
object PayloadShape {

    /**
     * Describe [raw] as `8 commands (item_add ×8)`, or null when there is nothing honest
     * to say (not a JSON object, or no non-empty fields).
     *
     * - An array of objects with a `type` field is counted BY KIND — the convention every
     *   batch API uses (`item_add`, `mail.send`, `changes.create`).
     * - An array without types (Gmail's `to`) is just a count.
     * - A scalar names the FIELD, never its value: the value is already a modal row and may
     *   be long or sensitive.
     * - An empty array contributes nothing, so an empty `cc`/`bcc` cannot pad the summary.
     */
    fun of(raw: String): String? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val parts = ArrayList<String>()
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONArray) {
                if (value.length() == 0) continue
                val kinds = LinkedHashMap<String, Int>()
                var typed = 0
                for (i in 0 until value.length()) {
                    val el = value.optJSONObject(i) ?: continue
                    val t = el.optString("type", "")
                    if (t.isNotBlank()) {
                        kinds[t] = (kinds[t] ?: 0) + 1
                        typed++
                    }
                }
                val what = if (typed > 0 && kinds.isNotEmpty()) {
                    kinds.entries.joinToString(", ") { "${it.key} ×${it.value}" }
                } else {
                    "items"
                }
                parts.add("${value.length()} $key ($what)")
            } else if (value != null && value !== JSONObject.NULL && value.toString().isNotBlank()) {
                parts.add(key)
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
