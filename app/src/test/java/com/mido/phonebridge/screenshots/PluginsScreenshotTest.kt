package com.mido.phonebridge.screenshots

import com.mido.phonebridge.PluginsFragment
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Plugins tab and the plugin help dialog. */
class PluginsScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test
    fun plugins_default() {
        Fixtures.registerPlugins()
        Fixtures.contactsPermission(granted = true)
        capture(hostTab(PluginsFragment::class.java, Theme.Tab.PLUGINS), "plugins", "default")
    }

    @Test
    fun plugins_needs_attention() {
        Fixtures.registerPlugins()
        Fixtures.contactsPermission(granted = false)
        capture(hostTab(PluginsFragment::class.java, Theme.Tab.PLUGINS), "plugins", "needs_attention")
    }

    @Test
    fun plugin_help_google() = helpDialog("Google account", "google")

    @Test
    fun plugin_help_contacts() = helpDialog("Contacts", "contacts")

    private fun helpDialog(displayName: String, state: String) {
        Fixtures.registerPlugins()
        val activity = hostTab(PluginsFragment::class.java, Theme.Tab.PLUGINS)
        val bulb = findByDescription(activity.window.decorView, "What $displayName does")
        assertNotNull("no bulb for $displayName", bulb)
        bulb!!.performClick()
        Shots.screen("plugin_help", state)
    }
}
