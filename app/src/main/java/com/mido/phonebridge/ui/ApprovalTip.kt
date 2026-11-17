package com.mido.phonebridge.ui

import kotlin.math.PI
import kotlin.math.tan

/**
 * Geometry for the resting approval tip: the paper wedge that waits against the right edge.
 *
 * **The lean IS the taper.** The wedge is not a rectangle with a decorative angle: it is a
 * triangle whose long edge runs down-right at [TILT_DEGREES] from vertical, so its width
 * shrinks to nothing over a height fixed by that same angle. Deriving one from the other is
 * what keeps the shape honest — a hand-picked "taper length" would drift away from the tilt the
 * moment either number changed, and the sheet would stop reading as a sheet.
 *
 * **Pure arithmetic, no Android types.** The view needs a `Path`, but every decision that can be
 * wrong — how wide, how tall, where the point lands — is decided here, where a JVM test can
 * check it. `ApprovalTipView` only draws what this returns.
 */
object ApprovalTip {

    /** Lean of the long edge, in degrees from vertical. The taper follows from this. */
    const val TILT_DEGREES = -8f

    /**
     * Top width as a fraction of screen width, before clamping.
     *
     * The requested size is "about 3 cm". A phone is roughly 7 cm wide, so a third of the width
     * lands there — but a fraction alone would give 5 cm on a tablet and 1.5 cm on a tiny
     * device, so it is clamped to [TOP_WIDTH_MIN_DP]..[TOP_WIDTH_MAX_DP] to stay readable to
     * look at and easy to hit.
     */
    private const val TOP_WIDTH_FRACTION = 0.24f

    /**
     * A narrow strip of paper, not a panel.
     *
     * This is the width the wedge reaches in from the edge, and every dp of it is screen the human
     * cannot see. Measured by eye on the device: at a third of the display it read as a card
     * leaning in, which is not what this is — it is a corner of a sheet caught mid-slide.
     */
    const val TOP_WIDTH_MIN_DP = 56f
    const val TOP_WIDTH_MAX_DP = 150f

    /**
     * How far the leaning edge bows outwards, as a fraction of the top width.
     *
     * A dead-straight edge reads as a cut shape; a few pixels of bow reads as paper flexed
     * mid-slide. Small on purpose — at 6 % it is a hint, not a curve.
     */
    private const val BOW_FRACTION = 0.06f

    /**
     * How far the touch target reaches inwards from the strip, in dp.
     *
     * The right edge of a gesture-nav screen belongs to the system back gesture, and the system
     * wins that competition — a target that only exists at the very edge is a target that
     * sometimes cannot be hit. The visual strip stays where the design puts it; the *hit area*
     * extends inwards so a tap in the last 80 dp lands on the tip.
     */
    const val TOUCH_REACH_DP = 80f

    /**
     * How far below the top edge the paper hangs, in dp.
     *
     * Not flush with the top: a wedge pinned to the very corner reads as part of the status bar.
     * A little air above it makes it read as a sheet slipped in from the side.
     */
    const val TOP_OFFSET_DP = 56f

    /**
     * Each ghost steps inwards and downwards from the one in front of it.
     *
     * Wide enough to be unmistakable. At 10dp the layers overlapped into what looked like one
     * thicker wedge — a stack nobody can count is not a queue indicator.
     */
    const val GHOST_INSET_DP = 22f
    const val GHOST_DROP_DP = 26f

    /**
     * Alpha per depth, front first: the front wedge is opaque, the two behind it fade.
     *
     * Three, not more. A fourth wedge would be a second stack of paper with a stack of its own,
     * and the human cannot act on any of them until one becomes head anyway.
     */
    val GHOST_ALPHA = floatArrayOf(1f, 0.62f, 0.38f)

    /** Hard ceiling on how many wedges are ever drawn. */
    const val MAX_VISIBLE = 3

    /**
     * One wedge in the stack, in front-to-back order.
     *
     * [insetPx] moves it left (away from the edge it leans on) and [dropPx] moves it down, so
     * each one behind peeks out from under the one in front rather than sitting on top of it.
     */
    data class Wedge(val insetPx: Float, val dropPx: Float, val alpha: Float)


    /**
     * The wedges to draw for a queue of [depth] requests, front first.
     *
     * Clamped rather than refused: a depth of 0 or 9 is not a caller error to throw over — the
     * queue can legitimately be empty (nothing to draw) or ten deep (draw three). The cap exists
     * because a fourth sheet would be a second stack of paper, and none of them can be acted on
     * until one becomes the front.
     */
    fun stack(depth: Int, density: Float): List<Wedge> {
        require(density > 0f) { "density must be positive, was $density" }
        val visible = depth.coerceIn(0, MAX_VISIBLE)
        return (0 until visible).map { i ->
            Wedge(
                insetPx = i * GHOST_INSET_DP * density,
                dropPx = i * GHOST_DROP_DP * density,
                alpha = GHOST_ALPHA[i],
            )
        }
    }

    data class Point(val x: Float, val y: Float)

