package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mido.phonebridge.ui.tokens.Motion

/**
 * The resting approval tip: one to three paper wedges leaning against the right edge.
 *
 * **Only the front wedge is real.** The ones behind it are paper and nothing else — no text, no
 * hit area, no clock. A queue you can act on from the third sheet would be a way to answer a
 * request you have not read, so depth is decoration until a request becomes the head.
 *
 * **Frames come from the clock, never from an animator.** Three silent animation failures in this
 * app traced to `ValueAnimator`/`ViewPropertyAnimator` not running on the target device (an
 * invisible overlay card, a stalled fade, a countdown that never moved). So the arrival, the
 * promotion step and the collapse are one `postOnAnimation` loop reading `SystemClock`. If the
 * loop stops, the tip stops — visible and honest, not frozen mid-slide while the request expires
 * underneath it.
 *
 * **Geometry is not decided here.** Width, taper, bow and stack offsets all come from
 * [ApprovalTip], where a JVM test can check them. This class only draws, and only hit-tests, what
 * that object returns.
 */
class ApprovalTipView(context: Context) : View(context) {

    /** How many wedges to draw, 1..[ApprovalTip.MAX_VISIBLE]. Set from the gate's queue depth. */
    var depth: Int = 1
        set(value) {
            if (field == value) return
            field = value
            rebuildPaths()
            invalidate()
        }

    /**
     * 0 = fully off the right edge, 1 = at rest.
     *
     * Starts AT REST: a view that has never been asked to present must draw the paper where the
     * design puts it, not parked off screen. [present] is what animates the arrival.
     *
     * The whole stack shares one slide, so the ghosts arrive with the front rather than
     * appearing beside it — one sheet of paper with layers, not three independent ones.
     */
    private var slide = 1f

