package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A rugged, grained, rectangular on/off switch.
 *
 * **Why a custom View rather than `SwitchCompat`.** That widget is Material: a thin capsule, a
 * circular thumb, a ripple, and a track that tints. Every one of those is wrong here. This app is
 * paper and ink — a switch has to look cut from the same sheet as the cards, which means grain, a
 * hairline edge and a chunky rectangular thumb. Restyling `SwitchCompat` far enough to stop
 * looking borrowed costs more than drawing thirty lines.
 *
 * **State, not action.** The knob's POSITION is the answer: left is off, right is on. There is no
 * "TURN OFF" label that has to be recomputed, so the control cannot contradict itself — a stale
 * label on a running server was a lie that only surfaced when someone tapped it.
 *
 * **Grain follows the shape.** Both the track and the knob clip `PaperGrain` to their own paths, so
 * the texture stops at the edge instead of being painted into the corners of a bounding box.
 *
 * **Flat on purpose.** Mido, 2026-11-04: *"you made the toggle more 3d. I want it more 2d but more
 * rugged looking with jagged lines"*. An earlier version faked depth — an inner gradient along the
 * track, a blurred drop shadow under the knob, a white highlight on its top edge. All three are
 * gone. The ruggedness is now carried entirely by the OUTLINE: both shapes are drawn as wandering,
 * hand-cut rectangles and filled flat. No blur, no gradient, no light direction.
 */
class RuggedToggle(context: Context) : View(context) {

    // --- geometry, in dp, resolved once ---

    private val trackHeightPx = dp(34)
    private val trackWidthPx = dp(62)
    private val insetPx = dp(4)
    /**
     * How far each edge strays from the true rectangle.
     *
     * Small numbers on purpose: this is a 30ish-dp control, and a wobble big enough to read as
     * "torn" up close becomes a blob at a glance. The track gets slightly more than the knob so
     * the outer shape looks hand-cut and the knob looks cut from the same sheet.
     */
    private val trackJagPx = dp(1.6f)
    private val knobJagPx = dp(1.1f)

    /**
     * The knob is SQUARE-ish on purpose. A circle would read as a Material thumb; a rectangle with
     * a slight radius reads as a physical slider cut from card stock.
     */
    private val knobSizePx = trackHeightPx - insetPx * 2

    /** How far the knob travels: everything between its two resting places. */
    private val travelPx = trackWidthPx - knobSizePx - insetPx * 2

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val knobPath = Path()
    private val rect = RectF()
    private val knobRect = RectF()

    /**
     * The wobble, as a fixed pattern rather than a random one.
     *
     * A `Random` here would re-roll on every `onDraw` and the outline would visibly crawl — the
     * same trap the torn card edge documents. Values sit in -1..1 and are multiplied by the
     * per-shape amplitude, so one table serves both the track and the knob at different strengths.
     */
    private val jagPattern = floatArrayOf(
        0.0f, 0.8f, -0.4f, 1.0f, -0.7f, 0.3f,
        -1.0f, 0.6f, -0.2f, 0.9f, -0.5f, 0.1f,
    )

    /** 0f = left (off), 1f = right (on). Animated; the checked VALUE flips immediately. */
    private var fraction = 0f
    private var animator: android.animation.ValueAnimator? = null

    var checked: Boolean = false
        private set

