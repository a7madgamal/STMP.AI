package com.mido.phonebridge.kit

import com.mido.phonebridge.ui.ShellCode
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.ReadoutText
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.widget.TextView
import android.view.View

/**
 * The verbatim readout (R1): wraps at whitespace with a continuation mark, copies the command and
 * not its wrapped picture, and says what a height cap hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadoutWrapTest : KitTest() {

    private val long = "cp -r ~/src/module-0 /mnt/backup/ && cp -r ~/src/module-37 /mnt/backup/ && " +
        "cp -r ~/src/module-75 /mnt/backup/ && cp -r ~/src/module-113 /mnt/backup/"

    @Test
    fun `a long command wraps and every soft wrap is remembered for the glyph`() {
        val a = host()
        val h = Readout.create(a, long, Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        assertTrue("expected soft wraps", text.insertedAt.isNotEmpty())
        // Only the inserted characters differ from the command.
        val skip = text.insertedAt.toSet()
        val shown = text.text.filterIndexed { i, _ -> i !in skip }.toString()
        assertEquals(long, shown)
    }

    @Test
    fun `a break that cuts a token is flagged so it draws another glyph than a whitespace wrap`() {
        val a = host()
        val url = "curl -fsSL https://downloads.example.com/releases/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/installer.sh"
        val h = Readout.create(a, url, Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        assertEquals(text.insertedAt.size, text.insertedHard.size)
        for (i in text.insertedAt.indices) {
            assertEquals(!text.text[text.insertedAt[i] - 1].isWhitespace(), text.insertedHard[i])
        }
    }

    @Test
    fun `each wrap sits right after whitespace`() {
        val a = host()
        val h = Readout.create(a, long, Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        for (at in text.insertedAt) assertTrue(text.text[at - 1].isWhitespace())
    }

    /** The platform must never add a wrap of its own: lines = logical lines + our breaks. */
    private fun assertNoForeignWrap(text: ReadoutText, source: String) {
        val logical = source.count { it == '\n' } + 1
        assertEquals(logical + text.insertedAt.size, text.layout.lineCount)
        val skip = text.insertedAt.toSet()
        assertEquals(source, text.text.filterIndexed { i, _ -> i !in skip }.toString())
    }

    @Test
    fun `Arabic, CJK and emoji never make the platform wrap silently`() {
        val samples = listOf(
            "send --subject \"مرحبا بالعالم هذا موضوع طويل جدا للرسالة\" --body \"تحقق من الطلب قبل الموافقة عليه\"",
            "contact set 你好世界你好世界你好世界你好世界你好世界你好世界你好世界你好世界 done",
            "post \uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00 end of message that keeps going and going",
            "tab\tseparated\tvalues\twith\ttabs\tand a long tail that must wrap at spaces only please ok"
        )
        for (src in samples) {
            val a = host()
            val h = Readout.create(a, src, Readout.Kind.COMMAND)
            a.place(h.root)
            assertNoForeignWrap(h.text as ReadoutText, src)
        }
    }

    @Test
    fun `an Arabic command keeps every line left-to-right with room for the glyph`() {
        val src = "send --subject \"مرحبا بالعالم هذا موضوع طويل جدا للرسالة\" --body \"تحقق من الطلب قبل الموافقة عليه\""
        val a = host()
        val h = Readout.create(a, src, Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        val l = text.layout
        assertTrue(text.insertedAt.isNotEmpty())
        val glyph = text.paint.measureText(com.mido.phonebridge.ui.SoftWrap.GLYPH)
        val limit = text.width - text.totalPaddingRight - text.totalPaddingLeft - glyph
        for (line in 0 until l.lineCount) {
            assertEquals(android.text.Layout.DIR_LEFT_TO_RIGHT, l.getParagraphDirection(line))
        }
        for (at in text.insertedAt) {
            val line = l.getLineForOffset(at)
            assertTrue("line $line too wide", l.getLineRight(line) <= limit + 0.5f)
        }
    }

    @Test
    fun `lines stay left-aligned and clear of the glyph when the layout direction is right-to-left`() {
        val a = host()
        val h = Readout.create(a, long, Readout.Kind.COMMAND)
        val text = h.text as ReadoutText
        text.layoutDirection = View.LAYOUT_DIRECTION_RTL
        a.place(h.root)
        val l = text.layout
        assertTrue(text.insertedAt.isNotEmpty())
        val glyph = text.paint.measureText(com.mido.phonebridge.ui.SoftWrap.GLYPH)
        val limit = text.width - text.totalPaddingRight - text.totalPaddingLeft - glyph
        for (at in text.insertedAt) {
            val line = l.getLineForOffset(at)
            assertEquals("line $line not at left edge", 0f, l.getLineLeft(line), 0.5f)
            assertTrue("line $line reaches the glyph", l.getLineRight(line) <= limit + 0.5f)
        }
    }

    @Test
    fun `an IPv6 address at a very narrow width is broken with the glyph, not by the platform`() {
        val ip = "fd7a:115c:a1e0:ab12:4843:cd96:6258:2f01"
        val a = host()
        val h = Readout.create(a, ip, Readout.Kind.DISPLAY, softWrap = true)
        a.place(h.root)
        val text = h.text as ReadoutText
        assertTrue(text.insertedAt.isNotEmpty())
        assertNoForeignWrap(text, ip)
    }

    @Test
    fun `a short command is not touched`() {
        val a = host()
        val h = Readout.create(a, "ls -la", Readout.Kind.COMMAND)
        a.place(h.root)
        assertTrue((h.text as ReadoutText).insertedAt.isEmpty())
        assertEquals("ls -la", h.text.text.toString())
    }

    @Test
    fun `the readout form of a command adds no separator breaks of its own`() {
        val p = Palettes.of(host())
        val cmd = "rm -rf build && ./gradlew assembleDebug | tee log > out.txt"
        assertEquals(cmd, ShellCode.renderForReadout(cmd, p).toString())
    }

    @Test
    fun `a capped readout says how many lines it hides`() {
        val a = host()
        val tall = (1..120).joinToString("\n") { "echo line $it" }
        val h = Readout.create(a, tall, Readout.Kind.COMMAND)
        a.place(h.root)
        val more = h.root.allViews().filterIsInstance<TextView>().first { it.text.startsWith("+") }
        assertTrue(more.text.toString(), more.text.contains("more lines"))
        assertEquals(View.VISIBLE, more.visibility)
    }

    @Test
    fun `a readout that fits shows no more-lines row`() {
        val a = host()
        val h = Readout.create(a, "ls -la", Readout.Kind.COMMAND)
        a.place(h.root)
        assertFalse(h.root.allViews().filterIsInstance<TextView>().any { it.text.startsWith("+") && it.visibility == View.VISIBLE })
    }

    @Test
    fun `more-lines copy is singular for one`() {
        assertEquals("+1 more line · scroll to read", Readout.moreLines(1))
        assertEquals("+7 more lines · scroll to read", Readout.moreLines(7))
    }
}
