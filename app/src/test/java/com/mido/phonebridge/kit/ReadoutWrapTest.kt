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
    fun `each wrap sits right after whitespace`() {
        val a = host()
        val h = Readout.create(a, long, Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        for (at in text.insertedAt) assertTrue(text.text[at - 1].isWhitespace())
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
