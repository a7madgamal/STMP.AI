package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.chip.ChipGroup
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** Filter chips that wrap instead of scrolling sideways (COMPONENTS 15). */
object Chips {
    fun group(ctx: Context, label: String): ChipGroup = ChipGroup(ctx).apply {
        isSingleLine = false
        chipSpacingHorizontal = ctx.dp(Dimens.SPACE_S)
        chipSpacingVertical = 0
        contentDescription = label
    }

    fun filter(ctx: Context, label: String, checked: Boolean, onToggle: (Boolean) -> Unit): FrameLayout {
        val p = Theme.palette(ctx)
        var on = checked
        val pill = Type.text(ctx, label, TypeRole.CHIP, p.ink).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = null
            minWidth = ctx.dp(Dimens.MIN_TARGET)
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        }
        fun paint() {
            pill.setTextColor(if (on) p.onInk else p.ink)
            val shape = GradientDrawable().apply {
                cornerRadius = ctx.dpf(Dimens.RADIUS_KNOB_THUMB.toFloat())
                if (on) setColor(p.ink) else { setColor(p.panel); setStroke(ctx.dp(1), p.outline) }
            }
            val mask = GradientDrawable().apply {
                setColor(p.ink); cornerRadius = ctx.dpf(Dimens.RADIUS_KNOB_THUMB.toFloat())
            }
            pill.background = RippleDrawable(rippleColor(ctx), shape, mask)
        }
        paint()
        return FrameLayout(ctx).apply {
            minimumHeight = ctx.dp(Dimens.MIN_TARGET)
            minimumWidth = ctx.dp(Dimens.MIN_TARGET)
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, ctx.dp(32), Gravity.CENTER,
            ))
            isClickable = true
            isFocusable = true
            installFocusRing(this, Dimens.RADIUS_KNOB_THUMB)
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.CheckBox"
                    info.isCheckable = true
                    info.isChecked = on
                    info.text = label
                }
            })
            setOnClickListener {
                on = !on
                paint()
                Haptics.perform(this, HapticEvent.SEGMENT)
                onToggle(on)
            }
        }
    }
}
