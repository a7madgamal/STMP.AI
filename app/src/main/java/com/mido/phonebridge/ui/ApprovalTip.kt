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
    const val TILT_DEGREES = -13f

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
     * A TRIANGLE, by construction. Three points, nothing clipped.
     *
     * Three reports shaped this:
     *
     * 1. "It should ALWAYS appear as a triangle, not a broken rectangle." A rectangle whose side is
     *    cut by the screen edge shows a band with two square corners — which is a broken rectangle
     *    however it is positioned. A triangle cannot look broken: it has one vertex and two edges
     *    that both run to the edge.
     * 2. "Bottom side is cut off." The previous shape was a long sheet inside a short window, so its
     *    lower end was clipped by the window rather than finished by the shape. This one is sized to
     *    fit the window exactly, so the point is always visible and always the paper's own point.
     * 3. "Still too far in." The taper's tip is what makes it read as tucked behind the edge, so the
     *    apex leans in only as far as the touch target needs.
     *
     * The apex is at [apexInsetPx] from the right edge; the two top corners sit ON the edge, so the
     * shape is flush against the screen and there is no vertical side of its own to cast a vertical
     * shadow — the edge it meets belongs to the screen.
     */
    fun outlineFit(
        viewWidthPx: Float,
        viewHeightPx: Float,
        density: Float,
        topPx: Float,
    ): List<Point> {
        val inset = SHAPE_INSET_DP * density
        val top = topPx.coerceIn(inset, (viewHeightPx - inset).coerceAtLeast(inset + 1f))
        val bottom = (viewHeightPx - inset).coerceAtLeast(top + 1f)
        val right = viewWidthPx

        // A horizontal top edge of [bandPx], then two edges down to a single apex: a stalactite of
        // paper hanging from the top edge, widest at the edge and tapering to nothing.
        val band = (viewWidthPx * BAND_FRACTION).coerceAtLeast(1f)
        val apexX = (right - apexInsetPx(density)).coerceAtLeast(band * 0.2f)

        return listOf(
            Point(right - band, top),
            Point(right, top),
            Point(apexX, bottom),
        )
    }

    /** How far the point of the taper reaches in from the right edge, in px. */
    fun apexInsetPx(density: Float): Float = APEX_INSET_DP * density

    /**
     * Whether [x],[y] is inside the triangle.
     *
     * Convex test: the point must be on the same side of all three edges. Order-independent, so a
     * shape change cannot silently invert it.
     */
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

    /** How wide the top edge is, relative to the window it hangs in. */
    const val BAND_FRACTION = 1f

    /** How far the apex reaches in from the right edge, in dp. A comfortable target, not a sheet. */
    const val APEX_INSET_DP = 84f

    /** How far inside the view the paper keeps, in dp. */
    const val SHAPE_INSET_DP = 4f
}
