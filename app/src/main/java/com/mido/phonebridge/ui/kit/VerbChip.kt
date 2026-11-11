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
    fun create(ctx: Context, verb: Verb): TextView {
        val p = Theme.palette(ctx)
        val loud = verb == Verb.DELETE || verb == Verb.EXECUTE
        return Type.text(ctx, verb.name, TypeRole.CHIP, if (loud) p.onSignal else p.ink).apply {
            setPadding(ctx.dp(5), ctx.dp(3), ctx.dp(5), ctx.dp(3))
            background = GradientDrawable().apply {
                setColor(if (loud) p.signal else p.sunken)
                cornerRadius = ctx.dpf(Dimens.RADIUS_VERB.toFloat())
            }
            maxLines = 1
            ellipsize = null
            contentDescription = "verb ${verb.name.lowercase()}"
        }
    }
}
