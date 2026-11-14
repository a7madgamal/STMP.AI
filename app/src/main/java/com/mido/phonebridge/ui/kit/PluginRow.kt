package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.ui.PluginRowModel
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * One plugin on the Plugins tab (COMPONENTS 27): help key, name, status line, detail, rocker.
 *
 * The status word sits under the name, never beside it, so at large text only the text column
 * grows and nothing has to fit on one line (05-large-text risk 1).
 */
object PluginRow {
    fun create(
        ctx: Context,
        model: PluginRowModel.Model,
        onOpen: () -> Unit,
        onHelp: () -> Unit,
        onToggle: (Boolean) -> Unit,
        showRule: Boolean = true,
    ): View {
        val p = Theme.palette(ctx)
        val row = RuleLinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(64)
            setPadding(0, ctx.dp(Dimens.SPACE_S), 0, ctx.dp(Dimens.SPACE_S))
            ruleEnabled = showRule
            layoutParams = matchWrap()
            isClickable = true
            isFocusable = true
            foreground = RippleDrawable(rippleColor(ctx), null, ColorDrawable(p.ink))
            installFocusRing(this, Dimens.RADIUS_KEY)
            setOnClickListener { onOpen() }
            setRole(this, "android.widget.Button")
            contentDescription = buildString {
                append(model.title).append(", ").append(model.word.lowercase())
                if (model.detail.isNotBlank()) append(", ").append(model.detail)
            }
        }

        row.addView(InfoKey.create(ctx, "About ${model.title}", onHelp).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })

        val wordColor = when (model.tone) {
            PluginRowModel.Tone.OK -> p.okText
            PluginRowModel.Tone.CAUTION -> p.cautionText
            PluginRowModel.Tone.NEUTRAL -> p.etched
        }
        val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text.addView(Type.text(ctx, model.title, TypeRole.ROW_LABEL, p.ink), matchWrap())
        val status = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        status.addView(
            Led(ctx).apply { state = model.led },
            LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
                marginEnd = ctx.dp(6)
                topMargin = ctx.dp(1)
            },
        )
        status.addView(
            Type.text(ctx, balanceWord(model.word), TypeRole.LABEL, wordColor),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        text.addView(status, matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_XS) })
        if (model.detail.isNotBlank()) {
            text.addView(Type.text(ctx, model.detail, TypeRole.DETAIL, p.etched), matchWrap().apply { topMargin = ctx.dp(2) })
        }
        for (i in 0 until text.childCount) text.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(4) })

        val rocker = Theme.rocker(ctx, on = model.showsOn, label = model.title, onChange = onToggle).apply {
            // A slightly sloppy tap must not be stolen by the row.
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
        row.addView(rocker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dp(Dimens.SPACE_S)
        })
        return row
    }
}

/**
 * A status word that has to wrap breaks in the middle, not with one orphan word: "NOT IN" over
 * "THIS BUILD". Words of three or more get their break after the first half, display only.
 */
internal fun balanceWord(word: String): String {
    val parts = word.split(' ')
    if (parts.size < 4) return word
    val half = parts.size / 2
    return parts.take(half).joinToString("\u00A0") + " " + parts.drop(half).joinToString("\u00A0")
}
