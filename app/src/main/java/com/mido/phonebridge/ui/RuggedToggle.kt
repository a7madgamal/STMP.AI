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
 * gone, and the ruggedness now comes from [TornEdge]: the SAME torn silhouette the cards and the
 * bottom bar use, so the whole app reads as cut from one sheet rather than three.
 */
class RuggedToggle(context: Context) : View(context) {

    // --- geometry, in dp, resolved once ---

    private val trackHeightPx = dp(34)
    private val trackWidthPx = dp(62)
    private val insetPx = dp(4)
    /**
     * How deep the tear is, in dp.
     *
     * Deliberately small: the cards tear 6dp out of a ~90dp block, so the same proportion on a
     * 34dp track is roughly 2dp. Using the card's absolute 6dp here would eat a fifth of the
     * control and turn it into a ragged blob.
     */
    private val trackTearPx = dp(2f)
    private val knobTearPx = dp(1.2f)

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
        // The app's own torn-paper shape, filled flat. An earlier pass invented a per-vertex
        // scatter here and at 34dp it came out visibly noisier than every card on the screen —
        // Mido, 2026-11-04: *"too jagged, can we just use the same exact effect we are using
        // everywhere else?"*. So this calls [TornEdge] now: the SAME nine-sample pattern and the
        // SAME rounded-top/square-side construction as `tornCard`, only with a smaller tear.
        TornEdge.path(
            out = path,
            left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
            tearPx = trackTearPx,
            cornerPx = dp(2f),
        )
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
        TornEdge.path(
            out = knobPath,
            left = knobRect.left, top = knobRect.top,
            right = knobRect.right, bottom = knobRect.bottom,
            tearPx = knobTearPx,
            cornerPx = dp(1.5f),
        )

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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = Theme.dpF(context, v)
    private fun dp(v: Int): Float = dp(v.toFloat())

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
