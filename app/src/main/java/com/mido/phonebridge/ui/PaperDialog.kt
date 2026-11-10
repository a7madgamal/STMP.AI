package com.mido.phonebridge.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A dialog cut from the same paper as the rest of the app.
 *
 * **Why not `AlertDialog`.** A platform dialog is a white card with a Material scrim and a
 * system title bar. On this screen it reads as an interruption borrowed from another app — the
 * one surface that does not look hand-made, sitting on top of a page that is entirely hand-made.
 * Mido, 2026-11-10: *"not in a boring system dialog use a paperlike one"*.
 *
 * **What it keeps from the platform dialog**, because these are the parts worth having: the
 * window is `WRAP_CONTENT` in both axes, it dims what is behind it, back dismisses it, and the
 * content scrolls when it is long. Only the SKIN changes.
 *
 * **The body is passed in, not templated.** A helper that decided its own layout would be one
 * more thing to fight the moment a screen needs a different shape — and the two callers here
 * already differ (one is prose, one is a form).
 */
object PaperDialog {

    /**
     * Show a paper dialog.
     *
     * @param title the heading, drawn in the handwritten face.
     * @param body what to show under it. Given the full width; scrolls if it overflows.
     * @param actions label → handler, drawn as buttons along the bottom. The handler runs after
     *   the dialog is dismissed, so a handler that opens another screen does not leave this one
     *   behind it.
     */
    fun show(
        ctx: Context,
        title: String,
        body: View,
        actions: List<Pair<String, () -> Unit>> = listOf("close" to {}),
        style: (Int) -> Theme.ButtonStyle = { Theme.ButtonStyle.NEUTRAL },
    ): Dialog {
        val dialog = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(ctx, 20), Theme.dp(ctx, 18),
                Theme.dp(ctx, 20), Theme.dp(ctx, 14),
            )
            // The same paper the cards use, WITH the drawn edge: a rounded rectangle would be
            // the flat surface this app has spent a lot of effort removing everywhere else.
            background = Theme.sheetDp(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }

        sheet.addView(Theme.text(ctx, title, 18f, Theme.ON_SURFACE).apply {
            typeface = Theme.handFont(ctx)
        })

        // ScrollView with weight-less WRAP_CONTENT: the dialog measures to content, and only
        // starts scrolling once the content exceeds the screen.
        val scroll = ScrollView(ctx).apply {
            addView(body)
            isFillViewport = false
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(ctx, 8) })

        if (actions.isNotEmpty()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, Theme.dp(ctx, 14), 0, 0)
            }
            actions.forEachIndexed { index, (label, handler) ->
                row.addView(
                    Theme.button(ctx, label, style(index), small = true) {
                        dialog.dismiss()
                        handler()
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) marginStart = Theme.dp(ctx, 8) }
                )
            }
            sheet.addView(row)
        }

        dialog.setContentView(sheet)

        dialog.window?.apply {
            // Clear, so the paper's own rounded edge is what the human sees rather than a white
            // rectangle behind it with a hard corner poking out at each side.
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.45f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Width: a paper sheet clamped short of the screen edges, never edge-to-edge. On a
            // tablet an unconstrained dialog stretches into a banner.
            setLayout(
                (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    /** A label/value line for dialog bodies, in the app's own type. */
    fun line(ctx: Context, label: String, value: String): TextView =
        Theme.text(ctx, "$label\n$value", 13f, Theme.ON_SURFACE).apply {
            setPadding(0, Theme.dp(ctx, 8), 0, 0)
        }

    /** A quiet caption. */
    fun caption(ctx: Context, text: String): TextView =
        Theme.text(ctx, text, 13f, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, Theme.dp(ctx, 6), 0, 0)
        }

    /** A small heading inside a dialog body. */
    fun heading(ctx: Context, text: String): TextView =
        Theme.text(ctx, text, 13f, Theme.ACCENT).apply {
            typeface = Theme.handFont(ctx)
            setPadding(0, Theme.dp(ctx, 14), 0, Theme.dp(ctx, 2))
        }
}
