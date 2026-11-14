package com.mido.phonebridge.ui.kit

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The in-app logo (COMPONENTS 29, D26, D53): the vector key glyph and the word STMP set in Geist.
 *
 * The word is a `TextView`, not paths, so it scales with the user's font size and takes the theme
 * colour. The container reads once as "STMP"; its children are hidden from accessibility.
 */
object Wordmark {

    private const val WORD = "STMP"
    private const val WORD_GAP_DP = 8

    fun create(ctx: Context, iconDp: Int = 20, withText: Boolean = true): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = WORD
            isFocusable = false
        }
        val glyph = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_logo_key)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(glyph, LinearLayout.LayoutParams(ctx.dp(iconDp), ctx.dp(iconDp)))
        if (withText) {
            val word = Type.text(ctx, WORD, TypeRole.WORDMARK, Theme.palette(ctx).ink).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(
                word,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = ctx.dp(WORD_GAP_DP) },
            )
        }
        return row
    }
}
