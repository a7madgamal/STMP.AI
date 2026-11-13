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
 * Escaped: every Unicode format character (Cf: bidi controls U+202A to U+202E and U+2066 to U+2069,
 * U+200B to U+200F, U+FEFF, U+00AD, tags and more), the line and paragraph separators U+2028 and
 * U+2029, a lone surrogate, and every C0/C1 control except `\n` and `\t`.
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
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true
            else -> false
        }
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
