package com.mido.phonebridge.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The bulb beside a plugin: what it does, and what state it is really in.
 *
 * **The status line is read when the dialog opens**, from the plugin itself. It used to print
 * "On, and ready." whenever the stored switch was on, even for a plugin that was not set up or
 * needed a permission, because it never asked the plugin. Purpose and examples are hand-written
 * ([PluginCopy]); `/help` remains the accurate contract.
 */
object PluginHelp {

    /** Show the help dialog for [plugin]; [onOpenDetail] adds an "Open plugin" action when it is not healthy. */
    fun show(ctx: Context, plugin: Plugin, enabled: Boolean, onOpenDetail: (() -> Unit)? = null) {
        val info = PluginCopy.forId(plugin.id, plugin.displayName)
        val line = PluginCopy.statusLine(
            plugin.id, enabled, PluginRowModel.statusOf(plugin), hasGoogleClient = AuthManager.clientId.isNotBlank(),
        )
        val healthy = line.led == Led.State.OK
        val p = Theme.palette(ctx)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val lineColor = when (line.tone) {
            PluginCopy.LineTone.OK -> p.okText
            PluginCopy.LineTone.CAUTION -> p.cautionText
            PluginCopy.LineTone.NEUTRAL -> p.ink
        }
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        statusRow.addView(
            Led(ctx).apply { state = line.led; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(Theme.dp(ctx, 16), Theme.dp(ctx, 16)).apply {
                marginEnd = Theme.dp(ctx, 10)
                topMargin = Theme.dp(ctx, 2)
            },
        )
        statusRow.addView(
            Type.text(ctx, line.text, TypeRole.BODY_STRONG, lineColor),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        body.addView(statusRow)

        body.addView(
            Type.text(ctx, info.blurb, TypeRole.BODY, p.ink),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, 12) },
        )

        if (info.examples.isNotEmpty()) {
            body.addView(heading(ctx, "Ask it things like"))
            for (example in info.examples) {
                body.addView(
                    Readout.create(ctx, "“$example”", Readout.Kind.BODY).root,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(ctx, 6) },
                )
            }
        }

        val perms = plugin.permissions()
        if (perms.isNotEmpty()) {
            body.addView(heading(ctx, "It will ask for"))
            for (perm in perms) {
                body.addView(
                    Type.text(
                        ctx,
                        "• ${perm.label} — ${perm.why}${if (perm.optional) " (optional)" else ""}",
                        TypeRole.DETAIL, p.etched,
                    ),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(ctx, 4) },
                )
            }
        }

        val actions: List<Pair<String, () -> Unit>> = when {
            healthy -> listOf("Got it" to {})
            onOpenDetail == null -> listOf("Close" to {})
            else -> listOf("Close" to {}, "Open plugin" to onOpenDetail)
        }
        StmpDialog.show(ctx, PluginRowModel.title(plugin), body, actions)
    }

    private fun heading(ctx: Context, text: String) = StmpDialog.heading(ctx, text).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(ctx, 16) }
    }

    /** The status the dialog would show now, for tests. */
    internal fun lineFor(plugin: Plugin, enabled: Boolean): PluginCopy.StatusLine =
        PluginCopy.statusLine(plugin.id, enabled, PluginRowModel.statusOf(plugin))
}
