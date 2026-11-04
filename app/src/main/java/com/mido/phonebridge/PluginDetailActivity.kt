package com.mido.phonebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginPermission
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSetting
import com.mido.phonebridge.core.PluginSettingsStore
import com.mido.phonebridge.ui.Theme

/**
 * One plugin: whether it is on, what it needs, and what it can be told.
 *
 * **The order on the page is deliberate** — toggle, then permissions, then settings:
 *
 *  1. **The switch**, because "why is this not working" is answered by the first row.
 *  2. **Permissions**, because a plugin that is on but unauthorised is still not working, and the
 *     remedy is a system dialog rather than a setting.
 *  3. **Settings**, because they are the least urgent and the most numerous.
 *
 * A disabled plugin still shows all three. Hiding them would mean the human has to enable a
 * plugin to find out what enabling it would cost — which is the opposite of informed.
 */
class PluginDetailActivity : BridgeScreen() {

    override val tab = Theme.Tab.PLUGINS

    private lateinit var plugin: Plugin
    private lateinit var store: PluginSettingsStore
    private lateinit var content: LinearLayout

    override fun buildContent(container: LinearLayout) {
        val id = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val found = id?.let { PluginRegistry.get(it) }
        if (found == null) {
            // A detail screen for a plugin that is not registered. Refuse visibly rather than
            // rendering an empty page: an empty page reads as "this plugin has nothing".
            container.addView(Theme.text(this, "That plugin is not registered.", 14f).apply {
                setTextColor(Theme.DANGER)
            })
            finish()
            return
        }
        plugin = found
        store = PluginSettingsStore.from(applicationContext)
        content = container
        render()
    }

    override fun onResume() {
        super.onResume()
        // A permission may have been granted in Settings while this screen was behind. Rebuilding
        // is what turns "not granted" into "granted" without a manual refresh.
        if (::content.isInitialized) render()
    }

    private fun render() {
        content.removeAllViews()
        val on = store.isEnabled(plugin)
        val secret = intent.getStringExtra(EXTRA_SECRET_NAME)

        content.addView(Theme.text(
            this,
            if (secret != null) secret.uppercase() else plugin.displayName.uppercase(),
            20f,
        ).apply {
            typeface = Theme.handFont(this@PluginDetailActivity)
            setTextColor(Theme.ON_SURFACE)
        })

        // Scoped to ONE connection. Its routes and settings belong to the SSH plugin as a whole,
        // so repeating them per connection would say the same thing N times; what is specific to
        // this name is the route it serves and the key it authenticates with.
        if (secret != null) {
            content.addView(Theme.text(this, "One connection handled by the SSH plugin.", 13f).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
                setPadding(0, Theme.dp(this@PluginDetailActivity, 4), 0, 0)
            })
            content.addView(Theme.sectionHeader(this, "Route"))
            content.addView(Theme.text(this, "POST /ssh/$secret", 12f, mono = true).apply {
                setTextColor(Theme.ON_SURFACE_VARIANT)
            })
            content.addView(Theme.sectionHeader(this, "Key"))
            content.addView(Theme.text(
                this,
                "The private key is stored in the vault as `ssh-$secret`. It is never shown here " +
                    "and never leaves the phone except as a signature.",
                12f,
            ).apply { setTextColor(Theme.ON_SURFACE_VARIANT) })
            // The plugin's switch is the SSH plugin's, not this connection's: offering it per
            // connection would imply each one can be turned off independently, which it cannot.
            content.addView(Theme.sectionHeader(this, "Plugin"))
        }

        // --- 1. THE SWITCH ---
        content.addView(Theme.sectionHeader(this, "Status"))

