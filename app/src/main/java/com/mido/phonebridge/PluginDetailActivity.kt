package com.mido.phonebridge

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginPermission
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSetting
import com.mido.phonebridge.core.PluginSettingsException
import com.mido.phonebridge.core.PluginSettingsStore
import com.mido.phonebridge.core.SpecialAccess
import com.mido.phonebridge.plugins.google.signInIntent
import com.mido.phonebridge.ui.PluginDetailCopy
import com.mido.phonebridge.ui.PluginRowModel
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Chips
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * One plugin: whether it is on, what it needs, and what it can be told.
 *
 * **The order on the page is deliberate**: status, permissions, system access, settings, routes.
 *
 *  1. **The switch**, because "why is this not working" is answered by the first row.
 *  2. **Permissions** and **system access**, because a plugin that is on but unauthorised is still
 *     not working, and the remedy is a system dialog or a Settings screen rather than a setting.
 *  3. **Settings**, then **routes**: the least urgent and the most numerous.
 *
 * A disabled plugin still shows all of them. Hiding them would mean the human has to enable a
 * plugin to find out what enabling it would cost, which is the opposite of informed.
 *
 * `render()` empties and refills the column, so a click handler never calls it inline (it would
 * remove the view being clicked): it posts.
 */
class PluginDetailActivity : BridgeScreen() {

    override val tab = Theme.Tab.PLUGINS

    private val targetId: String? by lazy { intent.getStringExtra(EXTRA_PLUGIN_ID) }
    private val secretName: String? by lazy { intent.getStringExtra(EXTRA_SECRET_NAME) }
    private val resolved: Plugin? by lazy { targetId?.let { PluginRegistry.get(it) } }

    private lateinit var plugin: Plugin
    private lateinit var store: PluginSettingsStore
    private lateinit var content: LinearLayout

    /** The display name, never the id (D21); one SSH connection shows the name the user typed. */
    override fun topBarTitle(): String {
        val connection = secretName
        val found = resolved ?: return "Plugin"
        return connection ?: PluginRowModel.title(found)
    }

