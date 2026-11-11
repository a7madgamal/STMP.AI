package com.mido.phonebridge.screenshots

import com.mido.phonebridge.PluginsFragment
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** Plugins tab. */
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
}
