package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** One status line inside a Panel (COMPONENTS 13). */
object StatusRow {
    enum class Tone { NEUTRAL, OK, CAUTION }

    fun create(
        ctx: Context,
        label: String,
        value: String,
        tone: Tone = Tone.NEUTRAL,
        detail: String? = null,
        led: Led.State? = null,
        onClick: (() -> Unit)? = null,
        trailing: View? = null,
        action: KeyButton.Spec? = null,
    ): View {
        require(trailing == null || (value.isEmpty() && onClick == null)) {
            "a row with a trailing control has no value and is not itself a link"
        }
        require(action == null || onClick == null) { "a row with an action key is not itself a link" }
        val p = Theme.palette(ctx)
        val description = buildString {
            append(label)
            if (value.isNotEmpty()) append(", ").append(value)
            if (!detail.isNullOrEmpty()) append(", ").append(detail)
        }
        val row = RuleLinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(10), 0, ctx.dp(10))
            layoutParams = matchWrap()
        }
        if (led != null) {
            row.addView(Led(ctx).apply { state = led }, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
                marginEnd = ctx.dp(10)
                gravity = Gravity.TOP
                topMargin = ctx.dp(1)
            })
        }

        val labelView = Type.text(ctx, label, TypeRole.ROW_LABEL, p.ink)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelView, matchWrap())
            if (!detail.isNullOrEmpty()) {
                addView(Type.text(ctx, detail, TypeRole.DETAIL, p.etched), matchWrap().apply { topMargin = ctx.dp(2) })
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(body, matchWrap())
            if (action != null) {
                val key = KeyButton.create(
                    ctx, action.label, KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL,
                    describe = action.describe, onClick = action.onClick,
                )
                addView(key, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END; topMargin = ctx.dp(Dimens.TARGET_GAP) })
            }
        }
        row.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (value.isNotEmpty()) {
            val color = when (tone) {
                Tone.NEUTRAL -> p.etched
                Tone.OK -> p.okText
                Tone.CAUTION -> p.cautionText
            }
            val valueView = Type.text(ctx, value, TypeRole.LABEL, color).apply {
                gravity = Gravity.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val maxW = (ctx.resources.displayMetrics.widthPixels * 0.45f).toInt()
            valueView.maxWidth = maxW
            row.addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = ctx.dp(Dimens.SPACE_S) })
        }
        if (trailing != null) {
            row.addView(trailing, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = ctx.dp(Dimens.SPACE_S) })
        }
        if (onClick != null) {
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_kit_chevron)
                setColorFilter(p.etched, PorterDuff.Mode.SRC_IN)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ctx.dp(Dimens.ICON), ctx.dp(Dimens.ICON)).apply { marginStart = ctx.dp(4) })
            row.minimumHeight = ctx.dp(56)
            row.isClickable = true
            row.isFocusable = true
            row.foreground = RippleDrawable(rippleColor(ctx), null, ColorDrawable(p.ink))
            installFocusRing(row, Dimens.RADIUS_KEY)
            row.setOnClickListener { onClick() }
            setRole(row, "android.widget.Button")
        }

        // One focus item for the text; a trailing control or action key keeps its own focus.
        val interactiveChildren = trailing != null || action != null
        val target = if (interactiveChildren) body else row
        target.contentDescription = description
        ViewCompat.setScreenReaderFocusable(target, true)
        if (interactiveChildren) {
            target.isFocusable = true
            labelView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return row
    }
}
