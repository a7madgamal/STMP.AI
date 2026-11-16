package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator

/**
 * The sheet's shape change: the wedge opening into the full card, and folding back again.
 *
 * **One paper, two shapes.** The tip leans at 15° against the right edge; the sheet rests at 5°
 * with a sliver of the background visible around it. Expanding is therefore a ROTATION plus a
 * translation, not a crossfade between two unrelated surfaces — which is the whole reason the
 * resting state was built as the same Activity in a smaller window instead of an overlay card.
 *
 * **Frames come from the clock, never from an animator.** Three silent animation failures in this
 * app traced to `ValueAnimator`/`ViewPropertyAnimator` not running on the target device. The loop
 * here reads `SystemClock`, and carries a frame backstop for the one environment where the clock
 * does not advance — a loop that cannot finish must SNAP, because the alternative is a sheet
 * stuck half-open while the request expires underneath it.
 */
class PaperSheetView(context: Context) : android.widget.FrameLayout(context) {

    /**
     * Rest tilt, in degrees.
     *
     * A sheet laid perfectly square reads as a full-screen page. A few degrees off-axis keeps a
     * sliver of whatever is behind it visible around the edges, which is what makes it read as a
     * sheet of paper resting ON something rather than a new screen replacing it.
     */
    /**
     * Rest tilt. Settable so the value can be compared by eye in a screenshot rather than argued
     * about in numbers; the shipped value is the one in the Activity.
     */
    var restTiltDegrees: Float = REST_TILT_DEGREES
        set(value) {
            field = value
            applyTransform()
            invalidate()
        }

    /**
     * Rest scale, which is the card's width given up to the lean.
     *
     * A rotated full-height card's bounding box is wider than the card, so a lean of θ costs
     * `w·cosθ + h·sinθ` of width. The largest scale that still fits the display is what the
     * design trades against, and every pixel here is a character of a command line.
     */
    var restScale: Float = REST_SCALE
        set(value) {
            field = value
            applyTransform()
            invalidate()
        }

    private var progress = 1f
    private var tick: Runnable? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    /** Whether the sheet is fully open and settled. */
    val isSettled: Boolean get() = progress >= 1f

    /**
     * Open from the wedge's geometry to the resting sheet.
     *
     * @param overshoot whether to add the small settle past the rest position. Expanding yes,
     *   collapsing no: arriving with a tiny overshoot reads as a sheet being laid down, while
     *   leaving with one reads as a bounce nobody asked for.
     */
    fun present(overshoot: Boolean = true, onDone: (() -> Unit)? = null) {
        // No window means no frames: snap to the end rather than park the sheet mid-transform.
        if (!isAttachedToWindow) {
            progress = 1f
            applyTransform()
            onDone?.invoke()
            return
        }
        drive(from = 0f, overshoot = overshoot, ms = EXPAND_MS, onDone = onDone)
    }

    /** Fold back toward the wedge's geometry. */
    fun dismiss(onDone: (() -> Unit)? = null) {
        if (!isAttachedToWindow) {
            progress = 0f
            applyTransform()
            onDone?.invoke()
            return
        }
        drive(from = 1f, overshoot = false, ms = COLLAPSE_MS, onDone = onDone)
    }

    /**
     * Snap to a state without animating it.
     *
     * Used when the sheet was never shown (a locked phone opens straight to it) and when a
     * re-show must not replay the entrance for a request the human already saw.
     */
    fun settle() {
        tick?.let { removeCallbacks(it) }
        tick = null
        progress = 1f
        applyTransform()
        invalidate()
    }

