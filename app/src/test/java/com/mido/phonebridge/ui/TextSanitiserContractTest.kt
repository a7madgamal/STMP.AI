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

    private fun jsonRanges(): List<IntRange> = json.getJSONArray("escapedRanges").let { a ->
        (0 until a.length()).map { a.getJSONArray(it).getInt(0)..a.getJSONArray(it).getInt(1) }
    }

    @Test
    fun `needsEscape matches the contract ranges for every code point`() {
        val ranges = jsonRanges()
        val never = setOf(10, 9)
        // Linear scan over the JSON: independent of the binary search and of Character.getType.
        for (cp in 0..0x10FFFF) {
            val expected = cp !in never && ranges.any { cp in it }
            if (expected != UntrustedText.needsEscape(cp)) {
                throw AssertionError("U+%04X: contract says %b".format(cp, expected))
            }
        }
    }

    @Test
    fun `the contract states its Unicode version and no longer names categories`() {
        assertEquals(2, json.getInt("version"))
        assertEquals("15.1", json.getString("unicodeVersion"))
        assertFalse(json.has("escapedCategories"))
    }

    @Test
    fun `the contract ranges are sorted, merged and leave tab and newline out`() {
        val ranges = jsonRanges()
        for ((i, r) in ranges.withIndex()) {
            assertTrue("empty range", r.first <= r.last)
            if (i > 0) assertTrue("range %d not sorted or not merged".format(i), r.first > ranges[i - 1].last + 1)
        }
        assertFalse(ranges.any { 9 in it || 10 in it })
        assertTrue(ranges.any { 0xD800 in it && 0xDFFF in it })
    }

    @Test
    fun `the generated Kotlin table equals the contract ranges`() {
        val ranges = jsonRanges()
        assertEquals(ranges.map { it.first }, TextSanitiserTable.STARTS.toList())
        assertEquals(ranges.map { it.last }, TextSanitiserTable.ENDS.toList())
        assertEquals(json.getString("unicodeVersion"), TextSanitiserTable.UNICODE_VERSION)
    }

    @Test
    fun `escape never consults the runtime's own Unicode tables for the characters that differ by version`() {
        for (cp in listOf(0x0890, 0x0891, 0x13430, 0x1343F, 0x1BCA0)) {
            assertEquals("a\\u{%04X}b".format(cp), UntrustedText.escape("a" + String(Character.toChars(cp)) + "b"))
        }
    }

    @Test
    fun `system surface rule is the published one and matches every vector`() {
        val rule = json.getJSONObject("systemSurface")
        assertTrue(rule.getBoolean("escapeNewlineTab"))
        assertEquals(UntrustedText.SYSTEM_MAX_CODE_POINTS, rule.getInt("maxCodePoints"))
        assertEquals("\u2026(+%d chars)", rule.getString("overflowSuffix"))
        val vectors = json.getJSONArray("systemSurfaceVectors")
        assertTrue(vectors.length() >= 10)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            assertEquals(v.getString("name"), v.getString("expected"), UntrustedText.escapeForSystemSurface(codePoints(v.getJSONArray("input"))))
        }
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
    fun `vectors cover the versioned code points and the string-end surrogates`() {
        val inputs = json.getJSONArray("vectors").let { a -> (0 until a.length()).map { codePoints(a.getJSONObject(it).getJSONArray("input")) } }
        for (cp in listOf(0x0890, 0x13430, 0x1BCA0)) {
            assertTrue("no vector for U+%04X".format(cp), inputs.any { s -> s.codePoints().anyMatch { it == cp } })
        }
        assertTrue(inputs.any { it.isNotEmpty() && it.last() == '\uD800' })
        assertTrue(inputs.any { it.isNotEmpty() && it.last() == '\uDFFF' })
    }
}
