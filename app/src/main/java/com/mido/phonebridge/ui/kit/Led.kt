package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Motion

/** A status light (COMPONENTS 3). 16dp including the halo; never carries meaning alone. */
class Led(ctx: Context) : View(ctx) {
    enum class State { OK, CAUTION, SIGNAL, OFF, PENDING }

    var state: State = State.OFF
        set(value) {
            if (field == value) return
            field = value
            reduced = Motion.reduced(context)
            rebuildHalo()
            invalidate()
        }

    private var reduced = Motion.reduced(ctx)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(1f)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun fillColor(): Int {
        val p = Theme.palette(context)
        return when (state) {
            State.OK -> p.ledOk
            State.CAUTION, State.PENDING -> p.ledCaution
            State.SIGNAL -> p.ledSignal
            State.OFF -> p.ledOff
        }
    }

    private fun ringColor(): Int {
        val p = Theme.palette(context)
        return when (state) {
            State.OK -> p.okText
            State.CAUTION, State.PENDING -> p.cautionText
            State.SIGNAL -> p.signalText
            State.OFF -> p.etched
        }
    }

    private fun rebuildHalo() {
        val r = context.dpf(Dimens.LED.toFloat())
        halo.shader = RadialGradient(
            context.dpf(8f), context.dpf(8f), r,
            Colors.withAlpha(fillColor(), 0x66), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(16)
        setMeasuredDimension(s, s)
    }

    /** Breathing alpha (0.4..1.0) at [nowMs], or 1 when not pending (COMPONENTS 3). */
    internal fun breathAlpha(nowMs: Long): Float {
        if (state != State.PENDING || reduced) return 1f
        val phase = 2.0 * Math.PI * (nowMs % Motion.DURATION_LED_BREATH) / Motion.DURATION_LED_BREATH
        return (0.7 + 0.3 * Math.cos(phase)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val core = context.dpf(Dimens.LED / 2f)
        val pendingReduced = state == State.PENDING && reduced
        val alpha = breathAlpha(SystemClock.uptimeMillis())
        val a = (alpha * 255).toInt()
        if (state != State.OFF && !pendingReduced) {
            halo.alpha = a
            canvas.save()
            canvas.translate(cx - context.dpf(8f), cy - context.dpf(8f))
            canvas.drawCircle(context.dpf(8f), context.dpf(8f), context.dpf(8f), halo)
            canvas.restore()
            fill.color = fillColor()
            fill.alpha = a
            canvas.drawCircle(cx, cy, core, fill)
        }
        ring.color = ringColor()
        ring.alpha = a
        canvas.drawCircle(cx, cy, core, ring)
        if (state == State.PENDING && !reduced) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduced = Motion.reduced(context)
        rebuildHalo()
    }
}
