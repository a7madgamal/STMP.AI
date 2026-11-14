package com.mido.phonebridge.ui

import android.content.Context
import android.widget.LinearLayout
import com.mido.phonebridge.core.Plugin

/**
 * The `?` beside a plugin: what it does, in words a person would use.
 *
 * **This replaced a generated technical sheet.** The first version listed routes, HTTP methods and
 * permission ids, built from the plugin's own declarations. It was accurate and it was useless —
 * nobody deciding whether to switch on "Google Drive" wants to read `GET /drive/files/{id}`. Mido,
 * 2026-11-10: *"friendly not a fucking api documentation. a non developer simple description of
 * the features and example prompts"*.
 *
 * **The trade, stated rather than hidden.** Generated copy cannot drift; this copy can. It is kept
 * to PURPOSE rather than mechanism, because purpose changes rarely and routes change constantly.
 * `/help` remains the accurate contract for anything that needs one — this dialog exists to answer
 * "should I turn this on", which is a different question.
 *
 * **Example prompts are the load-bearing part.** "Read my last email" explains Gmail faster than
 * any description of it, and it is the shape of thing a human will actually type.
 *
 * **The status line asks the plugin, and used to not.** It printed "On, and ready." whenever the
 * stored switch was on — including for a plugin that was never set up, or one missing the
 * permission its own `status()` reports as NEEDS_ATTENTION. The screen the human opens to ask
 * "why is this not working" was therefore answering "it is fine". The row above already calls
 * `status()`; passing it in costs nothing and stops the two from disagreeing.
 */
object PluginHelp {

    /**
     * Show the `?` dialog for [plugin].
     *
     * [status] is the plugin's OWN report, or null when the switch is off (nothing to report).
     * A caller that has not asked for one gets the honest "not asked" sentence rather than a
     * cheerful claim, because an unverified "ready" is the bug this signature exists to stop.
     */
    fun show(
        ctx: Context,
        plugin: Plugin,
        enabled: Boolean,
        status: com.mido.phonebridge.core.PluginStatus? = null,
    ) {
        val info = PluginCopy.forId(plugin.id, plugin.displayName)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // State first, because the question is usually "why is this not working" rather than
        // "what is this". Both sentences are short: the answer is the whole point of the row.
        val line = PluginCopy.statusLine(enabled, status)
        body.addView(
            Theme.text(
                ctx,
                line.text,
                Theme.TEXT_DETAIL_SP,
                when (line.tone) {
                    PluginCopy.LineTone.OK -> Theme.SUCCESS
                    PluginCopy.LineTone.CAUTION -> Theme.WARNING
                    PluginCopy.LineTone.NEUTRAL -> Theme.ON_SURFACE_VARIANT
                },
            )
        )

        body.addView(PaperDialog.caption(ctx, info.blurb))

        if (info.examples.isNotEmpty()) {
            body.addView(PaperDialog.heading(ctx, "Ask it things like"))
            for (example in info.examples) {
                body.addView(
                    Theme.text(ctx, "“$example”", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE).apply {
                        setPadding(
                            Theme.dp(ctx, 10), Theme.dp(ctx, 6),
                            Theme.dp(ctx, 10), Theme.dp(ctx, 6),
                        )
                        background = Theme.outlined(
                            ctx, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL,
                        )
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(ctx, 6) }
                )
            }
        }

        // The permissions this plugin will ask for, in the plugin's own words. Kept because a
        // prompt that appears later with no warning is the thing people refuse.
        val perms = plugin.permissions()
        if (perms.isNotEmpty()) {
            body.addView(PaperDialog.heading(ctx, "It will ask for"))
            for (p in perms) {
                body.addView(
                    PaperDialog.caption(
                        ctx,
                        "• ${p.label} — ${p.why}${if (p.optional) " (optional)" else ""}",
                    )
                )
            }
        }

        PaperDialog.show(
            ctx = ctx,
            title = plugin.displayName,
            body = body,
            actions = listOf("got it" to {}),
            style = { Theme.ButtonStyle.PRIMARY },
        )
    }
}
