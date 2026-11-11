package com.mido.phonebridge.ui.kit

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** Empty, error and loading blocks (COMPONENTS 18). */
object StateBlock {
    enum class Kind { EMPTY, ERROR, LOADING }

    fun create(
        ctx: Context,
        kind: Kind,
        title: String,
        body: String? = null,
        action: KeyButton.Spec? = null,
    ): View {
        val p = Theme.palette(ctx)
        val panel = Panel.create(ctx, Panel.Tone.PLAIN, padded = false)
        val v = ctx.dp(Dimens.SPACE_3XL)
        val h = ctx.dp(Dimens.MODULE_PAD)
        panel.setPadding(h, v, h, v)
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        val led = Led(ctx).apply {
            state = when (kind) {
                Kind.EMPTY -> Led.State.OFF
                Kind.ERROR -> Led.State.CAUTION
                Kind.LOADING -> Led.State.PENDING
            }
        }
        panel.addView(led, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = ctx.dp(Dimens.SPACE_S)
        })
        val titleColor = if (kind == Kind.ERROR) p.cautionText else p.ink
        panel.addView(Type.text(ctx, title, TypeRole.ROW_LABEL, titleColor).apply { gravity = Gravity.CENTER }, matchWrap())
        if (!body.isNullOrEmpty()) {
            val bodyColor = if (kind == Kind.ERROR) p.ink else p.etched
            panel.addView(
                Type.text(ctx, body, TypeRole.DETAIL, bodyColor).apply { gravity = Gravity.CENTER },
                matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_XS) },
            )
        }
        if (action != null && kind != Kind.LOADING) {
            panel.addView(
                KeyButton.create(ctx, action.label, KeyButton.Kind.PANEL, describe = action.describe, onClick = action.onClick),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = ctx.dp(Dimens.SPACE_L)
                },
            )
        }
        return panel
    }
}
