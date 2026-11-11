package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.mido.phonebridge.R

/**
 * Rubber-stamp verdict drawn over an already-resolved request.
 *
 * A single uptime tick owns the presentation and the backstop. Animator callback ordering never
 * owns removal: if a frame is skipped or this view loses its animation clock, the final frame
 * still converges and the caller is told exactly once. No decision, latch or audit timing is
 * changed by this class.
 */
class StampOverlay(
    context: Context,
    private val scrimAlpha: Int = 0x66,
) : View(context) {
    enum class Verdict(val word: String) { APPROVED("APPROVED"), DENIED("DENIED") }

    var onFinished: (() -> Unit)? = null
    var onImpact: (() -> Unit)? = null
    var reducedMotion: Boolean = false

    private var bitmap: android.graphics.Bitmap? = null
    private var verdict = Verdict.APPROVED
    private var startedAt = 0L
    private var impactSent = false
    private var finished = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val bleedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    private val dst = RectF()
    private val bleedDst = RectF()
    private val camera = android.graphics.Camera()
    private val matrix = android.graphics.Matrix()
    private var inkProgress = 0f

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (finished || !isAttachedToWindow) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val total = if (reducedMotion) PaperMotion.REDUCED_FADE_MS else PaperMotion.STAMP_TOTAL_MS
            val impactAt = if (reducedMotion) 0L else 150L
            if (!impactSent && elapsed >= impactAt) {
                impactSent = true
                inkProgress = if (reducedMotion) 1f else 0f
                onImpact?.invoke()
                invalidate()
            }
            inkProgress = ((elapsed - impactAt).toFloat() / 240f).coerceIn(0f, 1f)
            invalidate()
            if (elapsed >= total) {
                finishOnce()
            } else {
                postOnAnimation(this)
            }
        }
    }

    fun stamp(value: Verdict) {
        verdict = value
        bitmap?.recycle()
        bitmap = android.graphics.BitmapFactory.decodeResource(
            resources,
            if (value == Verdict.APPROVED) R.drawable.stamp_approved else R.drawable.stamp_rejected,
        )
        startedAt = SystemClock.uptimeMillis()
        impactSent = false
        finished = false
        inkProgress = if (reducedMotion) 1f else 0f
        removeCallbacks(tick)
        if (width > 0 && height > 0) postOnAnimation(tick)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (startedAt > 0L && w > 0 && h > 0) {
            removeCallbacks(tick)
            postOnAnimation(tick)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (startedAt > 0L) postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun finishOnce() {
        if (finished) return
        finished = true
        onFinished?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startedAt == 0L) return
        val art = bitmap ?: return
        if (art.width <= 0 || art.height <= 0 || width <= 0 || height <= 0) return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (scrimAlpha > 0) canvas.drawColor(scrimAlpha.coerceIn(0, 255) shl 24)
        val cx = width / 2f
        val cy = height / 2f
        val reduced = reducedMotion
        val scale = if (reduced) 1f else when {
            elapsed < 150L -> 2.0f + (1f - 2.0f) * (elapsed / 150f)
            elapsed < 210L -> 0.985f
            elapsed < 260L -> 1f
            else -> 1f
        }
        val alpha = if (reduced) {
            (elapsed.toFloat() / PaperMotion.REDUCED_FADE_MS).coerceIn(0f, 1f)
        } else {
            when {
                elapsed < 50L -> elapsed / 50f
                elapsed > 560L -> 1f - ((elapsed - 560L).toFloat() / 90f).coerceIn(0f, 1f)
                else -> 1f
            }
        }
        canvas.save()
        if (!reduced) {
            val tilt = -52f * (1f - (elapsed / 190f).coerceIn(0f, 1f))
            camera.save()
            camera.setLocation(0f, 0f, -12f * resources.displayMetrics.density)
            camera.rotateX(tilt)
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-cx, -cy)
            matrix.postTranslate(cx, cy)
            canvas.concat(matrix)
        }
        canvas.save()
        canvas.rotate(-6f, cx, cy)
        val fit = minOf(width * 0.94f / art.width, height * 0.50f / art.height) * scale
        val w = art.width * fit
        val h = art.height * fit
        dst.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        if (!reduced && impactSent && inkProgress > 0f) {
            drawMaskedBleed(canvas, art, dst, alpha)
        }
        paint.alpha = (alpha * 0.88f * 255f).toInt()
        paint.colorFilter = android.graphics.PorterDuffColorFilter(
            0xFFC8C8C8.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY,
        )
        canvas.drawBitmap(art, null, dst, paint)
        paint.colorFilter = null
        canvas.restore()
        canvas.restore()
    }

    /** Dilate the actual stamp-alpha mask; transparent pixels never become an opaque halo. */
    private fun drawMaskedBleed(canvas: Canvas, art: android.graphics.Bitmap, rect: RectF, alpha: Float) {
        val spread = 1f + 0.035f * inkProgress
        val w = rect.width() * spread
        val h = rect.height() * spread
        bleedDst.set(rect.centerX() - w / 2f, rect.centerY() - h / 2f,
            rect.centerX() + w / 2f, rect.centerY() + h / 2f)
        val save = canvas.saveLayer(bleedDst, null)
        bleedPaint.alpha = (alpha * inkProgress * 0.30f * 255f).toInt()
        // Two restrained, deterministic offsets read as ink finding paper fibres, not particles.
        for (offset in listOf(-1f, 1f)) {
            val shifted = RectF(bleedDst)
            shifted.offset(offset * inkProgress * resources.displayMetrics.density, 0f)
            canvas.drawBitmap(art, null, shifted, bleedPaint)
        }
        // The source's alpha keys the dilated result. This is the part the old unused `bleed`
        // value never did: a masked bleed from the supplied artwork, not a coloured oval.
        canvas.drawBitmap(art, null, bleedDst, maskPaint)
        canvas.restoreToCount(save)
    }

    @androidx.annotation.VisibleForTesting
    internal fun inkBleedProgressForTest(): Float = inkProgress
}
