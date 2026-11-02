package com.mido.phonebridge.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A coloured band down the left edge of a surface, drawn as a **layer** so it cannot be
 * laid out.
 *
 * **Why this exists at all.** The risk tint on the `NOTIFY` card was a child `View` with a
 * height, and that made the card's size depend on the tint. It shipped broken twice:
 * 0.6.2 (a `MATCH_PARENT` child under a `WRAP_CONTENT` parent forced the card to the whole
 * window) and 0.25.1 (the same bug again, invisible until the AI summary made the strip
 * `VISIBLE`). The 0.25.1 fix moved the height into a `post {}` stretch — which is not a fix,
 * it is the same dependency wearing a hat: the card still gets taller when the tint appears,
 * only now via a stale measured height instead of a layout constant.
 *
 * A background **layer** cannot participate in layout at all, so a tint drawn here can never
 * change the surface's size, whatever its colour or visibility. That is the whole point: the
 * AI arriving must touch text and pixels, never geometry.
 *
 * The left corners are rounded so the band follows the card's own silhouette instead of
 * drawing a square corner over it.
 */
class EdgeBand(
    private val widthPx: Float,
    private val cornerPx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val clip = Path()

    /** Corner radii in the order `Path.addRoundRect` wants: TL, TR, BR, BL. */
    private val radii = floatArrayOf(cornerPx, cornerPx, 0f, 0f, 0f, 0f, cornerPx, cornerPx)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || paint.alpha == 0) return
        rect.set(b.left.toFloat(), b.top.toFloat(), b.left + widthPx, b.bottom.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(clip, paint)
    }

    /**
     * Set the band's colour and make it visible in one call.
     *
     * Deliberately NOT a separate `show()`: a band with a colour and `alpha = 0` is the
     * "no assessment yet" state, and two calls that must agree is exactly how a surface ends
     * up tinted but invisible, or visible with the previous card's colour.
     */
    fun set(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
        invalidateSelf()
    }

    @Deprecated("Deprecated in the framework; required by the Drawable contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
