package com.mido.phonebridge.screenshots

import com.mido.phonebridge.HomeFragment
import com.mido.phonebridge.OptionsFragment
import com.mido.phonebridge.PluginsFragment
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** The tab shell: the 64 dp bar with each tab selected (WP04 section 11.2). */
class ShellScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun home() = shell(HomeFragment::class.java, Theme.Tab.HOME, "home")

    @Test fun plugins() {
        Fixtures.registerPlugins()
        shell(PluginsFragment::class.java, Theme.Tab.PLUGINS, "plugins")
    }

    @Test fun settings() = shell(OptionsFragment::class.java, Theme.Tab.SETTINGS, "settings")

    private fun shell(fragment: Class<out androidx.fragment.app.Fragment>, tab: Theme.Tab, state: String) {
        val activity = hostTab(fragment, tab)
        Shots.window(activity, "shell", state)
    }
}
