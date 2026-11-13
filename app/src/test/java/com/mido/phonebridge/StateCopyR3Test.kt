package com.mido.phonebridge

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.ui.OnboardingCopy
import com.mido.phonebridge.ui.PluginCopy
import com.mido.phonebridge.ui.PluginDetailCopy
import com.mido.phonebridge.ui.PluginLabel
import com.mido.phonebridge.ui.ServerStatus
import com.mido.phonebridge.ui.SettingsState
import com.mido.phonebridge.ui.VaultModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R3: the state and copy rules that are pure logic. */
class StateCopyR3Test {

    @Test fun `a fingerprint change is recorded with its own summary`() {
        val rec = PolicyAudit.recordFingerprint(true, GlobalMode.WHITELIST, "settings")
        assertEquals("fingerprint: on (settings)", rec.summary)
        assertEquals("fingerprint: off (home)", PolicyAudit.recordFingerprint(false, GlobalMode.PARANOID, "home").summary)
    }

    @Test fun `the switch label never says Disabled for a plugin that is switched on`() {
        assertEquals("Enabled", PluginDetailCopy.switchLabel(on = true, showsOn = true, notInBuild = false))
        assertEquals("Can't serve", PluginDetailCopy.switchLabel(on = true, showsOn = false, notInBuild = false))
        assertEquals("Disabled", PluginDetailCopy.switchLabel(on = false, showsOn = false, notInBuild = false))
        assertEquals("Not in this build", PluginDetailCopy.switchLabel(on = true, showsOn = false, notInBuild = true))
    }

    @Test fun `route descriptions are sentence case and keep acronyms`() {
        assertEquals("Trash email", PluginDetailCopy.routeDescription("TRASH EMAIL"))
        assertEquals("Quick-add calendar event", PluginDetailCopy.routeDescription("QUICK-ADD CALENDAR EVENT"))
        assertEquals("Run a command over SSH", PluginDetailCopy.routeDescription("RUN A COMMAND OVER SSH"))
        assertEquals("Show your Gmail profile", PluginDetailCopy.routeDescription("Show your Gmail profile"))
    }

    @Test fun `a route is two lines with the description indented`() {
        assertEquals(
            "POST /gmail/messages/send\n    Send email",
            PluginDetailCopy.routeEntry("POST", "/gmail/messages/send", "SEND EMAIL"),
        )
    }

    @Test fun `a plugin with no display name never starts its sentence with is`() {
        val blurb = PluginCopy.forId("deploy-token", "").blurb
        assertTrue(blurb, blurb.startsWith("This is one of the app's plugins"))
        assertTrue(PluginCopy.forId("zzz", "Zed").blurb.startsWith("Zed is one of"))
    }

    @Test fun `the Google account email may break before the at sign only`() {
        val d = PluginCopy.humanDetail("google", PluginStatus.State.OK, "someone@example.com")
        assertEquals("someone​@example.com", d)
    }

    @Test fun `brand names keep their casing`() {
        assertEquals("GitHub", PluginLabel.of("github"))
    }

    @Test fun `a switch that is still settling says Starting, never Stopped`() {
        assertEquals("Starting…", ServerStatus.pendingTitle(starting = true))
        assertEquals("Stopping…", ServerStatus.pendingTitle(starting = false))
        assertFalse(ServerStatus.pendingDetail(true).contains("Switch the bridge on"))
    }

    @Test fun `step four does not promise never less and step one leaves once the bridge is on`() {
        assertFalse(OnboardingCopy.GATE_INTRO.contains("never less"))
        assertTrue(OnboardingCopy.steps(serverOn = false).first().contains("Turn STMP on"))
        val on = OnboardingCopy.steps(serverOn = true)
        assertFalse(on.any { it.contains("Turn STMP on") })
        assertTrue(on.first().startsWith("1. "))
        assertEquals(on.size, OnboardingCopy.hermesSteps(serverOn = true).size - 1)
    }

    @Test fun `a wrong file with equal rounded sizes gives exact byte counts`() {
        val mb = { b: Long -> "${b / 1_000_000} MB" }
        assertEquals("The file is 300 MB but 328 MB was expected.", SettingsState.wrongFile(300_000_000, 328_000_000, mb))
        assertEquals(
            "The file is 328,100,000 bytes but 328,900,000 bytes were expected.",
            SettingsState.wrongFile(328_100_000, 328_900_000, mb),
        )
    }

    @Test fun `an empty list does not say tap ADD while the add key is disabled`() {
        assertTrue(VaultModule.emptyBody(true, "ADD SECRET").startsWith("Tap ADD SECRET"))
        assertTrue(VaultModule.emptyBody(false, "ADD SECRET").startsWith("Unlock the vault first"))
    }

    @Test fun `no screen text reads Needs attention or ACTION NEEDED`() {
        val sources = java.io.File("src/main/java/com/mido/phonebridge").walkTopDown().filter { it.extension == "kt" }
        for (f in sources) {
            val text = f.readText()
            assertFalse("${f.name} uses ACTION NEEDED", text.contains("\"ACTION NEEDED\""))
            assertFalse("${f.name} uses Needs attention", text.contains("\"Needs attention\""))
        }
    }

    @Suppress("unused") private fun walk(v: View): List<TextView> =
        if (v is TextView) listOf(v) else if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
}