    /** Called when the front wedge is tapped. Nothing else in this view is interactive. */
    var onOpen: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BG }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.BORDER
        style = Paint.Style.STROKE
        strokeWidth = Theme.dpF(context, Theme.SQUIGGLE_STROKE_DP)
    }
    private val grain = PaperGrain.paint()

    private var metrics: ApprovalTip.Metrics? = null

    /**
     * The front wedge's top edge, in this view's coordinates.
     *
     * The FRONT wedge is index 0, which has no drop, so this is always 0 — but it is read from
     * [ApprovalTip.stack] rather than hard-coded, so a future change to the front's offset moves
     * the hit area with the paper instead of leaving it behind.
     */
    private var frontTopPx: Float = 0f
    private val fronts = ArrayList<Path>()

    private var tick: Runnable? = null

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPaths()
    }

    /**
     * Recompute every wedge outline for the current size and depth.
     *
     * Paths are built here rather than in `onDraw` because a `Path` allocation per frame for
     * three wedges is pure garbage on the animation path. On draw we only translate.
     */
    private fun rebuildPaths() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        // The window is the strip only, so "screen width" for the geometry is this view's own
        // width: the wedge is anchored to what it can actually paint on.
        val m = ApprovalTip.metrics(w, density)
        metrics = m
        val screenWidth = w

        fronts.clear()
        val wedges = ApprovalTip.stack(depth, density)
        frontTopPx = wedges.firstOrNull()?.dropPx ?: 0f
        for (wedge in wedges) {
            val top = wedge.dropPx
            val points = ApprovalTip.outline(m, screenWidth, top)
            val control = ApprovalTip.bowControl(m, screenWidth, top)
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                lineTo(points[1].x, points[1].y)
                // Leaning edge, bowed. from top-left down to the point.
                quadTo(control.x, control.y, points[2].x, points[2].y)
                close()
            }
            fronts.add(path)
        }
    }

    /** Slide the stack in from the right edge. */
    fun present() {
        slide = 0f
        // A view with no window has no frames to receive: `postOnAnimation` never dispatches, so
        // driving here would park the paper off screen forever. Snap instead — the difference is
        // invisible to a human (nothing is on screen yet) and it cannot strand the state machine.
        if (!isAttachedToWindow) {
            slide = 1f
            invalidate()
            return
        }
        drive(ARRIVE_MS, end = 1f)
        invalidate()
    }

    /** Take the stack out to the right edge. */
    fun dismiss(onDone: (() -> Unit)? = null) {
        if (!isAttachedToWindow) {
            slide = 0f
            invalidate()
            onDone?.invoke()
            return
        }
        drive(LEAVE_MS, end = 0f, onDone = onDone)
    }

    private companion object {
        /**
         * The two motion beats, in one place.
         *
         * Arriving is slower than leaving on purpose: the asymmetry is what reads as snappy
         * rather than soft. Neither runs past a quarter second.
         */
        const val ARRIVE_MS = 180L
        const val LEAVE_MS = 140L
    }

    /**
     * Run `slide` from where it is to [end] over [ms], one frame per display refresh.
     *
     * A single loop for every motion in this view: arrival, dismissal, and the settle when a
     * ghost is promoted all animate the same value, so there is one clock and one place to stop
     * it. Starting a second motion cancels the first instead of letting two loops fight over
     * `slide` — which is exactly how a tip once froze half-open.
     */
    private fun drive(ms: Long, end: Float, onDone: (() -> Unit)? = null) {
        tick?.let { removeCallbacks(it) }
        val from = slide
        val start = android.os.SystemClock.uptimeMillis()
        val interpolator = DecelerateInterpolator()
        val frame = object : Runnable {
            override fun run() {
                val fraction = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / ms)
                    .coerceIn(0f, 1f)
                slide = from + (end - from) * interpolator.getInterpolation(fraction)
                invalidate()
                when {
                    fraction < 1f && isAttachedToWindow -> postOnAnimation(this)
                    else -> {
                        tick = null
                        onDone?.invoke()
                    }
                }
            }
        }
        tick = frame
        postOnAnimation(frame)
    }

    override fun onDraw(canvas: Canvas) {
        val m = metrics ?: return
        if (fronts.isEmpty()) return

        // Everything shifts right by the un-slid part, so the paper reads as still arriving.
        val offset = (1f - slide) * m.topWidthPx
        canvas.save()
        canvas.translate(offset, 0f)

        // Back to front: index 0 is the FRONT wedge, so draw in reverse and the head lands last.
        for (i in fronts.indices.reversed()) {
            val path = fronts[i]
            val alpha = ApprovalTip.GHOST_ALPHA[i]
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())

            canvas.drawPath(path, fill)
            // The fibre, clipped to the paper. Without this the tip is a flat triangle sitting on
            // a textured sheet, which reads as a sticker rather than the same stock.
            canvas.save()
            canvas.clipPath(path)
            canvas.drawPaint(grain)
            canvas.restore()
            canvas.drawPath(path, edge)

            canvas.restoreToCount(layer)
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        tick?.let { removeCallbacks(it) }
        tick = null
        super.onDetachedFromWindow()
    }

    /**
     * Only the front wedge opens the sheet.
     *
     * Hit testing uses the drawn path, not the view bounds: the wedge is a triangle, and a
     * rectangular target would swallow taps in the empty corners — which is the difference
     * between "the paper is there" and "a strip of the screen is missing".
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked != android.view.MotionEvent.ACTION_UP) {
            return event.actionMasked == android.view.MotionEvent.ACTION_DOWN
        }
        val m = metrics
        // The drawn stack is shifted by the un-slid part, so the hit target moves with it.
        val offset = m?.let { (1f - slide) * it.topWidthPx } ?: 0f
        val hit = m != null &&
            ApprovalTip.contains(m, width, frontTopPx, event.x - offset, event.y)
        if (hit) onOpen?.invoke()
        return true
    }

    /** Whether the stack is currently fully at rest. Used by tests and the Activity. */
    val isAtRest: Boolean get() = slide >= 1f

    /** The wedge count actually drawn, which is the queue depth capped at three. */
    val drawnWedges: Int get() = fronts.size
}