    override fun buildContent(container: LinearLayout) {
        val found = resolved
        if (found == null) {
            // Refuse visibly and stay open: finishing here removed the message before it could be read.
            container.addView(StateBlock.create(
                this, StateBlock.Kind.ERROR,
                title = "Plugin not found",
                body = "This plugin isn't registered. The bridge may not have started yet, or the " +
                    "plugin was removed. Go back and pick it from the Plugins tab.",
            ))
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

    private fun dp(v: Int) = Theme.dp(this, v)

    private fun full() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun render() {
        content.removeAllViews()
        val on = store.isEnabled(plugin)
        val model = PluginRowModel.of(plugin, on, insidePlugin = true)
        val connection = secretName

        if (connection != null) addConnection(connection)
        addStatus(model, on, connection != null)
        addPermissions()
        addAccess()
        addSettings()
        addRoutes()
    }

    // --- Connection (one SSH connection) -----------------------------------------------------

    private fun addConnection(name: String) {
        val p = Theme.palette(this)
        content.addView(SectionHeader.create(this, "Connection").also { zeroTop(it) })
        val panel = Panel.create(this, Panel.Tone.PLAIN)
        panel.addView(Type.text(this, "One connection handled by the SSH plugin.", TypeRole.DETAIL, p.etched), full())
        panel.addView(Etched.label(this, "Route").apply { topMargin(dp(Dimens.SPACE_M)) })
        panel.addView(Readout.create(this, "POST /ssh/$name", Readout.Kind.BODY).root)
        panel.addView(Etched.label(this, "Key"))
        panel.addView(Type.text(
            this,
            "The private key is stored in the vault as ssh-$name. It is never shown here and " +
                "never leaves the phone except as a signature.",
            TypeRole.DETAIL, p.ink,
        ), full())
        content.addView(panel)
    }

    // --- Status ------------------------------------------------------------------------------

    private fun addStatus(model: PluginRowModel.Model, on: Boolean, connection: Boolean) {
        val p = Theme.palette(this)
        val header = SectionHeader.create(this, if (connection) "SSH plugin" else "Status")
        content.addView(if (connection) header else zeroTop(header))
        val panel = Panel.create(this, Panel.Tone.PLAIN)

        val showsOn = model.showsOn
        val notInBuild = PluginRowModel.isNotInBuild(model)
        val sentence = if (notInBuild) model.detail else PluginDetailCopy.switchSentence(on, showsOn)
        val rocker = Theme.rocker(this, on = showsOn, label = "Enabled") { want ->
            store.setEnabled(plugin, want)
            Debug.log("plugin ${plugin.id}: enabled=$want")
            content.post { render() }
        }
        // D70: a plugin this build cannot serve has a rocker that is OFF and cannot be moved.
        if (notInBuild) rocker.isEnabled = false
        val switchRow = StatusRow.create(
            this, label = PluginDetailCopy.switchLabel(on, showsOn, notInBuild), value = "",
            detail = sentence, led = model.led, trailing = rocker,
        )
        if (PluginDetailCopy.isCaution(on, showsOn) && !notInBuild) tint(switchRow, sentence, p.cautionText)
        panel.addView(switchRow)

        if (on && !notInBuild) {
            panel.addView(StatusRow.create(
                this, label = "Status", value = model.word, tone = toneOf(model.tone),
                detail = model.detail, led = model.led, action = signInAction() ?: addConnectionAction(model),
            ))
        }
        content.addView(panel)
    }

    /**
     * The one way forward on a Gmail, Calendar or Drive screen that says "sign in first": the
     * Google account plugin owns the sign-in, so this opens the same screen. Decided from the
     * sign-in state itself, not from the status words. A build without a Google client has no
     * sign-in to offer, so it gets no action.
     */
    private fun signInAction(): KeyButton.Spec? {
        if (plugin.id !in GOOGLE_BACKED_IDS) return null
        if (AuthManager.clientId.isBlank() || AuthManager.isLoggedIn(this)) return null
        return KeyButton.Spec("Sign in", "Sign in with Google") {
            try {
                startActivity(signInIntent(this))
            } catch (e: Exception) {
                Debug.log("cannot open Google sign-in: ${e.message}")
            }
        }
    }

    /** The SSH plugin with no connection has one way forward: add one. */
    private fun addConnectionAction(model: PluginRowModel.Model): KeyButton.Spec? {
        if (plugin.id != "ssh" || !model.detail.startsWith("No connections")) return null
        return KeyButton.Spec("Add connection", "Add an SSH connection") {
            startActivity(Intent(this, SshActivity::class.java))
        }
    }

    // --- Permissions -------------------------------------------------------------------------

    private fun addPermissions() {
        val perms = plugin.permissions()
        if (perms.isEmpty()) return
        content.addView(SectionHeader.create(this, "Permissions"))
        val panel = Panel.create(this, Panel.Tone.PLAIN)
        for (perm in perms) panel.addView(permissionRow(perm))
        content.addView(panel)
    }

    /** One permission: its state as a word and a light, why it is needed, and the one action that fixes it. */
    private fun permissionRow(perm: PluginPermission): View {
        val granted = checkSelfPermission(perm.permission) == PackageManager.PERMISSION_GRANTED
        val state = PluginDetailCopy.permissionState(granted, perm.optional)
        var action: KeyButton.Spec? = null
        if (!granted) {
            // The two reasons a request can do nothing are different and need different words:
            // never asked (a dialog works) vs permanently denied (only Settings works).
            val dialogWorks = shouldShowRequestPermissionRationale(perm.permission) ||
                !isPermanentlyDenied(perm.permission)
            action = if (dialogWorks) {
                KeyButton.Spec("Allow", "Allow ${perm.label}") {
                    requestPermissions(arrayOf(perm.permission), REQ_PERMISSION)
                }
            } else {
                KeyButton.Spec("Open settings", "Open settings for ${perm.label}") { openAppSettings() }
            }
        }
        return StatusRow.create(
            this, label = perm.label, value = state.word, tone = toneOf(state.tone),
            detail = perm.why, led = state.led, action = action,
        )
    }

    // --- System access -----------------------------------------------------------------------

    private fun addAccess() {
        val access = plugin.specialAccess()
        if (access.isEmpty()) return
        content.addView(SectionHeader.create(this, "System access"))
        val panel = Panel.create(this, Panel.Tone.PLAIN)
        for (a in access) panel.addView(accessRow(a))
        content.addView(panel)
    }

    /**
     * One special access: state, why it is needed, and the only thing that can grant it.
     *
     * Every action opens an Android Settings screen. There is deliberately no "request" path
     * because none exists: the system shows no dialog for any of these.
     */
    private fun accessRow(access: SpecialAccess): View {
        val granted = when (access) {
            SpecialAccess.NOTIFICATION_LISTENER -> BridgeService.listenerRef != null
            SpecialAccess.OVERLAY -> com.mido.phonebridge.ui.SystemAccess.overlayGranted(this)
            SpecialAccess.BATTERY_EXEMPT -> com.mido.phonebridge.ui.SystemAccess.batteryExempt(this)
            SpecialAccess.FULL_SCREEN_INTENT -> ApprovalGate.canUseFullScreenIntent(this)
        }
        val state = PluginDetailCopy.accessState(granted)
        val action = if (granted) null else KeyButton.Spec("Open settings", "Open settings for ${access.label}") {
            openAccessSettings(access)
        }
        return StatusRow.create(
            this, label = access.label, value = state.word, tone = toneOf(state.tone),
            detail = PluginDetailCopy.accessReason(access), led = state.led, action = action,
        )
    }

    // --- Settings ----------------------------------------------------------------------------

    private fun addSettings() {
        // D70: without Google credentials there is no sign-in to offer, so no action key either.
        val noGoogle = com.mido.phonebridge.ui.PluginCopy.isGoogle(plugin.id) && AuthManager.clientId.isBlank()
        val settings = plugin.settings().filterNot { noGoogle && it is PluginSetting.Action }
        if (settings.isEmpty()) return
        content.addView(SectionHeader.create(this, "Settings"))
        val panel = Panel.create(this, Panel.Tone.PLAIN)
        settings.forEachIndexed { i, setting ->
            if (i > 0) panel.addView(hairline())
            panel.addView(settingRow(setting))
        }
        content.addView(panel)
    }

    private fun hairline(): View = View(this).apply {
        setBackgroundColor(Theme.palette(this@PluginDetailActivity).hairline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(Dimens.SPACE_M)
            bottomMargin = dp(Dimens.SPACE_M)
        }
    }

    private fun changed(block: () -> Unit) {
        block()
        content.post { render() }
    }

    /** One declared setting, rendered by its type. */
    private fun settingRow(setting: PluginSetting): View {
        val p = Theme.palette(this)
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun describe(text: String) {
            if (text.isEmpty()) return
            block.addView(Type.text(this, text, TypeRole.DETAIL, p.etched), full().apply { topMargin = dp(Dimens.SPACE_XS) })
        }
        when (setting) {
            is PluginSetting.Toggle -> block.addView(StatusRow.create(
                this, label = setting.label, value = "",
                detail = setting.description.ifEmpty { null },
                trailing = Theme.rocker(
                    this, on = store.booleanValue(plugin.id, setting.id, setting.default), label = setting.label,
                ) { want -> changed { store.putBoolean(plugin.id, setting.id, want) } },
            ))

            is PluginSetting.Choice -> {
                val current = store.value(plugin.id, setting.id, setting.default)
                block.addView(Type.text(this, setting.label, TypeRole.ROW_LABEL, p.ink), full())
                describe(setting.description)
                val pick = { option: String -> changed { store.put(plugin.id, setting, option) } }
                if (setting.options.size in 2..4) {
                    val knob = KnobSelector(this, setting.label, setting.options)
                    knob.select(setting.options.indexOf(current).coerceAtLeast(0), animate = false)
                    knob.onSelect = { i -> pick(setting.options[i]) }
                    block.addView(knob, full().apply { topMargin = dp(Dimens.SPACE_S) })
                } else {
                    val group = Chips.group(this, setting.label)
                    for (option in setting.options) {
                        group.addView(Chips.filter(this, option, checked = option == current) { pick(option) })
                    }
                    block.addView(group, full().apply { topMargin = dp(Dimens.SPACE_S) })
                }
            }

            is PluginSetting.Text -> {
                val current = store.value(plugin.id, setting.id, setting.default)
                block.addView(Type.text(this, setting.label, TypeRole.ROW_LABEL, p.ink), full())
                describe(setting.description)
                // A read-back readout plus an Edit key, stacked, rather than a live EditText: a text
                // field in a scrolling list steals focus and pops the keyboard on scroll.
                val shown: CharSequence = if (current.isEmpty()) {
                    SpannableString(PluginDetailCopy.textValue(current)).apply {
                        setSpan(ForegroundColorSpan(p.readoutMuted), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else current
                block.addView(
                    Readout.create(this, shown, Readout.Kind.BODY).root,
                    full().apply { topMargin = dp(Dimens.SPACE_S) },
                )
                block.addView(
                    KeyButton.create(
                        this, "Edit", KeyButton.Kind.PANEL, KeyButton.Size.SMALL, describe = "Edit ${setting.label}",
                    ) { promptText(setting, current) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(Dimens.SPACE_S) },
                )
            }

            is PluginSetting.Action -> {
                block.addView(
                    KeyButton.create(this, setting.label, KeyButton.Kind.PANEL, KeyButton.Size.REGULAR) {
                        changed { setting.run(this) }
                    },
                    full(),
                )
                describe(setting.description)
            }
        }
        return block
    }

    /**
     * Edits a text setting in a dialog. A value the store refuses keeps the dialog open and says
     * why, instead of closing as if it had saved.
     */
    @Suppress("DEPRECATION")
    private fun promptText(setting: PluginSetting.Text, current: String) {
        val p = Theme.palette(this)
        val input = EditText(this).apply {
            setText(current)
            hint = setting.placeholder
            inputType = if (setting.numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            Type.apply(this, TypeRole.BODY)
            setTextColor(p.ink)
            setHintTextColor(p.etched)
            background = Bevel.sunkenTrack(this@PluginDetailActivity, fill = p.sunken, r = Dimens.RADIUS_READOUT)
            setPadding(dp(Dimens.SPACE_M), dp(Dimens.SPACE_M), dp(Dimens.SPACE_M), dp(Dimens.SPACE_M))
            minimumHeight = dp(Dimens.MIN_TARGET)
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val error = Type.text(this, "", TypeRole.DETAIL, p.cautionText).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Dimens.SPACE_XL), dp(Dimens.SPACE_S), dp(Dimens.SPACE_XL), 0)
            addView(input, full())
            addView(error, full().apply { topMargin = dp(Dimens.SPACE_S) })
        }
        val dialog = StmpDialog.builder(this)
            .setTitle(setting.label)
            .setView(body)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.show()
        input.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                store.put(plugin.id, setting, input.text.toString().trim())
                dialog.dismiss()
                content.post { render() }
            } catch (e: PluginSettingsException) {
                error.text = PluginDetailCopy.refusal(e.message)
                error.visibility = View.VISIBLE
                Debug.log("setting refused: ${e.message}")
            }
        }
    }

    // --- Routes ------------------------------------------------------------------------------

    private fun addRoutes() {
        val routes = plugin.routes()
        if (routes.isEmpty()) return
        content.addView(SectionHeader.create(this, "Routes"))
        content.addView(Readout.create(
            this,
            routes.joinToString("\n\n") { PluginDetailCopy.routeEntry(it.method, it.path, it.describe) },
            Readout.Kind.LOG,
        ).root)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun toneOf(tone: PluginRowModel.Tone): StatusRow.Tone = when (tone) {
        PluginRowModel.Tone.OK -> StatusRow.Tone.OK
        PluginRowModel.Tone.CAUTION -> StatusRow.Tone.CAUTION
        PluginRowModel.Tone.NEUTRAL -> StatusRow.Tone.NEUTRAL
    }

    /** The first header of the screen sits right under the top bar: no extra section gap. */
    private fun zeroTop(header: TextView): TextView = header.apply {
        setPadding(paddingLeft, 0, paddingRight, paddingBottom)
    }

    private fun View.topMargin(px: Int) {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: full()).apply { topMargin = px }
    }

    /** Recolours the first text view showing [text] inside [root] (the kit row's detail is `etched`). */
    private fun tint(root: View, text: String, color: Int) {
        if (root is TextView && root.text.toString() == text) { root.setTextColor(color); return }
        if (root is ViewGroup) for (i in 0 until root.childCount) tint(root.getChildAt(i), text, color)
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
        if (::content.isInitialized) content.post { render() }
    }

    private fun openAppSettings() {
        startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ))
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

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"

        /**
         * The SSH connection this detail screen is scoped to, or absent for a whole plugin.
         *
         * One connection is a vault secret named `ssh-<host>`, not a plugin of its own, so the
         * screen takes the plugin AND a name, and narrows itself to that connection when present.
         */
        const val EXTRA_SECRET_NAME = "secret_name"
        private const val REQ_PERMISSION = 3001
        private val GOOGLE_BACKED_IDS = setOf("gmail", "calendar", "drive")
    }
}
