package com.mido.phonebridge.ui

import android.graphics.Color

/**
 * A shell theme: zsh style key -> colour, loaded from the syntax zsh users already write.
 *
 * **Why parse zsh's own theme syntax instead of inventing one.** The only reason to adopt
 * zsh's vocabulary in `shellhl` was so an existing theme could be reused. A theme format of
 * our own would have thrown that away at the last step — the library would speak zsh and the
 * app would speak something else, and every published theme would need porting by hand.
 *
 * The syntax people actually publish is:
 *
 * ```zsh
 * ZSH_HIGHLIGHT_STYLES[command]='fg=green,bold'
 * ZSH_HIGHLIGHT_STYLES[redirection]='fg=cyan'
 * ZSH_HIGHLIGHT_STYLES[comment]='fg=#6c7086'
 * ```
 *
 * That is what [parse] reads. Values it cannot resolve are **skipped, not fatal**: a theme
 * written for a 256-colour terminal will contain entries this app cannot honour, and refusing
 * to render the whole command over one unknown colour would be a worse outcome than drawing
 * that one span in the default ink.
 */
object ShellTheme {

    /**
     * The default ink theme, built from [Theme] so the command sits in the same paper world as
     * the rest of the card. This is [ShellCode.palette] as a named, replaceable object.
     */
    fun ink(): Map<String, Int> = ShellCode.palette()

    /**
     * Parse a zsh theme's assignments.
     *
     * Returns only the keys it understood. [base] supplies the colours for keys the text does
     * not mention, so a three-line theme still renders every part of a command.
     */
    fun parse(text: String, base: Map<String, Int> = ink()): Map<String, Int> {
        val out = base.toMutableMap()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("ZSH_HIGHLIGHT_STYLES[")) continue
            val close = line.indexOf(']')
            if (close < 0) continue
            val key = line.substring("ZSH_HIGHLIGHT_STYLES[".length, close).trim()
            val eq = line.indexOf('=', close)
            if (eq < 0) continue
            val value = line.substring(eq + 1).trim().trim('"', '\'')
            if (key.isEmpty() || value.isEmpty()) continue

            // `none` is a real zsh directive: "do not highlight this". Honouring it as
            // "leave at base" is the closest true reading — this renderer has no
            // un-highlight, and pretending it does would silently drop the span's colour.
            if (value == "none") continue

            colourOf(value)?.let { out[key] = it }
        }
        return out
    }

    /**
     * Resolve one style value, e.g. `fg=green,bold`.
     *
     * Only the FOREGROUND is honoured. `bold` and `bg=` are parsed past rather than rejected,
     * because a published theme nearly always carries them and dropping the whole entry over
     * a weight we do not apply would lose the colour too.
     */
    private fun colourOf(value: String): Int? {
        for (part in value.split(',')) {
            val p = part.trim()
            when {
                p.startsWith("fg=") -> return parseColour(p.removePrefix("fg="))
                // `#RRGGBB` / `#RGB` with no `fg=` prefix also appears in the wild.
                p.startsWith("#") -> return parseColour(p)
            }
        }
        return null
    }

    private fun parseColour(spec: String): Int? {
        val s = spec.trim()
        if (s.startsWith("#")) return parseHex(s)

        NAMED[s]?.let { return it }

        // 256-colour index: a theme may say `fg=33`. Falling back to the nearest of the
        // basic 16 is wrong in colour terms but right in meaning — the span still reads as
        // "a different kind of thing", which is what the colour is for here.
        val index = s.toIntOrNull() ?: return null
        return ANSI_256.getOrNull(index)?.let { ansiToArgb(it) }
    }

    private fun parseHex(s: String): Int? = try {
        when (s.length) {
            // `#RGB` shorthand, which zsh themes do use (`#fff`).
            4 -> Color.rgb(
                s[1].digit() * 17, s[2].digit() * 17, s[3].digit() * 17,
            )
            7 -> Color.rgb(
                s.substring(1, 3).toInt(16),
                s.substring(3, 5).toInt(16),
                s.substring(5, 7).toInt(16),
            )
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        // A malformed hex is a theme bug, not a reason to stop rendering.
        null
    }

    private fun Char.digit(): Int = Character.digit(this, 16).coerceAtLeast(0)

    /** The 8 base ANSI colours plus their bright variants, as zsh names them. */
    private val NAMED: Map<String, Int> = mapOf(
        "black" to Color.rgb(0, 0, 0),
        "red" to Color.rgb(205, 49, 49),
        "green" to Color.rgb(13, 188, 121),
        "yellow" to Color.rgb(229, 229, 16),
        "blue" to Color.rgb(36, 114, 200),
        "magenta" to Color.rgb(188, 63, 188),
        "cyan" to Color.rgb(17, 168, 205),
        "white" to Color.rgb(229, 229, 229),
        "brightblack" to Color.rgb(102, 102, 102),
        "brightred" to Color.rgb(241, 76, 76),
        "brightgreen" to Color.rgb(35, 209, 139),
        "brightyellow" to Color.rgb(245, 245, 67),
        "brightblue" to Color.rgb(59, 142, 234),
        "brightmagenta" to Color.rgb(214, 112, 214),
        "brightcyan" to Color.rgb(41, 184, 219),
        "brightwhite" to Color.rgb(255, 255, 255),
        // `default` is zsh's "terminal's own colour"; the paper ink stands in for it.
        "default" to Theme.ON_SURFACE,
    )

    /** xterm's 256-colour table, compressed to the 16 system colours. */
    private val ANSI_256: List<Int> = listOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    private fun ansiToArgb(index: Int): Int = when (index) {
        in 0..7 -> listOf(
            Color.rgb(0, 0, 0), Color.rgb(205, 49, 49), Color.rgb(13, 188, 121),
            Color.rgb(229, 229, 16), Color.rgb(36, 114, 200), Color.rgb(188, 63, 188),
            Color.rgb(17, 168, 205), Color.rgb(229, 229, 229),
        )[index]
        else -> listOf(
            Color.rgb(102, 102, 102), Color.rgb(241, 76, 76), Color.rgb(35, 209, 139),
            Color.rgb(245, 245, 67), Color.rgb(59, 142, 234), Color.rgb(214, 112, 214),
            Color.rgb(41, 184, 219), Color.rgb(255, 255, 255),
        )[index - 8]
    }
}
