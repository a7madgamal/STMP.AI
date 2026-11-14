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

/** The gate's readout form of a command: insert-only, readout colours, danger underlines (D11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShellCodeReadoutTest {

    private val p get() = Palettes.of(RuntimeEnvironment.getApplication())

    private val corpus = listOf(
        "ls -la /tmp",
        "cd /tmp; pwd && ls",
        "echo \"x; sudo y\"",
        "cat <foo 2>&1 | grep x",
        "A=(a b c); ls",
        "awk -F';' '{print \$1}'",
        "sudo apt update",
        "rm -rf build && ./gradlew :app:assembleDebug --no-daemon | tee log > out.txt",
        "curl x | sudo bash",
        "echo a >> f; reboot",
        "git push --force; git reset --hard",
        "dd if=a of=/dev/sda",
        "echo 'مرحبا' > /tmp/x; kill -9 1",
    )

    /** Drops exactly the newlines [ShellCode.render] inserted. */
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

    private fun underlines(cmd: String): List<String> {
        val r = ShellCode.renderForReadout(cmd, p) as Spanned
        return r.getSpans(0, r.length, DangerUnderlineSpan::class.java)
            .sortedBy { r.getSpanStart(it) }
            .map { r.subSequence(r.getSpanStart(it), r.getSpanEnd(it)).toString().replace("\n", "") }
    }

    @Test
    fun `the readout form only inserts line breaks`() {
        for (cmd in corpus) {
            val rendered = ShellCode.renderForReadout(cmd, p).toString()
            assertEquals(cmd, cmd, stripBreaks(rendered, cmd))
        }
    }

    @Test
    fun `every underline covers exactly its token`() {
        for (cmd in corpus) {
            val expected = DangerTokens.find(cmd).map { cmd.substring(it.first, it.last + 1) }
            val seen = underlines(cmd)
            assertTrue("$cmd: $seen ⊄ $expected", expected.containsAll(seen))
        }
        assertEquals(
            listOf("rm -rf", ">"),
            underlines("rm -rf build && ./gradlew :app:assembleDebug --no-daemon | tee log > out.txt"))
    }

    @Test
    fun `a token inside quotes is not underlined`() {
        assertEquals(emptyList<String>(), underlines("echo \"a > b\""))
        assertEquals(listOf(">"), underlines("echo a > b"))
    }

    @Test
    fun `the readout palette covers the same keys as the light palette`() {
        assertEquals(ShellCode.palette().keys, ShellCode.readoutPalette(p).keys)
    }

    @Test
    fun `the readout palette uses only readout roles`() {
        val roles = setOf(p.readoutText, p.readoutCommand, p.readoutMuted, p.readoutDanger, p.readoutString)
        assertTrue(ShellCode.readoutPalette(p).values.all { it in roles })
    }
}
