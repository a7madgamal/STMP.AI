package com.mido.phonebridge.plugins.oauth

/**
 * Pulls a refresh token out of whatever the human pasted.
 *
 * **Why this is lenient about shape and strict about content.** The value people paste arrives in
 * several forms from the same source: the bare token, the whole `"refresh_token": "1//0g…"` line
 * copied out of the JSON response, that line with a trailing comma, or the value wrapped in quotes.
 * Refusing any of those would mean the value is correct and the app still rejects it — the worst
 * possible combination, and exactly the friction this screen exists to remove.
 *
 * What it must NOT do is accept something that is obviously a different secret. A client secret
 * pasted into the token field would be stored and then fail at the provider as `invalid_grant`,
 * which reads as "the provider is refusing my credential" rather than "the wrong field was filled".
 *
 * Pure, so the scraping rules are tested rather than exercised on a phone.
 */
object RefreshTokenPaste {

    /** A real token is long and opaque. Anything shorter is a fragment or a typo. */
    private const val MIN_LENGTH = 20

    /**
     * Google client secrets start with this. A token never does, and they sit two fields apart in
     * the same console page — which is how they get swapped.
     */
    private const val CLIENT_SECRET_PREFIX = "GOCSPX-"

    /**
     * The token inside [raw], or null when [raw] does not plausibly contain one.
     *
     * Null is the only failure, and the caller turns it into a refusal naming what to paste instead.
     */
    fun extract(raw: String): String? {
        var v = raw.trim()
        if (v.isEmpty()) return null

        // A whole JSON line: take what follows the key, then what follows the colon.
        if (v.contains("\"refresh_token\"")) {
            val afterKey = v.substringAfter("\"refresh_token\"")
            v = afterKey.substringAfter(':', afterKey)
        }
        // Trailing comma before a quote or brace, as in a pretty-printed response.
        v = v.trim().trimEnd(',').trim()
        // Wrapping quotes.
        v = v.trim('"').trim()
        // Anything after a further comma (e.g. `"1//0g…", "scope": "…"`).
        v = v.substringBefore(',').trim().trim('"').trim()

        if (v.length < MIN_LENGTH) return null
        if (v.startsWith(CLIENT_SECRET_PREFIX)) return null
        // Whitespace inside is not a token; it is prose or a broken paste.
        if (v.any { it.isWhitespace() }) return null
        return v
    }
}
