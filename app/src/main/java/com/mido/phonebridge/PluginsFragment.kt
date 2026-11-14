package com.mido.phonebridge

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSettingsStore
import com.mido.phonebridge.ui.PluginHelp
import com.mido.phonebridge.ui.PluginRowModel
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.dp
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.PluginRow
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The Plugins screen: every capability the bridge exposes, whether it is on, and why not.
 *
 * Two different "not working" states stay apart, and the distinction is the point of the screen:
 * OFF is "you turned it off" (the switch is the remedy); NEEDS ACTION / NOT SET UP / ERROR is
 * "it is on but cannot serve" (the switch is not the remedy). Rows that need action sort first in
 * their group (D24); Debug, Help, Logs and Probe live in a collapsed Developer group.
 */
class PluginsFragment : BridgeFragment() {

    override val tab = Theme.Tab.PLUGINS

    private lateinit var list: LinearLayout
    private lateinit var summaryLabel: TextView

    /** One row's plugin and what it shows. */
    private data class Item(val plugin: Plugin, val model: PluginRowModel.Model)

    override fun buildContent(container: LinearLayout) {
        val p = Theme.palette(ctx)
        container.addView(Type.text(ctx, "Plugins", TypeRole.TITLE, p.ink).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        })
        summaryLabel = Etched.label(ctx, "").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(Dimens.SPACE_XS)
        }
        container.addView(summaryLabel)
        container.addView(
            Type.text(ctx, "Turning a plugin off keeps it answering; agents are told it is off.", TypeRole.CAPTION, p.etched),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(Dimens.SPACE_XS) },
        )
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list)
        render()
    }

    /**
     * The groups, by what the plugin is ABOUT: the Google services share an account, the phone ones
     * share device permissions, and the developer ones are the machinery that explains the rest.
     * An id in no group is a defect, not a plugin to hide: it is shown under "Other" and logged.
     */
    private val groups: List<Pair<String, Set<String>>> = listOf(
        "Google" to setOf("google", "gmail", "calendar", "drive"),
        "Phone" to setOf("notifications", "contacts", "audio", "tts"),
        "Secrets" to setOf("secrets"),
        "SSH" to setOf("ssh"),
        DEVELOPER to setOf("help", "debug", "logs", "probe"),
    )

    private fun render() {
        list.removeAllViews()
        val store = PluginSettingsStore.from(requireContext())
        // Per-secret plugins exist for routing; they are not capabilities a human switches on.
        val plugins = PluginRegistry.all().filterNot { it is com.mido.phonebridge.plugins.secrets.SecretPlugin }
        val byId = plugins.associateBy { it.id }
        val claimed = mutableSetOf<String>()
        val shown = ArrayList<PluginRowModel.Model>()

        fun itemsOf(members: List<Plugin>): List<Item> {
            val items = members.map { Item(it, PluginRowModel.of(it, store.isEnabled(it))) }
            shown += items.map { it.model }
            val order = PluginRowModel.sorted(items.map { it.model }).map { it.id }
            return items.sortedBy { order.indexOf(it.model.id) }
        }

        for ((heading, ids) in groups) {
            val members = ids.mapNotNull { byId[it] }
            if (members.isEmpty()) continue
            claimed += members.map { it.id }
            val items = itemsOf(members)
            if (heading == DEVELOPER) {
                list.addView(developerPanel(items, store))
                continue
            }
            list.addView(SectionHeader.create(ctx, heading))
            list.addView(groupPanel(heading, items, store))
        }

        val unlisted = plugins.filter { it.id !in claimed }
        if (unlisted.isNotEmpty()) {
            Debug.log("plugins: ungrouped ids ${unlisted.map { it.id }}")
            list.addView(SectionHeader.create(ctx, "Other"))
            list.addView(groupPanel("Other", itemsOf(unlisted), store))
        }

        summaryLabel.text = PluginRowModel.summary(shown)
        if (plugins.isEmpty()) {
            list.addView(emptyState().apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ctx.dp(Dimens.SPACE_L)
            })
        }
    }

    private fun groupPanel(heading: String, items: List<Item>, store: PluginSettingsStore): LinearLayout {
        val panel = Panel.create(ctx)
        for (item in items) panel.addView(rowView(item, store))
        if (heading == "SSH") {
            for (name in sshConnectionNames()) {
                panel.addView(StatusRow.create(
                    ctx, label = name, value = "", detail = "SSH connection \u00b7 runs commands on this server",
                    onClick = {
                        go(
                            Intent(ctx, PluginDetailActivity::class.java)
                                .putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, "ssh")
                                .putExtra(PluginDetailActivity.EXTRA_SECRET_NAME, name),
                        )
                    },
                ))
            }
        }
        val add = when (heading) {
            "Secrets" -> "+ Add secret" to SecretsActivity::class.java
            "SSH" -> "+ Add connection" to SshActivity::class.java
            else -> null
        }
        if (add != null) {
            panel.addView(
                KeyButton.create(ctx, add.first, KeyButton.Kind.PANEL, KeyButton.Size.REGULAR) { go(Intent(ctx, add.second)) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = ctx.dp(Dimens.SPACE_M) },
            )
        }
        return panel
    }

    /** The collapsed-by-default Developer group (D24); open when one of its plugins needs action. */
    private fun developerPanel(items: List<Item>, store: PluginSettingsStore): LinearLayout {
        val prefs = ctx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getBoolean(KEY_DEVELOPER_OPEN, false)
        val forced = items.any { it.model.rank <= PluginRowModel.RANK_ACTION }
        val open = saved || forced
        val panel = Panel.create(ctx)
        val header = StatusRow.create(
            ctx,
            label = "Developer",
            value = if (open) "HIDE" else "SHOW",
            detail = if (forced) "Needs action" else "Help, Debug, Logs and Probe",
            onClick = {
                prefs.edit().putBoolean(KEY_DEVELOPER_OPEN, !saved).apply()
                list.post { render() }
            },
        )
        ViewCompat.setStateDescription(header, if (open) "Expanded" else "Collapsed")
        panel.addView(header)
        if (open) for (item in items) panel.addView(rowView(item, store))
        return panel
    }

    private fun rowView(item: Item, store: PluginSettingsStore): View = PluginRow.create(
        ctx,
        item.model,
        onOpen = { openDetail(item.plugin.id) },
        onHelp = { PluginHelp.show(ctx, item.plugin, store.isEnabled(item.plugin)) { openDetail(item.plugin.id) } },
        onToggle = { want ->
            // Write, then repaint from what was written. Posted: render() empties the list that
            // holds the tapped rocker, and doing that inline eats the tap.
            store.setEnabled(item.plugin, want)
            Debug.log("plugin ${item.plugin.id}: enabled=$want")
            list.post { render() }
        },
    ).also { row ->
        // D70: a plugin this build cannot serve keeps its rocker OFF and cannot be switched.
        if (PluginRowModel.isNotInBuild(item.model) && row is ViewGroup) {
            row.getChildAt(row.childCount - 1)?.isEnabled = false
        }
    }

    private fun emptyState() = StateBlock.create(
        ctx,
        StateBlock.Kind.EMPTY,
        title = "No plugins yet",
        body = "STMP registers its plugins when the bridge starts. Switch the bridge on from Home.",
        action = KeyButton.Spec(label = "Open Home", describe = "Open Home", onClick = {
            go(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(Tabs.EXTRA_TAB, Theme.Tab.HOME.name)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }),
    )

    /**
     * The stored SSH connections, by display name: one vault secret named `ssh-<host>` each. The
     * vault's name index is plaintext precisely so this works while it is locked.
     */
    private fun sshConnectionNames(): List<String> = try {
        com.mido.phonebridge.core.SecretVault.names(requireContext())
            .filter { it.startsWith("ssh-") }
            .map { it.removePrefix("ssh-") }
    } catch (e: Exception) {
        Debug.log("plugins: cannot read ssh connections: ${e.message}")
        emptyList()
    }

    private fun openDetail(pluginId: String) {
        // Secrets has its own screen: the secret manager with each link on its own line.
        if (pluginId == "secrets") {
            go(Intent(ctx, SecretsActivity::class.java))
            return
        }
        go(Intent(ctx, PluginDetailActivity::class.java).putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, pluginId))
    }

    override fun onResume() {
        super.onResume()
        // A toggle changed on the detail screen must be visible on the way back. Only the list is
        // rebuilt: the title and summary are outside it, so a press on them is never torn down.
        if (::list.isInitialized) render()
    }

    private companion object {
        const val DEVELOPER = "Developer"
        const val UI_PREFS = "plugins_ui"
        const val KEY_DEVELOPER_OPEN = "developer_open"
    }
}
