package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
 */
class RuggedToggle(context: Context) : View(context) {

    // --- geometry, in dp, resolved once ---

    private val trackHeightPx = dp(30)
    private val trackWidthPx = dp(56)
    private val insetPx = dp(3)
    private val knobRadiusPx = dp(2)
    private val trackRadiusPx = dp(3)

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
        edge.strokeWidth = dp(1.2f)
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
        path.reset()
        path.addRoundRect(rect, trackRadiusPx, trackRadiusPx, Path.Direction.CW)
        fill.style = Paint.Style.FILL
        fill.color = Theme.SURFACE
        canvas.drawPath(path, fill)

        canvas.save()
        canvas.clipPath(path)
        canvas.drawPaint(PaperGrain.paint())
        canvas.restore()

        edge.color = Theme.BORDER
        canvas.drawPath(path, edge)

        // --- knob ---
        val knobLeft = rect.left + insetPx + travelPx * fraction
        val knobTop = rect.top + insetPx
        knobPath.reset()
        knobPath.addRoundRect(
            knobLeft, knobTop, knobLeft + knobSizePx, knobTop + knobSizePx,
            knobRadiusPx, knobRadiusPx, Path.Direction.CW,
        )

        val knobColor = when {
            pending -> withAlpha(Theme.WARNING, 0xB0)
            checked -> Theme.SUCCESS
            else -> Theme.ON_SURFACE_VARIANT
        }
        fill.style = Paint.Style.FILL
        fill.color = knobColor
        canvas.drawPath(knobPath, fill)

        canvas.save()
        canvas.clipPath(knobPath)
        canvas.drawPaint(PaperGrain.paint())
        canvas.restore()

        // A darker hairline on the knob so it reads as a separate piece of card sitting on the
        // track rather than a painted rectangle.
        edge.color = withAlpha(Theme.ON_SURFACE, 0x40)
        canvas.drawPath(knobPath, edge)

        // A faint highlight along the top of the knob: light from above is what makes a flat shape
        // look raised, and it costs one line.
        edge.color = withAlpha(Color.WHITE, 0x33)
        canvas.drawLine(
            knobLeft + knobRadiusPx, knobTop + dp(0.6f),
            knobLeft + knobSizePx - knobRadiusPx, knobTop + dp(0.6f),
            edge,
        )
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = Theme.dpF(context, v)
    private fun dp(v: Int): Float = dp(v.toFloat())

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
