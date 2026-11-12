package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Motion
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The rocker switch (COMPONENTS 4). Same public contract as the old paper toggle:
 * `checked` is read-only state, painted by the caller from stored truth.
 */
class RockerSwitch(ctx: Context) : View(ctx) {

    var checked: Boolean = false
        private set

    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshA11y()
            invalidate()
        }

    /** The accessibility name, e.g. "Auto-start on boot". */
    var label: String = ""
        set(value) { field = value; refreshA11y() }

    /** 0 = off, 1 = on. */
    internal var fraction = 0f
        private set

    private var spring: SpringAnimation? = null
    private val snapHard = Runnable { snapToEnd() }
    private val labelPaint = typePaint(ctx, TypeRole.CHIP)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Bevel.sunkenTrack(ctx, Theme.palette(ctx).rockerTrack, Dimens.RADIUS_ROCKER_TRACK)
    private var reduced = Motion.reduced(ctx)

    private val fractionProperty = object : FloatPropertyCompat<RockerSwitch>("fraction") {
        override fun getValue(obj: RockerSwitch) = obj.fraction
        override fun setValue(obj: RockerSwitch, value: Float) { obj.fraction = value; obj.invalidate() }
    }

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = ctx.dp(64)
        minimumHeight = ctx.dp(Dimens.MIN_TARGET)
        labelPaint.textAlign = Paint.Align.CENTER
        ringPaint.strokeWidth = ctx.dpf(Dimens.FOCUS_RING.toFloat())
        refreshA11y()
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = checked
            }
        })
    }

    private fun stateWord() = if (pending) "Working" else if (checked) "On" else "Off"

    private fun refreshA11y() {
        ViewCompat.setStateDescription(this, stateWord())
        contentDescription = if (BELOW_API_30) "$label, ${stateWord().lowercase()}" else label
    }

    private fun capWidthPx(): Float {
        val on = labelPaint.measureText("ON")
        val off = labelPaint.measureText("OFF")
        return maxOf(context.dpf(Dimens.ROCKER_CAP_W.toFloat()), maxOf(on, off) + context.dpf(8f))
    }

    private fun trackWidthPx(): Float =
        maxOf(context.dpf(Dimens.ROCKER_W.toFloat()), capWidthPx() * 2 + context.dpf(6f))

    fun setCheckedImmediately(value: Boolean) {
        spring?.cancel()
        removeCallbacks(snapHard)
        checked = value
        fraction = if (value) 1f else 0f
        refreshA11y()
        invalidate()
    }

    fun setCheckedAnimated(value: Boolean) {
        if (reducedNow() || !isAttachedToWindow) { setCheckedImmediately(value); return }
        if (checked == value && spring == null) { setCheckedImmediately(value); return }
        checked = value
        refreshA11y()
        val end = if (value) 1f else 0f
        if (fraction == end) { invalidate(); return }
        spring?.cancel()
        spring = SpringAnimation(this, fractionProperty, end).apply {
            this.spring = SpringForce(end).apply {
                stiffness = Motion.SPRING_ROCKER_STIFFNESS
                dampingRatio = Motion.SPRING_ROCKER_DAMPING
            }
            setMinimumVisibleChange(0.002f)
            addEndListener { _, _, _, _ -> removeCallbacks(snapHard); spring = null }
            start()
        }
        removeCallbacks(snapHard)
        postDelayed(snapHard, Motion.ROCKER_MAX_MS)
    }

    private fun snapToEnd() {
        spring?.cancel()
        spring = null
        fraction = if (checked) 1f else 0f
        invalidate()
    }

    private fun reducedNow(): Boolean { reduced = Motion.reduced(context); return reduced }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduced = Motion.reduced(context)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spring?.cancel()
        spring = null
        removeCallbacks(snapHard)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minW = maxOf(context.dp(64), (trackWidthPx()).toInt())
        setMeasuredDimension(
            resolveSize(minW, widthMeasureSpec),
            resolveSize(context.dp(Dimens.MIN_TARGET), heightMeasureSpec),
        )
    }

    override fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        super.setEnabled(enabled)
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette(context)
        val a = if (isEnabled) 255 else (255 * DISABLED_ALPHA_F).toInt()
        val tw = trackWidthPx()
        val th = context.dpf(Dimens.ROCKER_H.toFloat())
        val left = (width - tw) / 2f
        val top = (height - th) / 2f
        track.setBounds(left.toInt(), top.toInt(), (left + tw).toInt(), (top + th).toInt())
        track.alpha = a
        track.draw(canvas)

        val capW = capWidthPx()
        val capH = context.dpf(Dimens.ROCKER_CAP_H.toFloat())
        val inset = context.dpf(3f)
        val travel = tw - inset * 2 - capW
        val cx = left + inset + travel * fraction
        val cy = top + (th - capH) / 2f
        val cap = RectF(cx, cy, cx + capW, cy + capH)
        val radius = context.dpf(Dimens.RADIUS_ROCKER_CAP.toFloat())
        val down = if (isPressed && isEnabled) context.dpf(1f) else 0f
        val showOn = fraction >= 0.5f
        val breath = if (pending && !reduced) {
            val phase = 2.0 * Math.PI * (SystemClock.uptimeMillis() % Motion.DURATION_LED_BREATH) /
                Motion.DURATION_LED_BREATH
            (0.7 + 0.3 * Math.cos(phase)).toFloat()
        } else 1f
        val capAlpha = (a * breath).toInt()
        when {
            pending -> KeyPaint.draw(canvas, cap, radius, context.dpf(1f), p.keyLip, p.bevelHighlight, p.ledCaution, down, capAlpha)
            showOn -> KeyPaint.draw(canvas, cap, radius, context.dpf(1f), p.keyLipInk, p.inkHighlight, p.ink, down, capAlpha)
            else -> KeyPaint.draw(canvas, cap, radius, context.dpf(1f), p.keyLip, p.bevelHighlight, p.rockerCapOff, down, capAlpha)
        }
        labelPaint.color = when {
            pending -> p.ink
            showOn -> p.onInk
            else -> p.rockerOffText
        }
        labelPaint.alpha = capAlpha
        val word = if (pending) "…" else if (showOn) "ON" else "OFF"
        val baseline = cap.centerY() + down - (labelPaint.ascent() + labelPaint.descent()) / 2f - context.dpf(0.5f)
        canvas.drawText(word, cap.centerX(), baseline, labelPaint)

        if (isFocused && !isInTouchMode) {
            ringPaint.color = p.focus
            val g = context.dpf(2f)
            canvas.drawRoundRect(
                left - g, top - g, left + tw + g, top + th + g,
                context.dpf(Dimens.RADIUS_ROCKER_TRACK.toFloat()) + g, context.dpf(Dimens.RADIUS_ROCKER_TRACK.toFloat()) + g, ringPaint,
            )
        }
        if (pending && !reduced) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (isEnabled) Haptics.perform(this, HapticEvent.TOGGLE)
        return super.performClick()
    }
}
