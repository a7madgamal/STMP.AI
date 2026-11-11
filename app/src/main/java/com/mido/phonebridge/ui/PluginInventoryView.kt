package com.mido.phonebridge.ui

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.Debug
import com.mido.phonebridge.PluginDetailActivity
import com.mido.phonebridge.ScreenHost
import com.mido.phonebridge.SecretsActivity
import com.mido.phonebridge.SshActivity
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSettingsStore
import com.mido.phonebridge.core.PluginStatus

/** The single plugin inventory implementation, hosted directly by Preferences. */
class PluginInventoryView(
    context: Context,
    private val host: ScreenHost,
) : LinearLayout(context) {

    private val groupsByName = LinkedHashMap<String, LinearLayout>()
    private val inventoryHosts = LinkedHashMap<String, LinearLayout>()

    /** A coalescing flag so a burst of refresh requests becomes one rebuild. */
    private var renderPosted = false

    init {
        orientation = VERTICAL
        render()
        arrange()
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
        "Accounts & services" to setOf("google", "gmail", "calendar", "drive"),
        "Phone" to setOf("notifications", "contacts", "audio", "tts"),
        // **Secrets and SSH are separate headers, each with its own items.**
        //
        // Mido, 2026-11-04: *"split secrets into secrets and ssh with its own header and related
        // items"*. An earlier pass merged them because SSH IS a vault entry — but they are not the
        // same work: Secrets holds API keys you paste, SSH holds generated keys you install on a
        // server. Merging them put two different add flows under one word.
        "Secrets" to setOf("secrets"),
        "SSH" to setOf("ssh"),
        "Developer" to setOf("help", "debug", "logs", "probe"),
    )

    fun add(group: String, view: android.view.View, params: LayoutParams? = null) {
        val host = groupFor(group)
        if (params == null) host.addView(view) else host.addView(view, params)
    }

    /** Inventory-owned rows are replaced on refresh; global settings added by Preferences are not. */
    private fun addPlugin(group: String, view: android.view.View, params: LayoutParams? = null) {
        groupFor(group)
        val host = inventoryHosts.getValue(group)
        if (params == null) host.addView(view) else host.addView(view, params)
    }

    private fun groupFor(group: String): LinearLayout = groupsByName.getOrPut(group) {
        LinearLayout(context).apply { orientation = VERTICAL }
    }.also { host ->
        if (host.childCount == 0) {
            addView(host)
            host.addView(Theme.sectionHeader(context, group))
            val inventory = LinearLayout(context).apply { orientation = VERTICAL }
            inventoryHosts[group] = inventory
            host.addView(inventory)
        }
    }

    /** Keep the intent-based order even though plugin rows are discovered before global settings. */
    fun arrange() {
        val rank = listOf("Phone", "Accounts & services", "Secrets & connections", "AI",
            "Approval & exceptions", "System", "About & updates", "Developer",
            "Capabilities", "Other")
        val ordered = groupsByName.values.sortedBy { host ->
            rank.indexOf(groupsByName.entries.first { it.value == host }.key)
                .let { if (it < 0) rank.size else it }
        }
        removeAllViews()
        ordered.forEach(::addView)
    }

    /**
     * Deliberately does NOT re-arrange on attach.
     *
     * This used to call `arrange()`, which does `removeAllViews()` then re-adds every group —
     * while Android is still dispatching the attach down this view's children. Detaching a child
     * mid-dispatch is the crash: the group being iterated is removed from its parent as it is
     * visited, and the hierarchy is left half-attached. `arrange()` is already called from
     * [init] and from the end of `OptionsFragment.render()`, both at a safe moment, so this
     * override only added a way to crash.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    private fun render() {
        inventoryHosts.values.forEach(LinearLayout::removeAllViews)

        val store = PluginSettingsStore.from(context)
        // **Per-secret plugins are not rows on this screen.**
        //
        // Mido, 2026-11-04: *"delete the duplicate others list, currently showing 2 secrets
        // already shown under secrets"*. `BridgeService.registerSecretPlugin` gives every secret
        // its own plugin (id = the secret's name) so its route is reachable — that is a ROUTING
        // fact, not a capability a human switches on. Listing them made each secret appear twice:
        // once under Secrets, once in "Other".
        //
        // Filtered by "is one of the declared plugin ids", not by "is not in a group": an id in no
        // group is still a DEFECT worth showing (see `unlisted` below), and that guard stays.
        //
        // Filtered by TYPE, not by a list of ids: a list would drift from `groups` above and
        // silently hide the next plugin someone adds. `SecretPlugin` is the one class that
        // exists per-secret, so excluding it is precise and cannot go stale.
        val plugins = PluginRegistry.all()
            .filterNot { it is com.mido.phonebridge.plugins.secrets.SecretPlugin }

        if (plugins.isEmpty()) {
            // Real state: the service has not registered yet. Say so rather than showing a blank
            // page that reads as "no plugins exist".
            addPlugin("Capabilities", Theme.text(context, "No plugins registered yet.", Theme.TEXT_CAPTION_SP).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(context, 16), 0, 0)
            })
            return
        }

        val byId = plugins.associateBy { it.id }
        val claimed = mutableSetOf<String>()

        for ((heading, ids) in groups) {
            val group = if (heading == "Secrets" || heading == "SSH") {
                "Secrets & connections"
            } else heading
            val members = ids.mapNotNull { byId[it] }.sortedBy { it.displayName }
            if (members.isEmpty()) continue
            claimed += members.map { it.id }

            if (heading == "Accounts & services") {
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
                val googleCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.tornCard(context, Theme.SURFACE, Theme.RADIUS_CARD)
                }
                googleCard.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Theme.dp(context, 10) }

                val ordered = members.sortedBy { if (it.id == "google") 0 else 1 }
                for ((index, plugin) in ordered.withIndex()) {
                    if (index > 0) {
                        // A hairline between capabilities, so one card does not read as one blob.
                        googleCard.addView(android.view.View(context).apply {
                            background = Theme.sheet(Theme.BORDER)
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(context, 1),
                        ).apply { topMargin = Theme.dp(context, 2); bottomMargin = Theme.dp(context, 2) })
                    }
                    googleCard.addView(row(plugin, store, bare = true))
                }
                addPlugin(group, googleCard)
            } else {
                for (plugin in members) addPlugin(group, row(plugin, store))
                if (heading == "Secrets") addSecretsActions(members)
                if (heading == "SSH") addSshActions(members)
            }


        }

        // Anything not in a group is still shown. Silently dropping a plugin would make the
        // screen lie about what the bridge can do.
        val unlisted = plugins.filter { it.id !in claimed }.sortedBy { it.displayName }
        if (unlisted.isNotEmpty()) {
            Debug.log("plugins: ungrouped ids ${unlisted.map { it.id }}")
            for (plugin in unlisted) addPlugin("Other", row(plugin, store))
        }
    }

    /**
     * The stored SSH connections, by display name.
     *
     * Same source `HelpPlugin` reads: one vault secret named `ssh-<host>` per connection. The
     * vault's name index is plaintext precisely so this works while it is locked.
     */
    private fun sshConnectionNames(): List<String> = try {
        com.mido.phonebridge.core.SecretVault.names(context)
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
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(context, 14), Theme.dp(context, 12),
                Theme.dp(context, 14), Theme.dp(context, 12),
            )
            background = Theme.tornCard(context, Theme.SURFACE, Theme.RADIUS_CARD)
            isClickable = true
            isFocusable = true
            minimumHeight = Theme.dp(context, Theme.MIN_TARGET_DP)
            contentDescription = "SSH connection $name"
            setOnClickListener {
                host.go(
                    Intent(context, PluginDetailActivity::class.java)
                        .putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, "ssh")
                        .putExtra(PluginDetailActivity.EXTRA_SECRET_NAME, name)
                )
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        // Set as a statement, not inside `.apply`: there `this` is the LayoutParams, so
        // `Theme.dp(context, ...)` would be handed a params object where it wants a Context.
        lp.topMargin = Theme.dp(context, 10)
        card.layoutParams = lp

        val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(Theme.text(context, name, Theme.TEXT_BODY_SP).apply {
            setTextColor(Theme.ON_SURFACE)
            typeface = Theme.bodyFont(context)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.gravity = android.view.Gravity.CENTER_VERTICAL
        titleRow.addView(Theme.text(context, "SSH", Theme.TEXT_CAPTION_SP).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
        })
        card.addView(titleRow)

        card.addView(Theme.text(context, "One connection. POST /ssh/$name", Theme.TEXT_CAPTION_SP).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(context, 4), 0, 0)
        })
        return card
    }

    private fun row(
        plugin: com.mido.phonebridge.core.Plugin,
        store: PluginSettingsStore,
        /** Draw as a bare row inside a shared card instead of standing on its own. */
        bare: Boolean = false,
    ): LinearLayout {
        val on: Boolean
        val status: PluginStatus?
        try {
            on = store.isEnabled(plugin)
            status = if (on) plugin.status() else null
        } catch (t: Throwable) {
            // **One plugin must not take the whole screen down.**
            //
            // `store.isEnabled` deliberately THROWS on a corrupt stored value, and a plugin's
            // `status()` reads the Keystore and the vault. Either can fail, and because this
            // runs for every row on every render, one failure crashed Preferences on open —
            // repeatedly, which is what makes Android offer to clear the app's data.
            //
            // Surfaced, never hidden: the row names the failure, so the next fix has a message
            // instead of a symptom.
            Debug.log("plugin ${plugin.id}: row failed: ${t.javaClass.simpleName}: ${t.message}")
            return errorRow(plugin, t)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // A bare row has no card of its own: the group draws ONE surface around the set, so
            // four Google services read as one subject rather than four unrelated cards.
            setPadding(
                Theme.dp(context, 14), Theme.dp(context, if (bare) 8 else 12),
                Theme.dp(context, 14), Theme.dp(context, if (bare) 8 else 12),
            )
            background = Theme.rippled(context, if (bare) Theme.SURFACE else Theme.BG, Theme.RADIUS_CONTROL)
            isClickable = true
            isFocusable = true
            minimumHeight = Theme.dp(context, Theme.MIN_TARGET_DP)
            contentDescription = "${plugin.displayName}: ${stateLabel(on, status)}"
            setOnClickListener { openDetail(plugin.id) }
        }
        if (!bare) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = Theme.dp(context, 10)
            card.layoutParams = lp
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // CENTER_VERTICAL so the knob lines up with the name rather than the top of the word.
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // **A 💡 badge at the START of the row**, before the name.
        //
        // Mido, 2026-11-10: *"change the ? in the plugins list into a leftside into a 💡 circle
        // icon"*. It used to sit in the control cluster beside the knob, where a `?` reads as a
        // diagnostic on the switch. On the left it reads as what it is: a marker on the thing to
        // be explained, and the name sits beside the bulb it belongs to.
        //
        // Its own click listener means the row does NOT navigate when it is tapped — a clickable
        // child wins the touch. That is the point: the row already opens the detail screen, and a
        // help affordance that navigated somewhere would be the same door twice.
        titleRow.addView(
            Theme.bulbBadge(context, "What ${plugin.displayName} does") {
                PluginHelp.show(context, plugin, on)
            },
        )

        // Weight 1 on the name: it absorbs all the slack, which is what pushes the state word and
        // then the knob to the right edge. Without it the knob sat wherever the text happened to
        // end, so rows disagreed about where the switch lived.
        titleRow.addView(Theme.text(context, plugin.displayName, Theme.TEXT_BODY_SP).apply {
            setTextColor(Theme.ON_SURFACE)
            typeface = Theme.bodyFont(context)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // The state chip. Colour carries the meaning and the WORD carries it too, because a
        // colour alone is unreadable for a chunk of the population and invisible in a screenshot.
        val stateText = stateLabel(on, status)
        if (stateText.isNotEmpty()) {
            titleRow.addView(Theme.text(context, stateText, Theme.TEXT_CAPTION_SP).apply {
                setTextColor(stateColour(on, status))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Theme.dp(context, 10) })
        }

        val needsSetup = on && status != null && status.state != PluginStatus.State.OK
        if (needsSetup) {
            titleRow.addView(Theme.text(context, "›", Theme.TEXT_TITLE_SP,
                Theme.ON_SURFACE_VARIANT).apply {
                contentDescription = "Open settings"
                setPadding(Theme.dp(context, 4), 0, Theme.dp(context, 4), 0)
            })
            val grant = plugin.permissions().isNotEmpty() || plugin.specialAccess().isNotEmpty()
            card.addView(Theme.button(context, if (grant) "GRANT" else "SET UP",
                Theme.ButtonStyle.NEUTRAL, small = true) { openDetail(plugin.id) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(context, 6)
                })
        } else {
            val showsOn = on && (status == null || status.state == PluginStatus.State.OK)
            val toggle = Theme.toggle(context, showsOn) { want ->
                store.setEnabled(plugin, want)
                Debug.log("plugin ${plugin.id}: enabled=$want")
                // POSTED, not inline: rebuilding this row inside the knob's own click dispatch
                // eats the tap — the same defect that froze preference toggles (2026-11-11).
                resume()
            }
            toggle.setAccessibilityLabel(plugin.displayName)
            toggle.setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            titleRow.addView(toggle)
            titleRow.addView(Theme.text(context, "›", Theme.TEXT_TITLE_SP,
                Theme.ON_SURFACE_VARIANT).apply {
                contentDescription = "Open settings"
                setPadding(Theme.dp(context, 4), 0, 0, 0)
            })
        }

        card.addView(titleRow)

        card.addView(Theme.text(context, purpose(plugin), Theme.TEXT_DETAIL_SP,
            Theme.ON_SURFACE_VARIANT))

        val detail = stateDetail(on, status)
        if (detail.isNotEmpty()) {
            card.addView(Theme.text(context, detail, Theme.TEXT_CAPTION_SP).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(context, 4), 0, 0)
            })
        }
        return card
    }

    /** A row that failed to build, rendered as the failure instead of crashing the screen. */
    private fun errorRow(
        plugin: com.mido.phonebridge.core.Plugin,
        t: Throwable,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(
            Theme.dp(context, 14), Theme.dp(context, 12),
            Theme.dp(context, 14), Theme.dp(context, 12),
        )
        background = Theme.tornCard(context, Theme.SURFACE, Theme.RADIUS_CARD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(context, 10) }
        addView(Theme.text(context, "${plugin.displayName} — settings unreadable",
            Theme.TEXT_BODY_SP, Theme.DANGER))
        addView(Theme.text(context, "${t.javaClass.simpleName}: ${t.message ?: "no detail"}",
            Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT).apply {
                setPadding(0, Theme.dp(context, 4), 0, 0)
            })
    }

    /** Plain job language, independent of route names and implementation details. */
    private fun purpose(plugin: com.mido.phonebridge.core.Plugin): String = when (plugin.id) {
        "notifications" -> "Read notifications and perform their actions."
        "contacts" -> "Find phone numbers and email addresses."
        "audio" -> "Play a short attention cue on this phone."
        "tts" -> "Speak caller-supplied text aloud."
        "google" -> "Connect the Google account shared by Google services."
        "gmail" -> "Read, search and manage Gmail messages."
        "calendar" -> "Read and manage calendar events."
        "drive" -> "Read, upload and manage Drive files."
        "secrets" -> "Store credentials and broker approved calls."
        "ssh" -> "Run commands through configured SSH connections."
        "help" -> "Describe available bridge routes."
        "debug" -> "Inspect bridge health and recent state."
        "logs" -> "Read this app's diagnostic log."
        "probe" -> "Check whether the bridge is reachable."
        else -> "Open ${plugin.displayName} settings and current status."
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
        val intent = Intent(context, target)
        if (target == PluginDetailActivity::class.java) {
            intent.putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, pluginId)
        }
        host.go(intent)
    }

    fun resume() {
        // A toggle changed on the detail screen must be visible on the way back. This rebuilds
        // ONLY the list — the bar and the header are outside `list` and never move, so a press in
        // flight on them cannot be cancelled.
        //
        // POSTED and coalesced: this runs from `OptionsFragment.onResume` and from the plugin
        // toggle's own click callback, and `render()` calls every plugin's `status()`
        // synchronously. Running that inside click dispatch is the "hangs on preference click"
        // defect (2026-11-11); deferring to the next loop lets the tap complete first.
        if (renderPosted) return
        renderPosted = true
        post {
            renderPosted = false
            if (isAttachedToWindow) render()
        }
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
        addPlugin("Secrets & connections",
            Theme.button(context, "+ ADD SECRET", Theme.ButtonStyle.SUCCESS) {
                // Same destination as the Secrets row: one screen, one way to add.
                host.go(Intent(context, SecretsActivity::class.java))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(context, 10) },
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
        for (name in sshConnectionNames()) addPlugin("Secrets & connections", sshRow(name))
        addPlugin("Secrets & connections",
            Theme.button(context, "+ ADD CONNECTION", Theme.ButtonStyle.SUCCESS) {
                // The SSH screen owns the generate-and-install flow: a connection needs a key
                // before it can do anything, so adding one is a screen, not a form here.
                host.go(Intent(context, SshActivity::class.java))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(context, 10) },
        )
    }
}
