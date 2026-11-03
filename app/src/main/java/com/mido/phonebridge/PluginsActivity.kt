package com.mido.phonebridge

import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSettingsStore
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.Theme

/**
 * The Plugins screen: every capability the bridge exposes, whether it is on, and why not.
 *
 * **This screen exists because Home had become a wall.** Twelve plugins rendered as status cards
 * between the server control and the tools section, which pushed the one thing Home is FOR —
 * is the bridge running — off the first screen. Capability inventory belongs on its own page.
 *
 * **Two different "not working" states are kept apart**, and the distinction is the point of the
 * screen:
 *
 *  - **OFF — you turned it off.** The switch is the remedy, and the row says so.
 *  - **NEEDS ATTENTION — it is on but cannot serve.** No OAuth client, no SSH connections, a
 *    missing model. The switch is NOT the remedy, so offering it would be a lie that costs the
 *    human a tap and teaches them the wrong thing about their own app.
 *
 * Collapsing those two into one grey row is the easiest possible mistake here and the one that
 * makes the screen useless.
 */
class PluginsActivity : BridgeScreen() {

    override val tab = Theme.Tab.PLUGINS

    private lateinit var list: LinearLayout

    override fun buildContent(container: LinearLayout) {
        container.addView(Theme.text(this, "PLUGINS", 20f).apply {
            typeface = Theme.handFont(this@PluginsActivity)
            setTextColor(Theme.ON_SURFACE)
        })
        container.addView(Theme.text(
            this,
            "Each plugin owns its own routes, settings and permissions. Turning one off " +
                "keeps its routes answering — with a refusal that says it is off.",
            13f,
        ).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(this@PluginsActivity, 4), 0, 0)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list)

        render()
    }

    private fun render() {
        list.removeAllViews()

        val store = PluginSettingsStore.from(applicationContext)
        val plugins = PluginRegistry.all().sortedBy { it.displayName }

        if (plugins.isEmpty()) {
            // Real state: the service has not registered yet. Say so rather than showing a blank
            // page that reads as "no plugins exist".
            list.addView(Theme.text(this, "No plugins registered yet.", 13f).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(this@PluginsActivity, 16), 0, 0)
            })
            return
        }

        for (plugin in plugins) {
            list.addView(row(plugin, store))
        }
    }

    private fun row(
        plugin: com.mido.phonebridge.core.Plugin,
        store: PluginSettingsStore,
    ): LinearLayout {
        val on = store.isEnabled(plugin)
        // `status()` is the plugin's own answer about whether it can serve. Only consulted when
        // the plugin is ON: an off plugin may well report OK, and a page saying "OK" next to a
        // switch that is off is the confusion this screen exists to remove.
        val status = if (on) plugin.status() else null

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@PluginsActivity, 14), Theme.dp(this@PluginsActivity, 12),
                Theme.dp(this@PluginsActivity, 14), Theme.dp(this@PluginsActivity, 12),
            )
            background = Theme.tornCard(this@PluginsActivity, Theme.SURFACE, Theme.RADIUS_CARD)
            isClickable = true
            contentDescription = "${plugin.displayName}: ${stateLabel(on, status)}"
            setOnClickListener { openDetail(plugin.id) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, 10)
        card.layoutParams = lp

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(Theme.text(this, plugin.displayName, 16f).apply {
            setTextColor(Theme.ON_SURFACE)
            typeface = Theme.handFont(this@PluginsActivity)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // The state chip. Colour carries the meaning and the WORD carries it too, because a
        // colour alone is unreadable for a chunk of the population and invisible in a screenshot.
        titleRow.addView(Theme.text(this, stateLabel(on, status), 12f).apply {
            setTextColor(stateColour(on, status))
        })
        card.addView(titleRow)

        val detail = stateDetail(on, status)
        if (detail.isNotEmpty()) {
            card.addView(Theme.text(this, detail, 12f).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(this@PluginsActivity, 4), 0, 0)
            })
        }
        return card
    }

    private fun stateLabel(on: Boolean, status: PluginStatus?): String = when {
        !on -> "OFF"
        status == null -> "ON"
        status.state == PluginStatus.State.OK -> "ON"
        status.state == PluginStatus.State.NEEDS_ATTENTION -> "ACTION NEEDED"
        status.state == PluginStatus.State.ERROR -> "ERROR"
        // A plugin that is on but reports OFF internally is measurably misconfigured, not off by
        // the human. Naming it "NOT SET UP" keeps the two states distinct in the one place they
        // could collide.
        else -> "NOT SET UP"
    }

    private fun stateColour(on: Boolean, status: PluginStatus?): Int = when {
        !on -> Theme.ON_SURFACE_VARIANT
        status == null -> Theme.SUCCESS
        status.state == PluginStatus.State.OK -> Theme.SUCCESS
        status.state == PluginStatus.State.NEEDS_ATTENTION -> Theme.WARNING
        else -> Theme.DANGER
    }

    private fun stateDetail(on: Boolean, status: PluginStatus?): String = when {
        !on -> "Switched off. Its routes still answer, refusing with an explanation."
        status == null -> ""
        status.state == PluginStatus.State.OK -> status.detail
        else -> status.detail
    }

    private fun openDetail(pluginId: String) {
        startActivity(
            Intent(this, PluginDetailActivity::class.java)
                .putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, pluginId)
        )
    }

    override fun onResume() {
        super.onResume()
        // A toggle changed on the detail screen must be visible on the way back. This rebuilds
        // ONLY the list — the bar and the header are outside `list` and never move, so a press in
        // flight on them cannot be cancelled.
        if (::list.isInitialized) render()
    }
}
