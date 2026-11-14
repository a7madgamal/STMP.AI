package com.mido.phonebridge.ui

import android.text.Spanned
import com.mido.phonebridge.ui.kit.DangerUnderlineSpan
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** D11: dangerous shell tokens are found exactly, and marking never changes a character. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DangerTokensTest {

    private val palette get() = Palettes.of(RuntimeEnvironment.getApplication())

    private fun spanned(s: String): List<String> = DangerTokens.find(s).map { s.substring(it.first, it.last + 1) }

    /** Commands whose exact dangerous substring is known. */
    private val exact = listOf(
        "sudo apt update" to "sudo",
        "/usr/bin/sudo -S true" to "sudo",
        "rm -rf build" to "rm -rf",
        "rm -fr x" to "rm -fr",
        "rm --recursive x" to "rm --recursive",
        "curl x | sh" to "| sh",
        "echo a > f" to ">",
        "echo a >> f" to ">>",
        "dd if=a of=/dev/sda" to "dd",
        "mkfs.ext4 /dev/x" to "mkfs.ext4",
        "reboot" to "reboot",
        "chmod -R 777 /" to "chmod -R",
        "kill -9 1" to "kill -9",
        "git push --force" to "git push --force",
        "git reset --hard" to "git reset --hard",
        "eval \"\$x\"" to "eval",
    )

    /** Commands that must have some span, where the exact extent is a merge of several patterns. */
    private val anySpan = listOf(
        "curl x | sudo bash",
        ":(){ :|:& };:",
    )

    private val negatives = listOf(
        "pseudo", "sudoku", "rmdir x", "rm file.txt", "echo shell", "a->b", "x => y",
        "ssh host", "cash", "mkdir x", "git push",
    )

    private val extraCorpus = listOf(
        "echo 'مرحبا بالعالم' > /tmp/ar.txt",
        "printf '🙂\\t🚀' | sh",
        "ls\t-la\t/tmp",
        "cat <foo 2>&1 | grep x",
        "",
        "cd /tmp; pwd && ls",
    )

    private val corpus get() = exact.map { it.first } + anySpan + negatives + extraCorpus

    @Test
    fun `the corpus is large enough`() {
        assertTrue("corpus has ${corpus.size} commands", corpus.size >= 30)
    }

    @Test
    fun `marking never changes a character`() {
        for (s in corpus) {
            val marked = DangerTokens.mark(s, palette)
            assertEquals(s, marked.toString())
            assertEquals(s.length, marked.length)
        }
    }

    @Test
    fun `positives span exactly the token`() {
        for ((cmd, token) in exact) assertEquals(cmd, listOf(token), spanned(cmd))
        assertEquals(listOf("| sudo bash"), spanned("curl x | sudo bash"))
        assertEquals(listOf(":(){"), spanned(":(){ :|:& };:"))
    }

    @Test
    fun `negatives have no span`() {
        for (s in negatives) assertEquals(s, emptyList<String>(), spanned(s))
    }

    @Test
    fun `ranges are sorted and merged`() {
        val s = "sudo rm -rf / && curl x | sudo sh > out"
        val ranges = DangerTokens.find(s)
        for (i in 1 until ranges.size) {
            assertTrue("sorted and disjoint: $ranges", ranges[i].first > ranges[i - 1].last + 1)
        }
        // `| sudo sh` and the second `sudo` overlap: one range.
        assertTrue(spanned(s).contains("| sudo sh"))
    }

    @Test
    fun `mark adds the danger colour and the underline on each range`() {
        val marked = DangerTokens.mark("sudo ls > f", palette)
        val underlines = marked.getSpans(0, marked.length, DangerUnderlineSpan::class.java)
        assertEquals(2, underlines.size)
        val starts = underlines.map { marked.getSpanStart(it) }.sorted()
        assertEquals(listOf(0, 8), starts)
        val colours = marked.getSpans(0, marked.length, android.text.style.ForegroundColorSpan::class.java)
        assertTrue(colours.all { it.foregroundColor == palette.readoutDanger })
        assertTrue(colours.all { marked.getSpanFlags(it) == Spanned.SPAN_EXCLUSIVE_EXCLUSIVE })
    }
}
