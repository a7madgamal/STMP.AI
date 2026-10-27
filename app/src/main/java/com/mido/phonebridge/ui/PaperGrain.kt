package com.mido.phonebridge.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.Random

/**
 * Paper grain: the fibre texture that makes a flat colour read as a sheet.
 *
 * **Why this exists.** The palette was already warm cream with torn edges and hand-drawn rules, and
 * it still read as "beige UI" rather than paper. Real paper is never one flat value: it has fibre,
 * sparse darker specks and slight density variation, and the eye reads that variation as material.
 * A single `setColor` cannot produce it.
 *
 * **Deterministic, not random per frame.** The tile is built once from a fixed seed and cached, so
 * the grain is identical on every repaint. A `Random` inside `draw()` would re-roll each frame and
 * the surface would visibly crawl — the same trap the torn edge documents.
 *
 * **A tiled shader, not a full-screen bitmap.** One 96×96 tile repeated by `BitmapShader` costs
 * ~37 KB however large the surface is, so a full-screen background and a 40dp chip pay the same.
 */
object PaperGrain {

    private const val TILE = 96

    /** Fixed seed: the specks must not move between frames. */
    private const val SEED = 0x5EEDL

    /**
     * Strength of the texture.
     *
     * 22 was too timid: over cream, at phone brightness, the fibre was invisible and the surface
     * still read as flat beige. 34 is the range where it is visible as material without looking
     * like dirt on the glass.
     */
    private const val ALPHA = 34

    private var shader: BitmapShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun ensureShader(): BitmapShader {
        shader?.let { return it }
        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val rnd = Random(SEED)
        val pixels = IntArray(TILE * TILE)

        for (i in pixels.indices) {
            // Three populations, so the texture has depth rather than uniform static:
            // most pixels transparent (the paper itself), sparse dark fibre, rarer light fleck.
            val roll = rnd.nextInt(100)
            pixels[i] = when {
                roll < 18 -> Color.argb(ALPHA, 90, 74, 52)
                roll < 26 -> Color.argb(ALPHA / 2, 255, 252, 244)
                else -> Color.TRANSPARENT
            }
        }
        bmp.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE)

        val s = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.shader = s
        shader = s
        return s
    }

    /** The paint that draws the grain. Shared, and immutable after the first call. */
    fun paint(): Paint {
        ensureShader()
        return paint
    }
}

/**
 * Grain only, clipped to a rounded rect — layered ON TOP of whatever surface is already there.
 *
 * Layering rather than wrapping keeps every existing caller untouched: a card can be a
 * `GradientDrawable`, a torn-paper path or anything else, and the grain is one extra layer.
 *
 * The clip matters. An unclipped shader paints square corners over a rounded card and the corners
 * go visibly dirty, which looks like a rendering bug rather than texture.
 */
class GrainOverlay(private val cornerPx: Float = 0f) : Drawable() {

    private val clip = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        if (cornerPx > 0f) {
            rect.set(b)
            clip.reset()
            clip.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
            canvas.clipPath(clip)
        } else {
            canvas.clipRect(b)
        }
        canvas.drawPaint(PaperGrain.paint())
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { /* grain is fixed-strength; alpha lives in the tile */ }

    override fun setColorFilter(cf: ColorFilter?) { /* fixed colour by design */ }

    @Deprecated("Deprecated in the framework; required by the Drawable contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A full-bleed sheet: the paper base plus grain, for a screen root. */
class PaperSheet(private val fill: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        paint.color = fill
        paint.shader = null
        canvas.drawRect(bounds, paint)
        canvas.drawPaint(PaperGrain.paint())
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
