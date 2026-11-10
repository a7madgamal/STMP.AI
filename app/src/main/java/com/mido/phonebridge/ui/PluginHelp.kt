package com.mido.phonebridge.ui

import android.app.AlertDialog
import android.content.Context
import com.mido.phonebridge.core.Plugin

/**
 * What a plugin does, where it matters, shown as a dialog rather than a screen.
 *
 * **Built from the plugin's OWN declarations, never from a table of prose.** Every line here
 * comes from `routes()`, `permissions()`, `specialAccess()` or `scopes()` — the same data the
 * gate and the request prompts already use. A hand-written description beside a plugin is a
 * second truth: it drifts the first time a route is added, and it lies in the direction the
 * human cannot check, because it describes what they were *told* rather than what the app does.
 *
 * **Why a dialog and not another screen.** The question "what does this do" is asked while
 * looking at the toggle, and the answer must not cost a navigation — the row already navigates
 * on tap for the full detail screen. A dialog is dismissible in one gesture and leaves the
 * switch underneath exactly where it was.
 */
object PluginHelp {

    /** Show the help for [plugin]. */
    fun show(ctx: Context, plugin: Plugin, enabled: Boolean) {
        val body = StringBuilder()

        body.append("${plugin.displayName}\n\n")

        // STATE FIRST: the most common question is not "what is this" but "why is it not
        // working", and the answer is usually here.
        body.append(
            if (enabled) "On. Its routes are served and gated."
            else "Off. Its routes still answer, refusing with an explanation."
        ).append("\n")

        val routes = plugin.routes()
        if (routes.isNotEmpty()) {
            body.append("\nROUTES\n")
            routes.take(MAX_ROUTES).forEach { r ->
                body.append("  ${r.method} ${r.path}\n      ${r.describe}\n")
            }
            if (routes.size > MAX_ROUTES) {
                body.append("  …and ${routes.size - MAX_ROUTES} more\n")
            }
        }

        // Permissions are stated with their REASON, because the reason is what makes a prompt
        // answerable. `PluginPermission.why` is required for exactly that.
        val perms = plugin.permissions()
        if (perms.isNotEmpty()) {
            body.append("\nNEEDS\n")
            perms.forEach { p ->
                val optional = if (p.optional) " (optional — works without it)" else ""
                body.append("  ${p.label}$optional\n      ${p.why}\n")
            }
        }

        val special = plugin.specialAccess()
        if (special.isNotEmpty()) {
            body.append("\nSYSTEM ACCESS\n")
            special.forEach { s ->
                body.append("  ${s.label}\n      Granted in Android Settings, not in this app.\n")
            }
        }

        if (plugin.scopes.isNotEmpty()) {
            body.append("\nGOOGLE SCOPES\n")
            plugin.scopes.forEach { body.append("  $it\n") }
        }

        body.append("\nRefusals are recoverable: a plugin that is off answers `plugin_disabled` ")
        body.append("rather than disappearing, so nothing starts 404-ing because of a switch.")

        AlertDialog.Builder(ctx)
            .setTitle(plugin.displayName)
            .setMessage(body.toString().trimEnd())
            .setPositiveButton("close", null)
            .show()
    }

    /**
     * Route lines shown before summarising.
     *
     * A dialog that runs longer than the screen is a dialog nobody reads to the end, and the
     * count is the honest way to say "there is more" without pretending the list was complete.
     */
    private const val MAX_ROUTES = 6
}
