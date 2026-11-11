package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.View

/**
 * A deterministic tear of a SNAPSHOT, never the live approval controls or their request model.
 *
 * The snapshot is bounded by the current view size and recycled on detach/finish. Two clipped
 * halves separate with small opposing rotations, then the caller's already-resolved screen exits.
 */
class PaperTearView(context: Context) : View(context) {
    private var snapshot: Bitmap? = null
    private var top: Bitmap? = null
    private var bottom: Bitmap? = null
    private var topClip: Path? = null
    private var bottomClip: Path? = null
    private var startedAt = 0L
    private var finished = false
    var onFinished: (() -> Unit)? = null

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (finished || !isAttachedToWindow) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            alpha = (1f - elapsed.toFloat() / PaperMotion.TEAR_MS).coerceIn(0f, 1f)
            invalidate()
            if (elapsed >= PaperMotion.TEAR_MS) finishOnce() else postOnAnimation(this)
        }
    }

    fun tear(source: View): Boolean {
        if (source.width <= 0 || source.height <= 0) return false
        val full = try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return false
        }
        source.draw(Canvas(full))
        val split = full.height / 2
        val upper = Bitmap.createBitmap(full, 0, 0, full.width, split)
        val lower = Bitmap.createBitmap(full, 0, split, full.width, full.height - split)
        snapshot = full
        top = upper
        bottom = lower
        val edge = floatArrayOf(0f, -5f, 3f, -2f, 6f, -4f, 1f, -3f, 4f, 0f)
        topClip = Path().apply {
            moveTo(0f, 0f)
            lineTo(full.width.toFloat(), 0f)
            lineTo(full.width.toFloat(), split.toFloat())
            edge.forEachIndexed { index, dy ->
                val x = full.width * (1f - index.toFloat() / (edge.size - 1))
                lineTo(x, split + dy * resources.displayMetrics.density)
            }
            close()
        }
        bottomClip = Path().apply {
            moveTo(0f, 0f)
            edge.forEachIndexed { index, dy ->
                lineTo(full.width * index.toFloat() / (edge.size - 1), dy * resources.displayMetrics.density)
            }
            lineTo(full.width.toFloat(), lower.height.toFloat())
            lineTo(0f, lower.height.toFloat())
            close()
        }
        startedAt = SystemClock.uptimeMillis()
        finished = false
        alpha = 1f
        if (width > 0 && height > 0) postOnAnimation(tick)
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (startedAt > 0L) postOnAnimation(tick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val upper = top ?: return
        val lower = bottom ?: return
        val upperPath = topClip ?: return
        val lowerPath = bottomClip ?: return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val fraction = (elapsed.toFloat() / PaperMotion.TEAR_MS).coerceIn(0f, 1f)
        val travel = PaperMotion.dp(this.context, 24) * fraction
        canvas.save()
        canvas.translate(-travel, travel)
        canvas.rotate(-3.5f * fraction, upper.width / 2f, upper.height.toFloat())
        canvas.clipPath(upperPath)
        canvas.drawBitmap(upper, 0f, 0f, null)
        canvas.restore()
        canvas.save()
        canvas.translate(travel, upper.height - travel)
        canvas.rotate(3.5f * fraction, lower.width / 2f, 0f)
        canvas.clipPath(lowerPath)
        canvas.drawBitmap(lower, 0f, 0f, null)
        canvas.restore()
    }

    private fun finishOnce() {
        if (finished) return
        finished = true
        recycle()
        onFinished?.invoke()
    }

    private fun recycle() {
        snapshot?.recycle()
        top?.recycle()
        bottom?.recycle()
        snapshot = null
        top = null
        bottom = null
        topClip = null
        bottomClip = null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        recycle()
        super.onDetachedFromWindow()
    }
}
