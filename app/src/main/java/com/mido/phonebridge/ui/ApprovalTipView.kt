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

    /**
     * The screen width the paper is sized against, when this view is narrower than the display.
     *
     * In the resting shape the window is a STRIP pinned to the right edge, so `width` is the
     * strip and not the phone. Sizing the wedge from the strip would make the paper a third of a
     * third — a sliver. The paper's proportions belong to the screen; only the room it is drawn
     * in belongs to the strip. Zero means "this view is the screen", which is the case in a test
     * and in the expanded shape.
     */
    var screenWidthPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            rebuildPaths()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BG }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.BORDER
        style = Paint.Style.STROKE
        strokeWidth = Theme.dpF(context, Theme.SQUIGGLE_STROKE_DP)
    }
    private val grain = PaperGrain.paint()

    /**
     * The paper's shadow: a REAL blur, baked once into a bitmap.
     *
     * **Why not `setShadowLayer`.** That needs a software layer or a render node and is silently
     * ignored in some window configurations — this project has already paid twice for visuals that
     * failed without a trace.
     *
     * **Why not hand-offset copies.** That was the previous attempt and it was wrong for a reason
     * no offset value can fix: a few offset copies of a hard-edged path are still a hard-edged
     * shape, so the shadow read as a second grey wedge sitting behind the paper rather than as
     * something the paper casts. Reported from the device as "the shadow is vertical".
     *
     * So the blur is real: `BlurMaskFilter` over the actual outline, rendered ONCE into a bitmap
     * whenever the geometry changes. Per frame the cost is one `drawBitmap`.
     */
    private var shadowBitmap: android.graphics.Bitmap? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59000000
        style = Paint.Style.FILL
    }

    private var metrics: ApprovalTip.Metrics? = null

    /**
     * The front wedge's top edge, in this view's coordinates.
     *
     * The FRONT wedge is index 0, which has no drop, so this is always 0 — but it is read from
     * [ApprovalTip.stack] rather than hard-coded, so a future change to the front's offset moves
     * the hit area with the paper instead of leaving it behind.
     */
    private var frontTopPx: Float = 0f

    /** One slot's vertical step, captured at build time so promotion can move by exactly that. */
    private var stepDropPx: Float = 0f

    /** 0 = at rest, 1 = the step forward has completed. */
    private var promoteProgress = 0f

    /** True while a promotion is playing, which changes what `onDraw` does per wedge. */
    private var promoting = false
    private val fronts = ArrayList<Path>()


    private var tick: Runnable? = null

    /** Cached density, so the shadow maths does not re-read resources per frame. */
    private var density: Float = resources.displayMetrics.density


    /** Counts real draws, so the diagnostic line is emitted once rather than per frame. */
    private var drawnOnce = 0

    init {
        setWillNotDraw(false)
        com.mido.phonebridge.Debug.log("tip: constructed")
        // A control a screen reader cannot name is a control a blind user cannot use, and this
        // one is the only way into the approval sheet. Set here rather than by the Activity so
        // it cannot be forgotten at the one call site that builds a tip.
        contentDescription = "approval waiting — tap to open"
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        com.mido.phonebridge.Debug.log("tip: attached")
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        com.mido.phonebridge.Debug.log("tip: windowVisibility=$visibility")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        com.mido.phonebridge.Debug.log("tip: size ${w}x$h")
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
        // **Proportions come from the screen; the ANCHOR is this view's right edge.**
        //
        // These are two different questions and conflating them is what drew the first build
        // blank. How WIDE the paper should be is a property of the phone, so it is measured
        // against the display. Where its right edge sits is a property of the window it is drawn
        // in, which in the resting shape is a strip pinned to that same edge. Building the path
        // in screen coordinates and translating it afterwards got the second question wrong by
        // the width of the strip, and the paper landed outside its own view.
        val proportionWidth = if (screenWidthPx > 0) screenWidthPx else w
        val m = ApprovalTip.metrics(proportionWidth, density)
        metrics = m

        fronts.clear()
        val wedges = ApprovalTip.stack(depth, density)
        // The stack hangs below the top edge rather than flush against it.
        val topOffset = ApprovalTip.TOP_OFFSET_DP * density
        frontTopPx = topOffset + (wedges.firstOrNull()?.dropPx ?: 0f)
        // The step between two adjacent wedges, which is what a promotion travels.
        stepDropPx = (wedges.getOrNull(1)?.dropPx ?: (ApprovalTip.GHOST_DROP_DP * density)) -
            (wedges.firstOrNull()?.dropPx ?: 0f)
        for (wedge in wedges) {
            val top = topOffset + wedge.dropPx
            // Fitted to THIS view: the sheet must fill the canvas it is painted on, not the screen
            // it is floating over. Building it from screen proportions put most of it outside a
            // narrow strip, and only a corner appeared.
            val points = ApprovalTip.outlineFit(
                w.toFloat(), h.toFloat(), density, top,
            )
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            fronts.add(path)
        }
        buildShadow()
    }

    /**
     * Bake the blurred shadow for the current outline.
     *
     * The blur radius is set on the mask filter, and the paths are drawn once into an offscreen
     * bitmap — so the shadow follows the paper's ACTUAL shape, including its lean, instead of being
     * a translated copy. The bitmap is rebuilt only when the geometry changes.
     */
    private fun buildShadow() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || fronts.isEmpty()) {
            shadowBitmap = null
            return
        }
        val bmp = shadowBitmap?.takeIf { it.width == w && it.height == h }
            ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0)
        val c = Canvas(bmp)
        // A soft edge, and a small offset down-right so the sheet reads as lifted rather than
        // printed on. Both live in the mask filter, which is what makes it a shadow and not a copy.
        shadowPaint.maskFilter =
            android.graphics.BlurMaskFilter(SHADOW_BLUR_DP * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.translate(SHADOW_OFFSET_DP * density, SHADOW_OFFSET_DP * density * 1.4f)
        for (path in fronts) c.drawPath(path, shadowPaint)
        shadowPaint.maskFilter = null
        shadowBitmap = bmp
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

    /**
     * The request in front was answered: the wedge behind it steps forward into its place.
     *
     * **Why this needs a swap to exist at all.** With one Activity per request the window died
     * with the head, so the stack could only re-form from nothing — there was no paper left to
     * move. The handover keeps the window, so the promotion is a real motion: the answered wedge
     * leaves to the right while the one behind it rises into the front slot and brightens to full.
     *
     * [fromDepth] is drawn first, then [toDepth] is adopted once the motion ends. Both are passed
     * in rather than read from the gate, because the gate has already moved on: it no longer knows
     * how deep the stack WAS.
     */
    fun promote(fromDepth: Int, toDepth: Int, onDone: (() -> Unit)? = null) {
        val from = fromDepth.coerceIn(1, ApprovalTip.MAX_VISIBLE)
        val to = toDepth.coerceIn(1, ApprovalTip.MAX_VISIBLE)
        // One wedge leaving a stack of one has nothing to promote: the whole tip simply goes.
        if (from <= 1 || from == to) {
            depth = to
            onDone?.invoke()
            return
        }
        depth = from
        promoting = true
        promoteProgress = 0f
        val target = { depth = to; promoting = false; promoteProgress = 0f; rebuildPaths(); invalidate(); onDone?.invoke() }
        if (!isAttachedToWindow) {
            target()
            return
        }
        val start = android.os.SystemClock.uptimeMillis()
        val interpolator = DecelerateInterpolator()
        var frames = 0
        tick?.let { removeCallbacks(it) }
        val frame = object : Runnable {
            override fun run() {
                val fraction = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / STEP_MS)
                    .coerceIn(0f, 1f)
                val done = fraction >= 1f || frames++ >= MAX_FRAMES
                promoteProgress = if (done) 1f else interpolator.getInterpolation(fraction)
                invalidate()
                if (!done && isAttachedToWindow) postOnAnimation(this) else { tick = null; target() }
            }
        }
        tick = frame
        postOnAnimation(frame)
    }

    /**
     * Take the stack out to the right edge.
     *
     * Runs while the sheet is arriving, so the two motions overlap into one gesture. The view is
     * deliberately NOT hidden here: hiding it on the first frame is what turned a 140ms exit into
     * a flicker.
     */
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

        /**
         * The step forward when the front request is answered.
         *
         * Shorter than either of the others: this is a consequence of the human's own decision
         * arriving one frame later, not a new surface appearing, so it must feel like the stack
         * settling rather than a second thing to watch.
         */
        const val STEP_MS = 160L

        /**
         * Frames after which the loop snaps to its end no matter what the clock says.
         *
         * 120 frames is two seconds at 60 Hz — an order of magnitude longer than either beat, so
         * a working animation never approaches it. It exists for the one case where the clock
         * cannot advance, where "keep waiting for the next frame" becomes an infinite loop.
         */
        const val MAX_FRAMES = 120

        /** Blur radius of the paper's shadow, in dp. */
        const val SHADOW_BLUR_DP = 5f

        /** How far the shadow sits from the paper, in dp. */
        const val SHADOW_OFFSET_DP = 2f
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
        var frames = 0
        val frame = object : Runnable {
            override fun run() {
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                val fraction = (elapsed.toFloat() / ms).coerceIn(0f, 1f)
                val done = fraction >= 1f || frames++ >= MAX_FRAMES
                // **The backstop, and why it exists.** This loop stops on the CLOCK. Any
                // environment where the clock does not advance — a test harness that flushes the
                // main looper with a frozen clock, most obviously — would have it re-post forever
                // and never return to its caller. A motion that cannot finish must SNAP, because
                // the alternative is a tip stuck mid-slide with the request expiring under it.
                // Two seconds is several times the longest beat here, so a real animation never
                // reaches it.
                slide = if (done) end
                else from + (end - from) * interpolator.getInterpolation(fraction)
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

    override fun onDraw(canvas: Canvas) {
        val m = metrics
        com.mido.phonebridge.Debug.log("tip: onDraw called")
        if (drawnOnce == 0) {
            // One line, once, at the first real draw. The window geometry is invisible from
            // outside the view, so without this a blank tip is indistinguishable from a tip that
            // never got a size, never got a depth, or drew itself off the edge.
            // One line per request, at the first real draw.
            //
            // The tip is the only surface whose geometry is invisible from outside itself: a wedge
            // that gets a zero size, a zero depth, or an outline off the edge all look identical
            // from the rest of the app. Three numbers name which one happened.
            com.mido.phonebridge.Debug.log(
                "tip: draw ${width}x${height} depth=$depth wedges=${fronts.size} " +
                    "paperW=${m?.topWidthPx} paperH=${m?.heightPx}"
            )
            drawnOnce++
        }
        if (m == null || fronts.isEmpty()) return

        // Everything shifts right by the un-slid part, so the paper reads as still arriving.
        //
        // **The distance is the VIEW's width**, because that is what it has to travel to be fully
        // off screen. It used to be a screen-proportional metric — a number from a different
        // coordinate system — so the shape was never actually pushed out of its own window and
        // appeared in place instead of sliding in.
        val offset = (1f - slide) * width
        canvas.save()
        // The baked shadow travels with the paper.
        shadowBitmap?.let { canvas.drawBitmap(it, offset, 0f, null) }
        // Only the arrival displaces the stack now: the geometry is already in view space.
        canvas.translate(offset, 0f)

        // Back to front: index 0 is the FRONT wedge, so draw in reverse and the head lands last.
        for (i in fronts.indices.reversed()) {
            val path = fronts[i]
            var alpha = ApprovalTip.GHOST_ALPHA[i]
            var dy = 0f
            var dx = 0f
            if (promoting) {
                if (i == 0) {
                    // The answered wedge leaves: it fades and slides out to the right, the same
                    // direction the sheet went when it was collapsed.
                    alpha = 1f - promoteProgress
                    dx = promoteProgress * m.topWidthPx
                    dy = -promoteProgress * stepDropPx * 0.4f
                } else {
                    // Each ghost rises one slot and brightens to the alpha of the slot it enters.
                    dy = -promoteProgress * stepDropPx
                    alpha = ApprovalTip.GHOST_ALPHA[i] +
                        (ApprovalTip.GHOST_ALPHA[i - 1] - ApprovalTip.GHOST_ALPHA[i]) * promoteProgress
                }
                alpha = alpha.coerceIn(0f, 1f)
            }
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())

            canvas.save()
            canvas.translate(dx, dy)


            canvas.drawPath(path, fill)
            // The fibre, clipped to the paper. Without this the tip is a flat triangle sitting on
            // a textured sheet, which reads as a sticker rather than the same stock.
            canvas.save()
            canvas.clipPath(path)
            canvas.drawPaint(grain)
            canvas.restore()
            canvas.drawPath(path, edge)
            canvas.restore()

            canvas.restoreToCount(layer)
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        com.mido.phonebridge.Debug.log("tip: detached")
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
    /**
     * The tip's one action, through the platform's own activation path.
     *
     * A tap, a TalkBack double-tap and a programmatic click all arrive here, which is why the
     * touch handler below calls this instead of invoking `onOpen` itself: a control that only
     * responds to raw touch is a control a screen reader cannot operate, and this is the whole
     * approval gate — not a decorative affordance.
     */
    override fun performClick(): Boolean {
        super.performClick()
        onOpen?.invoke()
        return true
    }

    /**
     * Claim a gesture ONLY when it starts on the paper.
     *
     * **A window is touch-opaque across its whole rectangle**, transparent pixels included, so a
     * full-height strip blocked an empty column down the side of the screen — reported as "touches
     * are still blocked on the right side even the transparent parts". `FLAG_NOT_TOUCH_MODAL` only
     * helps OUTSIDE the window; inside it, the only way a touch reaches the app below is to refuse
     * it. So the decision happens on ACTION_DOWN and everything else is declined.
     *
     * Returning `true` for ACTION_DOWN before checking the shape was the defect: it claimed every
     * gesture in the window, then decided where it was.
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val m = metrics
        val offset = (1f - slide) * width
        val hit = ApprovalTip.containsFit(
            width.toFloat(), height.toFloat(), density,
            ApprovalTip.TOP_OFFSET_DP * density,
            event.x - offset, event.y,
        )
        return when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> hit
            android.view.MotionEvent.ACTION_UP -> {
                if (hit) performClick()
                hit
            }
            else -> hit
        }
    }


    /** Whether the stack is currently fully at rest. Used by tests and the Activity. */
    val isAtRest: Boolean get() = slide >= 1f

    /** The wedge count actually drawn, which is the queue depth capped at three. */
    val drawnWedges: Int get() = fronts.size
}
