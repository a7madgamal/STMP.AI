package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.UntrustedText
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole
import java.util.Date

/** One line of the receipt tape: verb chip, text, local time (COMPONENTS 19.1). The text may be a request summary, so hidden characters are drawn as marks. */
object Tape {
    fun row(ctx: Context, verb: Verb, text: String, epochMs: Long, onClick: (() -> Unit)? = null): View {
        val p = Theme.palette(ctx)
        val time = DateFormat.getTimeFormat(ctx).format(Date(epochMs))
        return RuleLinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            minimumHeight = ctx.dp(if (onClick != null) Dimens.MIN_TARGET else 40)
            layoutParams = matchWrap()
            // One chip column: every verb's chip is as wide as EXECUTE, so the text after it starts
            // at the same x on every row.
            addView(VerbChip.create(ctx, verb), LinearLayout.LayoutParams(
                VerbChip.columnWidthPx(ctx), LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(
                Type.text(ctx, UntrustedText.mark(text, p.cautionText), TypeRole.READOUT, p.ink),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = ctx.dp(Dimens.SPACE_S); marginEnd = ctx.dp(Dimens.SPACE_S) },
            )
            addView(Type.text(ctx, time, TypeRole.READOUT, p.etched).apply { gravity = Gravity.END })
            contentDescription = "${verb.name.lowercase()}, ${UntrustedText.escape(text)}, at $time"
            androidx.core.view.ViewCompat.setScreenReaderFocusable(this, true)
            if (onClick != null) {
                foreground = RippleDrawable(rippleColor(ctx), null, ColorDrawable(p.ink))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                setRole(this, "android.widget.Button")
            }
        }
    }
}
