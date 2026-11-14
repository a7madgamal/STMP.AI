package com.mido.phonebridge.ui.kit

import android.content.Context
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** A section heading (COMPONENTS 12). No rule line: the squiggle is retired. */
object SectionHeader {
    fun create(ctx: Context, text: String, inPanel: Boolean = false): TextView =
        Type.text(ctx, text, TypeRole.LABEL, Theme.palette(ctx).etched).apply {
            setPadding(0, if (inPanel) 0 else ctx.dp(Dimens.SPACE_XL), 0, ctx.dp(Dimens.SPACE_S))
            ViewCompat.setAccessibilityHeading(this, true)
        }
}
