package com.mido.phonebridge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context

/**
 * Tiny UI kit so state is readable at a glance: green = good, amber = needs
 * attention, red = error/off. No more uniform grey buttons.
 */
object UiKit {

    val GREEN = 0xFF1B5E20.toInt()
    val GREEN_BG = 0xFF2E7D32.toInt()
    val AMBER = 0xFF8D6E00.toInt()
    val AMBER_BG = 0xFFF9A825.toInt()
    val RED = 0xFFB71C1C.toInt()
    val RED_BG = 0xFFC62828.toInt()
    val NEUTRAL_BG = 0xFF37474F.toInt()
    val TEXT_ON_DARK = 0xFFFFFFFF.toInt()
    val TEXT_ON_LIGHT = 0xFF101418.toInt()
    val TEXT_MUTED = 0xFF90A4AE.toInt()

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Rounded background with a fill colour. */
    fun pill(color: Int, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.toFloat() * 3
        }

    /** A status row: coloured dot + label + value. */
    fun statusRow(ctx: Context, label: String, value: String, state: State): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(state.color())
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12)).apply {
                marginEnd = dp(ctx, 10)
            }
        }
        val text = TextView(ctx).apply {
            this.text = "$label: $value"
            textSize = 15f
            setTextColor(TEXT_ON_DARK)
        }
        row.addView(dot)
        row.addView(text)
        return row
    }

    enum class State { OK, WARN, ERROR, OFF;
        fun color(): Int = when (this) {
            OK -> GREEN_BG
            WARN -> AMBER_BG
            ERROR -> RED_BG
            OFF -> NEUTRAL_BG
        }
    }
}
