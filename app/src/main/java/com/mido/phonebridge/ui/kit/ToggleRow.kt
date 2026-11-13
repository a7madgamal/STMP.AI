package com.mido.phonebridge.ui.kit

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * A labelled switch row inside a [Panel] (COMPONENTS 26). The whole row is the target: a click
 * on it performs the rocker's click, so haptics and the live-state callback are identical to
 * tapping the switch itself.
 */
object ToggleRow {

    fun create(
        ctx: Context,
        label: String,
        detail: String,
        on: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View {
        val p = Theme.palette(ctx)
        val row = RuleLinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(ctx.dp(FOCUS_ROW_INSET_DP), ctx.dp(10), ctx.dp(FOCUS_ROW_INSET_DP), ctx.dp(10))
            layoutParams = matchWrap()
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Type.text(ctx, label, TypeRole.ROW_LABEL, p.ink), matchWrap())
            addView(Type.text(ctx, detail, TypeRole.DETAIL, p.etched), matchWrap().apply { topMargin = ctx.dp(2) })
        }
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val rocker = Theme.rocker(ctx, on, label, pending = false, onChange = onChange).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }
        row.addView(rocker, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = ctx.dp(12) })

        row.isClickable = enabled
        row.isFocusable = enabled
        installPressedFill(row, Dimens.RADIUS_KEY)
        installFocusRing(row, Dimens.RADIUS_KEY)
        if (enabled) row.setOnClickListener { rocker.performClick() }
        row.alpha = if (enabled) 1f else DISABLED_ALPHA_F

        val state = if (on) "On" else "Off"
        ViewCompat.setStateDescription(row, state)
        row.contentDescription = if (BELOW_API_30) "$label, ${state.lowercase()}, $detail" else "$label, $detail"
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = on
            }
        })
        return row
    }
}
