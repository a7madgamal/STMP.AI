package com.mido.phonebridge.ui

import android.graphics.Path

/**
 * The app's one torn-edge silhouette.
 *
 * **Why this is shared rather than copied.** Cards, the bottom bar and the toggle all have to look
 * cut by the same hand; the moment one of them uses its own wobble the set stops reading as paper.
 * A previous pass on the toggle invented a per-vertex scatter, and at 34dp it came out visibly
 * noisier than anything else on screen — Mido, 2026-11-04: *"too jagged, can we just use the same
 * exact effect we are using everywhere else?"*.
 *
 * So the pattern lives here once, `TornPaper` draws from it, and `RuggedToggle` draws from it. The
 * amplitude is passed in, because a 6dp tear on a card is a 6dp tear: proportion is the caller's
 * decision, the SHAPE is not.
 */
object TornEdge {

    /**
     * Fixed jaggedness, as a fraction of the tear depth.
     *
     * Deterministic by construction: a `Random` here would re-roll every frame and the edge would
     * visibly crawl. Nine samples, uneven on purpose — an even zigzag reads as a saw blade rather
     * than as torn fibre.
     */
    val PATTERN = floatArrayOf(
        0.05f, 0.70f, 0.20f, 1.00f, 0.35f, 0.85f, 0.10f, 0.60f, 0.25f
    )

    /**
     * A rounded-topped rectangle whose bottom edge is torn.
     *
     * Written as one closed path so a caller can both fill it and clip grain to it. The tear runs
     * right to left, which is the direction `TornPaper` has always used — reversing it would make
     * the same shape look mirrored beside every card on the screen.
     */
    fun path(
        out: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        tearPx: Float,
        cornerPx: Float,
    ) {
        out.reset()
        val r = cornerPx.coerceAtMost(minOf(right - left, bottom - top) / 2f)

        out.moveTo(left + r, top)
        out.lineTo(right - r, top)
        out.quadTo(right, top, right, top + r)

        out.lineTo(right, bottom - r)
        out.quadTo(right, bottom, right - r, bottom)

        val segments = PATTERN.size
        val span = (right - r) - (left + r)
        for (i in 1..segments) {
            val x = right - r - span * (i.toFloat() / segments)
            val jag = PATTERN[i - 1] * tearPx
            out.lineTo(x, bottom - jag)
        }

        out.quadTo(left, bottom, left, bottom - r)
        out.lineTo(left, top + r)
        out.quadTo(left, top, left + r, top)
        out.close()
    }
}
