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
 * Escaped, exactly: every code point in the general categories Cc (C0/C1 controls, except `\n` and
 * `\t`), Cf (bidi controls U+202A to U+202E and U+2066 to U+2069, U+200B to U+200F, U+FEFF, U+00AD,
 * tags, ...), Zl and Zp (U+2028, U+2029) and Cs (a lone surrogate); plus the rest of the Unicode
 * Default_Ignorable_Code_Point set, listed in [EXTRA_RANGES] (U+034F, Hangul fillers U+115F, U+1160,
 * U+3164 and U+FFA0, variation selectors U+FE00 to U+FE0F and U+E0100 to U+E01EF, U+2060 to U+206F, ...).
 * It is strict on purpose: approval text is security text, so U+FE0F and U+200D are escaped even
 * inside an emoji sequence. Inside the readout the wrap marks are escaped too (`escapeWrapGlyphs`).
 *
 * The rule is published as `contract/text-sanitiser.json`, the source of truth shared with the iOS
 * client. Any change here must change that file and bump its "version".
 *
 * Only for text a caller sent. The app's own copy is never passed through here: PluginCopy puts a
 * deliberate U+200B before an "@" so a Google address wraps, and that must keep working.
 */
object UntrustedText {

    /** The wrap marks [SoftWrap] draws; a source occurrence must not pass for one of ours. */
    private val WRAP_GLYPHS = setOf(SoftWrap.GLYPH.codePointAt(0), SoftWrap.GLYPH_HARD.codePointAt(0))

    /** Default_Ignorable_Code_Point ranges outside Cc/Cf/Zl/Zp/Cs (or unassigned gaps inside them). */
    val EXTRA_RANGES: List<IntRange> = listOf(
        0x034F..0x034F, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F, 0x2060..0x206F,
        0x3164..0x3164, 0xFE00..0xFE0F, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3,
        0x1D173..0x1D17A, 0xE0000..0xE0FFF,
    )

    /** `\u{202E}`: at least four upper-case hex digits. */
    fun token(codePoint: Int): String = "\\u{%04X}".format(codePoint)

    /** True when [codePoint] must be drawn as a token. [escapeWrapGlyphs] adds the readout's wrap marks. */
    fun needsEscape(codePoint: Int, escapeWrapGlyphs: Boolean = false): Boolean {
        if (codePoint == '\n'.code || codePoint == '\t'.code) return false
        if (escapeWrapGlyphs && codePoint in WRAP_GLYPHS) return true
        val byCategory = when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true
            else -> false
        }
        return byCategory || EXTRA_RANGES.any { codePoint in it }
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
}
