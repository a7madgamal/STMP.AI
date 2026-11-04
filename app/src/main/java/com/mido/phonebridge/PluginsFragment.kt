package com.mido.phonebridge

import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.Debug
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
class PluginsFragment : BridgeFragment() {

    override val tab = Theme.Tab.PLUGINS

    private lateinit var list: LinearLayout

    override fun buildContent(container: LinearLayout) {
        container.addView(Theme.text(ctx, "PLUGINS", 20f).apply {
            typeface = Theme.handFont(ctx)
            setTextColor(Theme.ON_SURFACE)
        })
        container.addView(Theme.text(
            ctx,
            "Each plugin owns its own routes, settings and permissions. Turning one off " +
                "keeps its routes answering — with a refusal that says it is off.",
            13f,
        ).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        })

        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list)

        render()
    }

    /**
     * The groups, in the order Mido asked for (2026-11-03): *"group related plugins... all google
     * in one large group with header / phone data like notifications and sound etc / misc like
     * debug and help / group secrets"*.
     *
     * Grouped by WHAT THE PLUGIN IS ABOUT rather than by its id or its display name, because the
     * list is a map of the phone: the Google services share an account, the phone ones share
     * device permissions, and the misc ones are the machinery that explains the rest.
     *
     * An id in no group is a DEFECT, not a plugin to hide: `unlisted` below renders it under
     * Misc and logs it, so a new plugin cannot quietly vanish from the screen.
     */
    private val groups: List<Pair<String, Set<String>>> = listOf(
        "Google" to setOf("google", "gmail", "calendar", "drive"),
        "Phone" to setOf("notifications", "contacts", "audio", "tts"),
        // **Secrets and SSH are separate headers, each with its own items.**
        //
        // Mido, 2026-11-04: *"split secrets into secrets and ssh with its own header and related
        // items"*. An earlier pass merged them because SSH IS a vault entry — but they are not the
        // same work: Secrets holds API keys you paste, SSH holds generated keys you install on a
        // server. Merging them put two different add flows under one word.
        "Secrets" to setOf("secrets"),
        "SSH" to setOf("ssh"),
        "Misc" to setOf("help", "debug", "logs", "probe"),
    )

    private fun render() {
        list.removeAllViews()

        val store = PluginSettingsStore.from(requireContext())
        val plugins = PluginRegistry.all()

        if (plugins.isEmpty()) {
            // Real state: the service has not registered yet. Say so rather than showing a blank
            // page that reads as "no plugins exist".
            list.addView(Theme.text(ctx, "No plugins registered yet.", 13f).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(ctx, 16), 0, 0)
            })
            return
        }

        val byId = plugins.associateBy { it.id }
        val claimed = mutableSetOf<String>()

        for ((heading, ids) in groups) {
            val members = ids.mapNotNull { byId[it] }.sortedBy { it.displayName }
            if (members.isEmpty()) continue
            claimed += members.map { it.id }

            list.addView(Theme.sectionHeader(ctx, heading))

            if (heading == "Google") {
                // **ONE card for the whole Google group.**
                //
                // Mido, 2026-11-04: *"the google plugin should be one card, show the email once"*.
                // Four separate cards said "these are four unrelated things" about one account.
                // They share an OAuth session and the same email, so they are one subject with
                // four capabilities — which is what a card should represent.
                //
                // The account row comes FIRST because it is the precondition: the three services
                // below cannot serve until it is signed in, and reading top-down tells you that
                // before you wonder why they are greyed.
                val googleCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
                }
                googleCard.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Theme.dp(ctx, 10) }

                val ordered = members.sortedBy { if (it.id == "google") 0 else 1 }
                for ((index, plugin) in ordered.withIndex()) {
                    if (index > 0) {
                        // A hairline between capabilities, so one card does not read as one blob.
                        googleCard.addView(android.view.View(ctx).apply {
                            setBackgroundColor(Theme.BORDER)
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 1),
                        ).apply { topMargin = Theme.dp(ctx, 2); bottomMargin = Theme.dp(ctx, 2) })
                    }
                    googleCard.addView(row(plugin, store, bare = true))
                }
                list.addView(googleCard)
            } else {
                for (plugin in members) list.addView(row(plugin, store))
                if (heading == "Secrets") addSecretsActions(members)
                if (heading == "SSH") addSshActions(members)
            }


        }

        // Anything not in a group is still shown. Silently dropping a plugin would make the
        // screen lie about what the bridge can do.
        val unlisted = plugins.filter { it.id !in claimed }.sortedBy { it.displayName }
        if (unlisted.isNotEmpty()) {
            Debug.log("plugins: ungrouped ids ${unlisted.map { it.id }}")
            list.addView(Theme.sectionHeader(ctx, "Other"))
            for (plugin in unlisted) list.addView(row(plugin, store))
        }
    }

    /**
     * The stored SSH connections, by display name.
     *
     * Same source `HelpPlugin` reads: one vault secret named `ssh-<host>` per connection. The
     * vault's name index is plaintext precisely so this works while it is locked.
     */
    private fun sshConnectionNames(): List<String> = try {
        com.mido.phonebridge.core.SecretVault.names(requireContext())
            .filter { it.startsWith("ssh-") }
            .map { it.removePrefix("ssh-") }
    } catch (e: Exception) {
        // A corrupt index must not blank the screen: the SSH plugin's own row still reports the
        // failure through its status.
        Debug.log("plugins: cannot read ssh connections: ${e.message}")
        emptyList()
    }

    /** One SSH connection, as its own row opening that connection's detail screen. */
    private fun sshRow(name: String): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(ctx, 14), Theme.dp(ctx, 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, 12),
            )
            background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
            isClickable = true
            contentDescription = "SSH connection $name"
            setOnClickListener {
                go(
                    Intent(ctx, PluginDetailActivity::class.java)
                        .putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, "ssh")
                        .putExtra(PluginDetailActivity.EXTRA_SECRET_NAME, name)
                )
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        // Set as a statement, not inside `.apply`: there `this` is the LayoutParams, so
        // `Theme.dp(ctx, ...)` would be handed a params object where it wants a Context.
        lp.topMargin = Theme.dp(ctx, 10)
        card.layoutParams = lp

        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(Theme.text(ctx, name, 16f).apply {
            setTextColor(Theme.ON_SURFACE)
            typeface = Theme.handFont(ctx)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.gravity = android.view.Gravity.CENTER_VERTICAL
        titleRow.addView(Theme.text(ctx, "SSH", 12f).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
        })
        card.addView(titleRow)

        card.addView(Theme.text(ctx, "One connection. POST /ssh/$name", 12f).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        })
        return card
    }

    private fun row(
        plugin: com.mido.phonebridge.core.Plugin,
        store: PluginSettingsStore,
        /** Draw as a bare row inside a shared card instead of standing on its own. */
        bare: Boolean = false,
    ): LinearLayout {
        val on = store.isEnabled(plugin)
        // `status()` is the plugin's own answer about whether it can serve. Only consulted when
        // the plugin is ON: an off plugin may well report OK, and a page saying "OK" next to a
        // switch that is off is the confusion this screen exists to remove.
        val status = if (on) plugin.status() else null

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // A bare row has no card of its own: the group draws ONE surface around the set, so
            // four Google services read as one subject rather than four unrelated cards.
            setPadding(
                Theme.dp(ctx, 14), Theme.dp(ctx, if (bare) 8 else 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, if (bare) 8 else 12),
            )
            if (!bare) background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
            isClickable = true
            contentDescription = "${plugin.displayName}: ${stateLabel(on, status)}"
            setOnClickListener { openDetail(plugin.id) }
        }
        if (!bare) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = Theme.dp(ctx, 10)
            card.layoutParams = lp
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // CENTER_VERTICAL so the knob lines up with the name rather than the top of the word.
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Weight 1 on the name: it absorbs all the slack, which is what pushes the state word and
        // then the knob to the right edge. Without it the knob sat wherever the text happened to
        // end, so rows disagreed about where the switch lived.
        titleRow.addView(Theme.text(ctx, plugin.displayName, 16f).apply {
            setTextColor(Theme.ON_SURFACE)
            typeface = Theme.handFont(ctx)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // The state chip. Colour carries the meaning and the WORD carries it too, because a
        // colour alone is unreadable for a chunk of the population and invisible in a screenshot.
        val stateText = stateLabel(on, status)
        if (stateText.isNotEmpty()) {
            titleRow.addView(Theme.text(ctx, stateText, 12f).apply {
                setTextColor(stateColour(on, status))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Theme.dp(ctx, 10) })
        }

        // The switch. EVERY plugin, no exceptions (Mido, 2026-11-04).
        run {
            val toggle = Theme.toggle(ctx, on) { want ->
                // Write, then repaint from what was written. The knob is NOT flipped optimistically:
                // a failed write would leave it showing a state the store does not hold.
                store.setEnabled(plugin, want)
                Debug.log("plugin ${plugin.id}: enabled=$want")
                render()
            }
            // **The knob must not become the row's click.**
            //
            // The card opens the detail screen on tap, and the knob sits inside it. A clickable
            // child wins the touch in Android, so tapping the knob flips the switch and does NOT
            // navigate -- which is what we want. This line makes that explicit rather than
            // accidental: the knob refuses to let its parent intercept a drag that starts on it,
            // so a slightly sloppy tap cannot be stolen mid-gesture by the row above.
            toggle.setOnTouchListener { v, event ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            titleRow.addView(toggle)
        }

        card.addView(titleRow)

        val detail = stateDetail(on, status)
        if (detail.isNotEmpty()) {
            card.addView(Theme.text(ctx, detail, 12f).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(ctx, 4), 0, 0)
            })
        }
        return card
    }

    /**
     * The row's status word — or empty when the knob already says it.
     *
     * **"ON" and "OFF" are gone on purpose.** The switch sits right there and its position IS the
     * answer; printing "ON" beside a knob pointing right is the same fact twice, and the label was
     * the thing that used to go stale. What stays is everything the knob CANNOT say: a plugin that
     * is switched on but cannot serve.
     */
    private fun stateLabel(on: Boolean, status: PluginStatus?): String = when {
        !on -> ""
        status == null -> ""
        status.state == PluginStatus.State.OK -> ""
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
        // **Secrets opens its own screen.**
        //
        // `SecretsActivity` is the secret manager: one row per secret, each URL on its own line as
        // a tappable link. It existed and was manifest-registered, but NOTHING launched it — every
        // plugin row went to `PluginDetailActivity`, so the screen with the link rendering could
        // never be reached. Mido asked twice: *"secrets need each url in a line, and they need to
        // be clickable links"*, then *"i said secrets plugin should show each url in its own line
        // and should be CLICKABLE links"*. The rendering was correct; the door was missing.
        val target = if (pluginId == "secrets") {
            SecretsActivity::class.java
        } else {
            PluginDetailActivity::class.java
        }
        val intent = Intent(ctx, target)
        if (target == PluginDetailActivity::class.java) {
            intent.putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, pluginId)
        }
        go(intent)
    }

    override fun onResume() {
        super.onResume()
        // A toggle changed on the detail screen must be visible on the way back. This rebuilds
        // ONLY the list — the bar and the header are outside `list` and never move, so a press in
        // flight on them cannot be cancelled.
        if (::list.isInitialized) render()
    }

    /**
     * The actions at the bottom of the Secrets group: add a secret, add an SSH connection.
     *
     * Mido, 2026-11-04: *"a clear way to add more should be there too"*. Adding secrets was only
     * reachable by opening a plugin detail page and finding a button inside it — which is a
     * two-step journey for the one thing this screen is FOR.
     *
     * Both are plain rows rather than buttons in a toolbar because this screen is a list of
     * things; an action that adds to the list belongs at the end of it, where you look after
     * scrolling past what you already have.
     */
    /** The Secrets group's own actions: its entries live on the Secrets screen. */
    private fun addSecretsActions(members: List<com.mido.phonebridge.core.Plugin>) {
        if (members.none { it.id == "secrets" }) return
        // The SecretsActivity manages secret values and their links; it has its own add flow.
        list.addView(
            Theme.button(ctx, "+ ADD SECRET", Theme.ButtonStyle.SUCCESS) {
                // Same destination as the Secrets row: one screen, one way to add.
                go(Intent(ctx, SecretsActivity::class.java))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, 10) },
        )
    }

    /**
     * The SSH group's own actions.
     *
     * One row per connection FIRST, then the way to add another — the list is what you scan, and
     * the add control belongs under it where you look after reading what you already have.
     */
    private fun addSshActions(members: List<com.mido.phonebridge.core.Plugin>) {
        if (members.none { it.id == "ssh" }) return
        for (name in sshConnectionNames()) list.addView(sshRow(name))
        list.addView(
            Theme.button(ctx, "+ ADD CONNECTION", Theme.ButtonStyle.SUCCESS) {
                // The SSH screen owns the generate-and-install flow: a connection needs a key
                // before it can do anything, so adding one is a screen, not a form here.
                go(Intent(ctx, SshActivity::class.java))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, 10) },
        )
    }
}
