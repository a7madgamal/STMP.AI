package com.mido.phonebridge.ui

import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.kit.Led

/**
 * What one row of the Plugins tab shows, as pure data (WP05 5.4). The fragment only wires it.
 *
 * Every mapping from a plugin's own `status()` to a word, an LED, a rocker position and a sort
 * rank lives here, so the row, the help dialog and the tests agree.
 */
object PluginRowModel {
    enum class Tone { OK, CAUTION, NEUTRAL }

    data class Model(
        val id: String,
        val title: String,
        /** ON, OFF, NOT SET UP, NEEDS ACTION, ERROR or NOT IN THIS BUILD. */
        val word: String,
        val tone: Tone,
        val led: Led.State,
        val detail: String,
        /** What the rocker shows. */
        val showsOn: Boolean,
        /** Sort key: 0 error, 1 action needed, 2 not set up, 3 on, 4 off, 5 not in this build. */
        val rank: Int,
    )

    const val RANK_ERROR = 0
    const val RANK_ACTION = 1
    const val RANK_NOT_SET_UP = 2
    const val RANK_ON = 3
    const val RANK_OFF = 4
    const val RANK_NOT_IN_BUILD = 5

    private val GOOGLE_IDS = setOf("google", "gmail", "calendar", "drive")
    private const val SWITCHED_OFF = "Switched off. Agents that call it are told it is off."
    private const val NO_GOOGLE = PluginCopy.NO_GOOGLE
    private const val NO_SECRETS = "No secrets yet — add one below."

    /** The name shown for [plugin]; ids never reach the screen (D21). */
    fun title(plugin: Plugin): String = if (plugin.id == "secrets") "Secrets" else plugin.displayName

    /** Never throws: a plugin whose `status()` throws becomes an ERROR status. */
    fun statusOf(plugin: Plugin): PluginStatus = try {
        plugin.status()
    } catch (e: Exception) {
        PluginStatus(
            plugin.displayName,
            PluginStatus.State.ERROR,
            "Status unavailable: ${e.message ?: e.javaClass.simpleName}",
        )
    }

    fun of(
        plugin: Plugin,
        enabled: Boolean,
        hasGoogleClient: Boolean = AuthManager.clientId.isNotBlank(),
        insidePlugin: Boolean = false,
    ): Model {
        val id = plugin.id
        val title = title(plugin)
        if (id in GOOGLE_IDS && !hasGoogleClient) {
            // The rocker is OFF whatever was stored: nothing in this build can serve it.
            return Model(id, title, PluginCopy.NOT_IN_BUILD_WORD, Tone.NEUTRAL, Led.State.OFF, NO_GOOGLE, false, RANK_NOT_IN_BUILD)
        }
        if (!enabled) {
            return Model(id, title, "OFF", Tone.NEUTRAL, Led.State.OFF, SWITCHED_OFF, false, RANK_OFF)
        }
        val status = statusOf(plugin)
        val detail = PluginCopy.humanDetail(id, status.state, status.detail, insidePlugin)
        if (id == "secrets" && status.state == PluginStatus.State.NEEDS_ATTENTION && status.detail.startsWith("0 secret")) {
            return Model(id, title, "NOT SET UP", Tone.NEUTRAL, Led.State.OFF, NO_SECRETS, false, RANK_NOT_SET_UP)
        }
        return when (status.state) {
            PluginStatus.State.OK -> Model(id, title, "ON", Tone.OK, Led.State.OK, detail, true, RANK_ON)
            PluginStatus.State.NEEDS_ATTENTION ->
                Model(id, title, "NEEDS ACTION", Tone.CAUTION, Led.State.CAUTION, detail, false, RANK_ACTION)
            PluginStatus.State.ERROR ->
                Model(id, title, "ERROR", Tone.CAUTION, Led.State.CAUTION, detail, false, RANK_ERROR)
            PluginStatus.State.OFF ->
                Model(id, title, "NOT SET UP", Tone.NEUTRAL, Led.State.OFF, detail, false, RANK_NOT_SET_UP)
        }
    }

    /** True for a plugin this build cannot serve at all (D70); its switch is disabled. */
    fun isNotInBuild(m: Model): Boolean = m.rank == RANK_NOT_IN_BUILD

    /** Needs-action rows first, switched-off last; ties by title. */
    fun sorted(models: List<Model>): List<Model> =
        models.sortedWith(compareBy({ it.rank }, { it.title.lowercase() }))

    /** True for the rows the summary counts as needing attention (error or action needed). */
    fun needsAttention(m: Model): Boolean = m.rank == RANK_ERROR || m.rank == RANK_ACTION

    fun summary(models: List<Model>): String {
        val installed = models.size
        val on = models.count { it.rank == RANK_ON }
        val attention = models.count(::needsAttention)
        return buildString {
            append(installed).append(if (installed == 1) " plugin" else " plugins")
            append(" · ").append(on).append(" on")
            if (attention > 0) {
                append(" · ").append(attention).append(if (attention == 1) " needs" else " need").append(" action")
            }
        }
    }
}
