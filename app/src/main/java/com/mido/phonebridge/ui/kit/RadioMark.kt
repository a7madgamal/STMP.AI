package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.ui.Theme

/** The 20dp radio circle for onboarding cards (COMPONENTS 23). Not clickable itself. */
object RadioMark {
    const val SIZE_DP = 20

    fun create(ctx: Context, selected: Boolean): View {
        val p = Theme.palette(ctx)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(ctx.dp(2), if (selected) p.ink else p.outline)
        }
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.ink) }
        return MarkView(ctx).apply {
            background = if (selected) LayerDrawable(arrayOf(ring, dot)).apply {
                setLayerInset(1, ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
            } else ring
            layoutParams = LinearLayout.LayoutParams(ctx.dp(SIZE_DP), ctx.dp(SIZE_DP))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /**
     * A plain View measured WRAP_CONTENT takes `getDefaultSize`: its minimum size, which is 0 here
     * (the background has no intrinsic size). The kit screenshot's own WRAP_CONTENT params gave a
     * 0 x 0 mark, hence the blank PNG. Wider parents give a plain View everything, so the mark
     * measures itself: always SIZE_DP square.
     */
    private class MarkView(ctx: Context) : View(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val s = context.dp(SIZE_DP)
            setMeasuredDimension(s, s)
        }
    }
}
