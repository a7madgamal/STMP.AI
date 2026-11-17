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
 * A hand-inked rule that sits under a section header.
 *
 * **Only the bottom strip is painted.** Drawn as a `Drawable` that occupies a thin band and marks
 * its own bottom edge, so it can be a `TextView`'s background without filling the whole view — the
 * first attempt used a plain `GradientDrawable` there and turned every header into a solid bar.
 *
 * Separate from the torn card edge on purpose: a card's edge is a silhouette cut into a shape, and
 * this is a line drawn on paper. They are different marks, so they are different classes. The card
 * silhouette lives in `TornEdge` / `Theme.tornCard`; this file draws only lines.
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

    /** setAlpha scales this, never replaces it. */
    private val declaredAlpha = android.graphics.Color.alpha(color)
    private var alphaMul = 255

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
        alphaMul = alpha.coerceIn(0, 255)
        paint.alpha = declaredAlpha * alphaMul / 255
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    @Deprecated("Deprecated in the framework; required by the Drawable contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
