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
    /**
     * How far each queued sheet steps in from the edge.
     *
     * Small: the sheets are stacked BEHIND the same edge, so their points line up along the sliver
     * rather than fanning across the screen. Enough to count them, little enough that they read as
     * one bundle of paper.
     */
    const val GHOST_INSET_DP = 5f

    /** How far each queued sheet steps down, so the stack reads as layers rather than a smear. */
    const val GHOST_DROP_DP = 34f

    /**
     * Alpha per depth, front first: the front wedge is opaque, the two behind it fade.
     *
     * Three, not more. A fourth wedge would be a second stack of paper with a stack of its own,
     * and the human cannot act on any of them until one becomes head anyway.
     */
    val GHOST_ALPHA = floatArrayOf(1f, 0.82f, 0.66f)

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
     * The visible corner of a sheet of paper: a RIGHT-ANGLED triangle.
     *
     * Three reports shaped this, and the third is the one that explains the others:
     *
     *  - **"a fucking paper is 90 degrees"** — a sheet's corner is a RIGHT ANGLE. Any shape whose
     *    visible corner is not 90 is not paper; it is a wedge, a lozenge or a spike, which is what
     *    every earlier version looked like. The right angle is the property that makes it read as a
     *    corner rather than a cut.
     *  - **"1cm only"** — a physical size, so it is 1cm on every screen and resolution.
     *  - **"top and bottom must be right 0"** — the two legs run to the edge; the corner pokes in.
     *
     * Built in the sheet's own frame and then ROTATED, so no leg lies parallel to the screen: a leg
     * along the screen edge would cast a vertical shadow, which is the defect reported earlier.
     */
    fun outlineFit(
        viewWidthPx: Float,
        viewHeightPx: Float,
        density: Float,
        topPx: Float,
    ): List<Point> {
        val leg = apexInsetPx(density)
        val t = Math.toRadians(TILT_DEGREES.toDouble()).toFloat()
        val cos = kotlin.math.cos(t)
        val sin = kotlin.math.sin(t)

        // The corner in the paper's own frame: a right angle at the origin, one leg along +u and one
        // along +v. Rotating the whole thing is what keeps both legs off the screen axes.
        val local = listOf(
            Point(0f, 0f),   // the corner — exactly 90 degrees between the two legs
            Point(leg, 0f),  // leg one
            Point(0f, leg),  // leg two, perpendicular by construction
        )
        val rotated = local.map { Point(it.x * cos - it.y * sin, it.x * sin + it.y * cos) }

        // Place it so the corner sits at the right edge, with the legs running inwards and down.
        val maxX = rotated.maxOf { it.x }
        val minY = rotated.minOf { it.y }
        val top = topPx.coerceIn(0f, (viewHeightPx - 1f).coerceAtLeast(0f))
        // Clamp inside the view, so a window smaller than the shape's bounding box squeezes the
        // corner in rather than letting it hang outside — a shape drawn outside its window is
        // invisible, which is worse than a shape that is merely clipped.
        val dx = (viewWidthPx - maxX).coerceAtLeast(-rotated.minOf { it.x })
        val dy = top - minY
        return rotated.map {
            Point(
                (it.x + dx).coerceIn(0f, viewWidthPx),
                (it.y + dy).coerceIn(0f, viewHeightPx),
            )
        }
    }

    /** Length of each leg of the corner, in px — 1cm. */
    fun apexInsetPx(density: Float): Float = APEX_INSET_DP * density

    /**
     * The width and height the rotated corner needs, in px.
     *
     * A rotated right angle no longer fits in a square of its leg length: the bounding box grows.
     * The window asks for this rather than guessing, so the shape can never be clipped by the
     * window that holds it — the defect behind "bottom side is cut off".
     */
    fun boundingBox(density: Float): Pair<Float, Float> {
        val leg = apexInsetPx(density)
        val t = Math.toRadians(TILT_DEGREES.toDouble()).toFloat()
        val cos = kotlin.math.abs(kotlin.math.cos(t))
        val sin = kotlin.math.abs(kotlin.math.sin(t))
        return Pair(leg * (cos + sin), leg * (cos + sin))
    }

    /**
     * Whether [x],[y] is inside the corner.
     *
     * Convex test: same side of all three edges. Order-independent, so a shape change cannot
     * silently invert it.
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

    /**
     * One leg of the corner, in dp — 1cm.
     *
     * A physical size, not a share of the display: "1cm" is the same corner on every screen, which a
     * fraction of the width can never guarantee.
     */
    const val APEX_INSET_DP = 1f * 160f / 2.54f

    /** How far inside the view the paper keeps, in dp. */
    const val SHAPE_INSET_DP = 4f
}
