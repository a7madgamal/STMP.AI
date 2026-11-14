package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.widget.TextViewCompat

/**
 * The type roles, on the PAPER faces.
 *
 * The kit's roles and metrics are kept — DISPLAY through CAPTION, with line height, tracking,
 * caps and tabular figures as data rather than repeated per call site. What changed is the
 * family behind each role: Handlee for the stamp identity, Atkinson for anything meant to be
 * read, JetBrains Mono NL for anything meant to be copied exactly.
 *
 * Roles keep their names even where the family differs from the kit's, so a screen written
 * against this layer does not have to know which face is behind "READOUT".
 */

enum class TypeRole(
    val face: FontFace,
    val sizeSp: Float,
    val lineHeightSp: Float,
    val letterSpacingEm: Float,
    val caps: Boolean,
    val tabular: Boolean,
) {
    // Handlee: the display role is the app's handwriting, and the stamp identity lives here.
    DISPLAY(FontFace.DISPLAY, 36f, 40f, -0.04f, false, true),
    NUMERAL(FontFace.MONO_400, 24f, 28f, -0.04f, false, true),
    // The rubber-stamp word: same family as the mark it announces.
    PUNCH(FontFace.DISPLAY, 28f, 32f, 0.02f, true, false),
    TITLE(FontFace.SANS_600, 20f, 26f, -0.02f, false, false),
    COMMAND(FontFace.MONO_400, 16f, 24f, 0f, false, false),
    ROW_LABEL(FontFace.SANS_600, 16f, 22f, 0f, false, false),
    BODY(FontFace.SANS_400, 14f, 20f, 0f, false, false),
    BODY_STRONG(FontFace.SANS_600, 14f, 20f, 0f, false, false),
    DETAIL(FontFace.SANS_400, 13f, 18f, 0f, false, false),
    READOUT(FontFace.MONO_400, 13f, 18f, 0f, false, true),
    MONO_BODY(FontFace.MONO_400, 13f, 18f, 0f, false, false),
    KEY(FontFace.MONO_600, 13f, 16f, 0.12f, true, false),
    LABEL(FontFace.MONO_400, 12f, 16f, 0.14f, true, false),
    CHIP(FontFace.MONO_600, 12f, 16f, 0.06f, true, false),
    TAB(FontFace.SANS_600, 12f, 16f, 0.02f, false, false),
    CAPTION(FontFace.SANS_400, 12f, 16f, 0f, false, false),
    WORDMARK(FontFace.SANS_600, 13f, 16f, 0.2f, false, false),
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
