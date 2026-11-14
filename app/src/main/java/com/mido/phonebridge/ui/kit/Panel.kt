package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.LinearLayout
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Dimens

/** A bevelled module (COMPONENTS 1). Replaces tornCard, surface, outlined and bounded sheet. */
object Panel {
    enum class Tone { PLAIN, OK, CAUTION, SIGNAL }

    fun create(
        ctx: Context,
        tone: Tone = Tone.PLAIN,
        padded: Boolean = true,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val p = Theme.palette(ctx)
        val fill = when (tone) {
            Tone.PLAIN -> p.panel
            Tone.OK -> p.okSurface
            Tone.CAUTION -> p.cautionSurface
            Tone.SIGNAL -> p.signalSurface
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Bevel.module(ctx, fill = fill, r = Dimens.RADIUS_MODULE)
            if (padded) {
                val pad = ctx.dp(Dimens.MODULE_PAD)
                setPadding(pad, pad, pad, pad)
            }
            layoutParams = matchWrap().apply { bottomMargin = ctx.dp(Dimens.MODULE_GAP) }
            if (onClick != null) {
                val mask = GradientDrawable().apply {
                    setColor(p.ink)
                    cornerRadius = ctx.dpf(Dimens.RADIUS_MODULE.toFloat())
                }
                foreground = RippleDrawable(rippleColor(ctx), null, mask)
                minimumHeight = ctx.dp(Dimens.MIN_TARGET)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                setRole(this, "android.widget.Button")
            }
        }
    }
}
