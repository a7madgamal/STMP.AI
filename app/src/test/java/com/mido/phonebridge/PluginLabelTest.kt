package com.mido.phonebridge

import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.ui.PluginLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginLabelTest {
    @After fun after() = Fixtures.clearPlugins()

    @Test fun `a registered id gives its display name`() {
        Fixtures.clearPlugins()
        PluginRegistry.register(WpFakePlugin("tts", "Speak"))
        assertEquals("Speak", PluginLabel.of("tts"))
    }

    @Test fun `an unregistered id still reads as a name`() {
        Fixtures.clearPlugins()
        assertEquals("My plugin", PluginLabel.of("my-plugin"))
        assertEquals("Gmail", PluginLabel.of("gmail"))
        assertEquals("", PluginLabel.of(" "))
    }

    @Test fun `an unregistered id with hidden characters is escaped, never raw`() {
        val label = PluginLabel.of("evil\u202Eplug\u200Bin")
        assertEquals("Evil\\u{202E}plug\\u{200B}in", label)
    }
}

/** A plugin with a fixed display name, for screens that print plugin names. */
internal class WpFakePlugin(
    override val id: String,
    override val displayName: String,
) : com.mido.phonebridge.core.Plugin {
    override fun routes() = emptyList<com.mido.phonebridge.core.Route>()
    override fun status() =
        com.mido.phonebridge.core.PluginStatus(id, com.mido.phonebridge.core.PluginStatus.State.OK)
}
