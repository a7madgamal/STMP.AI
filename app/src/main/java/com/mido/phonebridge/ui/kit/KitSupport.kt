package com.mido.phonebridge.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Palette

/** Small helpers shared by the kit. Internal: screens use the components, never these. */

internal fun Context.dp(v: Int): Int = Theme.dp(this, v)
internal fun Context.dpf(v: Float): Float = Theme.dpF(this, v)

internal fun matchWrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** Alpha of a disabled control (0.38, COMPONENTS 0). */
internal const val DISABLED_ALPHA_F = 0.38f

/** Role announcement: sets the accessibility class name, nothing else. */
internal fun setRole(view: View, className: String, roleDescription: String? = null) {
    ViewCompat.setAccessibilityDelegate(view, object : androidx.core.view.AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = className
            if (roleDescription != null) info.roleDescription = roleDescription
        }
    })
}

/** `stateDescription`, plus a spoken state in the description below API 30 where it is a no-op. */
internal fun setState(view: View, state: String) {
    ViewCompat.setStateDescription(view, state)
}

internal val BELOW_API_30: Boolean get() = Build.VERSION.SDK_INT < 30

/**
 * A 2dp [Palette.focus] ring, drawn as a foreground only while the view has keyboard focus
 * (COMPONENTS 0 "Focus"). Touch mode never focuses a button, so it never shows there.
 */
internal fun installFocusRing(view: View, radiusDp: Int) {
    val ctx = view.context
    val p = Theme.palette(ctx)
    val ring = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(ctx.dp(Dimens.FOCUS_RING), p.focus)
        cornerRadius = ctx.dpf(radiusDp.toFloat())
    }
    view.foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ring)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
}

/** Draws the 3-layer key face used by the rocker cap and the knob thumb. */
internal object KeyPaint {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas, r: RectF, radius: Float, lipDp: Float, lip: Int, highlight: Int, face: Int,
        pressedDown: Float = 0f, alpha: Int = 255,
    ) {
        paint.style = Paint.Style.FILL
        val a = alpha / 255f
        fun fill(color: Int, rect: RectF) {
            paint.color = Colors.withAlpha(color, ((color ushr 24) * a).toInt())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        val body = RectF(r)
        if (pressedDown == 0f) fill(lip, body)
        val hi = RectF(r.left, r.top + pressedDown, r.right, r.bottom - lipDp + pressedDown)
        fill(highlight, hi)
        val fc = RectF(r.left, r.top + pressedDown + 1f, r.right, r.bottom - lipDp + pressedDown)
        fill(face, fc)
    }
}

/**
 * A horizontal row that draws a 1dp dashed [Palette.hairline] rule along its top edge when it is
 * not the first child of its parent (the mechanism StatusRow, Tape and ActionRow share).
 */
internal open class RuleLinearLayout(ctx: Context) : LinearLayout(ctx) {
    var ruleEnabled = true
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(1f)
        color = Theme.palette(ctx).hairline
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(4f), ctx.dpf(3f)), 0f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (ruleEnabled && (parent as? ViewGroup)?.indexOfChild(this).let { it != null && it > 0 }) {
            canvas.drawLine(0f, 0f, width.toFloat(), 0f, rulePaint)
        }
    }

    init { setWillNotDraw(false) }
}

internal fun rippleColor(ctx: Context): ColorStateList = ColorStateList.valueOf(Theme.palette(ctx).ripple)

/**
 * A [android.text.TextPaint] for custom-drawn text: [role]'s typeface (or [face] when given), size
 * in the user's font scale, and tracking, taken from a probe TextView styled by `Type.apply`.
 * Colour and alignment are the caller's.
 */
internal fun typePaint(
    ctx: Context,
    role: com.mido.phonebridge.ui.tokens.TypeRole,
    face: com.mido.phonebridge.ui.tokens.FontFace = role.face,
): android.text.TextPaint {
    val probe = android.widget.TextView(ctx)
    com.mido.phonebridge.ui.tokens.Type.apply(probe, role)
    return android.text.TextPaint(probe.paint).also {
        it.typeface = com.mido.phonebridge.ui.tokens.Fonts.get(ctx, face)
    }
}
