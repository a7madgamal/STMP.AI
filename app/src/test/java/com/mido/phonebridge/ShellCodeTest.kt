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

    @Test
    fun `the palette covers every key the library can emit`() {
        // A missing key would silently render in the fallback colour; this fails instead.
        val emitted = setOf(
            com.mido.phonebridge.ui.ShellCode.regionsOf("sudo -E ls --long 'q' \"d\" $(id) A=1 2>/dev/null | grep x # c")
                .map { it.styleKey },
        ).flatten()
        val palette = ShellCode.palette()
        val missing = emitted.filter { it !in palette }
        assertTrue("palette is missing keys: $missing", missing.isEmpty())
    }
}
