package com.mido.phonebridge.ui

/**
 * Where a verbatim readout may break a long line (R1, the approval surfaces).
 *
 * Pure and Android-free so it is JVM-testable. It only ever reports positions: the caller inserts
 * a display-only line break at each one and draws a continuation glyph there, so a soft wrap can
 * never be read as a space (or as a newline that was in the command).
 *
 * Rules:
 *  - A break goes right after whitespace, never inside a word, URL, path or IPv6 address.
 *  - A range in `keepTogether` (a danger token such as `rm -rf` or `| sh`) is not split either.
 *  - A single token wider than the line cannot avoid a break. It breaks at the last natural
 *    boundary near the line end (after `/ ? & = # , ;`, or after a `:` of an IPv6 address, so a
 *    hextet is never cut), else at the last column. The glyph always marks it.
 *
 * A broken line leaves room for the glyph. Widths are measured advances, not a fixed cell, so
 * fallback-font text (Arabic, CJK, emoji) cannot make a line wider than the view.
 */
object SoftWrap {
    /** The glyph drawn at the end of a soft-wrapped line. */
    const val GLYPH = "↵"

    /** Drawn instead of [GLYPH] where a token is cut in two: there is no space at this break. */
    const val GLYPH_HARD = "↩"

    /** True when a break before [at] cuts through a token (the line above does not end in whitespace). */
    fun isHardBreak(text: CharSequence, at: Int): Boolean = at > 0 && !text[at - 1].isWhitespace()

    private const val BOUNDARY_CHARS = "/?&=#,;"

    /** How far back from the line end a hard break may search for a natural boundary. */
    private const val BOUNDARY_SEARCH_FRACTION = 0.4

    /** Float slack so a line that exactly fills the width is not broken by rounding. */
    private const val EPSILON = 0.01f

    /**
     * Column form: every character is one cell wide. Kept for callers and tests that reason in
     * cells; the readout itself uses the measured form below.
     */
    fun breaks(text: CharSequence, columns: Int, keepTogether: List<IntRange> = emptyList()): List<Int> =
        breaks(text, FloatArray(text.length) { 1f }, columns.toFloat(), 1f, keepTogether)

    /**
     * Sorted indices of [text] before which a display-only break is inserted, measured in real
     * advances: [advances] holds the drawn width of each char, [available] the line width and
     * [glyphWidth] the room the continuation glyph needs. A line that already fits [available] is
     * never broken, and an existing `\n` always ends a line. A broken line always leaves
     * [glyphWidth] free. There is no width too small to wrap: at least one char goes on each line
     * and the glyph marks the break, so the platform never has to wrap silently.
     */
    fun breaks(
        text: CharSequence,
        advances: FloatArray,
        available: Float,
        glyphWidth: Float,
        keepTogether: List<IntRange> = emptyList()
    ): List<Int> {
        require(advances.size >= text.length) { "one advance per char" }
        val out = ArrayList<Int>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = nextNewline(text, lineStart)
            var start = lineStart
            while (width(advances, start, lineEnd) > available + EPSILON) {
                val at = breakFor(text, advances, start, lineEnd, available - glyphWidth, keepTogether)
                if (at <= start || at >= lineEnd) break
                out.add(at)
                start = at
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun width(advances: FloatArray, from: Int, to: Int): Float {
        var w = 0f
        for (i in from until to) w += advances[i]
        return w
    }

    private fun nextNewline(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun splits(at: Int, r: IntRange) = r.first < at && at <= r.last

    /** The furthest break index `b` (the next line starts at `b`) with `[start, b)` within [budget]. */
    private fun capFor(advances: FloatArray, start: Int, lineEnd: Int, budget: Float): Int {
        var w = 0f
        var b = start
        while (b < lineEnd && w + advances[b] <= budget + EPSILON) { w += advances[b]; b++ }
        return maxOf(b, start + 1)
    }

    private fun breakFor(
        text: CharSequence, advances: FloatArray, start: Int, lineEnd: Int, budget: Float, keep: List<IntRange>
    ): Int {
        val cap = minOf(capFor(advances, start, lineEnd, budget), lineEnd - 1)
        // 1. After whitespace, the furthest one that leaves a whole token on the next line.
        for (b in cap downTo start + 1) {
            if (text[b - 1].isWhitespace() && !text[b].isWhitespace() && keep.none { splits(b, it) }) return b
        }
        // 2. One token is wider than the line. Keep a danger token whole when it fits a line.
        keep.firstOrNull {
            splits(cap, it) && it.first > start && width(advances, it.first, it.last + 1) <= budget + EPSILON
        }?.let { return it.first }
        return hardBreak(text, advances, start, cap)
    }

    private fun hardBreak(text: CharSequence, advances: FloatArray, start: Int, cap: Int): Int {
        val tokenStart = tokenStart(text, cap, start)
        val isHexAddress = isAddress(text, tokenStart, cap)
        val floor = maxOf(start + 1, cap - ((cap - start) * BOUNDARY_SEARCH_FRACTION).toInt())
        for (b in cap downTo floor) {
            val c = text[b - 1]
            if (c in BOUNDARY_CHARS || (isHexAddress && c == ':')) return keepCluster(advances, start, b)
        }
        return keepCluster(advances, start, cap)
    }

    /** Never cut a surrogate pair or a combining mark away from its base: back up over zero-width chars. */
    private fun keepCluster(advances: FloatArray, start: Int, at: Int): Int {
        var b = at
        while (b > start + 1 && b < advances.size && advances[b] == 0f) b--
        return b
    }

    private fun tokenStart(text: CharSequence, from: Int, floor: Int): Int {
        var i = from
        while (i > floor && !text[i - 1].isWhitespace()) i--
        return i
    }

    /** True when `[from, to)` looks like (part of) an IPv6 address: hex digits, `:` and `.`. */
    private fun isAddress(text: CharSequence, from: Int, to: Int): Boolean {
        if (to - from < 2) return false
        var colons = 0
        for (i in from until to) {
            val c = text[i]
            if (c == ':') colons++
            else if (!(c.isDigit() || c in 'a'..'f' || c in 'A'..'F' || c == '.')) return false
        }
        return colons >= 2
    }
}
