package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.R

/**
 * The app's single source of design truth.
 *
 * Before this file, every screen carried its own button helper (`coloredButton`,
 * `colored`, `smallButton`, `chip`, `small`) and its own raw hex literals — the same
 * colour copy-pasted up to nine times, `pill()` sizing its corners in PIXELS rather
 * than dp, and no press feedback anywhere because a `GradientDrawable` background
 * discards the ripple and the state list. That is what "looks like a hacky tool"
 * means in code: not one ugly value, but no single place that owns the values.
 *
 * Rules this file exists to enforce:
 *  - **Semantic, not decorative.** A colour says what a thing MEANS (`success`,
 *    `danger`, `accent`), never what it looks like. A screen that wants green asks
 *    for `success`, so restyling is one edit here, not a grep across seven files.
 *  - **dp, always.** Radii, padding and stroke widths are density-independent. The
 *    old `cornerRadius = radiusDp * 3` only looked right at ~3x density.
 *  - **Touch targets are ≥48dp.** A control you can miss is a control that denies a
 *    request by accident.
 *  - **Every button ripples.** Built on `MaterialButton` with a `backgroundTintList`,
 *    so the state list and the ripple survive — the thing a custom background kills.
 */
object Theme {

    // --- palette (dark, restrained: one accent, three semantic states) ---

    /** App background. Deliberately near-black, not pure black. */
    const val BG = 0xFF0F1115.toInt()

    /** Cards and raised surfaces. */
    const val SURFACE = 0xFF171A21.toInt()

    /** Secondary surface: chips, secondary buttons, inset blocks. */
    const val SURFACE_VARIANT = 0xFF1F242E.toInt()

    /** Hairline borders. The main tool for separating without shouting. */
    const val BORDER = 0xFF2A303B.toInt()

    const val ON_SURFACE = 0xFFE6E9EF.toInt()
    const val ON_SURFACE_VARIANT = 0xFF9AA4B2.toInt()

    /** The one accent: used for primary action and focus. */
    const val ACCENT = 0xFF5B7CFA.toInt()

    const val SUCCESS = 0xFF2EA043.toInt()
    const val SUCCESS_SURFACE = 0xFF12331C.toInt()
    const val WARNING = 0xFFD29922.toInt()
    const val WARNING_SURFACE = 0xFF3A2E0B.toInt()
    const val DANGER = 0xFFE5534B.toInt()
    const val DANGER_SURFACE = 0xFF3D1A18.toInt()

    /** Text/icons on a filled coloured control. */
    const val ON_COLOR = 0xFFFFFFFF.toInt()

    // --- metrics ---

    const val RADIUS_CARD = 16
    const val RADIUS_CONTROL = 12
    const val RADIUS_CHIP = 10

    /** Minimum touch target. Below this a tap is a coin flip. */
    const val MIN_TARGET_DP = 48

    /** Ripple colour for controls on a dark surface. */
    private const val RIPPLE = 0x33FFFFFF

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun radiusPx(ctx: Context, radiusDp: Int): Float = dp(ctx, radiusDp).toFloat()

    // --- surfaces ---

    /** A filled, rounded surface (card / block / badge). */
    fun surface(ctx: Context, color: Int, radiusDp: Int = RADIUS_CARD): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx(ctx, radiusDp)
        }

    /** A filled surface with a hairline border — the standard card look. */
    fun outlined(
        ctx: Context,
        color: Int = SURFACE,
        radiusDp: Int = RADIUS_CARD,
        strokeColor: Int = BORDER,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx(ctx, radiusDp)
        setStroke(dp(ctx, strokeDp), strokeColor)
    }

    /** A filled surface that ripples when tapped (for tappable rows). */
    fun rippled(ctx: Context, color: Int, radiusDp: Int = RADIUS_CARD): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            surface(ctx, color, radiusDp),
            null
        )

    // --- text ---

    /** A small all-caps section header. */
    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.15f
        setTextColor(ON_SURFACE_VARIANT)
        setPadding(0, dp(ctx, 20), 0, dp(ctx, 6))
    }

    /** A plain line of text. */
    fun text(
        ctx: Context,
        text: String,
        size: Float = 14f,
        color: Int = ON_SURFACE,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (mono) typeface = android.graphics.Typeface.MONOSPACE
        setLineSpacing(0f, 1.15f)
    }

    // --- controls ---

    /** How a button should read. Chosen by MEANING, never by colour name. */
    enum class ButtonStyle { PRIMARY, NEUTRAL, SUCCESS, DANGER, GHOST }

    private fun styleColors(style: ButtonStyle): Pair<Int, Int> = when (style) {
        ButtonStyle.PRIMARY -> ACCENT to ON_COLOR
        ButtonStyle.NEUTRAL -> SURFACE_VARIANT to ON_SURFACE
        ButtonStyle.SUCCESS -> SUCCESS to ON_COLOR
        ButtonStyle.DANGER -> DANGER to ON_COLOR
        ButtonStyle.GHOST -> Color.TRANSPARENT to ACCENT
    }

    /**
     * The one button. Every screen uses this — there is no second variant.
     *
     * `backgroundTintList` (not `background`) is what preserves the ripple and the
     * disabled/pressed states; setting a drawable directly is what removed them before.
     */
    fun button(
        ctx: Context,
        label: String,
        style: ButtonStyle = ButtonStyle.NEUTRAL,
        small: Boolean = false,
        onClick: () -> Unit
    ): MaterialButton {
        val (bg, fg) = styleColors(style)
        val h = if (small) dp(ctx, 40) else dp(ctx, MIN_TARGET_DP)
        return MaterialButton(ctx).apply {
            text = label
            textSize = if (small) 12f else 14f
            isAllCaps = false
            minHeight = h
            minimumHeight = h
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(bg)
            rippleColor = ColorStateList.valueOf(RIPPLE)
            cornerRadius = dp(ctx, RADIUS_CONTROL)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(ctx, if (small) 12 else 20), 0, dp(ctx, if (small) 12 else 20), 0)
            if (style == ButtonStyle.GHOST) {
                strokeWidth = dp(ctx, 1)
                strokeColor = ColorStateList.valueOf(ACCENT)
            }
            setOnClickListener { onClick() }
        }
    }

    /** A compact filter chip. Same machinery as [button], tighter. */
    fun chip(ctx: Context, label: String, selected: Boolean = false, onClick: () -> Unit): MaterialButton =
        button(ctx, label, if (selected) ButtonStyle.PRIMARY else ButtonStyle.NEUTRAL, small = true, onClick = onClick)

    /** A status row: coloured dot + label + value, optionally tappable. */
    fun statusRow(
        ctx: Context,
        label: String,
        value: String,
        color: Int,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = outlined(ctx, SURFACE, RADIUS_CONTROL)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply {
                marginEnd = dp(ctx, 12)
            }
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(ON_SURFACE)
        })
        body.addView(TextView(ctx).apply {
            text = value
            textSize = 13f
            setTextColor(ON_SURFACE_VARIANT)
        })
        row.addView(dot)
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) {
            row.addView(TextView(ctx).apply {
                text = "›"
                textSize = 20f
                setTextColor(ON_SURFACE_VARIANT)
            })
        }
        return row
    }

    /** Vertical space. */
    fun spacer(ctx: Context, dp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dp))
    }
}
