package com.mido.phonebridge

import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The design-token layer must serve the PAPER palette, not the Instrument one.
 *
 * WHY THIS EXISTS
 *   The kit introduced `ui/tokens` and, in the same change, replaced the colours with a cool
 *   grey scheme and the fonts with Geist. The structure was worth keeping; the values were not.
 *   Mido, 2026-11-14: *"keep the design system setup, but in the original design"*.
 *
 *   These assertions pin the actual paper hex values. A token layer that quietly serves a
 *   different palette is exactly the failure this guards — and it is invisible to a screenshot
 *   test, because those baselines would simply be re-recorded from whatever rendered.
 */
@RunWith(RobolectricTestRunner::class)
class PaperPaletteTest {

    @Test
    fun `the warm cream background is intact`() {
        assertEquals(0xFFF5EEDF.toInt(), Theme.BG)
        assertEquals(0xFFEDE3CB.toInt(), Theme.SURFACE)
        assertEquals(0xFFE3D6B8.toInt(), Theme.SURFACE_VARIANT)
    }

    @Test
    fun `the ink and stamp red are intact`() {
        assertEquals(0xFF2B2318.toInt(), Theme.ON_SURFACE)
        assertEquals(0xFF67563C.toInt(), Theme.ON_SURFACE_VARIANT)
        assertEquals(0xFFB23A2F.toInt(), Theme.ACCENT)
        assertEquals(0xFF8F805F.toInt(), Theme.BORDER)
    }

    @Test
    fun `the semantic inks are distinct from the accent`() {
        // A destructive action must not be the same colour as the brand accent, nor the same
        // amber as a warning. The kit shipped DANGER mapped onto the caution colour.
        assertEquals(0xFF94372D.toInt(), Theme.DANGER)
        assertEquals(0xFF745410.toInt(), Theme.WARNING)
        assertEquals(0xFF27673F.toInt(), Theme.SUCCESS)
        assert(Theme.DANGER != Theme.ACCENT) { "DANGER must not equal ACCENT" }
        assert(Theme.DANGER != Theme.WARNING) { "DANGER must not equal WARNING" }
    }

    @Test
    fun `the paper faces are the bundled ones`() {
        // Asserting on the RESOURCE, not the resolved Typeface. Robolectric cannot decode a real
        // TTF, so every face it hands back is the same placeholder object — an inequality check
        // on the Typeface passed while proving nothing. The resource id is the fact that holds
        // on a device and in the JVM.
        val paper = com.mido.phonebridge.ui.tokens.FontFace.entries
        assert(paper.any { it.res == R.font.handlee }) { "Handlee must back a role" }
        assert(paper.any { it.res == R.font.atkinson_regular }) { "Atkinson must back a role" }
        assert(paper.any { it.res == R.font.jetbrains_mono_nl_regular }) { "JetBrains Mono NL must back a role" }
        // And every role must point at one of the paper faces, never at a Geist resource: the
        // Geist TTFs are gone from this build, so a leftover reference would fail to resolve.
        val display = com.mido.phonebridge.ui.tokens.FontFace.DISPLAY.res
        val body = com.mido.phonebridge.ui.tokens.FontFace.SANS_400.res
        assert(display != body) { "DISPLAY and SANS_400 must be different resources" }
    }
}
