package com.mido.phonebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval card must show exactly what will run (R1). These pin the wrap rules: whitespace
 * only, danger tokens and addresses never cut mid-token, and every other character untouched.
 */
class SoftWrapTest {

    /** [text] with a `|` where each display-only break goes, one visual line per `\n`. */
    private fun wrapped(text: String, columns: Int, keep: List<IntRange> = emptyList()): List<String> {
        val cuts = SoftWrap.breaks(text, columns, keep)
        val sb = StringBuilder(text)
        for (i in cuts.indices.reversed()) sb.insert(cuts[i], "\n")
        return sb.toString().split("\n")
    }

    @Test
    fun `a line that fits is left alone`() {
        assertEquals(emptyList<Int>(), SoftWrap.breaks("ls -la ~/builds", 30))
    }

    @Test
    fun `an existing newline ends a line and is never counted twice`() {
        assertEquals(emptyList<Int>(), SoftWrap.breaks("echo one\necho two", 12))
    }

    @Test
    fun `breaks only after whitespace`() {
        val text = "cp -r ~/src/module-0 /mnt/backup/ && cp -r ~/src/module-37 /mnt/backup/"
        for (line in wrapped(text, 30).dropLast(1)) {
            assertTrue("line '$line' must end in whitespace", line.last().isWhitespace())
        }
    }

    @Test
    fun `every line leaves a column for the continuation glyph`() {
        val text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        for (line in wrapped(text, 20).dropLast(1)) assertTrue(line.length <= 19)
    }

    @Test
    fun `removing the inserted breaks gives back the original, character for character`() {
        val text = "curl -fsSL https://example.com/install.sh | sudo bash -s -- --yes && echo done > /tmp/out.txt"
        for (cols in 8..60) {
            val cuts = SoftWrap.breaks(text, cols)
            val sb = StringBuilder(text)
            for (i in cuts.indices.reversed()) sb.insert(cuts[i], "\n")
            var shown = sb.toString()
            // Drop exactly the inserted newlines (the text has none of its own).
            assertEquals("columns=$cols", text, shown.replace("\n", ""))
        }
    }

    @Test
    fun `a URL that fits a line is never split`() {
        val url = "https://example.com/a/very/long/path/that/fits"
        val lines = wrapped("curl -s $url -o out", 50)
        assertTrue(lines.any { url in it })
    }

    @Test
    fun `a danger token is kept whole across the wrap`() {
        val text = "tar czf backup.tgz /var/lib/data && rm -rf /var/lib/data/old"
        val start = text.indexOf("rm -rf")
        val keep = listOf(start until start + "rm -rf".length)
        for (cols in 12..40) {
            val lines = wrapped(text, cols, keep)
            assertTrue("columns=$cols: ${lines}", lines.any { "rm -rf" in it })
        }
    }

    @Test
    fun `a token wider than the line breaks with room for the glyph`() {
        val token = "x".repeat(50)
        val lines = wrapped(token, 20)
        assertTrue(lines.size > 1)
        for (line in lines.dropLast(1)) assertEquals(19, line.length)
        assertEquals(token, lines.joinToString(""))
    }

    @Test
    fun `an IPv6 address is cut only after a colon so no hextet is split`() {
        val ip = "fd7a:115c:a1e0:ab12:4843:cd96:6258:2f01"
        val hextets = ip.split(":")
        for (cols in 12..30) {
            val lines = wrapped(ip, cols)
            val joined = lines.joinToString("")
            assertEquals(ip, joined)
            for (line in lines.dropLast(1)) {
                assertTrue("columns=$cols line '$line' must end in ':'", line.endsWith(":"))
            }
            assertTrue(lines.flatMap { it.split(":") }.filter { it.isNotEmpty() }.all { it in hextets })
        }
    }

    @Test
    fun `too few columns leaves the text to the platform`() {
        assertEquals(emptyList<Int>(), SoftWrap.breaks("a very long line indeed, longer than the width", 4))
    }

    @Test
    fun `an empty text has no breaks`() {
        assertEquals(emptyList<Int>(), SoftWrap.breaks("", 20))
    }
}
