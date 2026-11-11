package com.mido.phonebridge.ui.kit

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** Every dialog in the app is built here, so every dialog wears the panel theme (COMPONENTS 16). */
object StmpDialog {

    /** A `MaterialAlertDialogBuilder` styled by `ThemeOverlay.STMP.Dialog` (app theme). */
    fun builder(ctx: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(ctx)

    /**
     * A dialog with a caller-supplied body. [actions] map to the buttons in Material order:
     * the last is positive, the first negative, a middle one neutral. A handler runs after the
     * dialog has dismissed. Initial focus is never on a destructive action (D54: it stays ink).
     */
    fun show(
        ctx: Context,
        title: String,
        body: View,
        actions: List<Pair<String, () -> Unit>> = listOf("Close" to {}),
    ): AlertDialog {
        val scroll = NestedScrollView(ctx).apply {
            setPadding(ctx.dp(Dimens.SPACE_XL), 0, ctx.dp(Dimens.SPACE_XL), 0)
            addView(body)
        }
        val b = builder(ctx).setTitle(title).setView(scroll)
        fun handler(i: Int) = DialogInterface.OnClickListener { _, _ -> actions[i].second() }
        when (actions.size) {
            0 -> {}
            1 -> b.setPositiveButton(actions[0].first, handler(0))
            2 -> { b.setNegativeButton(actions[0].first, handler(0)); b.setPositiveButton(actions[1].first, handler(1)) }
            else -> {
                b.setNegativeButton(actions.first().first, handler(0))
                b.setNeutralButton(actions[1].first, handler(1))
                b.setPositiveButton(actions.last().first, handler(actions.lastIndex))
            }
        }
        return b.show()
    }

    /** An etched label with its value underneath. */
    fun line(ctx: Context, label: String, value: String): View {
        val p = Theme.palette(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(Dimens.SPACE_S), 0, 0)
            addView(Etched.label(ctx, label))
            addView(Type.text(ctx, value, TypeRole.BODY, p.ink), matchWrap())
        }
    }

    fun caption(ctx: Context, text: String): TextView =
        Type.text(ctx, text, TypeRole.CAPTION, Theme.palette(ctx).etched).apply {
            setPadding(0, ctx.dp(6), 0, 0)
        }

    fun heading(ctx: Context, text: String): TextView = SectionHeader.create(ctx, text, inPanel = true)
}
