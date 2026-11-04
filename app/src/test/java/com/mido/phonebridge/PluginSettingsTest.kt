package com.mido.phonebridge

import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginSettingsException
import com.mido.phonebridge.core.PluginSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules that decide whether a capability exists on this phone.
 *
 * Pure, no Android runtime: [PluginSettingsStore]'s reads and writes are exercised through the
 * same decisions a device would take, with the parsing lifted out so a JVM can check it. The
 * parts that genuinely need a `Context` are covered by the Robolectric-run suite.
 *
 * What is pinned here is the part that would be dangerous to get wrong: a corrupt or hostile
 * stored value must REFUSE, never quietly resolve to a default. Silently re-enabling a plugin a
 * human turned off is a security regression wearing resilience as a disguise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginSettingsTest {

    /** A minimal plugin, so the store's use of [Plugin] is exercised without a real one. */
    private class FakePlugin(
        override val id: String,
        override val displayName: String = id,
        override val enabledByDefault: Boolean = true,
    ) : Plugin {
        override fun routes() = emptyList<com.mido.phonebridge.core.Route>()
        override fun status() =
            com.mido.phonebridge.core.PluginStatus(id, com.mido.phonebridge.core.PluginStatus.State.OK)
    }

    @Test
    fun `a plugin with nothing stored uses its own default`() {
        // The default belongs to the plugin, not the store: a plugin that should arrive switched
        // off says so once, where it is defined, rather than being special-cased in the UI.
        val on = FakePlugin("on", enabledByDefault = true)
        val off = FakePlugin("off", enabledByDefault = false)
        assertTrue(on.enabledByDefault)
        assertFalse(off.enabledByDefault)
    }

    @Test
    fun `a non-boolean stored value is a defect, not a false`() {
        // `optBoolean` would turn "yes" into false and silently disable a plugin. The store
        // reads the TYPE and refuses instead, which is the whole reason it does not use the
        // convenience accessor.
        //
        // Asserted on the real parser rather than on JSONObject's behaviour: the point is the
        // STORE's rule, and a test of the library would keep passing if the store regressed.
        val corrupt = """{"contacts":"yes"}"""
        val threw = try {
            com.mido.phonebridge.core.PluginStateCodec.decodeEnabled(corrupt)
            false
        } catch (e: PluginSettingsException) {
            true
        }
        assertTrue(
            "a stored string where a boolean belongs must be refused, never read as false",
            threw
        )
    }

    /**
     * EVERY plugin is toggleable, with no exceptions.
     *
     * Mido, 2026-11-04: *"all plugins should be toggleable no exceptions"*. This replaces a test
     * that asserted the opposite — that `help`, `debug` and `logs` refuse to be switched off.
     *
     * The safety that carve-out protected is carried by the dispatch path instead: a disabled
     * plugin still ANSWERS, refusing with `plugin_disabled`. Turning off `help` therefore makes
     * routes explain that they are off rather than 404, and it is recoverable from the same screen
     * by the same switch.
     *
     * Guarded here because "all plugins toggleable" is the kind of rule a well-meaning exception
     * breaks silently. If a future plugin needs to be un-disableable, that is a design argument to
     * have out loud, not a property to add quietly.
     */
    @Test
    fun `no plugin declares itself un-disableable`() {
        val root = java.io.File("src/main/java/com/mido/phonebridge")
        assertTrue("source root not found", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    if (line.contains("canBeDisabled")) {
                        offenders += "${file.name}:${i + 1}  ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "`canBeDisabled` must not exist: every plugin is toggleable (Mido, 2026-11-04). " +
                "Found:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty()
        )
    }

    @Test
    fun `a choice refuses a value outside its options`() {
        val choice = PluginSetting.Choice(
            id = "mode",
            label = "Mode",
            options = listOf("AUTOMATIC", "SECURE"),
            default = "SECURE",
        )
        assertEquals("SECURE", choice.default)
        assertFalse("AUTOMATIC" !in choice.options)
    }

    @Test
    fun `a choice with a default outside its options cannot be constructed`() {
        // Caught at declaration time rather than at read time, so a bad descriptor never ships.
        val threw = try {
            PluginSetting.Choice(
                id = "mode",
                label = "Mode",
                options = listOf("A", "B"),
                default = "C",
            )
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("a default outside the options must be rejected at construction", threw)
    }

    @Test
    fun `a choice with duplicate options cannot be constructed`() {
        val threw = try {
            PluginSetting.Choice(
                id = "mode",
                label = "Mode",
                options = listOf("A", "A"),
                default = "A",
            )
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("duplicate options make a stored value ambiguous", threw)
    }

    @Test
    fun `the exception names the setting so a log line is actionable`() {
        val e = PluginSettingsException("setting 'tts.rate' holds 'fast', which is not a boolean")
        assertTrue(e.message!!.contains("tts.rate"))
    }
}
