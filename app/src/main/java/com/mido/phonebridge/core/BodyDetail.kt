package com.mido.phonebridge.core

import org.json.JSONObject

/**
 * Pulls the human-meaningful detail out of a request body, deterministically.
 *
 * **Why this exists.** Mido, 2026-10-20: *"I want FUCKING DETAILS FROM THE BODY"*. Eight releases
 * of prompt wording produced structural restatements — "Create task with content and description" —
 * and even when the model was finally handed the verbatim JSON it paraphrased the KEYS rather than
 * quoting the VALUES. A 1.7B model cannot be relied on to do this, and there is no reason it should:
 * the value is sitting right there in the body.
 *
 * So when the model's answer does not contain real body text, this supplies one instead. The human
 * gets the detail either way — the model becomes an optional refinement, not the only path to a
 * usable description.
 *
 * Deliberately prefers the fields that NAME the thing being acted on. `content` for a note,
 * `subject` for mail, `query` for a search: these are the words a person would use to describe the
 * request, and they are exactly what the summary kept omitting.
 *
 * Pure and Context-free so the extraction is unit-testable — the same split as `PayloadShape`,
 * `PromptBudget` and `OutboundVerb`.
 */
object BodyDetail {

    /**
     * Fields whose value IS the object of the request, in priority order.
     *
     * Order matters: a mail send carries both `subject` and `body`, and the subject is the phrase a
     * human would name it by. A note carries `content`. A search carries `query`.
     */
    private val PRIORITY = listOf(
        "content", "subject", "title", "name", "query", "text", "message", "summary", "label",
        "description", "body"
    )

    /**
     * Keys that are ROUTING METADATA, not content.
     *
     * The fallback walk must never quote these. Found by a test: a brokered GET carries
     * `{"url": …, "method": "GET", "query": {...}}`, and the fallback happily returned the string
     * `"GET"` — producing the summary `Reading "GET"`, which is worse than saying nothing.
     *
     * `query` is included even though it can hold a real search term: as a container it is already
     * skipped by [scalar], and when it is a plain string it is still a parameter name's worth of
     * routing rather than the object of the request.
     */
    private val NOT_CONTENT = setOf(
        "url", "method", "headers", "query", "id", "uuid", "temp_id", "project_id",
        "parent_id", "section_id", "label_id", "account", "version"
    )

    /** Long enough to be informative, short enough for a 216dp card. */
    private const val MAX_CHARS = 70

    /**
     * A description built from the body, or null when the body names nothing.
     *
     * [verb] decides the phrasing, so the line reads as an action rather than a data dump:
     * `Creating "test todo item"`, `Deleting "old notes"`, `Reading "project_id=…"`.
     */
    fun of(verb: Verb, rawBody: String): String? {
        val value = pickValue(rawBody) ?: return null
        val quoted = "\"${clip(value)}\""
        return when (verb) {
            Verb.READ -> "Reading $quoted"
            // "Writing", not "Sending": a POST is usually a create or an update, and "Sending"
            // reads as mail for a todo. Writing is true of every write without guessing which.
            Verb.WRITE -> "Writing $quoted"
            Verb.EDIT -> "Editing $quoted"
            Verb.DELETE -> "Deleting $quoted"
            Verb.EXECUTE -> "Running $quoted"
        }
    }

    /**
     * The first meaningful string value in the body.
     *
     * Walks PRIORITY first so the best field wins regardless of JSON key order, then falls back to
     * any string value — a provider with an unusual schema should still produce something better
     * than a generic line.
     */
    private fun pickValue(rawBody: String): String? {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        for (key in PRIORITY) {
            val text = scalar(obj.opt(key)) ?: continue
            if (text.isNotBlank() && !looksSensitive(text)) return text
        }
        // Fall back to any short string value that is not obviously a credential.
        for (key in obj.keys()) {
            if (key.lowercase() in NOT_CONTENT) continue
            val text = scalar(obj.opt(key)) ?: continue
            if (text.isNotBlank() && text.length <= MAX_CHARS && !looksSensitive(text)) return text
        }
        return null
    }

    /**
     * A JSON value as prose, or null when it is not prose.
     *
     * **`optString` is the trap this exists to avoid.** On an array or an object it does not
     * return "", it returns the serialised STRUCTURE — `["a@b.com"]`, `{"query":"today"}` — so a
     * naive walk quotes raw JSON back at the user as though it were a sentence. Two tests caught
     * exactly that (`Writing "["a@b.com"]"`), which is why only real strings and numbers are
     * accepted here and containers are skipped.
     *
     * A number is fine as text: `{"count":5}` is a legitimate thing to name.
     */
    private fun scalar(value: Any?): String? = when (value) {
        null -> null
        is String -> value.trim()
        is Number -> value.toString()
        else -> null // JSONArray / JSONObject / Boolean — structure, not prose
    }

    /**
     * Refuse to quote something credential-shaped.
     *
     * This string goes on screen. A token, a URL with a key in it, or a bare hash adds nothing to a
     * summary and would print a secret — worse than being generic.
     */
    private fun looksSensitive(text: String): Boolean {
        val t = text.lowercase()
        if (t.startsWith("http") && t.contains("key=")) return true
        return t.length > 40 && t.none { it == ' ' } // a long unbroken run is a token/hash, not prose
    }

    /** Bound the quote; the full value remains visible in the modal's own rows. */
    private fun clip(text: String): String =
        text.replace("\n", " ").let { if (it.length <= MAX_CHARS) it else it.take(MAX_CHARS) + "…" }

    /**
     * Does [description] already carry a real value from the body?
     *
     * The router for the whole feature: if the model managed to quote the actual content, keep its
     * sentence (it also carries the reasoning). If it only restated the structure — "Create task
     * with content and description" — the body supplies the detail instead.
     *
     * Matches on the FIRST 24 characters of the value, case-insensitively: models re-case and
     * re-punctuate freely, and demanding an exact match would discard good sentences. The prefix is
     * what makes it specific — "test" alone would match almost anything.
     */
    fun alreadyMentions(description: String, rawBody: String): Boolean {
        val value = pickValue(rawBody)?.trim() ?: return false
        if (value.length < 4) return false
        val needle = value.take(24).lowercase()
        return description.lowercase().contains(needle)
    }
}
