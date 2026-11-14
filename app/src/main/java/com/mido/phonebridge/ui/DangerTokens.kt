package com.mido.phonebridge.ui

import android.content.res.Resources
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.mido.phonebridge.ui.kit.DangerUnderlineSpan
import com.mido.phonebridge.ui.tokens.Palette

/**
 * The shell tokens worth a second look before a tap (D11): `sudo`, `rm -rf`, `| sh`, `>` and the
 * rest. Each one is underlined IN PLACE on the gate's readout. Nothing here changes, adds or
 * reorders a character: [find] only reports where the tokens are, and [mark] only adds spans.
 *
 * Every pattern lives in this one list so the set is reviewed and tested in one place.
 */
object DangerTokens {
    data class Pattern(val name: String, val regex: Regex)

    val PATTERNS: List<Pattern> = listOf(
        Pattern("sudo", Regex("""(?:(?<=/)|(?<![\w.\-/]))(?:sudo|doas)\b""")),
        Pattern("rm-force-or-recursive", Regex("""\brm\s+(?:-[A-Za-z]*[rRf][A-Za-z]*|--(?:recursive|force)\b)""")),
        Pattern("pipe-to-shell", Regex("""\|\s*(?:sudo\s+)?(?:ba|z|da|k)?sh\b""")),
        Pattern("redirect", Regex("""(?<![\-=>])>{1,2}""")),
        Pattern("dd-of", Regex("""\bdd\b(?=[^\n;|&]*\bof=)""")),
        Pattern("mkfs", Regex("""\bmkfs(?:\.\w+)?\b""")),
        Pattern("power", Regex("""\b(?:shutdown|reboot|poweroff|halt)\b""")),
        Pattern("recursive-perms", Regex("""\bch(?:mod|own)\s+-R\b""")),
        Pattern("kill-9", Regex("""\bkill(?:all)?\s+-9\b""")),
        Pattern("git-force", Regex("""\bgit\s+(?:push\s+(?:-f|--force(?:-with-lease)?)|reset\s+--hard|clean\s+-[A-Za-z]*f)\b""")),
        Pattern("fork-bomb", Regex(""":\(\)\s*\{""")),
        Pattern("eval", Regex("""\beval\b""")),
    )

    /** Thickness of the underline bar, dp. */
    internal const val UNDERLINE_DP = 2f

    /**
     * Sorted, merged ranges in [text], as `IntRange(start, endExclusive - 1)`. Overlapping or
     * touching matches become one range. Pure; never throws.
     */
    fun find(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val raw = PATTERNS.flatMap { p -> p.regex.findAll(text).map { it.range }.filter { !it.isEmpty() }.toList() }
            .sortedBy { it.first }
        val merged = ArrayList<IntRange>(raw.size)
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    /** A copy of [text] with readoutDanger + DangerUnderlineSpan on every range. Characters unchanged. */
    fun mark(text: CharSequence, p: Palette): Spannable {
        val out = SpannableString(text)
        val thickness = UNDERLINE_DP * Resources.getSystem().displayMetrics.density
        for (r in find(text.toString())) addSpans(out, r.first, r.last + 1, p, thickness)
        return out
    }

    /** The two spans of one dangerous token, on `[start, end)` of [target]. */
    internal fun addSpans(target: Spannable, start: Int, end: Int, p: Palette, thicknessPx: Float) {
        target.setSpan(ForegroundColorSpan(p.readoutDanger), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        target.setSpan(DangerUnderlineSpan(p.signal, thicknessPx, start, end), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
