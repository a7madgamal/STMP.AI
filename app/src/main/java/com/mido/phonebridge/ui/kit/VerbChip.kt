package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** The READ / WRITE / EDIT / DELETE / EXECUTE chip (COMPONENTS 7). */
object VerbChip {
    /**
     * The width of the widest chip (EXECUTE) at the user's font scale, so a column of chips of
     * different verbs shares one edge on the left and one on the right (the tape).
     */
    fun columnWidthPx(ctx: Context): Int {
        val widest = Verb.entries.maxOf { measureText(ctx, TypeRole.CHIP, it.name) }
        return Math.ceil(widest.toDouble()).toInt() + 2 * ctx.dp(CHIP_PAD_H_DP)
    }

    internal const val CHIP_PAD_H_DP = 5

    fun create(ctx: Context, verb: Verb): TextView {
        val p = Theme.palette(ctx)
        val loud = verb == Verb.DELETE || verb == Verb.EXECUTE
        return Type.text(ctx, verb.name, TypeRole.CHIP, if (loud) p.onSignal else p.ink).apply {
            setPadding(ctx.dp(CHIP_PAD_H_DP), ctx.dp(3), ctx.dp(CHIP_PAD_H_DP), ctx.dp(3))
            background = GradientDrawable().apply {
                setColor(if (loud) p.signal else p.sunken)
                // The sunken fill is nearly the card colour in dark; the outline is the chip's edge.
                if (!loud) setStroke(ctx.dp(1), p.outline)
                cornerRadius = ctx.dpf(Dimens.RADIUS_VERB.toFloat())
            }
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = null
            contentDescription = "verb ${verb.name.lowercase()}"
        }
    }
}
