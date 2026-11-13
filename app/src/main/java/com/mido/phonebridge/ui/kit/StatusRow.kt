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

    /**
     * A value is "long" when its single-line width exceeds this share of the row. Beside the title
     * it would leave the title under 70 % of the row, which at the large font scale is what
     * crushed the body to 125dp and 8 lines. Measured with the real typeface and font scale, so
     * the same value flips at 1.35 where it fits at 1.0, for every caller.
     */
    internal const val INLINE_VALUE_MAX_SHARE = 0.3f

    /** Row width the rule measures against: the screen minus the gutters and the panel padding. */
    private fun rowWidthPx(ctx: Context): Float =
        ctx.resources.displayMetrics.widthPixels.toFloat() - 2 * ctx.dp(Dimens.GUTTER) - 2 * ctx.dp(Dimens.MODULE_PAD)

    /** Whether [value] goes under the title (true) or at the row's end (false). */
    internal fun isLongValue(ctx: Context, value: String): Boolean {
        if (value.isEmpty()) return false
        val width = measureText(ctx, TypeRole.LABEL, value.uppercase())
        return width > rowWidthPx(ctx) * INLINE_VALUE_MAX_SHARE
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

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
        // The detail repeats the value in some screens ("Off" / "off"); say it once.
        val shownDetail = detail?.takeIf { it.isNotEmpty() && !it.equals(value, ignoreCase = true) }
        val description = buildString {
            append(label)
            if (value.isNotEmpty()) append(", ").append(value)
            if (!shownDetail.isNullOrEmpty()) append(", ").append(shownDetail)
        }
        val stacked = isLongValue(ctx, value)

        // The row is a column: the line (LED, text, end control) and, under it, the action key.
        val row = RuleLinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(10), 0, ctx.dp(10))
            layoutParams = matchWrap()
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(36)
        }
        row.addView(line, matchWrap())

        val labelView = Type.text(ctx, label, TypeRole.ROW_LABEL, p.ink)
        if (led != null) {
            // On the first text line: centred on the label's line box, not on the whole body.
            val ledSize = ctx.dp(16)
            val top = ((labelView.lineHeight - ledSize) / 2).coerceAtLeast(0)
            line.addView(Led(ctx).apply { state = led }, LinearLayout.LayoutParams(ledSize, ledSize).apply {
                marginEnd = ctx.dp(10)
                gravity = Gravity.TOP
                topMargin = top
            })
        }

        val valueColor = when (tone) {
            Tone.NEUTRAL -> p.etched
            Tone.OK -> p.okText
            Tone.CAUTION -> p.cautionText
        }
        val valueView = if (value.isEmpty()) null else Type.text(ctx, value, TypeRole.LABEL, valueColor).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelView, matchWrap())
            if (valueView != null && stacked) {
                addView(valueView, matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_2XS) })
            }
            if (shownDetail != null) {
                addView(Type.text(ctx, shownDetail, TypeRole.DETAIL, p.etched), matchWrap().apply { topMargin = ctx.dp(2) })
            }
        }
        line.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (valueView != null && !stacked) {
            valueView.gravity = Gravity.END
            line.addView(valueView, wrap().apply { marginStart = ctx.dp(Dimens.SPACE_S) })
        }
        if (trailing != null) {
            line.addView(trailing, wrap().apply { marginStart = ctx.dp(Dimens.SPACE_S) })
        }
        if (action != null) {
            // Its own line, end-aligned to the row (not to the text column).
            val key = KeyButton.create(
                ctx, action.label, KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL,
                describe = action.describe, onClick = action.onClick,
            )
            row.addView(key, wrap().apply { gravity = Gravity.END; topMargin = ctx.dp(Dimens.TARGET_GAP) })
        }
        if (onClick != null) {
            line.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_kit_chevron)
                setColorFilter(p.etched, PorterDuff.Mode.SRC_IN)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ctx.dp(Dimens.ICON), ctx.dp(Dimens.ICON)).apply { marginStart = ctx.dp(4) })
            row.minimumHeight = ctx.dp(56)
            row.isClickable = true
            row.isFocusable = true
            installPressedFill(row, Dimens.RADIUS_KEY)
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