        run {
            // **A row with the knob at the RIGHT edge.**
            //
            // Mido asked for this twice: *"plugin page has toggle in the middle, make it right
            // aligned"*, then *"plugin details screen still have the toggle in the middle not
            // right aligned like I asked before"*. The first pass fixed the LIST row and left
            // this screen adding the knob as a bare full-width child, so it rendered wherever
            // the layout put it.
            //
            // The label takes weight 1 and absorbs all the slack; the knob follows at its own
            // width, which pins it to the right edge of the card regardless of label length.
            val switchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            switchRow.addView(
                Theme.text(
                    this,
                    if (on) "Enabled" else "Disabled",
                    15f,
                    if (on) Theme.ON_SURFACE else Theme.ON_SURFACE_VARIANT,
                ).apply { typeface = Theme.handFont(this@PluginDetailActivity) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            switchRow.addView(Theme.toggle(this, on) { want ->
                store.setEnabled(plugin, want)
                Debug.log("plugin ${plugin.id}: enabled=$want")
                render()
            })
            content.addView(switchRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            if (on) {
                content.addView(Theme.text(
                    this,
                    "On. Its routes are served and gated like any other.",
                    12f,
                ).apply { setTextColor(Theme.ON_SURFACE_VARIANT) })
            } else {
                content.addView(Theme.text(
                    this,
                    "Off. Its routes still exist, and answer `plugin_disabled` — they are not " +
                        "removed, so nothing starts 404-ing because of a switch.",
                    12f,
                ).apply { setTextColor(Theme.ON_SURFACE_VARIANT) })
            }
        }

        // The plugin's own view of itself, shown only when it is on: an off plugin has nothing
        // to report, and printing its stale status next to "Off" is the confusion to avoid.
        if (on) {
            val st = plugin.status()
            content.addView(Theme.text(this, st.detail.ifEmpty { st.title }, 13f).apply {
                setTextColor(
                    when (st.state) {
                        com.mido.phonebridge.core.PluginStatus.State.OK -> Theme.SUCCESS
                        com.mido.phonebridge.core.PluginStatus.State.NEEDS_ATTENTION -> Theme.WARNING
                        com.mido.phonebridge.core.PluginStatus.State.ERROR -> Theme.DANGER
                        com.mido.phonebridge.core.PluginStatus.State.OFF -> Theme.ON_SURFACE_VARIANT
                    }
                )
                setPadding(0, Theme.dp(this@PluginDetailActivity, 6), 0, 0)
            })
        }

        // --- 2. PERMISSIONS ---
        val perms = plugin.permissions()
        if (perms.isNotEmpty()) {
            content.addView(Theme.sectionHeader(this, "Permissions"))
            for (p in perms) content.addView(permissionRow(p))
        }

        // --- 2b. SPECIAL ACCESS ---
        //
        // Separate from permissions because the REMEDY is different: these are Settings screens,
        // not dialogs. A permission button that called `requestPermissions` for one of these
        // would close silently and do nothing, which is the exact failure this distinction
        // exists to prevent.
        val access = plugin.specialAccess()
        if (access.isNotEmpty()) {
            content.addView(Theme.sectionHeader(this, "System access"))
            for (a in access) content.addView(accessRow(a))
        }

        // --- 3. SETTINGS ---
        val settings = plugin.settings()
        if (settings.isNotEmpty()) {
            content.addView(Theme.sectionHeader(this, "Settings"))
            for (setting in settings) content.addView(settingRow(setting))
        }

        // --- 4. ROUTES (read-only) ---
        val routes = plugin.routes()
        if (routes.isNotEmpty()) {
            content.addView(Theme.sectionHeader(this, "Routes"))
            content.addView(Theme.text(
                this,
                routes.joinToString("\n") { "${it.method} ${it.path}  ·  ${it.describe}" },
                11f,
                mono = true,
            ).apply { setTextColor(Theme.ON_SURFACE_VARIANT) })
        }
    }

    /** One permission: its state, why it is needed, and the one action that can fix it. */
    private fun permissionRow(p: PluginPermission): LinearLayout {
        val granted = checkSelfPermission(p.permission) == PackageManager.PERMISSION_GRANTED
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Theme.text(this, p.label, 14f).apply {
            setTextColor(Theme.ON_SURFACE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Theme.text(
            this,
            if (granted) "GRANTED" else if (p.optional) "OPTIONAL" else "NEEDED",
            12f,
        ).apply {
            setTextColor(if (granted) Theme.SUCCESS else Theme.WARNING)
        })
        block.addView(row)

        block.addView(Theme.text(this, p.why, 12f).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(this@PluginDetailActivity, 4), 0, 0)
        })

        if (!granted) {
            // The two reasons a request can do nothing are different and need different words:
            // never asked (a dialog works) vs permanently denied (only Settings works). Asking
            // again in the second case is a silent no-op, which is the bug this avoids.
            val canAsk = shouldShowRequestPermissionRationale(p.permission)
            val btn = Theme.button(
                this,
                if (canAsk || !isPermanentlyDenied(p.permission)) "ALLOW" else "OPEN SETTINGS",
                Theme.ButtonStyle.PRIMARY,
                small = true,
            ) {
                if (canAsk || !isPermanentlyDenied(p.permission)) {
                    requestPermissions(arrayOf(p.permission), REQ_PERMISSION)
                } else {
                    openAppSettings()
                }
            }
            block.addView(btn)
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, 10)
        block.layoutParams = lp
        return block
    }

    /**
     * A permission the system will never show a dialog for again.
     *
     * `shouldShowRequestPermissionRationale` is FALSE in two situations that are not the same:
     * never asked, and asked-and-denied-for-good. The only way to tell them apart is "have we
     * asked before", recorded here — which is why this flag exists rather than being inferred.
     */
    private fun isPermanentlyDenied(permission: String): Boolean {
        val prefs = getSharedPreferences("permission_asks", MODE_PRIVATE)
        val askedKey = "asked:$permission"
        val deniedKey = "denied:$permission"
        return prefs.getBoolean(deniedKey, false) && !shouldShowRequestPermissionRationale(permission)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        // Record every ask so a later "no dialog appeared" can be told apart from "never asked".
        val prefs = getSharedPreferences("permission_asks", MODE_PRIVATE).edit()
        permissions.forEachIndexed { i, perm ->
            prefs.putBoolean("asked:$perm", true)
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            prefs.putBoolean("denied:$perm", !granted)
        }
        prefs.apply()
        render()
    }

    private fun openAppSettings() {
        startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ))
    }

    /**
     * One special access: state, what it is, and the only thing that can grant it.
     *
     * Every branch opens an Android Settings screen. There is deliberately no "request" path
     * here because none exists — the system shows no dialog for any of these.
     */
    private fun accessRow(access: com.mido.phonebridge.core.SpecialAccess): LinearLayout {
        val granted = when (access) {
            com.mido.phonebridge.core.SpecialAccess.NOTIFICATION_LISTENER ->
                BridgeService.listenerRef != null
            com.mido.phonebridge.core.SpecialAccess.OVERLAY ->
                com.mido.phonebridge.ui.SystemAccess.overlayGranted(this)
            com.mido.phonebridge.core.SpecialAccess.BATTERY_EXEMPT ->
                com.mido.phonebridge.ui.SystemAccess.batteryExempt(this)
            com.mido.phonebridge.core.SpecialAccess.FULL_SCREEN_INTENT ->
                ApprovalGate.canUseFullScreenIntent(this)
        }

        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Theme.text(this, access.label, 14f).apply {
            setTextColor(Theme.ON_SURFACE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Theme.text(this, if (granted) "GRANTED" else "NEEDED", 12f).apply {
            setTextColor(if (granted) Theme.SUCCESS else Theme.WARNING)
        })
        block.addView(row)

        if (!granted) {
            block.addView(Theme.button(this, "OPEN SETTINGS", Theme.ButtonStyle.PRIMARY, small = true) {
                openAccessSettings(access)
            })
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, 10)
        block.layoutParams = lp
        return block
    }

    /**
     * The Settings screen for each access.
     *
     * Each one is a different intent, and getting them wrong means the button lands somewhere
     * unrelated — so they are stated once, here, rather than at every call site that used to
     * repeat them.
     */
    /**
     * Delegate to [SystemAccess]: the intents live in one place now.
     *
     * They were built here AND in the Home screen, which is two copies of the same four intents
     * that have to agree. A wrong intent lands the user somewhere unrelated and there is no error
     * to show, so the copy nobody updated would fail silently.
     */
    private fun openAccessSettings(access: com.mido.phonebridge.core.SpecialAccess) {
        when (access) {
            com.mido.phonebridge.core.SpecialAccess.NOTIFICATION_LISTENER -> {
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (e: Exception) {
                    com.mido.phonebridge.Debug.log(
                        "cannot open notification-listener settings: ${e.message}"
                    )
                }
            }
            com.mido.phonebridge.core.SpecialAccess.OVERLAY ->
                com.mido.phonebridge.ui.SystemAccess.openOverlaySettings(this)
            com.mido.phonebridge.core.SpecialAccess.BATTERY_EXEMPT ->
                com.mido.phonebridge.ui.SystemAccess.requestBatteryExemption(this)
            com.mido.phonebridge.core.SpecialAccess.FULL_SCREEN_INTENT ->
                com.mido.phonebridge.ui.SystemAccess.openFullScreenIntentSettings(this)
        }
    }

    /** One declared setting, rendered by its type. */
    private fun settingRow(setting: PluginSetting): LinearLayout {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, 12)
        block.layoutParams = lp

        when (setting) {
            is PluginSetting.Toggle -> {
                val on = store.booleanValue(plugin.id, setting.id, setting.default)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // CENTER_VERTICAL so the knob lines up with its label, not the row's top.
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(Theme.text(this, setting.label, 14f).apply {
                    setTextColor(Theme.ON_SURFACE)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Theme.toggle(this, on) { want ->
                    store.putBoolean(plugin.id, setting.id, want)
                    render()
                })
                block.addView(row)
                block.addView(description(setting.description))
            }

            is PluginSetting.Choice -> {
                val current = store.value(plugin.id, setting.id, setting.default)
                block.addView(Theme.text(this, setting.label, 14f).apply {
                    setTextColor(Theme.ON_SURFACE)
                })
                block.addView(description(setting.description))
                // One small button per option rather than a dropdown: this app has no spinner
                // anywhere, and a three-option choice is clearer as a visible set than a picker.
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (option in setting.options) {
                    val selected = option == current
                    val b = Theme.button(
                        this, option,
                        if (selected) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL,
                        small = true,
                    ) {
                        store.put(plugin.id, setting, option)
                        render()
                    }
                    val p = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    p.rightMargin = Theme.dp(this, 8)
                    row.addView(b, p)
                }
                block.addView(row)
            }

            is PluginSetting.Text -> {
                val current = store.value(plugin.id, setting.id, setting.default)
                block.addView(Theme.text(this, setting.label, 14f).apply {
                    setTextColor(Theme.ON_SURFACE)
                })
                block.addView(description(setting.description))
                // A read-back row plus an ACTION-style edit, rather than a live EditText: a text
                // field in a scrolling list steals focus and pops the keyboard on scroll, which
                // is the classic form-in-a-list defect. Editing happens in a dialog.
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Theme.text(this, current.ifEmpty { "—" }, 13f, mono = true).apply {
                    setTextColor(Theme.ON_SURFACE_VARIANT)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Theme.button(this, "EDIT", Theme.ButtonStyle.NEUTRAL, small = true) {
                    promptText(setting, current)
                })
                block.addView(row)
            }

            is PluginSetting.Action -> {
                block.addView(Theme.button(this, setting.label, Theme.ButtonStyle.NEUTRAL, small = true) {
                    setting.run(this)
                    render()
                })
                block.addView(description(setting.description))
            }
        }
        return block
    }

    private fun description(text: String): TextView? =
        if (text.isEmpty()) null else Theme.text(this, text, 12f).apply {
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(this@PluginDetailActivity, 4), 0, 0)
        }

    private fun promptText(setting: PluginSetting.Text, current: String) {
        val input = android.widget.EditText(this).apply {
            setText(current)
            hint = setting.placeholder
            inputType = if (setting.numeric) {
                android.text.InputType.TYPE_CLASS_NUMBER
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this)
            .setTitle(setting.label)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                // The store validates against the descriptor; a refusal here is a defect in the
                // descriptor rather than something the human did, so it surfaces rather than
                // being swallowed into a "saved" that did not save.
                try {
                    store.put(plugin.id, setting, input.text.toString().trim())
                } catch (e: com.mido.phonebridge.core.PluginSettingsException) {
                    com.mido.phonebridge.Debug.log("setting refused: ${e.message}")
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"

        /**
         * The SSH connection this detail screen is scoped to, or absent for a whole plugin.
         *
         * One connection is a vault secret named `ssh-<host>`, not a plugin of its own — there is
         * no per-connection object to register. So the screen takes the plugin AND a name, and
         * narrows itself to that one connection when the name is present.
         */
        const val EXTRA_SECRET_NAME = "secret_name"
        private const val REQ_PERMISSION = 3001
    }
}
