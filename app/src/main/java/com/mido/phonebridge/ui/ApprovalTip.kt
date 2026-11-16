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
    const val TILT_DEGREES = 15f

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

    /** Each ghost steps inwards and downwards from the one in front of it. */
    const val GHOST_INSET_DP = 10f
    const val GHOST_DROP_DP = 6f

    /**
     * Alpha per depth, front first: the front wedge is opaque, the two behind it fade.
     *
     * Three, not more. A fourth wedge would be a second stack of paper with a stack of its own,
     * and the human cannot act on any of them until one becomes head anyway.
     */
    val GHOST_ALPHA = floatArrayOf(1f, 0.45f, 0.22f)

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
     * Whether [x],[y] falls on the wedge whose top edge is at [topPx].
     *
     * **Pure arithmetic, not a `Region`.** The wedge is a triangle, so a point test is three
     * cross products — and it is a FUNCTION, so the hit area can be asserted in a JVM test
     * instead of trusted. A `Region` would need rebuilding on every layout and can answer
     * differently under a shadowed runtime than on the device, which is the last thing a hit
     * target should do.
     *
     * The bow is deliberately ignored: it is 6 % of the width, well inside a fingertip, and
     * approximating the curved edge with the straight one can only make the target slightly
     * LARGER — a tap near the paper's edge still opens it, which is the forgiving direction.
     */
    fun contains(m: Metrics, screenWidthPx: Int, topPx: Float, x: Float, y: Float): Boolean {
        val right = screenWidthPx.toFloat()
        // Triangle: top-right (A), top-left (B), point (C).
        val ax = right; val ay = topPx
        val bx = right - m.topWidthPx; val by = topPx
        val cx = right; val cy = topPx + m.heightPx
        // Cross products of the three edges; one consistent sign means inside. The triangle
        // winds one way only, so a single convention is enough and no ordering can flip it.
        val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
        val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
        val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

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

        // tan(15°) = width / height, so the height is the width divided by it. This is the
        // whole taper: change the tilt and the length follows, which is why neither number is
        // stored twice.
        val heightPx = topWidthPx / tan(TILT_DEGREES * PI.toFloat() / 180f)

        return Metrics(
            topWidthPx = topWidthPx,
            heightPx = heightPx,
            reachPx = TOUCH_REACH_DP * density,
            bowPx = topWidthPx * BOW_FRACTION,
        )
    }

    /**
     * The three corners of the wedge, front-face, anchored to the right edge at [topPx].
     *
     * Order matters: top-right, top-left, then the point at the bottom of the leaning edge.
     * A `Path` closes from the last point back to the first, which draws the right edge as the
     * vertical screen edge it is meant to be flush against.
     */
    fun outline(m: Metrics, screenWidthPx: Int, topPx: Float): List<Point> {
        val right = screenWidthPx.toFloat()
        return listOf(
            Point(right, topPx),
            Point(right - m.topWidthPx, topPx),
            Point(right, topPx + m.heightPx),
        )
    }

    /**
     * Control point for the bow on the leaning edge, pulled inwards (left) from its midpoint.
     *
     * Inwards, because the leaning edge is the wedge's LEFT boundary: bowing it left makes the
     * paper flex OUT, which is the shape a sheet makes when it is caught mid-slide. Bowing it
     * right would eat into the strip and make the tip look pinched instead.
     *
     * Returned rather than baked into [outline] so the view can `quadTo` it and a test can
     * assert the clamp: on a narrow screen a fixed bow could push the control point off the
     * left edge, and a quadratic control point outside the window draws a curve that leaves it.
     */
    fun bowControl(m: Metrics, screenWidthPx: Int, topPx: Float): Point {
        val right = screenWidthPx.toFloat()
        val topLeft = Point(right - m.topWidthPx, topPx)
        val point = Point(right, topPx + m.heightPx)
        val midX = (topLeft.x + point.x) / 2f
        val midY = (topLeft.y + point.y) / 2f
        // Floored at 0 so the curve can never be dragged off the left edge of the display.
        val x = (midX - m.bowPx).coerceAtLeast(0f)
        return Point(x, midY)
    }
}
