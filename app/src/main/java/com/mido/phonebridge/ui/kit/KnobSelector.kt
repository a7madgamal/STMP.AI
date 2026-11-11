package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
 * The segmented knob (COMPONENTS 5): gate mode, AI mode. `onSelect` fires on a user tap only; the
 * caller renders stored truth with [select], so the thumb never moves on its own.
 */
class KnobSelector(
    ctx: Context,
    val groupLabel: String,
    val labels: List<String>,
) : ViewGroup(ctx) {

    var onSelect: ((Int) -> Unit)? = null

    var selected: Int = 0
        private set

    /** Thumb position in segment units (animated). */
    internal var position = 0f
        private set

    private val segments = ArrayList<TextView>()
    private val trackDrawable = Bevel.sunkenTrack(ctx, Theme.palette(ctx).sunken, Dimens.RADIUS_READOUT)
    private var spring: SpringAnimation? = null
    private val thumbRect = RectF()
    private var trackTop = 0
    private var trackBottom = 0

    private val positionProperty = object : FloatPropertyCompat<KnobSelector>("position") {
        override fun getValue(obj: KnobSelector) = obj.position
        override fun setValue(obj: KnobSelector, value: Float) { obj.position = value; obj.invalidate() }
    }

    init {
        require(labels.size in 2..4) { "a knob has 2 to 4 positions" }
        setWillNotDraw(false)
        minimumHeight = ctx.dp(Dimens.MIN_TARGET)
        contentDescription = groupLabel
        setRole(this, "android.widget.RadioGroup")
        val p = Theme.palette(ctx)
        labels.forEachIndexed { i, label ->
            val seg = Type.text(ctx, label, TypeRole.LABEL, p.etched).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = null
                isClickable = true
                isFocusable = true
                contentDescription = label
                setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
                minimumHeight = ctx.dp(Dimens.MIN_TARGET)
                installFocusRing(this, Dimens.RADIUS_KNOB_THUMB)
                setOnClickListener { onTap(i) }
            }
            ViewCompat.setAccessibilityDelegate(seg, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = (i == selected)
                }
            })
            segments.add(seg)
            addView(seg)
        }
        paintSegments()
    }

    private fun onTap(index: Int) {
        if (!isEnabled || index == selected) return
        Haptics.perform(this, HapticEvent.SEGMENT)
        onSelect?.invoke(index)
    }

    /** Move the thumb to [index] without calling [onSelect]. */
    fun select(index: Int, animate: Boolean) {
        require(index in labels.indices)
        selected = index
        paintSegments()
        spring?.cancel()
        val end = index.toFloat()
        if (!animate || Motion.reduced(context) || !isAttachedToWindow || position == end) {
            position = end
            invalidate()
            return
        }
        spring = SpringAnimation(this, positionProperty, end).apply {
            this.spring = SpringForce(end).apply {
                stiffness = Motion.SPRING_ROCKER_STIFFNESS
                dampingRatio = Motion.SPRING_ROCKER_DAMPING
            }
            setMinimumVisibleChange(0.002f)
            start()
        }
    }

    private fun paintSegments() {
        val p = Theme.palette(context)
        segments.forEachIndexed { i, s ->
            s.setTextColor(if (i == selected) p.onInk else p.etched)
            ViewCompat.setStateDescription(s, if (i == selected) "Selected" else "Not selected")
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        segments.forEach { it.isEnabled = enabled }
        alpha = if (enabled) 1f else DISABLED_ALPHA_F
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val segW = (w - context.dp(6)) / labels.size
        var tallest = 0
        segments.forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(segW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            tallest = maxOf(tallest, it.measuredHeight)
        }
        val textH = tallest - context.dp(16)
        val trackH = maxOf(context.dp(Dimens.KNOB_H), textH + context.dp(16))
        val total = maxOf(context.dp(Dimens.MIN_TARGET), trackH + context.dp(8))
        trackTop = (total - trackH) / 2
        trackBottom = trackTop + trackH
        segments.forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(segW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, total)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = context.dp(3)
        val segW = (r - l - inset * 2) / labels.size
        segments.forEachIndexed { i, s ->
            val x = inset + i * segW
            s.layout(x, 0, x + segW, b - t)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette(context)
        trackDrawable.setBounds(0, trackTop, width, trackBottom)
        trackDrawable.draw(canvas)
        val inset = context.dpf(3f)
        val segW = (width - inset * 2) / labels.size
        val left = inset + segW * position + context.dpf(2f)
        thumbRect.set(left, trackTop + inset, left + segW - context.dpf(4f), trackBottom - inset)
        KeyPaint.draw(
            canvas, thumbRect, context.dpf(Dimens.RADIUS_KNOB_THUMB.toFloat()), context.dpf(2f),
            p.keyLipInk, p.inkHighlight, p.ink,
        )
    }
}
