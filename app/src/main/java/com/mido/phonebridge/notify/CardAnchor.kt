package com.mido.phonebridge.notify

import android.content.Context
import android.content.res.Configuration

/**
 * Where the draggable NOTIFY card sits, and the rule that keeps it on screen.
 *
 * y is measured UP from the bottom edge, because the card is bottom-anchored: a larger
 * y is higher on the screen. That inversion is the whole reason this is a type rather
 * than two loose ints — the drag and the restore both have to agree about it.
 */
data class CardAnchor(val x: Int, val y: Int) {
    companion object {
        fun default(minEdge: Int) = CardAnchor(minEdge, minEdge)

        /**
         * Clamp so the whole card stays on screen with [minEdge] to spare.
         *
         * **Pure and total.** Every input produces a usable anchor, which is what lets
         * the restore path trust it: a position saved on a wider display cannot come back
         * off-screen, because the restore goes through the SAME rule as the drag instead
         * of trusting the stored number.
         *
         * A card taller than the screen collapses both bounds onto [minEdge]. That is the
         * honest answer for something that cannot fit, and it avoids a negative maximum
         * that would make `coerceIn` throw.
         */
        fun clamp(
            x: Int,
            y: Int,
            screenWidth: Int,
            screenHeight: Int,
            cardWidth: Int,
            cardHeight: Int,
            minEdge: Int,
        ): CardAnchor {
            val maxX = (screenWidth - cardWidth - minEdge).coerceAtLeast(minEdge)
            val maxY = (screenHeight - cardHeight - minEdge).coerceAtLeast(minEdge)
            return CardAnchor(x.coerceIn(minEdge, maxX), y.coerceIn(minEdge, maxY))
        }
    }
}

/**
 * Persists the card anchor, **one entry per orientation**.
 *
 * Per orientation because a position that is right in portrait is wrong in landscape:
 * the card is anchored to the bottom-left corner, so a landscape anchor restored into
 * portrait lands somewhere the user never chose. Two keys, not one, is the difference
 * between "remembers where I put it" and "remembers a number".
 *
 * A missing entry returns null rather than a default, so the caller decides what
 * "never placed" means. Returning `(0,0)` here would be a position that happens to be
 * off the edge, and a default masquerading as a measurement is the kind of silent
 * fallback this app does not do.
 */
object CardAnchorStore {

    private const val PREFS = "notify_card_anchor"

    fun keyFor(orientation: Int): String =
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

    fun load(ctx: Context, orientation: Int): CardAnchor? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = keyFor(orientation)
        if (!p.contains("$k.x")) return null
        return CardAnchor(p.getInt("$k.x", 0), p.getInt("$k.y", 0))
    }

    fun save(ctx: Context, orientation: Int, anchor: CardAnchor) {
        val k = keyFor(orientation)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("$k.x", anchor.x)
            .putInt("$k.y", anchor.y)
            .apply()
    }
}
