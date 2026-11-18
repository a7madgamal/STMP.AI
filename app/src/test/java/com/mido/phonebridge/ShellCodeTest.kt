package com.mido.phonebridge

import com.mido.phonebridge.ui.ShellCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ShellCodeTest {

    private fun text(cmd: String): String =
        ShellCode.render(cmd).toString()

    /** The insert-only rule: strip inserted newlines, get the original back. */
    private fun stripBreaks(rendered: String, original: String): String {
        val b = StringBuilder()
        var i = 0
        var j = 0
        while (j < rendered.length && i < original.length) {
            if (rendered[j] == '\n' && original[i] != '\n') j++
            else { b.append(rendered[j]); i++; j++ }
        }
        return b.toString()
    }

    @Test
    fun `a single command is not broken`() {
        assertEquals("ls -la /tmp", text("ls -la /tmp"))
    }

    @Test
    fun `separators break onto new lines`() {
        // The space after `;` stays. It is a real character and the renderer may only
        // INSERT — trimming it here would be the first step down a road that ends with a
        // card showing something other than what runs.
        assertEquals("cd /tmp;\n pwd &&\n ls", text("cd /tmp; pwd && ls"))
    }

    @Test
    fun `a semicolon inside quotes does not break`() {
        // The whole reason this is a parse and not split(";"): splitting here would
        // silently cut a quoted string in half on the approval card.
        assertTrue(!text("awk -F';' '{print \$1}'").contains("';\n"))
    }

    @Test
    fun `rendering only inserts newlines`() {
        listOf(
            "cd /tmp; pwd && ls",
            "echo \"x; sudo y\"",
            "cat <foo 2>&1 | grep x",
            "A=(a b c); ls",
        ).forEach { cmd ->
            assertEquals("rendered form must be the original plus breaks", cmd, stripBreaks(text(cmd), cmd))
        }
    }

    @Test
    fun `colour spans land on the right characters after breaks`() {
        val rendered = ShellCode.render("cd /tmp; ls")
        val spans = (rendered as android.text.Spanned).getSpans(0, rendered.length, android.text.style.ForegroundColorSpan::class.java)
        assertTrue("expected coloured spans", spans.isNotEmpty())
        // No span may be zero-length or run past the buffer.
        spans.forEach { s ->
            val start = rendered.getSpanStart(s)
            val end = rendered.getSpanEnd(s)
            assertTrue("span start $start", start in 0 until rendered.length)
            assertTrue("span end $end", end in start..rendered.length)
        }
    }

    /**
     * The indent a break creates is collapsed for DRAWING, and the characters survive.
     *
     * Both halves matter, and they are the two ways this could go wrong: a trim that edits the
     * string would change the command a human approved, and a trim that only claims to work would
     * leave the phantom indent on screen (which no assertion on the string would ever catch).
     */
    @Test
    fun `a break's leading space is collapsed for drawing but kept in the text`() {
        val cmd = "echo a; echo b"
        val rendered = ShellCode.render(cmd)

        // 1. STRING FIDELITY: the buffer still holds every original character, in order.
        assertEquals(cmd, stripBreaks(rendered.toString(), cmd))

        // 2. The space after the break is still literally there.
        assertTrue(
            "the space the caller sent must still be in the rendered text",
            rendered.toString().contains(";\n "),
        )

        // 3. DRAWING: that run carries exactly one zero-width span, and it covers only whitespace.
        val spans = (rendered as android.text.Spanned)
            .getSpans(0, rendered.length, com.mido.phonebridge.ui.ZeroWidthSpan::class.java)
        assertEquals("exactly one break indent in this command", 1, spans.size)
        val span = spans.first()
        val start = rendered.getSpanStart(span)
        val end = rendered.getSpanEnd(span)
        assertEquals("the run must be collapsed before the next token but not include it", " ", rendered.subSequence(start, end).toString())
        assertEquals("echo", rendered.subSequence(end, minOf(end + 4, rendered.length)).toString())
    }

    /** The first line keeps its indent: that one came from the caller, not from a break. */
    @Test
    fun `a deliberate first-line indent is not trimmed`() {
        val cmd = "  echo indented; ls"
        val rendered = ShellCode.render(cmd)
        val spans = (rendered as android.text.Spanned)
            .getSpans(0, rendered.length, com.mido.phonebridge.ui.ZeroWidthSpan::class.java)
        val starts = spans.map { rendered.getSpanStart(it) }
        assertTrue("the leading run of line one must not be collapsed", starts.none { it == 0 })
        assertEquals(cmd, stripBreaks(rendered.toString(), cmd))
    }

    /**
     * A dangerous token after a break still underlines the characters actually drawn.
     *
     * The file documents this exact off-by-N: a range computed against the original and applied to
     * the displayed string underlines the wrong characters by the number of breaks before it. A
     * zero-width span must not disturb that mapping — a zero advance moves nothing — so this pins
     * both together.
     */
    @Test
    fun `a danger underline after a break lands on the right characters`() {
        val cmd = "cd /tmp; rm -rf build"
        val rendered = ShellCode.render(cmd, dangerUnderlinePx = 2f)
        val spans = (rendered as android.text.Spanned).getSpans(
            0, rendered.length, com.mido.phonebridge.ui.DangerUnderlineSpan::class.java,
        )
        assertEquals("one dangerous token", 1, spans.size)
        val span = spans.first()
        val drawn = rendered.subSequence(rendered.getSpanStart(span), rendered.getSpanEnd(span)).toString()
        assertEquals("the underline must cover the token, not a shifted window", "rm -rf", drawn)
    }

    /** A 400-character command is drawn whole — the scroller handles the width, not a truncation. */
    @Test
    fun `a very long command keeps every character`() {
        val cmd = "echo " + "a".repeat(400)
        val rendered = ShellCode.render(cmd)
        assertTrue(
            "a long command must not be truncated or wrapped into a different string",
            rendered.toString().contains("a".repeat(400)),
        )
        assertEquals(cmd, stripBreaks(rendered.toString(), cmd))
    }

    @Test
    fun `the palette covers every key the library can emit`() {        // A missing key would silently render in the fallback colour; this fails instead.
        val emitted = setOf(
            com.mido.phonebridge.ui.ShellCode.regionsOf("sudo -E ls --long 'q' \"d\" $(id) A=1 2>/dev/null | grep x # c")
                .map { it.styleKey },
        ).flatten()
        val palette = ShellCode.palette()
        val missing = emitted.filter { it !in palette }
        assertTrue("palette is missing keys: $missing", missing.isEmpty())
    }
}
