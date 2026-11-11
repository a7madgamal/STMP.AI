package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.view.animation.DecelerateInterpolator

/**
 * Only this wrapper moves; its live children keep their normal hit testing and countdown.
 *
 * Camera geometry is derived from the measured sheet, not a hard-coded pixel distance. With the
 * camera one sheet-height away and a +2.5° X rotation, perspective projects the top edge about 9%
 * wider than the bottom. A tiny Z tilt and translation settle with the same clock-driven pass.
 */
class PaperEntranceView(context: Context) : android.widget.FrameLayout(context) {
    private val camera = Camera()
    private val matrix = android.graphics.Matrix()
    private var progress = 1f
    private var tick: Runnable? = null

    init {
        setWillNotDraw(false)
        clipToOutline = false
    }

    fun present(reduced: Boolean) {
        if (reduced) {
            alpha = 0f
            animate().alpha(1f).setDuration(PaperMotion.REDUCED_FADE_MS).start()
            return
        }
        alpha = 1f
        progress = 0f
        val start = android.os.SystemClock.uptimeMillis()
        val frame = object : Runnable {
            override fun run() {
                val fraction = ((android.os.SystemClock.uptimeMillis() - start).toFloat() /
                    PaperMotion.PRESENT_MS).coerceIn(0f, 1f)
                progress = DecelerateInterpolator().getInterpolation(fraction)
                alpha = (fraction / 0.25f).coerceIn(0f, 1f)
                elevation = PaperMotion.dp(context, 4) + PaperMotion.dp(context, 24) * (1f - progress)
                invalidate()
                if (fraction < 1f && isAttachedToWindow) postOnAnimation(this)
                else {
                    progress = 1f
                    elevation = PaperMotion.dp(context, 4)
                    alpha = 1f
                    invalidate()
                }
            }
        }
        tick = frame
        postOnAnimation(frame)
    }

    override fun draw(canvas: Canvas) {
        if (progress >= 1f || width == 0 || height == 0) {
            super.draw(canvas)
            return
        }
        val remaining = 1f - progress
        camera.save()
        camera.setLocation(0f, 0f, -height.toFloat())
        camera.rotateX(PaperMotion.PRESENT_TILT_DEGREES * remaining)
        camera.rotateZ(-1.5f * remaining)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-width / 2f, -height / 2f)
        matrix.postTranslate(
            width / 2f + PaperMotion.dp(context, 8) * remaining,
            height / 2f + PaperMotion.dp(context, 20) * remaining,
        )
        canvas.save()
        canvas.concat(matrix)
        super.draw(canvas)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        tick?.let { removeCallbacks(it) }
        tick = null
        animate().cancel()
        super.onDetachedFromWindow()
    }
}
