package com.mido.phonebridge.core

/**
 * Refuses request text that can hide what it says.
 *
 * WHY THIS EXISTS
 *   The approval modal is the whole safety model: a human reads the command and decides. That
 *   only holds if what the screen SHOWS is what the phone will RUN, and a few Unicode code
 *   points break that. A U+202E RIGHT-TO-LEFT OVERRIDE flips everything after it, so
 *
 *       rm -rf ~/<U+202E>stcejorP
 *
 *   draws as `rm -rf ~/stcejorP` while the bytes handed to the shell are `rm -rf ~/Projects`.
 *   The human approved one string and ran another. Zero-width spaces, BOMs, soft hyphens,
 *   lone surrogates and the C0/C1 controls do the same class of thing: they are present in
 *   the payload and absent from the picture.
 *
 * WHY A REJECTION AND NOT AN ESCAPE
 *   The other option is to draw each hidden character as a visible `\u{XXXX}` token. That
 *   keeps the request runnable and is what the Instrument build does. This build refuses
 *   instead, and the reason is that a request carrying a bidi override is not a request with
 *   a rendering problem — it is an attempt to defeat the gate. There is no honest version of
 *   it to display. Refusing costs nothing real, because no legitimate caller sends one.
 *
 * WHAT IS ALLOWED, AND WHY THE LINE IS THERE
 *   **`\n` and `\t`** are invisible and completely ordinary. Every multi-line command and
 *   every formatted message carries them.
 *
 *   **`U+200D` (zero-width joiner) and `U+FE00`-`U+FE0F` (variation selectors)** are also
 *   allowed, and this is the one judgement call in the file. They are invisible, but they
 *   cannot reorder or conceal anything: they only tell the renderer how to COMPOSE a glyph.
 *   Every emoji family sequence is `U+1F468 U+200D U+1F469 U+200D U+1F467`, and `❤️` is
 *   `U+2764 U+FE0F`. Refusing them would reject a mail body that says "shipped 🚀" — a
 *   failure with no safety benefit, aimed at the wrong caller.
 *
 *   Every other invisible code point is refused.
 *
 * FAIL EARLY, FAIL HARD
 *   No trimming, no stripping, no "cleaned" request. Either the text is safe to show or the
 *   call is refused with the exact code point named, so the caller can fix the payload rather
 *   than guess what the phone disliked.
 */
object HiddenText {

    /** What a caller is told. Names the code point so the payload can be fixed, not guessed at. */
    fun message(codePoint: Int, where: String): String {
        val hex = String.format("U+%04X", codePoint)
        return "hidden character $hex in $where. It can change how the approval screen draws " +
            "the request, so nothing was shown and nothing ran. Remove it and send the request again."
    }

    /**
     * The first code point in [text] that must be refused, or null when the text is safe.
     *
     * Returns the code point rather than a boolean so the refusal can name it: "U+202E" tells a
     * caller exactly what happened, where "invalid characters" sends them hunting.
     */
    fun firstHidden(text: CharSequence): Int? {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isHidden(cp)) return cp
            i += Character.charCount(cp)
        }
        return null
    }

    /** True when [codePoint] is invisible AND can reorder, conceal or control the text. */
    fun isHidden(codePoint: Int): Boolean {
        // Ordinary whitespace in a body: allowed, see the class comment.
        if (codePoint == '\n'.code || codePoint == '\t'.code) return false
        // Glyph COMPOSERS: emoji ZWJ sequences and variation selectors. Invisible, but they
        // cannot reorder or hide anything, and refusing them would reject ordinary messages.
        if (codePoint == 0x200D) return false
        if (codePoint in 0xFE00..0xFE0F) return false

        return when (Character.getType(codePoint).toByte()) {
            // C0 and C1 controls (NUL, ESC, BEL, ...), everything in the Cf format class
            // (bidi overrides and isolates, ZWSP, BOM, soft hyphen, tag characters), the two
            // line/paragraph separators, and a lone surrogate.
            Character.CONTROL,
            Character.FORMAT,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE -> true
            else -> false
        }
    }
}
