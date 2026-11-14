package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.widget.TextViewCompat

enum class TypeRole(
    val face: FontFace,
    val sizeSp: Float,
    val lineHeightSp: Float,
    val letterSpacingEm: Float,
    val caps: Boolean,
    val tabular: Boolean,
) {
    DISPLAY(FontFace.MONO_500, 36f, 40f, -0.04f, false, true),
    NUMERAL(FontFace.MONO_500, 24f, 28f, -0.04f, false, true),
    PUNCH(FontFace.SANS_700, 28f, 32f, 0.02f, true, false),
    TITLE(FontFace.SANS_600, 20f, 26f, -0.02f, false, false),
    COMMAND(FontFace.MONO_500, 16f, 24f, 0f, false, false),
    ROW_LABEL(FontFace.SANS_500, 16f, 22f, 0f, false, false),
    BODY(FontFace.SANS_400, 14f, 20f, 0f, false, false),
    BODY_STRONG(FontFace.SANS_500, 14f, 20f, 0f, false, false),
    DETAIL(FontFace.SANS_400, 13f, 18f, 0f, false, false),
    READOUT(FontFace.MONO_500, 13f, 18f, 0f, false, true),
    MONO_BODY(FontFace.MONO_400, 13f, 18f, 0f, false, false),
    KEY(FontFace.MONO_600, 13f, 16f, 0.12f, true, false),
    LABEL(FontFace.MONO_500, 12f, 16f, 0.14f, true, false),
    CHIP(FontFace.MONO_600, 12f, 16f, 0.06f, true, false),
    TAB(FontFace.SANS_500, 12f, 16f, 0.02f, false, false),
    CAPTION(FontFace.SANS_400, 12f, 16f, 0f, false, false),
    WORDMARK(FontFace.SANS_700, 13f, 16f, 0.2f, false, false),
}

object Type {
    /** Apply [role] to [tv]: typeface, size, line height, tracking, caps, tnum. */
    fun apply(tv: TextView, role: TypeRole) {
        tv.typeface = Fonts.get(tv.context, role.face)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sizeSp)
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, role.lineHeightSp, tv.resources.displayMetrics,
        )
        TextViewCompat.setLineHeight(tv, px.toInt())
        tv.letterSpacing = role.letterSpacingEm
        tv.isAllCaps = role.caps
        tv.fontFeatureSettings = if (role.tabular) "tnum" else null
    }

    /** A new TextView with [role] and [color] applied. */
    fun text(ctx: Context, text: CharSequence, role: TypeRole, @ColorInt color: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            apply(this, role)
            setTextColor(color)
        }
}
