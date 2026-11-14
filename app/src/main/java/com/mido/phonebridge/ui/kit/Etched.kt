package com.mido.phonebridge.ui.kit

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** The small etched caption above a value (COMPONENTS 6). */
object Etched {
    /** Ties [label] to [value] for screen readers (`labelFor`). */
    fun labelFor(label: TextView, value: android.view.View) { label.labelFor = value.id }


    fun label(ctx: Context, text: String): TextView =
        Type.text(ctx, text, TypeRole.LABEL, Theme.palette(ctx).etched).apply {
            layoutParams = matchWrap().apply { bottomMargin = ctx.dp(Dimens.SPACE_XS) }
        }
}
