package com.mido.phonebridge.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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

/** Gap between a control's edge and its focus ring, dp. The ring sits clear of a face that has margin. */
internal const val FOCUS_GAP_DP = 1

/**
 * Horizontal inset of a row whose focus ring sits inside its edge (gap plus stroke), so the ring
 * clears the title and the end control. Every row in a panel uses it, so left edges line up.
 */
internal const val FOCUS_ROW_INSET_DP = FOCUS_GAP_DP + Dimens.FOCUS_RING

/**
 * A [Palette.focus] ring (COMPONENTS 0 "Focus"). Shown only while the view has keyboard focus.
 * The ring always stays inside the view's own bounds (a child is clipped to them), so where it sits
 * relative to the face depends on [insetDp], how far the visible face sits inside the bounds
 * vertically (a SMALL key is a 48dp target around a 36dp face):
 *  - [insetDp] > 0: the ring is drawn OUTSIDE the face, in that margin: a [FOCUS_GAP_DP] gap, then
 *    the 2dp stroke, so it never covers the key's own border.
 *  - [insetDp] == 0: the face fills the bounds, so the ring sits just INSIDE the edge, over the
 *    face. A row that does this must keep its content [FOCUS_ROW_INSET_DP] clear of the edge, or
 *    the ring cuts through the title and the control.
 */
internal fun installFocusRing(view: View, radiusDp: Int, insetDp: Int = 0) {
    val ctx = view.context
    val p = Theme.palette(ctx)
    val ring = OutsetRing(
        color = p.focus,
        strokePx = ctx.dpf(Dimens.FOCUS_RING.toFloat()),
        gapPx = ctx.dpf(FOCUS_GAP_DP.toFloat()),
        radiusPx = ctx.dpf(radiusDp.toFloat()),
        insetPx = ctx.dpf(insetDp.toFloat()),
    )
    view.foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ring)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
}

/** The ring itself: a rounded stroke outside the drawable's bounds. */
internal class OutsetRing(
    color: Int, private val strokePx: Float, private val gapPx: Float,
    private val radiusPx: Float, private val insetPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = strokePx
    }
    private val rect = RectF()

    /**
     * The stroke's centre line for [b]. It never leaves [b]: a child is clipped to its own bounds,
     * and a ring outside them would show only its rounded corners. Where the face sits inside the
     * bounds ([insetPx] > 0) the ring is drawn in that margin, a gap clear of the face; otherwise
     * the stroke sits just inside the edge.
     */
    fun ringRect(b: android.graphics.Rect): RectF {
        val half = strokePx / 2f
        val out = gapPx + half
        val top = maxOf(b.top + half, b.top + insetPx - out)
        val bottom = minOf(b.bottom - half, b.bottom - insetPx + out)
        return RectF(b.left + half, top, b.right - half, bottom)
    }

    override fun draw(canvas: Canvas) {
        rect.set(ringRect(bounds))
        // Outside the face the ring wraps it (radius grows by gap + half stroke); inside the face
        // the stroke centre line already follows the face's corner.
        val r = if (insetPx > 0f) radiusPx + gapPx + strokePx / 2f else radiusPx
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * A pressed fill for a row that is a whole-row target. [installFocusRing] owns the foreground, so
 * the press is a state background (a [Palette.ripple] wash) and is visible without a ripple.
 */
internal fun installPressedFill(view: View, radiusDp: Int) {
    val ctx = view.context
    val wash = GradientDrawable().apply {
        setColor(Theme.palette(ctx).ripple)
        cornerRadius = ctx.dpf(radiusDp.toFloat())
    }
    view.background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), wash)
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

/** Width of [text] in [role] at the user's font scale, measured on a real styled TextView. */
internal fun measureText(ctx: Context, role: com.mido.phonebridge.ui.tokens.TypeRole, text: String): Float =
    com.mido.phonebridge.ui.tokens.Type.text(ctx, text, role, 0).paint.measureText(text)
