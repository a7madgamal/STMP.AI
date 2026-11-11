package com.mido.phonebridge

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.ui.PluginInventoryView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The plugin inventory must survive its own construction.
 *
 * **The bug this pins.** `init { render(); arrange() }` was declared ABOVE `private val groups`,
 * and Kotlin runs property initialisers and `init` blocks in DECLARATION order. So `render()`
 * iterated a field that was still `null`, and every construction threw:
 *
 * ```
 * java.lang.NullPointerException: Attempt to invoke interface method
 *   'java.util.Iterator java.util.List.iterator()' on a null object reference
 *   at PluginInventoryView.render(PluginInventoryView.kt:145)
 *   at PluginInventoryView.<init>(PluginInventoryView.kt:30)
 * ```
 *
 * The visible symptom was not an obvious crash. `MainActivity.show()` runs `commitNow()`, so the
 * exception surfaced as the Preferences tab freezing mid-transition — reported 2026-11-11 as
 * *"hangs on preference click"*, then as a crash, then as a hang again, across three releases.
 *
 * Both assertions are kept: the SOURCE ORDER (which is the actual rule) and a real CONSTRUCTION
 * under the Android runtime, because order is what the compiler is happy to accept silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InventoryConstructionTest {

    private val source = File("src/main/java/com/mido/phonebridge/ui/PluginInventoryView.kt")

    private class FakePlugin(override val id: String) : Plugin {
        override val displayName = id
        override fun routes() = emptyList<com.mido.phonebridge.core.Route>()
        override fun status() = PluginStatus(id, PluginStatus.State.OK, "ready")
    }

    private class FakeHost(private val ctx: Context) : ScreenHost {
        override fun go(intent: Intent) = Unit
        override fun toast(message: String, duration: Int) = Unit
        override fun ui(block: () -> Unit) = block()
        override fun confirm(
            title: String,
            message: String,
            positive: String,
            negative: String,
            onNegative: (() -> Unit)?,
            onPositive: () -> Unit,
        ) = Unit
        override fun hasPermission(permission: String) = true
        override fun askPermission(permission: String, requestCode: Int) = Unit
    }

    @Test
    fun `every field the init block reads is declared above it`() {
        assertTrue("PluginInventoryView.kt missing", source.isFile)
        val text = source.readText()
        val initAt = text.indexOf("\n    init {")
        val groupsAt = text.indexOf("private val groups:")
        assertTrue("init block not found", initAt > 0)
        assertTrue("groups declaration not found", groupsAt > 0)
        assertTrue(
            "`groups` must be declared BEFORE the init block that renders with it: Kotlin " +
                "initialises in declaration order, so `render()` from `init` sees a null list " +
                "and throws on every construction (2026-11-11).",
            groupsAt < initAt,
        )
    }

    @Test
    fun `constructing the inventory renders without throwing`() {
        val context: Context = RuntimeEnvironment.getApplication()
        // Registered so the render path reaches the group loop rather than returning early on
        // "no plugins registered" — the early return would hide the very ordering bug above.
        com.mido.phonebridge.core.PluginRegistry.register(FakePlugin("construction-probe"))

        val view = PluginInventoryView(context, FakeHost(context))
        assertNotNull("construction must produce a view", view)
        assertTrue("a section header must be rendered", view.childCount > 0)
    }
}
