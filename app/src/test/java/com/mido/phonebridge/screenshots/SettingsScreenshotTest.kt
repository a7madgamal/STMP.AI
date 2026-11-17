package com.mido.phonebridge.screenshots

import com.mido.phonebridge.OptionsFragment
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.policy.AiBackend
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** Settings tab (OptionsFragment). */
class SettingsScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun shoot(state: String) =
        capture(hostTab(OptionsFragment::class.java, Theme.Tab.PREFERENCES), "settings", state)

    @Test fun default() = shoot("default")

    @Test fun ai_on_phone() { Fixtures.aiBackend(AiBackend.LOCAL); shoot("ai_on_phone") }

    @Test fun ai_remote() {
        Fixtures.aiBackend(AiBackend.EXTERNAL, "http://192.168.1.9:11434")
        shoot("ai_remote")
    }

    /**
     * The BOTTOM of the screen, scrolled into view.
     *
     * The bottom half of Preferences — DEVELOPER, About & updates — never appears in the normal
     * capture because the content is taller than the window, and `Shots.full` only grows the image
     * by the overflow. That left the one section the human complained about ("no space") with no
     * pixel coverage at all, so a flush button there could only be found by installing a build.
     */
    @Test fun developer_section() {
        val activity = hostTab(OptionsFragment::class.java, Theme.Tab.PREFERENCES)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val scroll = firstScroll(activity.window.decorView)
        scroll?.scrollTo(0, scroll.getChildAt(0)?.height ?: 0)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Shots.window(activity, "settings", "bottom")
    }

    @Test fun debug_log_open() {
        Fixtures.debugLog(listOf(
            "09:40:00  server started on 192.168.1.100:8787",
            "09:40:03  GET /help -> 200",
            "09:40:41  POST /contacts/search -> approval requested",
            "09:40:44  approved",
        ))
        shoot("debug_log_open")
    }

    @Test fun callers_one() {
        Fixtures.callers("100.64.0.7" to CallerState.Verified("Work laptop"))
        shoot("callers_one")
    }

    @Test fun callers_empty() = shoot("callers_empty")
}
