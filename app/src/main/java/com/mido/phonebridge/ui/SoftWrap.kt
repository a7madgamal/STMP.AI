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
 * Every visual line holds at most `columns - 1` characters, so the last cell is free for the glyph.
 */
object SoftWrap {
    /** The glyph drawn at the end of a soft-wrapped line. */
    const val GLYPH = "↵"

    /** Fewer columns than this and wrapping would be noise; the text is left to the platform. */
    internal const val MIN_COLUMNS = 8

    private const val BOUNDARY_CHARS = "/?&=#,;"

    /** How far back from the line end a hard break may search for a natural boundary. */
    private const val BOUNDARY_SEARCH_FRACTION = 0.4

    /**
     * Sorted indices of [text] before which a display-only break is inserted. A line that already
     * fits in [columns] is never broken, and an existing `\n` always ends a line.
     */
    fun breaks(text: CharSequence, columns: Int, keepTogether: List<IntRange> = emptyList()): List<Int> {
        if (columns < MIN_COLUMNS) return emptyList()
        val out = ArrayList<Int>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = nextNewline(text, lineStart)
            var start = lineStart
            while (lineEnd - start > columns) {
                val at = breakFor(text, start, columns, keepTogether)
                out.add(at)
                start = at
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun nextNewline(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun splits(at: Int, r: IntRange) = r.first < at && at <= r.last

    private fun breakFor(text: CharSequence, start: Int, columns: Int, keep: List<IntRange>): Int {
        val cap = start + columns - 1
        // 1. After whitespace, the furthest one that leaves a whole token on the next line.
        for (b in cap downTo start + 1) {
            if (text[b - 1].isWhitespace() && !text[b].isWhitespace() && keep.none { splits(b, it) }) return b
        }
        // 2. One token is wider than the line. Keep a danger token whole when it fits a line.
        keep.firstOrNull { splits(cap, it) && it.first > start && it.last - it.first + 1 <= columns - 1 }
            ?.let { return it.first }
        return hardBreak(text, start, cap)
    }

    private fun hardBreak(text: CharSequence, start: Int, cap: Int): Int {
        val tokenStart = tokenStart(text, cap, start)
        val isHexAddress = isAddress(text, tokenStart, cap)
        val floor = maxOf(start + 1, cap - ((cap - start) * BOUNDARY_SEARCH_FRACTION).toInt())
        for (b in cap downTo floor) {
            val c = text[b - 1]
            if (c in BOUNDARY_CHARS || (isHexAddress && c == ':')) return b
        }
        return cap
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
