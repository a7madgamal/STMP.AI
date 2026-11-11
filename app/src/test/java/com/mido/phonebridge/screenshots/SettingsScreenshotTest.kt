package com.mido.phonebridge.screenshots

import com.mido.phonebridge.OptionsFragment
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.policy.AiBackend
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** Settings tab (OptionsFragment). */
class SettingsScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun shoot(state: String) =
        capture(hostTab(OptionsFragment::class.java, Theme.Tab.SETTINGS), "settings", state)

    @Test fun default() = shoot("default")

    @Test fun ai_on_phone() { Fixtures.aiBackend(AiBackend.LOCAL); shoot("ai_on_phone") }

    @Test fun ai_remote() {
        Fixtures.aiBackend(AiBackend.EXTERNAL, "http://192.168.1.9:11434")
        shoot("ai_remote")
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
