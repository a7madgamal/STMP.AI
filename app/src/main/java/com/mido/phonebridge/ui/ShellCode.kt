package com.mido.phonebridge.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import io.github.a7madgamal.shellhl.highlight.Highlight
import io.github.a7madgamal.shellhl.highlight.Region
import io.github.a7madgamal.shellhl.highlight.StyleKey
import io.github.a7madgamal.shellhl.highlight.lineBreakOffsets

/**
 * A shell command rendered for the approval card: coloured by token role, broken at
 * command boundaries, monospace.
 *
 * **The library emits zsh style KEYS, never colours** — that is the whole point of the split.
 * `shellhl` returns `Region(1, 4, "command")`; deciding that `command` is green is a
 * presentation decision and lives here, next to the palette it belongs to. It also means a
 * different theme is a different map, not a different library.
 *
 * **Line breaks are inserted, never removed.** Every character of the input appears in the
 * output, in order. The only additions are newlines at separator boundaries — see
 * docs/plans/SHELL-HIGHLIGHT-PLAN.md §9 (Q1). The string that gets SENT is the untouched
 * original; this function is only ever given the copy that will be drawn.
 */
object ShellCode {

    /**
     * The default ink palette, mapping zsh style keys to this app's colours.
     *
     * Keys are zsh-syntax-highlighting's names verbatim, so an upstream theme file maps onto
     * this one entry for entry. Unlisted keys fall back to [Theme.ON_SURFACE] — an unknown
     * key must still render legibly rather than disappear.
     */
    fun palette(): Map<String, Int> = mapOf(
        // Command position: the thing being run.
        StyleKey.COMMAND to Theme.SUCCESS,
        StyleKey.BUILTIN to Theme.SUCCESS,
        StyleKey.FUNCTION to Theme.SUCCESS,
        StyleKey.ALIAS to Theme.SUCCESS,
        // `sudo` is a command in the slot, but not the command. Accent, because "this runs
        // elevated" is the fact most worth noticing before a tap.
        StyleKey.PRECOMMAND to Theme.ACCENT,

        // Arguments and options: quiet. Options slightly stronger than plain arguments.
        StyleKey.DEFAULT to Theme.ON_SURFACE,
        StyleKey.SINGLE_HYPHEN_OPTION to Theme.ON_SURFACE,
        StyleKey.DOUBLE_HYPHEN_OPTION to Theme.ON_SURFACE,

        // Quoting: a different material from bare text.
        StyleKey.SINGLE_QUOTED to Theme.WARNING,
        StyleKey.DOUBLE_QUOTED to Theme.WARNING,
        StyleKey.DOLLAR_QUOTED to Theme.WARNING,
        StyleKey.BACKQUOTED to Theme.WARNING,
        // An unclosed quote is the one syntax error worth seeing on a half-typed command.
        StyleKey.SINGLE_QUOTED_UNCLOSED to Theme.DANGER,
        StyleKey.DOUBLE_QUOTED_UNCLOSED to Theme.DANGER,
        StyleKey.DOLLAR_QUOTED_UNCLOSED to Theme.DANGER,
        StyleKey.BACKQUOTED_UNCLOSED to Theme.DANGER,

        // Structure: separators and redirects are where one operation becomes two.
        StyleKey.COMMANDSEPARATOR to Theme.ACCENT,
        StyleKey.REDIRECTION to Theme.ACCENT,

        // Expansions. A `$(…)` is a command nested inside this one — worth seeing as its
        // own thing, because "this line also runs something else" is exactly the fact a
        // human must not miss before approving.
        StyleKey.CMD_SUBST_UNQUOTED to Theme.WARNING,
        StyleKey.CMD_SUBST_QUOTED to Theme.WARNING,
        StyleKey.CMD_SUBST_DELIM_UNQUOTED to Theme.ON_SURFACE_VARIANT,
        StyleKey.CMD_SUBST_DELIM_QUOTED to Theme.ON_SURFACE_VARIANT,
        StyleKey.ARITHMETIC_EXPANSION to Theme.ON_SURFACE_VARIANT,
        StyleKey.PROCESS_SUBSTITUTION to Theme.WARNING,
        StyleKey.PROCESS_SUBSTITUTION_DELIM to Theme.ON_SURFACE_VARIANT,

        // Facts about the call.
        StyleKey.ASSIGN to Theme.ON_SURFACE_VARIANT,
        StyleKey.NUMERIC_FD to Theme.ON_SURFACE_VARIANT,
        StyleKey.GLOBBING to Theme.ON_SURFACE_VARIANT,
        StyleKey.COMMENT to Theme.ON_SURFACE_VARIANT,
        StyleKey.RESERVED_WORD to Theme.ACCENT,

        // A command the resolver positively said does not exist.
        StyleKey.UNKNOWN_TOKEN to Theme.DANGER,
    )

    /**
     * Build the drawable form of [command].
     *
     * @param palette style key -> colour. Defaults to [palette], so a caller that wants the
     *   app's own ink does not pass anything.
     */
    fun render(
        command: String,
        palette: Map<String, Int> = palette(),
    ): CharSequence {
        if (command.isEmpty()) return command

        // Breaks are resolved from the SAME tokens the colours come from, so "where a line
        // ends" and "what colour it is" can never disagree.
        val cuts = lineBreakOffsets(command)

        // Insert the breaks first, then colour against display offsets. Colouring first and
        // inserting after would shift every span by the number of newlines before it — the
        // exact off-by-N that renders a command mostly-right and entirely untrustworthy.
        //
        // No capacity hint: `SpannableStringBuilder` has a `CharSequence` constructor and no
        // `int` one, so `SpannableStringBuilder(n)` is a type error, not an optimisation.
        val text = SpannableStringBuilder()
        var from = 0
        for (cut in cuts) {
            text.append(command, from, cut)
            text.append('\n')
            from = cut
        }
        text.append(command, from, command.length)

        for (region in Highlight.highlight(command)) {
            val start = displayOffset(region.start - 1, cuts)
            val end = displayOffset(region.end, cuts)
            if (end <= start) continue
            val colour = palette[region.styleKey] ?: Theme.ON_SURFACE
            text.setSpan(
                ForegroundColorSpan(colour),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        // An unstyled gap would take the TextView's colour, which is the WARN amber the
        // surrounding row uses. Setting it explicitly keeps "no colour claimed" honest.
        return text
    }

    /** Monospace face that cannot be substituted by the handwritten default. */
    fun typeface(context: android.content.Context): Typeface = Theme.monoFont(context)

    /**
     * Where an original offset lands once newlines are inserted.
     *
     * A cut at `c` puts a newline *before* index `c`, so every boundary at or after `c`
     * moves by one. Boundaries strictly before `c` do not move — which is why the count is
     * `it < index` and not `<=`. Getting that wrong shifts the first character of every
     * line by one, which looks like a rendering glitch rather than an arithmetic one.
     */
    private fun displayOffset(index: Int, cuts: List<Int>): Int =
        index + cuts.count { it < index }

    /**
     * The style keys this renderer can colour, for a caller that wants to verify a theme
     * covers them. Exposed so a test can assert the palette is complete rather than trusting
     * that every key happens to be listed.
     */
    fun styledKeys(): Set<String> = palette().keys

    /** Regions for a command, unrendered — used by tests that assert colouring. */
    fun regionsOf(command: String): List<Region> = Highlight.highlight(command)
}
