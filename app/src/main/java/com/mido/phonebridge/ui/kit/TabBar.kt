package com.mido.phonebridge.ui.kit

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.BottomInset
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.FontFace
import com.mido.phonebridge.ui.tokens.Fonts
import com.mido.phonebridge.ui.tokens.Motion
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The bottom tab bar (COMPONENTS 11). Built once and repainted in place by [select], like the
 * legacy `Theme.BottomBar`.
 */
class TabBar(
    ctx: Context,
    current: Theme.Tab,
    private val onSelect: (Theme.Tab) -> Unit,
) : LinearLayout(ctx) {

    private class Cell(
        val tab: Theme.Tab, val root: LinearLayout, val indicator: View,
        val icon: ImageView, val label: TextView,
    )

    private val cells = ArrayList<Cell>()
    var selectedTab: Theme.Tab = current
        private set
    private var animator: ValueAnimator? = null
    private val p = Theme.palette(ctx)

    init {
        orientation = HORIZONTAL
        val line = GradientDrawable().apply { setColor(p.hairline) }
        val fill = GradientDrawable().apply { setColor(p.body) }
        background = LayerDrawable(arrayOf(fill, line)).apply {
            setLayerHeight(1, ctx.dp(1))
            setLayerGravity(1, Gravity.TOP)
        }
        elevation = 0f
        for (tab in Theme.Tab.entries) {
            val cell = buildCell(tab)
            cells.add(cell)
            addView(cell.root, LayoutParams(0, ctx.dp(Dimens.TAB_BAR_H), 1f))
        }
        BottomInset.apply(this)
        paint(1f, null)
    }

    private fun buildCell(tab: Theme.Tab): Cell {
        val ctx = context
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply { setColor(p.ink); cornerRadius = ctx.dpf(1f) }
        }
        val icon = ImageView(ctx).apply {
            setImageResource(
                when (tab) {
                    Theme.Tab.HOME -> R.drawable.ic_tab_home
                    Theme.Tab.PLUGINS -> R.drawable.ic_tab_plugins
                    Theme.Tab.SETTINGS -> R.drawable.ic_tab_settings
                },
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = Type.text(ctx, tab.label, TypeRole.TAB, p.etched).apply {
            maxLines = 1
            gravity = Gravity.CENTER
        }
        val root = LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            minimumWidth = ctx.dp(Dimens.MIN_TARGET)
            installFocusRing(this, 0)
            addView(indicator, LayoutParams(ctx.dp(24), ctx.dp(2)))
            addView(icon, LayoutParams(ctx.dp(Dimens.ICON), ctx.dp(Dimens.ICON)).apply { topMargin = ctx.dp(8) })
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
            setRole(this, "android.widget.TextView", "Tab")
            setOnClickListener { if (tab != selectedTab) onSelect(tab) }
        }
        return Cell(tab, root, indicator, icon, label)
    }

    /** Repaint for [tab]; cross-fades over DURATION_TAB unless motion is reduced. */
    fun select(tab: Theme.Tab, animate: Boolean = true) {
        val previous = selectedTab
        selectedTab = tab
        animator?.cancel()
        if (!animate || Motion.reduced(context) || previous == tab) {
            paint(1f, previous)
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.DURATION_TAB
            interpolator = Motion.EASE_STANDARD
            addUpdateListener { paint(it.animatedValue as Float, previous) }
            start()
        }
    }

    /** [t] is 0..1 progress from [previous] to [selectedTab]. */
    private fun paint(t: Float, previous: Theme.Tab?) {
        val eval = ArgbEvaluator()
        for (c in cells) {
            val sel = c.tab == selectedTab
            val wasSel = c.tab == previous && previous != selectedTab
            val k = if (sel) t else if (wasSel) 1f - t else 0f
            val color = eval.evaluate(k, p.etched, p.ink) as Int
            c.indicator.alpha = k
            c.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            c.label.setTextColor(color)
            c.label.typeface = Fonts.get(context, if (sel) FontFace.SANS_600 else FontFace.SANS_500)
            c.root.isSelected = sel
        }
    }
}
