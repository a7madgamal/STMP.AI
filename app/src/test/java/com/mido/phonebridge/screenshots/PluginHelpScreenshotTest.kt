package com.mido.phonebridge.screenshots

import com.mido.phonebridge.PluginsFragment
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Plugin help dialog: every state shows the plugin's real status line (WP05 5.7). */
class PluginHelpScreenshotTest(variant: Variant) : ShotTest(variant) {

    private val attention = PluginStatus.State.NEEDS_ATTENTION
    private val unconfigured = PluginStatus.State.OFF

    @Test
    fun google_not_set_up() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("google", unconfigured, "no OAuth client id configured"))
        open("Google account", "google_not_set_up")
    }

    @Test
    fun contacts_needs_action() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("contacts", attention, "READ_CONTACTS not granted — searching answers 503 contacts_unavailable"))
        open("Contacts", "contacts_needs_action")
    }

    @Test
    fun tts_ok() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        open("Speak", "tts_ok")
    }

    @Test
    fun ssh_off() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.pluginsSwitchedOff("ssh")
        open("SSH", "ssh_off")
    }

    @Test
    fun broken_error() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("debug", PluginStatus.State.ERROR, "Status unavailable: disk full"))
        open("Debug", "broken_error")
    }

    @Test
    fun contacts_off() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.pluginsSwitchedOff("contacts")
        open("Contacts", "contacts_off")
    }

    private fun open(displayName: String, state: String) {
        val activity = hostTab(PluginsFragment::class.java, Theme.Tab.PLUGINS)
        val bulb = findByDescription(activity.window.decorView, "About $displayName")
        assertNotNull("no bulb for $displayName", bulb)
        bulb!!.performClick()
        Shots.screen("plugin_help", state)
    }
}
