package com.mido.phonebridge.ui

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** FIX4: request text is shown as sent; nothing invisible or re-ordering survives to the screen. */
@RunWith(RobolectricTestRunner::class)
class UntrustedTextTest {

    private val caution = Color.rgb(200, 120, 0)

    @Test
    fun `every bidi control is escaped`() {
        val controls = (0x202A..0x202E) + (0x2066..0x2069)
        for (cp in controls) {
            val raw = "a" + String(Character.toChars(cp)) + "b"
            assertEquals("a\\u{%04X}b".format(cp), UntrustedText.escape(raw))
        }
    }

    @Test
    fun `zero width, separators, BOM, soft hyphen and other format characters are escaped`() {
        for (cp in listOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x2028, 0x2029, 0xFEFF, 0x00AD, 0x061C, 0xE0001, 0x2060)) {
            assertTrue("U+%04X".format(cp), UntrustedText.needsEscape(cp))
        }
        assertEquals("x\\u{E0001}y", UntrustedText.escape("x\uDB40\uDC01y"))
    }

    @Test
    fun `C0 and C1 controls are escaped except newline and tab`() {
        assertEquals("a\nb\tc", UntrustedText.escape("a\nb\tc"))
        assertEquals("a\\u{0000}b\\u{001B}[0m\\u{007F}\\u{0085}", UntrustedText.escape("a\u0000b\u001B[0m\u007F\u0085"))
        assertEquals("x\\u{000D}\ny", UntrustedText.escape("x\r\ny"))
    }

    @Test
    fun `a lone surrogate is escaped and a real pair is kept`() {
        assertEquals("\\u{D800}", UntrustedText.escape("\uD800"))
        assertEquals("\uD83D\uDE00", UntrustedText.escape("\uD83D\uDE00"))
    }

    @Test
    fun `ordinary text, Arabic, CJK and emoji pass through untouched`() {
        for (raw in listOf("rm -rf /tmp/x", "مرحبا بالعالم", "你好，世界", "ssh a@b.c \"echo hi\" # ok \uD83D\uDE80")) {
            assertEquals(raw, UntrustedText.escape(raw))
            assertFalse(UntrustedText.hasHidden(raw))
            assertSame(raw, UntrustedText.mark(raw, caution))
        }
    }

    @Test
    fun `a reordering command is shown with its controls visible`() {
        val shown = UntrustedText.escape("echo safe \u202Etxt.exe\u202C")
        assertEquals("echo safe \\u{202E}txt.exe\\u{202C}", shown)
        assertTrue(shown.none { it == '\u202E' || it == '\u202C' })
    }

    @Test
    fun `wrap glyphs in the source are escaped only when asked`() {
        val raw = "a\u21B5b\u21A9c"
        assertEquals(raw, UntrustedText.escape(raw))
        assertEquals("a\\u{21B5}b\\u{21A9}c", UntrustedText.escape(raw, escapeWrapGlyphs = true))
    }

    @Test
    fun `mark draws each token in the caution colour and keeps the other spans`() {
        val src = android.text.SpannableStringBuilder("rm -rf \u202E/")
        src.setSpan(ForegroundColorSpan(Color.BLUE), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val out = UntrustedText.mark(src, caution) as Spanned
        assertEquals("rm -rf \\u{202E}/", out.toString())
        val tokenStart = out.indexOf('\\')
        val colours = out.getSpans(tokenStart, tokenStart + 8, ForegroundColorSpan::class.java).map { it.foregroundColor }
        assertTrue(caution in colours)
        val kept = out.getSpans(0, 5, ForegroundColorSpan::class.java).first { it.foregroundColor == Color.BLUE }
        assertEquals(0, out.getSpanStart(kept))
        assertEquals(5, out.getSpanEnd(kept))
    }
}