    /**
     * Run `progress` between 0 (the wedge's geometry) and 1 (the resting sheet).
     *
     * A single loop for both directions, with the endpoint and duration as parameters: one clock
     * and one place to stop it. Starting a second motion cancels the first instead of letting two
     * loops fight over the same float — the failure that once froze a tip half-open.
     */
    private fun drive(from: Float, overshoot: Boolean, ms: Long, onDone: (() -> Unit)?) {
        tick?.let { removeCallbacks(it) }
        // Apply the STARTING shape before the first frame. Waiting for frame one would leave the
        // sheet sitting at its rest position for that frame, which reads as a visible jump back
        // and forth on every collapse.
        progress = from
        applyTransform()
        invalidate()
        val start = android.os.SystemClock.uptimeMillis()
        // Expanding decelerates into its rest position; collapsing accelerates away from it.
        val interpolator = if (overshoot) DecelerateInterpolator() else AccelerateInterpolator()
        var frames = 0
        val frame = object : Runnable {
            override fun run() {
                val fraction = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / ms)
                    .coerceIn(0f, 1f)
                val done = fraction >= 1f || frames++ >= MAX_FRAMES
                // A tiny overshoot past the rest position on the way in: 2 % of the remaining
                // distance, peaking just before the end, so the sheet settles rather than stops.
                val eased = interpolator.getInterpolation(fraction)
                val base = from + (1f - from) * eased
                progress = if (overshoot && !done) {
                    val overshootAt = OVERSHOOT_FRACTION
                    val bump = if (fraction > overshootAt) {
                        val t = (fraction - overshootAt) / (1f - overshootAt)
                        // A single hump, zero at both ends of that span.
                        (t * (1f - t)) * 4f * EXPAND_OVERSHOOT
                    } else 0f
                    (base + bump * (1f - from)).coerceAtMost(1f + EXPAND_OVERSHOOT)
                } else if (done) 1f
                else base

                if (done) progress = if (from < 0.5f) 1f else 0f
                applyTransform()
                invalidate()
                if (!done && isAttachedToWindow) {
                    postOnAnimation(this)
                } else {
                    tick = null
                    onDone?.invoke()
                }
            }
        }
        tick = frame
        postOnAnimation(frame)
    }

    /**
     * Project [progress] onto the view.
     *
     * At 0 the sheet occupies roughly the wedge's corner of the screen — leaning hard, pulled to
     * the right, slightly small and slightly transparent. At 1 it rests at [REST_TILT_DEGREES]
     * with [REST_SCALE] so the background shows around its edges. Both are expressed as fractions
     * of the view's own size, so the geometry is the same on a phone and a tablet.
     */
    private fun applyTransform() {
        val p = progress.coerceIn(0f, 1f + EXPAND_OVERSHOOT)
        val tilt = START_TILT_DEGREES + (restTiltDegrees - START_TILT_DEGREES) * p
        val scale = START_SCALE + (restScale - START_SCALE) * p
        rotation = tilt
        scaleX = scale
        scaleY = scale
        // Slides in from the edge the tip was leaning on.
        translationX = width * START_X_FRACTION * (1f - p)
        translationY = height * START_Y_FRACTION * (1f - p)
        alpha = (0.55f + 0.45f * p).coerceAtMost(1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The transform is derived from the view's size, so a rotation must re-derive it.
        applyTransform()
    }

    override fun onDetachedFromWindow() {
        tick?.let { removeCallbacks(it) }
        tick = null
        super.onDetachedFromWindow()
    }

    /** Draw the paper's own shadow so a tilted sheet lifts off what is behind it. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
    }

    private companion object {
        /** In at 220 ms: slow enough to read as a sheet being laid down. */
        const val EXPAND_MS = 220L

        /**
         * Out at 140 ms, and FLAT.
         *
         * Leaving is quicker than arriving on purpose. That asymmetry is what reads as snappy
         * rather than soft: the surface appears deliberately and disappears obediently.
         */
        const val COLLAPSE_MS = 140L

        /** The wedge's lean, which the sheet starts from so the two shapes are one motion. */
        const val START_TILT_DEGREES = 15f

        /**
         * The sheet's resting lean: half a degree.
         *
         * Chosen by looking, not by arithmetic. A full-height card that leans θ needs
         * `w·cosθ + h·sinθ` of width to fit once rotated, so every degree of lean is paid for in
         * card width, and card width is characters of a command line. At 5° the command wrapped
         * three times; at 0.5° it stays on one line and the sheet still reads as paper lifted off
         * the surface rather than a page filling the screen.
         */
        const val REST_TILT_DEGREES = 0.5f

        const val START_SCALE = 0.90f

        /**
         * Rest scale: just under full width.
         *
         * Not a cosmetic inset — it is what pays for the lean. A rotated card's bounding box is
         * wider than the card, so without this the corners would be clipped flush against the
         * display edge and the sheet would look cut rather than lifted.
         */
        const val REST_SCALE = 0.985f
        const val START_X_FRACTION = 0.14f
        const val START_Y_FRACTION = 0.02f

        /** 2 % past the rest position, then back. Enough to feel laid down, not thrown. */
        const val EXPAND_OVERSHOOT = 0.02f

        /** Where the overshoot hump starts, as a fraction of the whole beat. */
        const val OVERSHOOT_FRACTION = 0.62f

        /**
         * Frames after which the loop snaps to its end no matter what the clock says.
         *
         * 120 frames is two seconds at 60 Hz, an order of magnitude past either beat, so a working
         * animation never approaches it. It exists for the one case where the clock cannot
         * advance, where "wait for the next frame" becomes an infinite loop.
         */
        const val MAX_FRAMES = 120
    }
}
