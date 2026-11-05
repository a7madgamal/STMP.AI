package com.mido.phonebridge

import com.mido.phonebridge.ui.JsonCode
import com.mido.phonebridge.ui.JsonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonCodeTest {

    @Test
    fun `escaped slashes are gone`() {
        // The artifact that made a URL unreadable. `/` never needs escaping in JSON.
        assertEquals(
            "https://api.todoist.com/rest/v2/tasks",
            JsonCode.present("""{"url":"https://api.todoist.com/rest/v2/tasks"}""")
                .lines().first { "url" in it }
                .substringAfter(": ")
                .trim('"', ',')
        )
    }

    @Test
    fun `other escapes are left alone`() {
        // `\n` and `\"` carry meaning: decoding them would rewrite the document.
        val out = JsonCode.present("""{"a":"line\nbreak","b":"quote\""}""")
        assertTrue("newline escape preserved", out.contains("\\n"))
        assertTrue("quote escape preserved", out.contains("\\\""))
    }

    @Test
    fun `objects are pretty printed`() {
        val out = JsonCode.present("""{"a":1,"b":{"c":2}}""")
        assertTrue("multi-line", out.lines().size > 3)
        assertTrue("indented", out.lines().any { it.startsWith("  ") })
    }

    @Test
    fun `arrays are pretty printed`() {
        val out = JsonCode.present("""[1,2,3]""")
        assertEquals(5, out.lines().size) // [ 1 2 3 ]
    }

    @Test
    fun `a non-json body is still shown`() {
        // The detach poll output is JSON, but a body need not be. Failing to render one
        // because it is not JSON would hide more than it tidies.
        val raw = "plain text, not json"
        assertEquals(raw, JsonCode.present(raw))
    }

    @Test
    fun `a broken json body is shown as-is rather than thrown away`() {
        val raw = """{"a": }"""
        assertEquals(raw, JsonCode.present(raw))
    }

    @Test
    fun `keys and values are distinguished`() {
        val spans = JsonCode.tokenize("""{"name": "value"}""")
        val keys = spans.filter { it.kind == JsonKind.KEY }
        val strings = spans.filter { it.kind == JsonKind.STRING }
        assertEquals(1, keys.size)
        assertEquals(1, strings.size)
    }

    @Test
    fun `numbers and literals are found`() {
        val spans = JsonCode.tokenize("""{"n": -12.5e3, "b": true, "z": null}""")
        assertEquals(1, spans.count { it.kind == JsonKind.NUMBER })
        assertEquals(2, spans.count { it.kind == JsonKind.LITERAL })
    }

    @Test
    fun `every character is accounted for by a span or is whitespace`() {
        // A scanner that stalls would colour half a document and look plausible.
        val text = JsonCode.present("""{"a":[1,true,null,"x"],"b":{"c":2}}""")
        val covered = BooleanArray(text.length)
        JsonCode.tokenize(text).forEach { s ->
            for (i in s.start until s.end) covered[i] = true
        }
        text.forEachIndexed { i, c ->
            if (!c.isWhitespace()) assertTrue("uncovered char '$c' at $i", covered[i])
        }
    }

    @Test
    fun `a string containing a colon is not mistaken for a key`() {
        // `"a: b"` as a VALUE — the lookahead must skip whitespace and check the real next char.
        val spans = JsonCode.tokenize("""{"k": "a: b"}""")
        assertEquals(1, spans.count { it.kind == JsonKind.KEY })
        assertEquals(1, spans.count { it.kind == JsonKind.STRING })
    }

    @Test
    fun `rendering produces a spannable with colours`() {
        val rendered = JsonCode.render("""{"a":1}""")
        val spans = (rendered as android.text.Spanned).getSpans(
            0, rendered.length, android.text.style.ForegroundColorSpan::class.java
        )
        assertTrue("expected colours", spans.isNotEmpty())
    }

    @Test
    fun `looksJson only claims objects and arrays`() {
        assertTrue(JsonCode.looksJson("""{"a":1}"""))
        assertTrue(JsonCode.looksJson("""[1]"""))
        assertFalse(JsonCode.looksJson("hello"))
        assertFalse(JsonCode.looksJson("123"))
    }
}
