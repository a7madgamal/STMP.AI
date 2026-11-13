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
    /**
     * Where on the SCREEN to stamp, in the overlay window's own coordinates.
     *
     * Null means the original full-screen behaviour: centred, sized against the whole view.
     * Non-null makes the mark land ON that rectangle — the hint card — at roughly its own
     * scale, which is what "stamping on it" means. The rect is a place to press, so the mark
     * is sized from it and cannot drift to the middle of a screen the card does not occupy.
     */
    private val anchor: android.graphics.Rect? = null,
    /**
     * Whether this window can ever take focus.
     *
     * **A `FLAG_NOT_FOCUSABLE` overlay never reports focus, and that is why the card stamp
     * vanished.** The arming loop below waits for `hasWindowFocus()` to hold off the clock while
     * the biometric window dismisses — correct for the modal, which is a normal focusable window.
     * But [com.mido.phonebridge.notify.NotifyOverlay] adds its stamp with `FLAG_NOT_FOCUSABLE`,
     * so focus never arrives, the loop sat out its whole `STAMP_ARM_FALLBACK_MS`, and the 2.2s
     * backstop removed the window first: zero frames, and no impact callback, so no sound.
     *
     * A caller that is already sure it is visible passes false and the clock starts on the first
     * draw. Keeping it a parameter rather than a guess matters: the modal must still WAIT.
     */
    private val focusGate: Boolean = true,
) : View(context) {
    enum class Verdict(val word: String) { APPROVED("APPROVED"), DENIED("DENIED") }

    var onFinished: (() -> Unit)? = null
    var onImpact: (() -> Unit)? = null

    init {
        // A tap dismisses early. The stamp also leaves on its own after STAMP_HOLD_MS, so this is
        // a shortcut for an impatient human, never the only way out — the "wait for a tap" version
        // made every approval a stuck screen (reported 2026-11-11).
        isClickable = true
        setOnClickListener {
            android.util.Log.i("StampOverlay", "dismissed by tap after ${paintCount} paints")
            finishOnce()
        }
    }

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

    /**
     * Actual `onDraw` calls. **The old counter incremented in the tick**, so it measured the
     * timer and not the paint — and it said "105 frames" for a stamp nobody could see. A number
     * that cannot answer the question it is quoted for is worse than no number.
     */
    private var paintCount = 0

    /** When [stamp] was called. The clock starts later; this only measures how long we waited. */
    private var requestedAt = 0L

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (finished || !isAttachedToWindow) return
            if (startedAt == 0L) {
                // **Wait for the window to be ours again.** The biometric prompt is a separate
                // window that is still dismissing when the approve callback fires, so arming with
                // it on top spends the animation where nobody can see it.
                //
                // The fallback exists so a window that never regains focus cannot swallow the
                // receipt entirely: after STAMP_ARM_FALLBACK_MS the stamp plays regardless. Without it,
                // "wait until visible" would mean "never" on an OEM that handles focus oddly.
                val waited = SystemClock.uptimeMillis() - requestedAt
                // A non-focusable window is never going to report focus, so waiting for it is
                // waiting for the fallback — and on the card path the backstop arrives first.
                val blocked = focusGate && !hasWindowFocus()
                if (blocked && waited < PaperMotion.STAMP_ARM_FALLBACK_MS) {
                    postOnAnimation(this)
                    return
                }
                startedAt = SystemClock.uptimeMillis()
                android.util.Log.i(
                    "StampOverlay",
                    "armed after ${waited}ms (focus=${hasWindowFocus()}): $verdict",
                )
            }
            val elapsed = SystemClock.uptimeMillis() - startedAt
            // The contact frame. The thud, the ink bleed and the sheet compression are all keyed
            // off this single instant, so they can never disagree about when impact happened.
            val impactAt = IMPACT_MS
            if (!impactSent && elapsed >= impactAt) {
                impactSent = true
                inkProgress = 0f
                onImpact?.invoke()
                invalidate()
            }
            // **The bleed is TRANSIENT.** It draws the mark plus two offset copies, which reads as
            // ink soaking into paper for a quarter second and as a permanent ghost if it is held.
            // Holding at 1.0 made it permanent and was reported as *"a weird shadow duplicate
            // effect"* (2026-11-11). It now peaks at the contact frame and decays to nothing.
            val bleedElapsed = elapsed - impactAt
            inkProgress = if (!impactSent || bleedElapsed <= 0L) {
                0f
            } else {
                1f - (bleedElapsed.toFloat() / PaperMotion.STAMP_BLEED_MS).coerceIn(0f, 1f)
            }
            invalidate()
            // **The stamp leaves on its own.** A permanent stamp was the previous overcorrection:
            // it fixed invisibility by making every approval a stuck screen (same report). The
            // receipt needs to be readable, not to require a tap.
            if (elapsed >= PaperMotion.STAMP_TOTAL_MS + PaperMotion.STAMP_HOLD_MS) {
                android.util.Log.i(
                    "StampOverlay",
                    "finished: $verdict (paints=$paintCount, ${elapsed}ms)",
                )
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
        // **The clock does NOT start here.** It starts on the first frame that can be seen — see
        // the tick's arming branch. Reported 2026-11-11: *"still no fucking approval animation
        // just a fingerprint request"*. With a biometric prompt, this method runs while the
        // fingerprint window is still dismissing; 650ms of the animation burned behind that
        // dialog, and by the time the modal was visible again `elapsed` was past the total, so it
        // finished on its first frame. Every frame was correct and none was visible.
        requestedAt = SystemClock.uptimeMillis()
        startedAt = 0L
        impactSent = false
        finished = false
        inkProgress = 0f
        paintCount = 0
        removeCallbacks(tick)
        // Start the arming loop NOW, so it can notice focus returning. Waiting for a draw would
        // never start anything while another window is on top and this one is not drawn.
        postOnAnimation(tick)
        invalidate()
    }

    /** Arm on a draw that happens while nothing is on top of us. */
    private fun armIfVisible() {
        if (startedAt != 0L || bitmap == null) return
        if (focusGate && !hasWindowFocus()) return
        startedAt = SystemClock.uptimeMillis()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The biometric window has gone: this is the first moment the stamp can be seen, so ask
        // for a draw, which arms the clock.
        if (hasFocus) {
            android.util.Log.i("StampOverlay", "focus gained; armed=${startedAt != 0L}")
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // No arming here: a measured view is not a visible one, and the whole bug was arming
        // before the biometric window had gone. The next draw arms it.
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Same reason: attached is not visible. onDraw arms it.
        invalidate()
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
        android.util.Log.i("StampOverlay", "finished: $verdict")
        onFinished?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val art = bitmap ?: return
        if (art.width <= 0 || art.height <= 0 || width <= 0 || height <= 0) return
        // Arm on the first draw that happens while no other window is on top.
        armIfVisible()
        paintCount++
        if (paintCount == 1) {
            android.util.Log.i(
                "StampOverlay",
                "first paint: ${width}x${height} art=${art.width}x${art.height}",
            )
        }
        // Still covered (the fingerprint window is closing): draw nothing yet, so no part of the
        // animation is spent where it cannot be seen.
        if (startedAt == 0L) return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (scrimAlpha > 0) canvas.drawColor(scrimAlpha.coerceIn(0, 255) shl 24)
        // The mark lands on [anchor] when one was given, and is sized FROM it: a stamp that
        // presses onto a 216dp card should be about that wide, not a sixth of a phone. Without
        // an anchor the old full-screen geometry applies unchanged.
        val cx = anchor?.exactCenterX() ?: (width / 2f)
        val cyBase = anchor?.exactCenterY() ?: (height / 2f)
        val maxW = anchor?.let { it.width() * ANCHOR_WIDTH_FRACTION } ?: (width * 0.94f)
        val maxH = anchor?.let { it.height() * ANCHOR_HEIGHT_FRACTION } ?: (height * 0.50f)
        // **Raised by half its own height when stamping onto a card.**
        //
        // Centred on the card, the mark's lower half sat on top of the card's own text and read as
        // covering the hint rather than pressing onto it. Lifting it by half its height puts the
        // mark's bottom edge at the card's centre, so it sits over the upper half and leaves the
        // summary readable underneath.
        //
        // The offset is computed from the RESTING height (`scale` = 1), never the animated one.
        // Keying it to the live frame would make the mark rise and fall as it slams, which reads as
        // a wobble instead of a single downward press.
        val restingFit = minOf(maxW / art.width, maxH / art.height)
        val restingH = art.height * restingFit
        val cy = if (anchor != null) cyBase - restingH / 2f else cyBase
        val scale = when {
            elapsed < IMPACT_MS -> 2.0f + (1f - 2.0f) * (elapsed / IMPACT_MS.toFloat())
            elapsed < 210L -> 0.985f
            else -> 1f
        }
        val total = PaperMotion.STAMP_TOTAL_MS + PaperMotion.STAMP_HOLD_MS
        val alpha = when {
            // In over the first frames, full ink while it is being read, out at the very end.
            elapsed < 50L -> elapsed / 50f
            elapsed > total - 200L -> ((total - elapsed).toFloat() / 200f).coerceIn(0f, 1f)
            else -> 1f
        }
        canvas.save()
        val tilt = -52f * (1f - (elapsed / 190f).coerceIn(0f, 1f))
        camera.save()
        camera.setLocation(0f, 0f, -12f * resources.displayMetrics.density)
        camera.rotateX(tilt)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-cx, -cy)
        matrix.postTranslate(cx, cy)
        canvas.concat(matrix)
        canvas.save()
        canvas.rotate(-6f, cx, cy)
        // Both bounds are the RESTING size. `scale` multiplies them, so the overshoot frames are
        // deliberately larger than the card — that excess is what reads as a slam rather than a
        // fade. Only where the mark comes to rest is tied to the anchor.
        val fit = restingFit * scale
        val w = art.width * fit
        val h = art.height * fit
        dst.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        if (impactSent && inkProgress > 0f) {
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

    private companion object {
        /** How much of the anchor's width the resting mark spans. */
        const val ANCHOR_WIDTH_FRACTION = 0.92f

        /** How much of the anchor's height it may span before width wins. */
        const val ANCHOR_HEIGHT_FRACTION = 0.95f

        /**
         * The contact frame, in milliseconds from `stamp()`.
         *
         * The thud, the ink bleed and the sheet compression are all keyed off this one instant
         * so they can never disagree about when the stamp hit the paper. 150ms lets the art
         * travel from the 2× overshoot down to size first.
         */
        const val IMPACT_MS = 150L
    }
}
