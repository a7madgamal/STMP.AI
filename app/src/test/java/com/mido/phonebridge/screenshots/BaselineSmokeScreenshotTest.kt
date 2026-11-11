package com.mido.phonebridge.screenshots

import com.mido.phonebridge.HomeFragment
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** Harness check: Home in its default state, all four variants. */
class BaselineSmokeScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test
    fun home_default() {
        val activity = hostTab(HomeFragment::class.java, Theme.Tab.HOME)
        Shots.window(activity, "home", "default")
    }
}
