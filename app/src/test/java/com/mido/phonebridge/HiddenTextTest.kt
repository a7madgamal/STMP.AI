package com.mido.phonebridge

import com.mido.phonebridge.core.HiddenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Request text that can hide what it says is refused before the approval screen draws it.
 *
 * The case that motivates the whole file, stated as a test: a command with a
 * RIGHT-TO-LEFT OVERRIDE in it renders as different text than it runs. If this test ever
 * starts passing a bidi character, the approval gate is decorative.
 *
 * **Robolectric is not decoration here.** The JSON round-trip test needs the REAL `org.json`, and
 * the plain JVM stub throws `Method not mocked` for every method — which would have made that test
 * fail for a reason unrelated to what it asserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HiddenTextTest {

    @Test
    fun `a right-to-left override is refused`() {
        // What a caller sends vs what the approval screen would show. Same bytes, two strings.
        val sent = "rm -rf ~/\u202EstcejorP"
        val drawn = "rm -rf ~/stcejorP" // what a renderer shows after the override
        assertFalse("the case is only meaningful if the strings differ", sent == drawn)

        assertEquals(0x202E, HiddenText.firstHidden(sent))
        assertTrue(HiddenText.isHidden(0x202E))
    }

    @Test
    fun `the rest of the bidi and isolate block is refused`() {
        // U+202A-202E overrides/embeddings, U+2066-2069 isolates. All can reorder what follows.
        for (cp in (0x202A..0x202E) + (0x2066..0x2069)) {
            assertTrue("U+%04X must be refused".format(cp), HiddenText.isHidden(cp))
        }
    }

    @Test
    fun `zero-width and other concealing characters are refused`() {
        // Zero-width space / non-joiner / joiner-as-separator, word joiner, BOM, soft hyphen.
        for (cp in listOf(0x200B, 0x200C, 0x200E, 0x200F, 0x2060, 0xFEFF, 0x00AD, 0x180E)) {
            assertTrue("U+%04X must be refused".format(cp), HiddenText.isHidden(cp))
        }
    }

    @Test
    fun `controls except newline and tab are refused`() {
        for (cp in listOf(0x00, 0x07, 0x1B, 0x7F, 0x85, 0x9B)) {
            assertTrue("U+%04X must be refused".format(cp), HiddenText.isHidden(cp))
        }
        // The two an ordinary command or message actually contains.
        assertFalse(HiddenText.isHidden('\n'.code))
        assertFalse(HiddenText.isHidden('\t'.code))
    }

    @Test
    fun `line and paragraph separators are refused`() {
        assertTrue(HiddenText.isHidden(0x2028))
        assertTrue(HiddenText.isHidden(0x2029))
    }

    @Test
    fun `emoji keep working`() {
        // **The one judgement call in the rule, pinned.** These are invisible and are NOT
        // refused: a zero-width joiner and a variation selector only tell the renderer how to
        // compose a glyph. They cannot reorder or conceal text.
        //
        // Refusing them would reject an ordinary mail body — 👨‍👩‍👧 is three people joined by
        // U+200D, and ❤️ is U+2764 plus U+FE0F.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val heart = "\u2764\uFE0F"
        assertNull(HiddenText.firstHidden(family))
        assertNull(HiddenText.firstHidden(heart))
        assertNull(HiddenText.firstHidden("shipped 🚀"))
        assertFalse(HiddenText.isHidden(0x200D))
        assertFalse(HiddenText.isHidden(0xFE0F))
    }

    @Test
    fun `a JSON-escaped hidden character is still found`() {
        // **The bug this test exists for, found by running the guard against a real client.**
        // Python's `json.dumps` writes U+202E as six ASCII characters — `\u202e` — so a scan of the
        // raw request bytes sees an escape sequence and passes it. `org.json`'s own `quote()` does
        // the same on the way back out for C0 controls, so scanning `toString()` is no better.
        //
        // The value has to be read as a VALUE, after parsing, which is what the walker does. This
        // asserts the character survives the round trip `JSONObject` performs, so the scan will
        // see it.
        val parsed = org.json.JSONObject("{\"text\":\"rm -rf ~/\\u202estcejorP\"}")
        val text = parsed.getString("text")
        assertEquals(0x202E, HiddenText.firstHidden(text))
    }

    @Test
    fun `ordinary text passes`() {
        assertNull(HiddenText.firstHidden("rm -rf ./build\nmake -j4\nls -la ~/"))
        assertNull(HiddenText.firstHidden("Send the invoice to Ahmed — subject: Q4 numbers"))
        assertNull(HiddenText.firstHidden(""))
    }

    @Test
    fun `a lone surrogate is refused`() {
        // An unpaired half is not a character; a renderer may drop it or draw a box, either way
        // what is on screen is not what was sent.
        assertTrue(HiddenText.isHidden(0xD800))
        assertEquals(0xD800, HiddenText.firstHidden("abc\uD800def"))
    }

    @Test
    fun `the refusal names the code point`() {
        // "invalid characters" sends a caller hunting. The exact code point does not.
        val message = HiddenText.message(0x202E, "the request body")
        assertTrue(message, message.contains("U+202E"))
        assertTrue(message, message.contains("the request body"))
    }

    @Test
    fun `astral hidden characters are found, not just BMP ones`() {
        // Tag characters sit above the BMP: U+E0041 is TAG LATIN CAPITAL LETTER A. A scan that
        // walked `text[i]` as a Char would still find it, but one that mis-advanced by one would
        // desynchronise on the surrogate pair before it.
        assertTrue(HiddenText.isHidden(0xE0041))
        assertEquals(0xE0041, HiddenText.firstHidden("ok\uDB40\uDC41"))
    }
}
