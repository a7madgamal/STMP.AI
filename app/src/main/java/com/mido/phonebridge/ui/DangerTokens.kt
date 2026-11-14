package com.mido.phonebridge.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LineBackgroundSpan
import android.text.style.UpdateAppearance

/**
 * Shell tokens worth a second look before an approval: `sudo`, `rm -rf`, `| sh`, `dd of=`, `mkfs`
 * and the rest.
 *
 * **Why a list and not a `contains("rm -rf")`.** The thing being protected is a human reading a
 * command on a phone before they approve it. A list of patterns is reviewable in one place, can be
 * tested against real strings, and — critically — never changes the text. Every function here
 * either reports a range or adds a span to a copy; no character is added, removed or reordered.
 * A marker that shifted the command by one character would make the approval dialog lie about
 * what it is approving, which is worse than no marker at all.
 *
 * **Why underlines and not colour.** The readout is already monospace on paper; replacing the glyph
 * colour would fight the syntax tint and, for a destructive token, could read as "link". A rule
 * under the exact run is additive: it survives copy-paste, it survives a screenshot, and it cannot
 * be mistaken for a different word.
 */
object DangerTokens {

    /** One reviewed pattern. [name] is the test label, not user-facing. */
    data class Pattern(val name: String, val regex: Regex)

    /**
     * Every pattern, in one reviewed list.
     *
     * The word-boundary guards matter: `sudo` must not match inside `pseudo`, and `>` must not
     * match the `>` of `2>/dev/null` — that redirect is noise on nearly every command and
     * underlining it would train the eye to ignore the underline entirely.
     */
    val PATTERNS: List<Pattern> = listOf(
        // A privilege escalation, at the head of a segment or after a path separator.
        Pattern("sudo", Regex("""(?:(?<=/)|(?<![\w.\-/]))(?:sudo|doas)\b""")),
        // rm with any recursive or force flag, in either the short-bundle or long form.
        Pattern(
            "rm-force-or-recursive",
            Regex("""\brm\s+(?:-[A-Za-z]*[rRf][A-Za-z]*|--(?:recursive|force)\b)"""),
        ),
        // Piping into a shell: the canonical "download and execute" shape.
        Pattern("pipe-to-shell", Regex("""\|\s*(?:sudo\s+)?(?:ba|z|da|k)?sh\b""")),
        // A redirect that writes. `2>` and `2>&1` are excluded: they are plumbing, not intent.
        Pattern("redirect-write", Regex("""(?<![\-=>\d])>{1,2}(?![>&])""")),
        Pattern("dd-of", Regex("""\bdd\b(?=[^\n;|&]*\bof=)""")),
        Pattern("mkfs", Regex("""\bmkfs(?:\.\w+)?\b""")),
        Pattern("power", Regex("""\b(?:shutdown|reboot|poweroff|halt)\b""")),
        Pattern("recursive-perms", Regex("""\bch(?:mod|own)\s+-R\b""")),
        Pattern("kill-9", Regex("""\bkill(?:all)?\s+-9\b""")),
        Pattern(
            "git-force",
            Regex("""\bgit\s+(?:push\s+(?:-f|--force(?:-with-lease)?)|reset\s+--hard|clean\s+-[A-Za-z]*f)\b"""),
        ),
        // The classic fork bomb: `:(){ :|:& };:`
        Pattern("fork-bomb", Regex(""":\(\)\s*\{""")),
        Pattern("eval", Regex("""\beval\b""")),
    )

    /**
     * Sorted, merged ranges in [text], as inclusive [IntRange]s.
     *
     * Overlapping and touching matches merge into one range, so a token matched by two patterns
     * gets one underline rather than two stacked ones. Pure: takes a String, returns ranges, has
     * no Android dependency and never throws.
     */
    fun find(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val raw = PATTERNS
            .flatMap { p -> p.regex.findAll(text).map { it.range }.filter { !it.isEmpty() } }
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

    /**
     * A copy of [text] with an underline under every dangerous run. Characters are unchanged.
     *
     * [underlineColor] is passed in rather than read from a palette, so this stays usable from a
     * widget that only has colours in hand and testable without a Context.
     */
    fun mark(text: CharSequence, underlineColor: Int, thicknessPx: Float): Spannable {
        val out = SpannableString(text)
        for (r in find(text.toString())) {
            out.setSpan(
                DangerUnderlineSpan(underlineColor, thicknessPx, r.first, r.last + 1),
                r.first,
                r.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }
}

/**
 * Draws a rule under one run of text, following it across a line wrap.
 *
 * A `LineBackgroundSpan` rather than a `TextPaint` underline because the text is laid out in a
 * `TextView` that may wrap: the built-in underline flag applies to the WHOLE `TextView`, so
 * toggling it would underline the entire command. This draws only `[tokenStart, tokenEnd)` and
 * recomputes the horizontal extent on every line it touches, so a token split across two lines
 * gets a rule on each half and none in between.
 */
class DangerUnderlineSpan(
    private val color: Int,
    private val thicknessPx: Float,
    private val tokenStart: Int,
    private val tokenEnd: Int,
) : LineBackgroundSpan {

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        // The part of this token that falls on THIS line.
        val s = maxOf(tokenStart, start)
        val e = minOf(tokenEnd, end)
        if (s >= e) return
        val x0 = left + paint.measureText(text, start, s)
        val x1 = left + paint.measureText(text, start, e)
        val saved = paint.color
        paint.color = color
        val y = baseline + thicknessPx * 0.5f
        canvas.drawRect(x0, y, x1, y + thicknessPx, paint)
        paint.color = saved
    }
}
