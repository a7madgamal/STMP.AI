package com.mido.phonebridge

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every "yes" row of TOKENS 4.1 (light) and 4.2 (dark), asserted against the live resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContrastTest {

    private class Pair(val name: String, val min: Double, val fg: (Palette) -> Int, val bg: (Palette) -> Int, val darkOnly: Boolean = false)

    private fun p(name: String, min: Double, fg: (Palette) -> Int, bg: (Palette) -> Int, darkOnly: Boolean = false) =
        Pair(name, min, fg, bg, darkOnly)

    /** [fg] drawn over [bg] at [alpha] (0..255), the colour the eye gets. */
    private fun over(fg: Int, bg: Int, alpha: Int): Int {
        val a = alpha / 255.0
        fun mix(f: Int, b: Int) = Math.round(f * a + b * (1 - a)).toInt()
        return Color.rgb(mix(Color.red(fg), Color.red(bg)), mix(Color.green(fg), Color.green(bg)), mix(Color.blue(fg), Color.blue(bg)))
    }

    private val surfaces: List<kotlin.Pair<String, (Palette) -> Int>> = listOf(
        "body" to { x: Palette -> x.body }, "panel" to { x: Palette -> x.panel }, "sunken" to { x: Palette -> x.sunken },
    )

    private val pairs: List<Pair> = buildList {
        for ((n, bg) in surfaces) {
            add(p("ink on $n", 4.5, { it.ink }, bg))
            add(p("etched on $n", 4.5, { it.etched }, bg))
            add(p("okText on $n", 4.5, { it.okText }, bg))
            add(p("cautionText on $n", 4.5, { it.cautionText }, bg))
            add(p("signalText on $n", 4.5, { it.signalText }, bg))
        }
        add(p("etched on tapePaper", 4.5, { it.etched }, { it.tapePaper }))
        add(p("ink on tapePaper", 4.5, { it.ink }, { it.tapePaper }))
        add(p("onInk on ink", 4.5, { it.onInk }, { it.ink }))
        add(p("onSignal on signal", 4.5, { it.onSignal }, { it.signal }))
        add(p("okText on okSurface", 4.5, { it.okText }, { it.okSurface }))
        add(p("cautionText on cautionSurface", 4.5, { it.cautionText }, { it.cautionSurface }))
        add(p("signalText on signalSurface", 4.5, { it.signalText }, { it.signalSurface }))
        add(p("ink on okSurface", 4.5, { it.ink }, { it.okSurface }))
        add(p("ink on cautionSurface", 4.5, { it.ink }, { it.cautionSurface }))
        add(p("ink on signalSurface", 4.5, { it.ink }, { it.signalSurface }))
        add(p("onInk on okText", 4.5, { it.onInk }, { it.okText }))
        add(p("onInk on cautionText", 4.5, { it.onInk }, { it.cautionText }))
        add(p("onInk on signalText", 4.5, { it.onInk }, { it.signalText }))
        add(p("readoutText on readout", 4.5, { it.readoutText }, { it.readout }))
        add(p("readoutCommand on readout", 4.5, { it.readoutCommand }, { it.readout }))
        add(p("readoutMuted on readout", 4.5, { it.readoutMuted }, { it.readout }))
        add(p("readoutDanger on readout", 4.5, { it.readoutDanger }, { it.readout }))
        add(p("readoutString on readout", 4.5, { it.readoutString }, { it.readout }))
        add(p("signal underline on readout", 3.0, { it.signal }, { it.readout }))
        add(p("rockerOffText on rockerCapOff", 4.5, { it.rockerOffText }, { it.rockerCapOff }))
        for (n in listOf("body", "panel")) {
            val bg = surfaces.first { it.first == n }.second
            add(p("okGlyph on $n", 3.0, { it.okGlyph }, bg))
            add(p("cautionGlyph on $n", 3.0, { it.cautionGlyph }, bg))
            add(p("signalGlyph on $n", 3.0, { it.signalGlyph }, bg))
            add(p("outline on $n", 3.0, { it.outline }, bg))
        }
        add(p("LED ring okText on panel", 3.0, { it.okText }, { it.panel }))
        add(p("LED ring cautionText on panel", 3.0, { it.cautionText }, { it.panel }))
        add(p("LED ring signalText on panel", 3.0, { it.signalText }, { it.panel }))
        add(p("LED ring etched on panel", 3.0, { it.etched }, { it.panel }))
        add(p("focus on body", 3.0, { it.focus }, { it.body }))
        // R2 kit. Rocker OFF cap (dark): the cap must stand off its track (1.4.11) and its label
        // must read on it (1.4.3, asserted above). The verb chip's edge on its sunken fill, the
        // PANEL key's outline on the panel and the progress label on the progress fill.
        add(p("rockerCapOff on rockerTrack", 3.0, { it.rockerCapOff }, { it.rockerTrack }, darkOnly = true))
        add(p("rockerTrack edge (outline) on panel", 3.0, { it.outline }, { it.panel }))
        add(p("verb chip edge (outline) on sunken", 3.0, { it.outline }, { it.sunken }))
        add(p("ink on verb chip sunken fill", 4.5, { it.ink }, { it.sunken }))
        for (n in listOf("body", "panel")) {
            val bg = surfaces.first { it.first == n }.second
            add(p("progress label (ink) on its fill over $n", 4.5, { it.ink }, { x -> over(x.ink, bg(x), KeyButton.PROGRESS_FILL_ALPHA) }))
            add(p("progress label (ink) on its track over $n", 4.5, { it.ink }, bg))
        }
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val a = lum(fg)
        val b = lum(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun check(night: Boolean) {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        Palettes.clearCacheForTest()
        val palette = Palettes.of(ApplicationProvider.getApplicationContext())
        val theme = if (night) "dark" else "light"
        val failures = pairs.filter { !it.darkOnly || night }.mapNotNull { pair ->
            val r = ratio(pair.fg(palette), pair.bg(palette))
            if (r + 1e-9 >= pair.min) null else "%s (%s): %.2f < %.1f".format(pair.name, theme, r, pair.min)
        }
        assertTrue("contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `light pairs meet their minimum`() = check(night = false)

    @Test fun `dark pairs meet their minimum`() = check(night = true)
}
