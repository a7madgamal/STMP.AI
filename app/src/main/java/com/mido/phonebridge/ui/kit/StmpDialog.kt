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

    /**
     * A delete confirmation in the D54 style (Forget, Delete secret, Delete SSH connection, Reset
     * vault): the safe key first and focused, the delete key LAST as an OUTLINE key with an ink
     * label (never signal orange), [Dimens.DESTRUCTIVE_GAP] clear of it. Returns the dialog
     * created, not shown, so a secure screen can show it with FLAG_SECURE. [onDelete] runs after
     * the dialog has dismissed.
     */
    fun confirmDelete(
        ctx: Context,
        title: String,
        message: String,
        deleteLabel: String,
        keepLabel: String = "Cancel",
        onKeep: (() -> Unit)? = null,
        onDelete: () -> Unit,
    ): AlertDialog {
        val p = Theme.palette(ctx)
        var dialog: AlertDialog? = null
        val keep = KeyButton.create(ctx, keepLabel, KeyButton.Kind.PANEL, KeyButton.Size.REGULAR) {
            dialog?.dismiss()
            onKeep?.invoke()
        }
        val delete = KeyButton.create(ctx, deleteLabel, KeyButton.Kind.OUTLINE, KeyButton.Size.REGULAR) {
            dialog?.dismiss()
            onDelete()
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(Dimens.SPACE_XL), ctx.dp(Dimens.SPACE_S), ctx.dp(Dimens.SPACE_XL), ctx.dp(Dimens.SPACE_S))
            addView(Type.text(ctx, message, TypeRole.BODY, p.ink), matchWrap())
            addView(keep, matchWrap().apply { topMargin = ctx.dp(Dimens.SPACE_L) })
            addView(delete, matchWrap().apply { topMargin = ctx.dp(Dimens.DESTRUCTIVE_GAP) })
        }
        val scroll = NestedScrollView(ctx).apply { addView(body) }
        return builder(ctx).setTitle(title).setView(scroll).create().also { d ->
            dialog = d
            // The key that destroys data is never the one a stray tap lands on.
            d.setOnShowListener {
                keep.isFocusableInTouchMode = true
                keep.requestFocus()
            }
        }
    }

    /** One row of [choose]. [detail] is the second line; [selected] marks the current value. */
    data class Choice(val label: String, val detail: String? = null, val selected: Boolean = false)

    /**
     * A single-choice list: every row is at least 56dp, has a [RadioMark], and is separated from
     * the next by [Dimens.TARGET_GAP], so options never run together. Picking a row dismisses the
     * dialog and reports its index; Cancel is the one text button.
     */
    fun choose(ctx: Context, title: String, choices: List<Choice>, onPick: (Int) -> Unit): AlertDialog {
        val p = Theme.palette(ctx)
        var dialog: AlertDialog? = null
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(Dimens.SPACE_XL), ctx.dp(Dimens.SPACE_XS), ctx.dp(Dimens.SPACE_XL), 0)
        }
        choices.forEachIndexed { i, c ->
            val text = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Type.text(ctx, c.label, TypeRole.ROW_LABEL, p.ink), matchWrap())
                if (c.detail != null) {
                    addView(Type.text(ctx, c.detail, TypeRole.DETAIL, p.etched), matchWrap().apply { topMargin = ctx.dp(2) })
                }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(Dimens.SPACE_S), ctx.dp(Dimens.SPACE_S), ctx.dp(Dimens.SPACE_S), ctx.dp(Dimens.SPACE_S))
                tag = i
                isClickable = true
                isFocusable = true
                installPressedFill(this, Dimens.RADIUS_KEY)
                installFocusRing(this, Dimens.RADIUS_KEY)
                addView(RadioMark.create(ctx, c.selected), LinearLayout.LayoutParams(ctx.dp(RadioMark.SIZE_DP), ctx.dp(RadioMark.SIZE_DP)).apply {
                    marginEnd = ctx.dp(Dimens.SPACE_M)
                })
                addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                contentDescription = listOfNotNull(c.label, c.detail).joinToString(", ")
                setRole(this, "android.widget.RadioButton")
                androidx.core.view.ViewCompat.setStateDescription(this, if (c.selected) "Selected" else "Not selected")
                setOnClickListener {
                    dialog?.dismiss()
                    onPick(i)
                }
            }
            list.addView(row, matchWrap().apply { if (i > 0) topMargin = ctx.dp(Dimens.TARGET_GAP) })
        }
        val scroll = NestedScrollView(ctx).apply { addView(list) }
        return builder(ctx).setTitle(title).setView(scroll).setNegativeButton("Cancel", null).create()
            .also { dialog = it }
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