    /**
     * Draw the knob as "working on it" without moving it.
     *
     * The server takes a moment to bind, and a knob that slides then slides back would be showing
     * intent rather than truth — the same fault the old button labels had. The knob stays where the
     * last KNOWN state put it and takes on a pending look until the host reports the real value.
     */
    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        // A MINIMUM touch target larger than the drawing: 30dp of paint is easy to see and hard to
        // hit, and a switch that misses is worse than one that looks slightly small.
        minimumWidth = Theme.dp(context, 64)
        minimumHeight = Theme.dp(context, 48)
        contentDescription = "Off"
        // Thicker and darker than a hairline. A 1.2dp light stroke on a 30dp track read as a
        // Material pill; a 2dp darker edge reads as card stock with thickness.
        edge.strokeWidth = dp(2f)
    }

    /** Set the state WITHOUT animating. Used on first render, where motion would be a lie. */
    fun setCheckedImmediately(value: Boolean) {
        animator?.cancel()
        checked = value
        fraction = if (value) 1f else 0f
        contentDescription = if (value) "On" else "Off"
        invalidate()
    }

    /** Set the state and animate to it. Used after a real change. */
    fun setCheckedAnimated(value: Boolean) {
        if (checked == value && animator == null) {
            setCheckedImmediately(value)
            return
        }
        checked = value
        contentDescription = if (value) "On" else "Off"
        val from = fraction
        val to = if (value) 1f else 0f
        if (from == to) {
            invalidate()
            return
        }
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A running animator holds the View through its listener, and this app has already paid
        // for a stray callback outliving the thing it painted.
        animator?.cancel()
        animator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(64).toInt(), widthMeasureSpec),
            resolveSize(dp(48).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        // The track is centred in the touch target, so the drawing stays small while the hit area
        // stays generous.
        val left = (width - trackWidthPx) / 2f
        val top = (height - trackHeightPx) / 2f
        rect.set(left, top, left + trackWidthPx, top + trackHeightPx)

        // --- track ---
        //
        // A wandering rectangle, filled flat. This replaced a `addRoundRect` plus an inner
        // gradient: the gradient was there to fake a routed channel, and at this size it read as
        // a grey smear rather than as depth. The jagged edge does that job now, and it does it
        // without inventing a light direction.
        jaggedRect(path, rect, trackJagPx)
        fill.style = Paint.Style.FILL
        fill.color = Theme.SURFACE
        canvas.drawPath(path, fill)

        canvas.save()
        canvas.clipPath(path)
        canvas.drawPaint(PaperGrain.paint())
        canvas.restore()

        edge.color = withAlpha(Theme.ON_SURFACE, 0x66)
        canvas.drawPath(path, edge)

        // --- knob ---
        val knobLeft = rect.left + insetPx + travelPx * fraction
        val knobTop = rect.top + insetPx
        knobRect.set(knobLeft, knobTop, knobLeft + knobSizePx, knobTop + knobSizePx)
        jaggedRect(knobPath, knobRect, knobJagPx)

        fill.style = Paint.Style.FILL
        fill.color = when {
            pending -> withAlpha(Theme.WARNING, 0xB0)
            checked -> Theme.SUCCESS
            else -> Theme.ON_SURFACE_VARIANT
        }
        canvas.drawPath(knobPath, fill)

        canvas.save()
        canvas.clipPath(knobPath)
        canvas.drawPaint(PaperGrain.paint())
        canvas.restore()

        // Its own outline, heavier and darker than the track's. That weight difference is the only
        // thing separating the knob from the track now that the drop shadow is gone — a lighter
        // line would make the knob disappear into the paper at a glance.
        edge.color = withAlpha(Theme.ON_SURFACE, 0xAA)
        canvas.drawPath(knobPath, edge)
    }

    /**
     * A rectangle whose edges wander, as one closed path.
     *
     * Each edge is subdivided into equal steps and every vertex is pushed off the line by
     * `amplitude * pattern[i]`. Stepped rather than curved on purpose: at 1-2dp the eye reads a
     * chain of straight segments as cut paper, where a smooth curve reads as a rounded rectangle
     * and takes the whole thing back to Material.
     */
    private fun jaggedRect(out: Path, r: RectF, amplitude: Float) {
        out.reset()
        var i = 0
        fun put(x: Float, y: Float) {
            val a = amplitude * jagPattern[i % jagPattern.size]
            i++
            out.lineTo(x + a, y + a)
        }

        // 7 steps across and 5 down: enough vertices that the edge is genuinely ragged, few
        // enough that no two segments average out into a straight line.
        val stepsX = 7
        val stepsY = 5

        out.moveTo(r.left, r.top)
        for (s in 1..stepsX) put(r.left + r.width() * s / stepsX, r.top)
        for (s in 1..stepsY) put(r.right, r.top + r.height() * s / stepsY)
        for (s in 1..stepsX) put(r.right - r.width() * s / stepsX, r.bottom)
        for (s in 1..stepsY) put(r.left, r.bottom - r.height() * s / stepsY)
        out.close()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = Theme.dpF(context, v)
    private fun dp(v: Int): Float = dp(v.toFloat())

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
