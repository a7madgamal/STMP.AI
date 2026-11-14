package com.mido.phonebridge.screenshots

import com.mido.phonebridge.HomeFragment
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.ui.Theme
import org.junit.Test

/** Home: every server, gate, audit and updates state (WP00 section 5). */
class HomeScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun capture(state: String) =
        capture(hostTab(HomeFragment::class.java, Theme.Tab.HOME), "home", state)

    @Test fun stopped() { Fixtures.server(Fixtures.Server.STOPPED); capture("stopped") }
    @Test fun running_healthy() { Fixtures.server(Fixtures.Server.RUNNING_HEALTHY); capture("running_healthy") }
    @Test fun running_attention() { Fixtures.server(Fixtures.Server.RUNNING_ATTENTION); capture("running_attention") }
    @Test fun unresponsive() { Fixtures.server(Fixtures.Server.UNRESPONSIVE); capture("unresponsive") }
    @Test fun failed() { Fixtures.server(Fixtures.Server.FAILED); capture("failed") }
    @Test fun audit_degraded() { Fixtures.auditDegraded(); capture("audit_degraded") }
    @Test fun gate_whitelist() { Fixtures.gate(GlobalMode.WHITELIST); capture("gate_whitelist") }
    @Test fun gate_paranoid() { Fixtures.gate(GlobalMode.PARANOID); capture("gate_paranoid") }
    @Test fun gate_dont_disturb() { Fixtures.gate(GlobalMode.DONT_DISTURB); capture("gate_dont_disturb") }
    @Test fun gate_unreadable() { Fixtures.gateUnreadable(); capture("gate_unreadable") }
    @Test fun updates_up_to_date() { Fixtures.updatesUpToDate(); capture("updates_up_to_date") }
    @Test fun updates_available() { Fixtures.updatesAvailable(); capture("updates_available") }
}
