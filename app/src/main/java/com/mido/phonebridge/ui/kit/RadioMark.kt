package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.ui.Theme

/** The 20dp radio circle for onboarding cards (COMPONENTS 23). Not clickable itself. */
object RadioMark {
    fun create(ctx: Context, selected: Boolean): View {
        val p = Theme.palette(ctx)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(ctx.dp(2), if (selected) p.ink else p.outline)
        }
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.ink) }
        return View(ctx).apply {
            background = if (selected) LayerDrawable(arrayOf(ring, dot)).apply {
                setLayerInset(1, ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
            } else ring
            layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
}
