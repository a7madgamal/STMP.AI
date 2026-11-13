package com.mido.phonebridge.ui

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt

/**
 * The one sanitiser for request text a human is asked to approve (FIX4): the command, target,
 * requester and summary a caller sent. It never hides, drops or reorders a character. A character
 * that cannot be seen, or that can change how the text around it is ordered or read, is replaced by
 * a visible `\u{XXXX}` token drawn in the caution colour, so the shown text is the sent text.
 *
 * Escaped, exactly: the code points listed in [TextSanitiserTable] (contract v2), generated at Unicode
 * 15.1: every Cc (C0/C1 controls, except `\n` and `\t`), Cf (bidi controls U+202A to U+202E and U+2066
 * to U+2069, U+200B to U+200F, U+FEFF, U+00AD, tags, ...), Zl and Zp (U+2028, U+2029), every surrogate
 * (U+D800 to U+DFFF, so a lone one), the Unicode Default_Ignorable_Code_Point set and the project's
 * extra ranges (U+034F, Hangul fillers U+115F, U+1160, U+3164 and U+FFA0, variation selectors, ...).
 * The table, not `Character.getType`, decides: API 26 knows Unicode 9, and U+0890 or U+13430 must be
 * escaped there exactly as on a newer runtime and on iOS.
 * It is strict on purpose: approval text is security text, so U+FE0F and U+200D are escaped even
 * inside an emoji sequence. Inside the readout the wrap marks are escaped too (`escapeWrapGlyphs`).
 *
 * The rule is published as `contract/text-sanitiser.json`, the source of truth shared with the iOS
 * client. Any change here must change that file and bump its "version"; the table comes from
 * tools/gen-text-sanitiser.ts.
 *
 * Only for text a caller sent. The app's own copy is never passed through here: PluginCopy puts a
 * deliberate U+200B before an "@" so a Google address wraps, and that must keep working.
 */
object UntrustedText {

    /** The wrap marks [SoftWrap] draws; a source occurrence must not pass for one of ours. */
    private val WRAP_GLYPHS = setOf(SoftWrap.GLYPH.codePointAt(0), SoftWrap.GLYPH_HARD.codePointAt(0))

    /** `\u{202E}`: at least four upper-case hex digits. */
    fun token(codePoint: Int): String = "\\u{%04X}".format(codePoint)

    /** True when [codePoint] must be drawn as a token. [escapeWrapGlyphs] adds the readout's wrap marks. */
    fun needsEscape(codePoint: Int, escapeWrapGlyphs: Boolean = false): Boolean {
        if (codePoint == '\n'.code || codePoint == '\t'.code) return false
        if (escapeWrapGlyphs && codePoint in WRAP_GLYPHS) return true
        return TextSanitiserTable.contains(codePoint)
    }

    /** True when [text] holds anything [escape] would change. */
    fun hasHidden(text: CharSequence, escapeWrapGlyphs: Boolean = false): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (needsEscape(cp, escapeWrapGlyphs)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** [text] with every hidden character written out as a token. Plain string: logs, titles, a11y. */
    fun escape(text: CharSequence, escapeWrapGlyphs: Boolean = false): String {
        if (!hasHidden(text, escapeWrapGlyphs)) return text.toString()
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (needsEscape(cp, escapeWrapGlyphs)) out.append(token(cp)) else out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * [text] for a view: every hidden character replaced by its token, drawn in [color]. Spans on
     * the rest of the text (danger underline, syntax colour) are kept and follow the text. Returns
     * [text] itself when nothing needs escaping.
     */
    fun mark(text: CharSequence, @ColorInt color: Int, escapeWrapGlyphs: Boolean = false): CharSequence {
        if (!hasHidden(text, escapeWrapGlyphs)) return text
        val out = SpannableStringBuilder(text)
        // Back to front: an earlier replacement cannot move a later, still unvisited index.
        var end = out.length
        while (end > 0) {
            val cp = Character.codePointBefore(out, end)
            val start = end - Character.charCount(cp)
            if (needsEscape(cp, escapeWrapGlyphs)) {
                val token = token(cp)
                out.replace(start, end, token)
                out.setSpan(ForegroundColorSpan(color), start, start + token.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            end = start
        }
        return out
    }

    /** Newline and tab, written out as visible tokens on a system surface (`neverEscaped` elsewhere). */
    private const val NEWLINE_TOKEN = "\\u{000A}"
    private const val TAB_TOKEN = "\\u{0009}"

    /** Longest system-surface text, in code points of the rendered text (contract `systemSurface.maxCodePoints`). */
    const val SYSTEM_MAX_CODE_POINTS = 120

    /** `…(+N chars)`, N being the rendered code points dropped (contract `systemSurface.overflowSuffix`). */
    private const val SYSTEM_OVERFLOW_SUFFIX = "\u2026(+%d chars)"

    /**
     * [text] for a system dialog line (BiometricPrompt subtitle), which is one line, takes no spans
     * and has little room: [escape], then `\n` and `\t` as visible tokens, then at most
     * [SYSTEM_MAX_CODE_POINTS] code points. A longer text keeps the longest prefix of whole units (a
     * token, or one code point, so no token and no surrogate pair is cut) and ends with the overflow
     * suffix naming how many code points were dropped. The rule is `systemSurface` in the contract.
     */
    fun escapeForSystemSurface(text: CharSequence): String {
        val units = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            units += when {
                cp == '\n'.code -> NEWLINE_TOKEN
                cp == '\t'.code -> TAB_TOKEN
                needsEscape(cp) -> token(cp)
                else -> String(Character.toChars(cp))
            }
            i += Character.charCount(cp)
        }
        val total = units.sumOf { it.codePointCount(0, it.length) }
        if (total <= SYSTEM_MAX_CODE_POINTS) return units.joinToString("")
        val out = StringBuilder()
        var kept = 0
        for (unit in units) {
            val length = unit.codePointCount(0, unit.length)
            if (kept + length > SYSTEM_MAX_CODE_POINTS) break
            out.append(unit)
            kept += length
        }
        return out.append(SYSTEM_OVERFLOW_SUFFIX.format(total - kept)).toString()
    }
}
