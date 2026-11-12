package com.mido.phonebridge.screenshots

import com.mido.phonebridge.PluginsFragment
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** Plugins tab (WP05). */
class PluginsScreenshotTest(variant: Variant) : ShotTest(variant) {

    private val attention = PluginStatus.State.NEEDS_ATTENTION
    private val unconfigured = PluginStatus.State.OFF

    private fun shoot(state: String) =
        capture(hostTab(PluginsFragment::class.java, Theme.Tab.PLUGINS), "plugins", state)

    @Test
    fun default() {
        Fixtures.registerPlugins()
        Fixtures.contactsPermission(granted = true)
        shoot("default")
    }

    @Test
    fun needs_attention() {
        Fixtures.registerPlugins()
        Fixtures.contactsPermission(granted = false)
        Fixtures.notificationListener(granted = false, connected = false)
        shoot("needs_attention")
    }

    @Test
    fun all_ok() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.secretNames("one", "two")
        shoot("all_ok")
    }

    @Test
    fun no_google_client() {
        PluginFixtures.register()
        shoot("no_google_client")
    }

    @Test
    fun google_not_set_up() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(
            Triple("google", unconfigured, "no OAuth client id configured"),
            Triple("gmail", unconfigured, "no OAuth client id configured"),
            Triple("calendar", unconfigured, "no OAuth client id configured"),
            Triple("drive", unconfigured, "no OAuth client id configured"),
        )
        shoot("google_not_set_up")
    }

    @Test
    fun error_row() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(throwing = setOf("tts"))
        shoot("error_row")
    }

    @Test
    fun switched_off() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.pluginsSwitchedOff("tts", "audio")
        shoot("switched_off")
    }

    @Test
    fun secrets_empty() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("secrets", attention, "0 secret(s) · GET /secrets"))
        shoot("secrets_empty")
    }

    @Test
    fun secrets_two() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        shoot("secrets_two")
    }

    @Test
    fun ssh_two_connections() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.secretNames("ssh-kvm-box", "ssh-nas")
        shoot("ssh_two_connections")
    }

    @Test
    fun developer_collapsed() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        shoot("developer_collapsed")
    }

    @Test
    fun developer_open() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.register()
        Fixtures.developerOpen(true)
        shoot("developer_open")
    }

    @Test
    fun developer_forced_open() {
        Fixtures.googleClient("fixture-client")
        PluginFixtures.registerWith(Triple("debug", attention, "needs a permission"))
        shoot("developer_forced_open")
    }

    @Test
    fun empty_registry() {
        Fixtures.clearPlugins()
        shoot("empty_registry")
    }
}
