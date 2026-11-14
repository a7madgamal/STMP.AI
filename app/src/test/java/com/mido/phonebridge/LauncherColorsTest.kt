package com.mido.phonebridge

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** D9, D53: the launcher colours are not night-qualified and equal the light palette roles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LauncherColorsTest {

    private fun launcher(name: String): Int {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = ctx.resources.getIdentifier("stmp_launcher_$name", "color", ctx.packageName)
        check(id != 0) { "no colour stmp_launcher_$name" }
        return ctx.resources.getColor(id, null)
    }

    @Test
    fun `the launcher colours equal the light palette roles in both modes`() {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+notnight")
            Palettes.clearCacheForTest()
            val light = Palettes.of(ApplicationProvider.getApplicationContext())
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            assertEquals("bg night=$night", light.body, launcher("bg"))
            assertEquals(light.ink, launcher("key"))
            assertEquals(light.keyLipInk, launcher("key_lip"))
            assertEquals(light.inkHighlight, launcher("key_hi"))
            assertEquals(light.ledOk, launcher("led"))
            assertEquals(light.okText, launcher("led_ring"))
            assertEquals(Color.argb(0x66, Color.red(light.ledOk), Color.green(light.ledOk), Color.blue(light.ledOk)), launcher("led_halo"))
            assertEquals(light.readoutText, launcher("glyph"))
        }
    }
}
