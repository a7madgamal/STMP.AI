package com.mido.phonebridge.ui

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `contract/text-sanitiser.json` is the source of truth shared with iOS; the sanitiser must match it. */
@RunWith(RobolectricTestRunner::class)
class TextSanitiserContractTest {

    private val file: File = run {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "contract/text-sanitiser.json").isFile) dir = dir.parentFile
        File(requireNotNull(dir) { "contract/ not found above user.dir" }, "contract/text-sanitiser.json")
    }
    private val json = JSONObject(file.readText(Charsets.UTF_8))

    private fun codePoints(a: JSONArray): String =
        StringBuilder().apply { for (i in 0 until a.length()) appendCodePoint(a.getInt(i)) }.toString()

    @Test
    fun `contract holds at least 40 vectors and the published token shape`() {
        assertTrue(json.getJSONArray("vectors").length() >= 40)
        assertEquals(1, json.getInt("version"))
        val token = json.getJSONObject("token")
        assertEquals("\\u{XXXX}", token.getString("format"))
        assertEquals(4, token.getInt("minHexDigits"))
        assertEquals("upper", token.getString("hexCase"))
        assertEquals(setOf(10, 9), (0 until 2).map { json.getJSONArray("neverEscaped").getInt(it) }.toSet())
        val glyphs = json.getJSONObject("wrapGlyphs")
        assertEquals(SoftWrap.GLYPH.codePointAt(0), glyphs.getInt("soft"))
        assertEquals(SoftWrap.GLYPH_HARD.codePointAt(0), glyphs.getInt("hard"))
    }

    @Test
    fun `escape matches every vector`() {
        val vectors = json.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val actual = UntrustedText.escape(codePoints(v.getJSONArray("input")), v.getBoolean("escapeWrapGlyphs"))
            assertEquals(v.getString("name"), v.getString("expected"), actual)
        }
    }

    private fun categoryNames(): Set<Int> {
        val map = mapOf(
            "Cc" to Character.CONTROL, "Cf" to Character.FORMAT, "Zl" to Character.LINE_SEPARATOR,
            "Zp" to Character.PARAGRAPH_SEPARATOR, "Cs" to Character.SURROGATE,
        )
        val a = json.getJSONArray("escapedCategories")
        return (0 until a.length()).map { map.getValue(a.getString(it)).toInt() }.toSet()
    }

    @Test
    fun `needsEscape matches the contract for every code point`() {
        val cats = categoryNames()
        val ranges = json.getJSONArray("escapedRanges").let { a ->
            (0 until a.length()).map { a.getJSONArray(it).getInt(0)..a.getJSONArray(it).getInt(1) }
        }
        val never = setOf(10, 9)
        for (cp in 0..0x10FFFF) {
            val expected = cp !in never && (Character.getType(cp) in cats || ranges.any { cp in it })
            if (expected != UntrustedText.needsEscape(cp)) {
                throw AssertionError("U+%04X: contract says %b".format(cp, expected))
            }
        }
        assertEquals(UntrustedText.EXTRA_RANGES, ranges)
    }

    @Test
    fun `the contract file holds no literal invisible character`() {
        val text = String(file.readBytes(), Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val bareWhitespace = cp == '\n'.code || cp == ' '.code
            assertFalse("literal U+%04X at %d".format(cp, i), !bareWhitespace && UntrustedText.needsEscape(cp))
            assertTrue("non-ASCII literal U+%04X at %d".format(cp, i), cp < 0x80)
            i += Character.charCount(cp)
        }
    }

    @Test
    fun `vectors cover each default-ignorable range`() {
        val inputs = json.getJSONArray("vectors").let { a -> (0 until a.length()).map { codePoints(a.getJSONObject(it).getJSONArray("input")) } }
        for (r in UntrustedText.EXTRA_RANGES) {
            assertTrue("no vector touches %X".format(r.first), inputs.any { s -> s.codePoints().anyMatch { it in r } })
        }
    }
}
