package com.mido.phonebridge.screenshots

import com.mido.phonebridge.PluginsFragment
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.Theme
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSettingsStore
import com.mido.phonebridge.ui.PluginHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowDialog
import org.junit.Test

/** Plugin help dialog: every state shows the plugin's real status line (WP05 5.7). */
class PluginHelpScreenshotTest(variant: Variant) : ShotTest(variant) {

    private val attention = PluginStatus.State.NEEDS_ATTENTION
    private val unconfigured = PluginStatus.State.OFF

    @Test
    fun google_not_set_up() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("google", unconfigured, "no OAuth client id configured"))
        open("Google account", "google", "google_not_set_up", "On, but not set up yet.", listOf("Close", "Open plugin"))
    }

    @Test
    fun contacts_needs_action() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("contacts", attention, "READ_CONTACTS not granted — searching answers 503 contacts_unavailable"))
        open("Contacts", "contacts", "contacts_needs_action", "On, but it needs action.", listOf("Close", "Open plugin"))
    }

    @Test
    fun tts_ok() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        open("Speak", "tts", "tts_ok", "On, and ready.", listOf("Got it"))
    }

    @Test
    fun ssh_off() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.pluginsSwitchedOff("ssh")
        open("SSH", "ssh", "ssh_off", null, listOf("Close", "Open plugin"))
    }

    @Test
    fun broken_error() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("debug", PluginStatus.State.ERROR, "Status unavailable: disk full"))
        open("Debug", "debug", "broken_error", "On, but it hit an error.", listOf("Close", "Open plugin"))
    }

    @Test
    fun contacts_off() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.pluginsSwitchedOff("contacts")
        open("Contacts", "contacts", "contacts_off", null, listOf("Close", "Open plugin"))
    }

    /**
     * Opens the bulb dialog and checks the words, not only the pixels (spec 11.2): the status line
     * is the one [PluginHelp.lineFor] gives for the plugin as it is NOW (so it is read fresh on
     * open), it starts with [lead] (null for a switched-off plugin, which shows the off note), and
     * the buttons are exactly [buttons]: "Got it" when healthy, "Close" + "Open plugin" otherwise.
     */
    private fun open(displayName: String, pluginId: String, state: String, lead: String?, buttons: List<String>) {
        val activity = hostTab(PluginsFragment::class.java, Theme.Tab.PLUGINS)
        val bulb = findByDescription(activity.window.decorView, "About $displayName")
        assertNotNull("no bulb for $displayName", bulb)
        bulb!!.performClick()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("the help dialog is open", dialog.isShowing)
        val plugin = PluginRegistry.get(pluginId)!!
        val line = PluginHelp.lineFor(plugin, PluginSettingsStore.from(Fixtures.app).isEnabled(plugin))
        if (lead != null) assertTrue("status '${line.text}' starts with '$lead'", line.text.startsWith(lead))
        assertNotNull("the dialog shows the status line '${line.text}'", findByText(dialog.window!!.decorView, line.text))

        val shown = listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)
            .mapNotNull { dialog.getButton(it) }
            .filter { it.visibility == View.VISIBLE }
            .map { it.text.toString() }
        assertEquals("buttons in $state", buttons.map { it.uppercase() }, shown.map { it.uppercase() })
        Shots.screen("plugin_help", state)
    }
}
