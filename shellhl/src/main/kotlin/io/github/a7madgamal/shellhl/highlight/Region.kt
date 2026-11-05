package io.github.a7madgamal.shellhl.highlight

/**
 * One colored span, in **zsh's** convention: 1-indexed, inclusive of both ends.
 *
 * **Why not reuse [io.github.a7madgamal.shellhl.lexer.Token]'s convention.** Tokens
 * are half-open and 0-indexed (Kotlin); regions are not, because the 271-file
 * upstream corpus is written in `"1 1 builtin"` form and comparing our output to it
 * must not involve arithmetic on either side. Either everyone converts, or exactly
 * one place converts. This is that one place — [Highlight.highlight] — and it is
 * covered by a test that pins the boundary.
 *
 * `style` is a plain `String` key from [StyleKey], not an enum: the corpus contains
 * keys a pure lexer cannot produce, and a closed type would make it impossible to
 * report them faithfully.
 */
data class Region(
    val start: Int,
    val end: Int,
    val style: String,
) {
    init {
        require(start >= 1) { "zsh regions are 1-indexed; got start=$start" }
        require(end >= start) { "end $end must be >= start $start" }
    }

    /** The style key zsh keys its tables on. Alias for [style], named for clarity. */
    val styleKey: String get() = style

    /** Length of the span, which is what a renderer needs. */
    val length: Int get() = end - start + 1
}
