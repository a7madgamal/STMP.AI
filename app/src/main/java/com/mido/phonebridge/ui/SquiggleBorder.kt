package com.mido.phonebridge.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * A hand-drawn wobbly outline, for every card, button and rule in the app.
 *
 * **Why not `DiscretePathEffect`.** It is the obvious answer and it is wrong twice: it chops a path
 * into segments with a RANDOM deviation, so the border re-rolls on every draw and visibly crawls
 * (the same trap `TornPaper` documents), and the result is a shaky straight line rather than a
 * drawn one. A wobble is a shape, so it is built as a shape.
 *
 * **How the wobble works.** Each straight edge is subdivided into segments, and each segment is a
 * cubic Bézier whose control points alternate above and below the ideal line. That gives the
 * gentle S-curve of a pen dragged by hand — one bump up, one bump down — instead of noise.
 *
 * **Deterministic by construction.** The deviation per segment comes from a fixed array, not
 * `Random`, so a given width always produces the same wobble. A border that changes between frames
 * reads as a rendering bug, not as ink.
 *
 * **Subtle by default.** 1.5dp of amplitude: enough to see the line was drawn, not enough to make a
 * 40dp button look like a doodle.
 */
class SquiggleBorder(
    private val color: Int,
    private val strokePx: Float,
    private val radiusPx: Float,
    private val amplitudePx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = this@SquiggleBorder.color
    }

    private val path = Path()
    private val builtFor = RectF()
    private var built = false

    /**
     * Per-segment deviation, as a fraction of the amplitude, alternating sign.
     *
     * The pattern repeats, and it is asymmetric on purpose: a perfectly mirrored wobble looks
     * mechanical, which is the opposite of what this is for.
     */
    private val wobble = floatArrayOf(0.85f, -0.60f, 0.40f, -1.00f, 0.55f, -0.75f, 1.00f, -0.35f)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (!built || builtFor != RectF(b)) {
            build(RectF(b))
            builtFor.set(b)
            built = true
        }
        canvas.drawPath(path, paint)
    }

    private fun build(b: RectF) {
        path.reset()
        val amp = amplitudePx
        // Inset by half a stroke so the drawn line sits fully inside the view and cannot be
        // clipped by the parent's bounds.
        val half = strokePx / 2f
        val left = b.left + half
        val top = b.top + half
        val right = b.right - half
        val bottom = b.bottom - half
        if (right <= left || bottom <= top) return

        val r = min(radiusPx, min(right - left, bottom - top) / 2f)
        // Standard circle-to-Bézier constant: a quarter arc drawn with two cubics using this
        // control-point offset is accurate to well under a pixel.
        val k = 0.5523f * r

        // Corners stay true quarter-arcs: a wobbly corner on a 10dp radius reads as damage, not
        // as handwriting. Only the straight runs wobble.
        path.moveTo(left + r, top)
        wavyLine(left + r, top, right - r, top, horizontal = true, amp = amp, from = 0)
        // top-right corner
        path.cubicTo(right - r + k, top, right, top + r - k, right, top + r)
        wavyLine(right, top + r, right, bottom - r, horizontal = false, amp = amp, from = 3)
        // bottom-right
        path.cubicTo(right, bottom - r + k, right - r + k, bottom, right - r, bottom)
        wavyLine(right - r, bottom, left + r, bottom, horizontal = true, amp = amp, from = 5)
        // bottom-left
        path.cubicTo(left + r - k, bottom, left, bottom - r + k, left, bottom - r)
        wavyLine(left, bottom - r, left, top + r, horizontal = false, amp = amp, from = 2)
        // top-left
        path.cubicTo(left, top + r - k, left + r - k, top, left + r, top)
        path.close()
    }

    /**
     * One wavy edge from ([x1],[y1]) to ([x2],[y2]).
     *
     * @param from index into [wobble] to start at, so the four edges do not all begin with the same
     *   deviation — that would make the corners repeat a visible rhythm.
     */
    private fun wavyLine(
        x1: Float, y1: Float, x2: Float, y2: Float,
        horizontal: Boolean, amp: Float, from: Int,
    ) {
        val len = if (horizontal) x2 - x1 else y2 - y1
        if (len == 0f) return

        // One bump per ~34dp-equivalent of length: fewer looks like a bent line, more looks like a
        // saw. Clamped so a very short edge still gets one bump and a very long one does not get
        // hundreds.
        val segments = (kotlin.math.abs(len) / (amp * 24f)).toInt().coerceIn(2, 12)
        val step = len / segments

        var x = x1
        var y = y1
        for (i in 0 until segments) {
            val nx = if (horizontal) x1 + step * (i + 1) else x
            val ny = if (horizontal) y else y1 + step * (i + 1)
            val dev = wobble[(from + i) % wobble.size] * amp
            if (horizontal) {
                val cx1 = x + step / 3f
                val cx2 = nx - step / 3f
                path.cubicTo(cx1, y + dev, cx2, ny + dev, nx, ny)
            } else {
                val cy1 = y + step / 3f
                val cy2 = ny - step / 3f
                path.cubicTo(x + dev, cy1, nx + dev, cy2, nx, ny)
            }
            x = nx
            y = ny
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    @Deprecated("Deprecated in the framework; required by the Drawable contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A hand-drawn horizontal rule — the same wobble, for separators and section underlines.
 *
 * A separator is a 1dp view, so it cannot carry a stroked outline; it needs its own thin shape.
 * The wobble amplitude is capped to a third of the view height so a 1dp rule does not draw outside
 * its own bounds and get clipped into a straight line by accident.
 */
class SquiggleRule(
    private val color: Int,
    private val strokePx: Float,
    private val amplitudePx: Float,
    /** Fraction of the width to cover, from the left. 1.0 spans the whole rule. */
    private val spanFraction: Float = 1.0f,
    /**
     * Draw along the BOTTOM edge instead of through the middle.
     *
     * The default (middle) is right for a separator, where the rule IS the content. It is wrong
     * for an underline: used as a `TextView` background, a centre-line strikes through the text
     * it was meant to sit under — which is exactly what shipped on the section headers and the
     * bottom bar.
     */
    private val atBottom: Boolean = false,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        this.color = this@SquiggleRule.color
    }

    private val path = Path()
    private val builtFor = RectF()
    private var built = false

    private val wobble = floatArrayOf(0.7f, -1.0f, 0.5f, -0.8f, 0.9f, -0.4f, 1.0f, -0.6f)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (!built || builtFor != RectF(b)) {
            build(RectF(b))
            builtFor.set(b)
            built = true
        }
        canvas.drawPath(path, paint)
    }

    private fun build(b: RectF) {
        path.reset()
        // Centre the stroke vertically, and never let the wave leave the view.
        val amp = amplitudePx.coerceAtMost((b.height() - strokePx) / 2f).coerceAtLeast(0f)
        // Room for the wave to swing without leaving the view: half its amplitude plus half the
        // stroke. On the bottom edge that means the line sits just inside the bounds rather than
        // being clipped into a straight line.
        val inset = amp + strokePx / 2f
        val y = if (atBottom) b.bottom - inset else b.centerY()
        val x1 = b.left + strokePx
        val x2 = b.left + b.width() * spanFraction - strokePx
        if (x2 <= x1) return

        val segments = 9
        val step = (x2 - x1) / segments
        path.moveTo(x1, y)
        var x = x1
        for (i in 0 until segments) {
            val nx = x + step
            val dev = wobble[i % wobble.size] * amp
            path.cubicTo(x + step / 3f, y + dev, nx - step / 3f, y - dev, nx, y)
            x = nx
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    @Deprecated("Deprecated in the framework; required by the Drawable contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
