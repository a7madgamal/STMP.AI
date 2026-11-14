package com.mido.phonebridge.ui.kit

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The one error card (R3): a plain-words title and sentence, the raw exception text behind a
 * "Details" disclosure inside the same card, and one recovery key. A destructive recovery (delete) is an OUTLINE key, never filled (D54). Every screen that used to print
 * `e.message` into a panel of its own builds this instead.
 */
object ErrorBlock {
    /** Label on the disclosure key; a test taps it by this text. */
    const val DETAILS = "Details"
    const val HIDE_DETAILS = "Hide details"

    fun create(
        ctx: Context,
        title: String,
        body: String,
        details: String?,
        recovery: KeyButton.Spec? = null,
        framed: Boolean = true,
        destructiveRecovery: Boolean = false,
    ): LinearLayout {
        val p = Theme.palette(ctx)
        // [framed] false is for a card that already sits inside a panel: no second bevel.
        val panel = if (framed) Panel.create(ctx, Panel.Tone.CAUTION) else LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
        }
        panel.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            Led(ctx).apply { state = Led.State.CAUTION },
            LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
                marginEnd = ctx.dp(10)
                topMargin = ctx.dp(3)
            },
        )
        head.addView(
            Type.text(ctx, title, TypeRole.ROW_LABEL, p.cautionText),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        panel.addView(head, matchWrap())
        panel.addView(
            Type.text(ctx, body, TypeRole.DETAIL, p.ink),
            matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_XS) },
        )

        val raw = details?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val readout = Readout.create(ctx, raw, Readout.Kind.BODY).root.apply { visibility = View.GONE }
            lateinit var toggle: com.google.android.material.button.MaterialButton
            toggle = KeyButton.create(ctx, DETAILS, KeyButton.Kind.PANEL, KeyButton.Size.SMALL, describe = "Show the technical details") {
                val show = readout.visibility != View.VISIBLE
                readout.visibility = if (show) View.VISIBLE else View.GONE
                toggle.text = if (show) HIDE_DETAILS else DETAILS
            }
            val row = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(Dimens.SPACE_M) }
            panel.addView(toggle, row)
            panel.addView(readout, matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_S) })
        }
        if (recovery != null) {
            panel.addView(
                KeyButton.create(ctx, recovery.label, if (destructiveRecovery) KeyButton.Kind.OUTLINE else KeyButton.Kind.INK, KeyButton.Size.REGULAR, describe = recovery.describe, onClick = recovery.onClick),
                matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_M) },
            )
        }
        return panel
    }
}
