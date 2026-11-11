package com.mido.phonebridge.ui

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.TextView
import com.mido.phonebridge.ui.kit.StmpDialog

/**
 * Forwarder kept so the callers of the retired paper dialog (PluginHelp, Home, Settings) change
 * look without changing code. Everything is drawn by [StmpDialog]; WP12 deletes this file.
 */
object PaperDialog {

    /** Shows a themed dialog. [style] is ignored: buttons are the dialog theme's own. */
    @Suppress("UNUSED_PARAMETER")
    fun show(
        ctx: Context,
        title: String,
        body: View,
        actions: List<Pair<String, () -> Unit>> = listOf("close" to {}),
        style: (Int) -> Theme.ButtonStyle = { Theme.ButtonStyle.NEUTRAL },
    ): Dialog = StmpDialog.show(ctx, title, body, actions)

    /** A label/value line for dialog bodies. */
    fun line(ctx: Context, label: String, value: String): View = StmpDialog.line(ctx, label, value)

    /** A quiet caption. */
    fun caption(ctx: Context, text: String): TextView = StmpDialog.caption(ctx, text)

    /** A small heading inside a dialog body. */
    fun heading(ctx: Context, text: String): TextView = StmpDialog.heading(ctx, text)
}
