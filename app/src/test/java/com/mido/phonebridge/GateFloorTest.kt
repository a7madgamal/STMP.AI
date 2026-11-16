package com.mido.phonebridge

import com.mido.phonebridge.core.GateFloor
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.policy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A route's `minMode` is a FLOOR: it can make a request louder and never quieter.
 *
 * The point of the guard is that policy and the route cannot undercut each other. A rule or a
 * `DONT_DISTURB` global says how a pair is usually asked about; a route that carries something
 * sensitive insists on being asked properly on top of that. If the floor could ever LOWER a
 * resolved mode, applying it after policy would be a way to turn the gate off by editing a route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GateFloorTest {

    /**
     * **Leave the registry empty for whatever runs next.** `PluginRegistry` is a process-wide
     * singleton, so registering a plugin here leaks into every later test in the same JVM — this
     * broke `InventoryConstructionTest` with a Material-theme error the first time, and that is
     * already the recorded gotcha from an earlier run. Clearing is cheap and the alternative is a
     * failure in a file that has nothing to do with this one.
     */
    @org.junit.After
    fun clearPlugins() {
        com.mido.phonebridge.screenshots.Fixtures.clearPlugins()
    }

    @Test
    fun `a paranoid floor raises a quiet resolution`() {
        assertEquals(Mode.PARANOID, GateFloor.raise(Mode.DONT_DISTURB, Mode.PARANOID))
        assertEquals(Mode.PARANOID, GateFloor.raise(Mode.PARANOID, Mode.PARANOID))
    }

    @Test
    fun `no floor changes nothing`() {
        assertEquals(Mode.DONT_DISTURB, GateFloor.raise(Mode.DONT_DISTURB, null))
        assertEquals(Mode.PARANOID, GateFloor.raise(Mode.PARANOID, null))
    }

    @Test
    fun `a dont-disturb floor never makes anything quieter`() {
        // Both modes ask a human, so a DONT_DISTURB floor is already satisfied by either. The
        // important half is the second assertion: a PARANOID resolution must stay PARANOID.
        assertEquals(Mode.DONT_DISTURB, GateFloor.raise(Mode.DONT_DISTURB, Mode.DONT_DISTURB))
        assertEquals(Mode.PARANOID, GateFloor.raise(Mode.PARANOID, Mode.DONT_DISTURB))
    }

    @Test
    fun `the floor is monotonic over every input pair`() {
        // Exhaustive, so "only louder" is a fact rather than an intention. A future Mode added
        // without a branch fails to compile in `raise`, and this test then fails on the value.
        for (resolved in Mode.values()) {
            for (floor in listOf<Mode?>(Mode.PARANOID, Mode.DONT_DISTURB, null)) {
                val out = GateFloor.raise(resolved, floor)
                if (resolved == Mode.PARANOID) {
                    assertEquals(
                        "PARANOID is the strictest: nothing may relax it (floor=$floor)",
                        Mode.PARANOID, out,
                    )
                }
            }
        }
    }

    private fun debugPlugin(): com.mido.phonebridge.plugins.debug.DebugPlugin {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val existing = com.mido.phonebridge.core.PluginRegistry.get("debug")
        if (existing is com.mido.phonebridge.plugins.debug.DebugPlugin) return existing
        val fresh = com.mido.phonebridge.plugins.debug.DebugPlugin(app)
        com.mido.phonebridge.core.PluginRegistry.register(fresh)
        return fresh
    }

    private fun secretsPlugin(): com.mido.phonebridge.plugins.secrets.SecretIndexPlugin {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val existing = com.mido.phonebridge.core.PluginRegistry.get("secrets")
        if (existing is com.mido.phonebridge.plugins.secrets.SecretIndexPlugin) return existing
        val fresh = com.mido.phonebridge.plugins.secrets.SecretIndexPlugin(app)
        com.mido.phonebridge.core.PluginRegistry.register(fresh)
        return fresh
    }

    @Test
    fun `the diagnostic routes that leak more demand a stamp`() {
        // `/debug` carries the account, the policy and a request history; `/secrets` names every
        // credential and its allowed hosts. Both were quiet by default, which is what made them a
        // concern on a tailnet-reachable bridge.
        val debug = debugPlugin().routes().first { it.path == "/debug" }
        val clearAudit = debugPlugin().routes().first { it.path == "/audit" }
        val list = secretsPlugin().routes().first { it.path == "/secrets" }
        val one = secretsPlugin().routes().first { it.path == "/secrets/{name}" }

        assertEquals("GET /debug must need a stamp", Mode.PARANOID, debug.minMode)
        assertEquals("DELETE /audit must need a stamp", Mode.PARANOID, clearAudit.minMode)
        assertEquals("GET /secrets must need a stamp", Mode.PARANOID, list.minMode)
        assertEquals("GET /secrets/{name} must need a stamp", Mode.PARANOID, one.minMode)
    }

    @Test
    fun `health is the one diagnostic left quiet`() {
        // The trade that makes the stricter `/debug` affordable: a wedged phone can still answer
        // "is it running, which build, is the disk full" with no card.
        val health = debugPlugin().routes().first { it.path == "/health" }

        assertEquals("GET /health must stay answerable under DONT_DISTURB", null, health.minMode)
        assertTrue(
            "and it must not need the model, or it is useless when the bridge is broken",
            !health.aiSummary,
        )
    }
}