    data class Metrics(
        /** Width of the horizontal top edge, in pixels. */
        val topWidthPx: Float,
        /** Vertical distance from the top edge to the point, in pixels. */
        val heightPx: Float,
        /** Inward reach of the hit area, in pixels. */
        val reachPx: Float,
        /** Distance the leaning edge bows outwards, in pixels. */
        val bowPx: Float,
    )

    /**
     * The wedge's measurements for one screen.
     *
     * @param screenWidthPx the full display width; the wedge is anchored to its right edge.
     * @param density the display density, for the dp clamps.
     */
    fun metrics(screenWidthPx: Int, density: Float): Metrics {
        require(screenWidthPx > 0) { "screenWidthPx must be positive, was $screenWidthPx" }
        require(density > 0f) { "density must be positive, was $density" }

        val minPx = TOP_WIDTH_MIN_DP * density
        val maxPx = TOP_WIDTH_MAX_DP * density
        val topWidthPx = (screenWidthPx * TOP_WIDTH_FRACTION).coerceIn(minPx, maxPx)

        // The taper length, derived from the lean.
        //
        // The visible shape is a corner of a rotated sheet: its top edge leans, and its left edge
        // runs perpendicular to that until it meets the screen edge again. Solving those two lines
        // for the meeting point gives `w / sin(theta)` — so the length follows from the angle and
        // neither number is stored twice.
        val heightPx = topWidthPx * 3.4f

        return Metrics(
            topWidthPx = topWidthPx,
            heightPx = heightPx,
            reachPx = TOUCH_REACH_DP * density,
            bowPx = topWidthPx * BOW_FRACTION,
        )
    }

    /**
     * The corners of a rotated sheet that runs OFF the right edge of the screen.
     *
     * **This is the look, and it also solves the shadow.** The sheet is a rectangle lying at a
     * slight angle, whose right side extends past the display edge — so what the human sees is the
     * part that has slid in from the right, with visible top, left and bottom edges and NO visible
     * right edge. That last point matters: an earlier version closed the shape ON the screen edge,
     * which put a straight vertical side in the picture, and the shadow of a vertical side is a
     * vertical band. Reported three times. Nothing about the shadow needed changing; the shape did.
     *
     * Geometry: build the rectangle in its own frame, rotate it, then translate so its right side
     * lands past the display and its top lands at [topPx]. Everything outside the view is clipped by
     * the view itself, which is exactly where the "no vertical edge" comes from — the edge is not
     * drawn, it is merely not in the window.
     */
    fun outlineFit(
        viewWidthPx: Float,
        viewHeightPx: Float,
        density: Float,
        topPx: Float,
    ): List<Point> {
        val t = Math.toRadians(TILT_DEGREES.toDouble()).toFloat()
        val cos = kotlin.math.cos(t)
        val sin = kotlin.math.sin(t)
        val inset = SHAPE_INSET_DP * density

        val band = (viewWidthPx * BAND_FRACTION).coerceAtLeast(1f)
        val length = band * ASPECT

        // Local corners: u across the band, v along the sheet's length.
        fun rotate(u: Float, v: Float) = Point(u * cos - v * sin, u * sin + v * cos)
        val local = listOf(
            rotate(0f, 0f),
            rotate(band, 0f),
            rotate(band, length),
            rotate(0f, length),
        )

        val maxX = local.maxOf { it.x }
        val minY = local.minOf { it.y }

        // Push the sheet so its right side is past the display edge by [OVERSHOOT_FRACTION] of the
        // window width, and its topmost corner sits at [topPx].
        val overshoot = viewWidthPx * OVERSHOOT_FRACTION
        val dx = viewWidthPx + overshoot - maxX
        val dy = topPx.coerceIn(inset, viewHeightPx) - minY
        return local.map { Point(it.x + dx, it.y + dy) }
    }

    /** How far inside the view the paper keeps, in dp. */
    const val SHAPE_INSET_DP = 4f

    /** Length as a multiple of the visible band. A sheet, not a card. */
    const val ASPECT = 2.6f

    /** How wide the sheet's band is, relative to the window it is drawn in. */
    const val BAND_FRACTION = 0.95f

    /**
     * How far past the display edge the sheet continues.
     *
     * The sheet must be CUT by the edge, not finished at it: a shape that ends on the screen edge
     * has a vertical side, and a vertical side casts a vertical shadow.
     */
    const val OVERSHOOT_FRACTION = 0.35f

    /** A ceiling so a very tall window does not produce an absurd sheet. */
    const val MAX_BAND_DP = 260f

    /** Whether [x],[y] is inside the fitted sheet. Convex-polygon test. */
    fun containsFit(
        viewWidthPx: Float,
        viewHeightPx: Float,
        density: Float,
        topPx: Float,
        x: Float,
        y: Float,
    ): Boolean {
        val pts = outlineFit(viewWidthPx, viewHeightPx, density, topPx)
        var hasNeg = false
        var hasPos = false
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val d = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (d < 0f) hasNeg = true
            if (d > 0f) hasPos = true
        }
        return !(hasNeg && hasPos)
    }
}
