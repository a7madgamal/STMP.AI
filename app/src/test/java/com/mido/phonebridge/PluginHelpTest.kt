package com.mido.phonebridge

import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.ui.PluginHelp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog

/**
 * The help dialog must be built from a plugin's OWN declarations.
 *
 * The alternative — hand-written prose per plugin — drifts the first time a route is added, and
 * it drifts silently: nobody re-reads a help string when they register a route. These tests use
 * a stub plugin whose declarations are known, so they assert the dialog reflects *declarations*
 * rather than a fixed script.
 *
 * The stub is deliberate: `PluginRegistry.all()` is empty in a bare Robolectric context (plugins
 * are registered by the service), so binding to real ids would test the harness rather than the
 * renderer.
 */
@RunWith(RobolectricTestRunner::class)
class PluginHelpTest {

    private class Stub(
        override val id: String = "stub",
        override val displayName: String = "Stub plugin",
    ) : Plugin {
        override fun routes(): List<Route> = listOf(
            Route(
                "GET", "/stub/things", "LIST stub things",
                com.mido.phonebridge.core.Verb.READ,
            ) { com.mido.phonebridge.core.Resp.ok() },
            Route(
                "POST", "/stub/things", "CREATE a stub thing",
                com.mido.phonebridge.core.Verb.WRITE,
            ) { com.mido.phonebridge.core.Resp.ok() },
        )

        override fun status(): PluginStatus = PluginStatus("Stub plugin", PluginStatus.State.OK)

        override fun describeRows(req: Req): List<ApprovalRow> = emptyList()

        // Not overridden: permissions(), specialAccess() and scopes() all default to empty,
        // which is itself the case worth covering — a plugin that declares nothing must still
        // produce a dialog rather than an empty box or a crash.
    }

    private fun messageOf(body: () -> Unit): String {
        body()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("no dialog was shown")
        val shadow = org.robolectric.Shadows.shadowOf(dialog)
        return shadow.message?.toString() ?: ""
    }

    @Test
    fun `routes are listed from the plugin, not from a script`() {
        val ctx = RuntimeEnvironment.getApplication()
        val text = messageOf { PluginHelp.show(ctx, Stub(), enabled = true) }
        assertTrue("route path missing:\n$text", "/stub/things" in text)
        assertTrue("route description missing:\n$text", "LIST stub things" in text)
        assertTrue("both routes expected:\n$text", "CREATE a stub thing" in text)
    }

    @Test
    fun `an enabled plugin says it is served`() {
        val ctx = RuntimeEnvironment.getApplication()
        val text = messageOf { PluginHelp.show(ctx, Stub(), enabled = true) }
        assertTrue("must state it is on:\n$text", text.contains("On."))
    }

    @Test
    fun `a disabled plugin says off and stays recoverable`() {
        val ctx = RuntimeEnvironment.getApplication()
        val text = messageOf { PluginHelp.show(ctx, Stub(), enabled = false) }
        assertTrue("must state it is off:\n$text", text.contains("Off."))
        // The point of the sentence: turning a plugin off must not read as deleting it.
        assertTrue("must say routes still answer:\n$text", "still answer" in text)
    }

    @Test
    fun `a plugin declaring nothing still gets a dialog`() {
        // The row guarantee: tapping "?" on ANY plugin opens something. No dead taps.
        val ctx = RuntimeEnvironment.getApplication()
        val bare = object : Plugin {
            override val id = "bare"
            override val displayName = "Bare"
            override fun routes(): List<Route> = emptyList()
            override fun status(): PluginStatus = PluginStatus("Bare", PluginStatus.State.OK)
            override fun describeRows(req: Req): List<ApprovalRow> = emptyList()
        }
        val text = messageOf { PluginHelp.show(ctx, bare, enabled = true) }
        assertTrue("must name the plugin:\n$text", "Bare" in text)
    }
}
