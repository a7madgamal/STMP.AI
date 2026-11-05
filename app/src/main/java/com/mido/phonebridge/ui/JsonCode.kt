package com.mido.phonebridge.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What a run of characters in a JSON document IS, for colouring. */
enum class JsonKind {
    /** A string in key position (followed by `:`). */
    KEY,
    /** A string in value position. */
    STRING,
    NUMBER,
    /** `true`, `false`, `null`. */
    LITERAL,
    /** `{ } [ ] , :` */
    PUNCT,
}

/** A coloured run: half-open `[start, end)`, the same convention as everywhere else here. */
data class JsonSpan(val start: Int, val end: Int, val kind: JsonKind)

/**
 * JSON for reading: pretty-printed, coloured, and free of the `\/` artifact.
 *
 * **Display only.** Nothing here is ever sent, stored, or re-parsed into a request. The caller
 * keeps the original string; this produces a copy a human can read. That distinction is why the
 * unescape below is safe even though it is lossy.
 *
 * **Why `\/` specifically.** `/` never needs escaping in JSON — it is legal unescaped. Some
 * encoders (Android's `org.json` among them) escape it anyway, so every URL comes out as
 * `https:\/\/host\/path` and reads like line noise. It is the one escape with no semantic
 * content, so replacing it with `/` cannot change the meaning of anything. No OTHER escape is
 * touched: `\n`, `\u00e9` and `\"` carry meaning, and decoding those would silently rewrite the
 * document being approved.
 */
object JsonCode {

    /**
     * The readable form of [raw]: pretty-printed when it parses as JSON, `\/` removed either way.
     *
     * A string that is not JSON comes back with only the slash fix — a body does not have to be
     * JSON to appear on a card, and refusing to render one because it is not would hide more than
     * it tidies.
     */
    fun present(raw: String): String {
        val trimmed = raw.trim()
        val pretty = try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        } catch (e: JSONException) {
            // Not JSON, or JSON we cannot index. Show it as it is; a rendering path must never be
            // the thing that makes a body unreadable.
            raw
        }
        return unescapeSlash(pretty)
    }

    /**
     * `\/` -> `/`, and nothing else.
     *
     * Written as a scan rather than `replace("\\/", "/")` so it cannot be fooled by an already
     * escaped backslash: in `a\\\/b` the `\\` is a literal backslash and the `\/` after it is
     * still an escaped slash. A blind replace gets that right by accident; a scan keeps it right
     * on purpose.
     */
    private fun unescapeSlash(s: String): String {
        if (!s.contains("\\/")) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '/' -> { out.append('/'); i += 2 }
                    // Any other escape is preserved whole, backslash included.
                    else -> { out.append(s[i]); out.append(s[i + 1]); i += 2 }
                }
            } else {
                out.append(s[i]); i++
            }
        }
        return out.toString()
    }

    /** True when [raw] is a JSON object or array — the test for "worth pretty-printing". */
    fun looksJson(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("{") || t.startsWith("[")
    }

    /**
     * The drawable form of [raw].
     *
     * @param palette kind -> colour. Defaults to [ink], which follows the card's paper palette.
     */
    fun render(raw: String, palette: Map<JsonKind, Int> = ink()): CharSequence {
        val text = present(raw)
        if (text.isEmpty()) return text
        val sb = SpannableStringBuilder(text)
        for (span in tokenize(text)) {
            if (span.end <= span.start) continue
            val colour = palette[span.kind] ?: Theme.ON_SURFACE
            sb.setSpan(
                ForegroundColorSpan(colour),
                span.start,
                span.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    /** Key colours for the app's paper world: keys carry, values inform, punctuation recedes. */
    fun ink(): Map<JsonKind, Int> = mapOf(
        JsonKind.KEY to Theme.ACCENT,
        JsonKind.STRING to Theme.WARNING,
        JsonKind.NUMBER to Theme.SUCCESS,
        JsonKind.LITERAL to Theme.SUCCESS,
        JsonKind.PUNCT to Theme.ON_SURFACE_VARIANT,
    )

    /**
     * Split already-presented text into coloured runs.
     *
     * A hand-written scan rather than a regex: the only real question is "is this string a key",
     * which is answered by looking ahead for `:`, and a regex that tried to encode that would be
     * harder to read than the five lines it replaced.
     */
    fun tokenize(text: String): List<JsonSpan> {
        val out = ArrayList<JsonSpan>(text.length / 4 + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val end = stringEnd(text, i)
                    // A string is a KEY only when the next non-space character is `:`. That is
                    // the whole distinction, and it is why the lookahead is here rather than a
                    // separate pass over the document.
                    var j = end
                    while (j < text.length && text[j].isWhitespace()) j++
                    val kind = if (j < text.length && text[j] == ':') JsonKind.KEY else JsonKind.STRING
                    out.add(JsonSpan(i, end, kind))
                    i = end
                }

                c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':' -> {
                    out.add(JsonSpan(i, i + 1, JsonKind.PUNCT))
                    i++
                }

                c.isDigit() || (c == '-' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] == '.' ||
                            text[i] == 'e' || text[i] == 'E' || text[i] == '+' ||
                            (text[i] == '-' && (text[i - 1] == 'e' || text[i - 1] == 'E')))
                    ) i++
                    out.add(JsonSpan(start, i, JsonKind.NUMBER))
                }

                text.startsWith("true", i) -> { out.add(JsonSpan(i, i + 4, JsonKind.LITERAL)); i += 4 }
                text.startsWith("false", i) -> { out.add(JsonSpan(i, i + 5, JsonKind.LITERAL)); i += 5 }
                text.startsWith("null", i) -> { out.add(JsonSpan(i, i + 4, JsonKind.LITERAL)); i += 4 }

                // Whitespace and anything unexpected: no span, no colour, cursor advances. A
                // character this highlighter does not understand must not stall the scan.
                else -> i++
            }
        }
        return out
    }

    /** Index just past the closing quote of the string opening at [open], or end of text. */
    private fun stringEnd(text: String, open: Int): Int {
        var i = open + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }
}
