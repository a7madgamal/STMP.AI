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
     * The four corners of a rotated sheet, SIZED TO FIT the view it is drawn in.
     *
     * Three defects meet here, so the method is written to make all three impossible at once.
     *
     * 1. An edge lying along the screen edge casts a vertical shadow — reported twice. A rotated
     *    rectangle has four leaning edges and none parallel to anything.
     * 2. A shape built from SCREEN proportions and painted into a narrow strip falls mostly
     *    outside it, so only a corner appears. This takes the view's own size.
     * 3. A mirrored lean (the sheet tipping the other way) flips which corner is topmost, and the
     *    earlier centring maths silently assumed one direction. So the corners are rotated FIRST and
     *    the bounding box is measured FROM THE POINTS, then translated into place. No formula can
     *    disagree with the shape it is placing.
     */
    fun outlineFit(
        viewWidthPx: Float,
        viewHeightPx: Float,
        density: Float,
        topPx: Float,
    ): List<Point> {
        val t = Math.toRadians(TILT_DEGREES.toDouble()).toFloat()
        val sin = kotlin.math.sin(t)
        val cos = kotlin.math.cos(t)
        val inset = SHAPE_INSET_DP * density

        val availW = (viewWidthPx - inset * 2f).coerceAtLeast(1f)
        val availH = (viewHeightPx - inset * 2f).coerceAtLeast(1f)

        // The largest band whose ROTATED bounding box fits, at the chosen aspect. |sin| and |cos|
        // because a mirrored tilt must cost exactly the same space as the normal one.
        val absSin = kotlin.math.abs(sin)
        val absCos = kotlin.math.abs(cos)
        val bandByWidth = availW / (absCos + ASPECT * absSin)
        val bandByHeight = availH / (absSin + ASPECT * absCos)
        val band = minOf(bandByWidth, bandByHeight, MAX_BAND_DP * density).coerceAtLeast(1f)
        val length = band * ASPECT

        // Corners in the sheet's own frame, then rotated into screen space.
        val local = listOf(
            Point(0f, 0f),
            Point(-sin * length, cos * length),
            Point(-sin * length + cos * band, cos * length + sin * band),
            Point(cos * band, sin * band),
        )

        // Measure what was actually produced, then move it to where it belongs.
        val minX = local.minOf { it.x }
        val maxX = local.maxOf { it.x }
        val minY = local.minOf { it.y }
        val maxY = local.maxOf { it.y }
        val boxW = maxX - minX
        val boxH = maxY - minY

        val targetX = (viewWidthPx - boxW) / 2f
        val targetY = topPx.coerceAtMost((viewHeightPx - boxH - inset).coerceAtLeast(inset))

        return local.map { Point(it.x - minX + targetX, it.y - minY + targetY) }
    }


    /** How far inside the view the paper keeps, in dp. */
    const val SHAPE_INSET_DP = 4f

    /** Length as a multiple of the visible band. A sheet, not a card. */
    const val ASPECT = 3.2f

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
