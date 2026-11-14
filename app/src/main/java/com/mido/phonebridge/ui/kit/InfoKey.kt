package com.mido.phonebridge.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens

/** The help bulb, 48dp touch frame around a 32dp disc (COMPONENTS 22). */
object InfoKey {
    fun create(ctx: Context, describe: String, onClick: () -> Unit): FrameLayout {
        val p = Theme.palette(ctx)
        fun disc(fill: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(ctx.dp(1), p.outline)
        }
        val discView = View(ctx).apply {
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), disc(p.hairline))
                addState(intArrayOf(), disc(p.sunken))
            }
            setDuplicateParentStateEnabled(true)
        }
        val glyph = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_kit_bulb)
            setColorFilter(p.ink, PorterDuff.Mode.SRC_IN)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(ctx).apply {
            addView(discView, FrameLayout.LayoutParams(ctx.dp(32), ctx.dp(32), Gravity.CENTER))
            addView(glyph, FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(Dimens.MIN_TARGET), ctx.dp(Dimens.MIN_TARGET)).apply {
                marginEnd = ctx.dp(8)
            }
            contentDescription = describe
            isClickable = true
            isFocusable = true
            installFocusRing(this, 24)
            setRole(this, "android.widget.Button")
            setOnClickListener { onClick() }
        }
    }
}
