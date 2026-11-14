package com.mido.phonebridge.kit

import android.view.Menu
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.ReadoutText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FIX4: the readout draws what was sent, marks a failed fit, and lets a selection out only through Copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadoutUntrustedTest : KitTest() {

    @Test
    fun `a bidi control in a command is drawn as a token, never as the control`() {
        val a = host()
        val h = Readout.create(a, "echo ok ‮ txt.exe", Readout.Kind.COMMAND)
        a.place(h.root)
        val shown = (h.text as ReadoutText).verbatimText().toString()
        assertEquals("echo ok \\u{202E} txt.exe", shown)
        assertFalse(h.text.text.contains('‮'))
    }

    @Test
    fun `a literal wrap glyph in a command is escaped so it cannot pass for an inserted wrap`() {
        val a = host()
        val h = Readout.create(a, "echo a↵b ↩", Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        assertEquals("echo a\\u{21B5}b \\u{21A9}", text.verbatimText().toString())
        assertFalse(text.text.contains('↵') || text.text.contains('↩'))
    }

    @Test
    fun `the readout prompt and a non-wrapping body are escaped too`() {
        val a = host()
        val h = Readout.create(a, "a​b", Readout.Kind.BODY, prompt = "POST /x‮")
        a.place(h.root)
        assertEquals("a\\u{200B}b", (h.text as ReadoutText).verbatimText().toString())
        assertEquals("POST /x\\u{202E}", h.prompt!!.text.toString())
    }

    @Test
    fun `a selection menu keeps Copy and Select all and nothing else`() {
        val a = host()
        val h = Readout.create(a, "ls -la", Readout.Kind.COMMAND)
        a.place(h.root)
        val menu = MenuBuilder(a)
        menu.add(Menu.NONE, android.R.id.copy, 0, "Copy")
        menu.add(Menu.NONE, android.R.id.selectAll, 1, "Select all")
        menu.add(Menu.NONE, android.R.id.shareText, 2, "Share")
        menu.add(Menu.NONE, Menu.NONE, 3, "Process A")
        menu.add(Menu.NONE, Menu.NONE, 4, "Process B")
        menu.add(Menu.NONE, android.R.id.cut, 5, "Cut")
        val callback = h.text.customSelectionActionModeCallback
        assertTrue(callback.onPrepareActionMode(fakeMode(menu), menu))
        val ids = (0 until menu.size()).map { menu.getItem(it).itemId }
        assertEquals(listOf(android.R.id.copy, android.R.id.selectAll), ids)
    }

    @Test
    fun `Share and the other menu actions do nothing but Copy still strips the inserted breaks`() {
        val a = host()
        val long = "cp -r ~/src/module-0 /mnt/backup/ && cp -r ~/src/module-37 /mnt/backup/ && cp -r ~/src/module-75 /mnt/backup/"
        val h = Readout.create(a, long, Readout.Kind.COMMAND)
        a.place(h.root)
        val text = h.text as ReadoutText
        assertTrue(text.insertedAt.isNotEmpty())
        assertTrue(text.onTextContextMenuItem(android.R.id.shareText))
        assertTrue(text.onTextContextMenuItem(android.R.id.cut))
        val cm = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("x", "untouched"))
        assertTrue(text.onTextContextMenuItem(android.R.id.shareText))
        assertEquals("untouched", cm.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun `text that cannot fit fails visibly with a marker and a description`() {
        val a = host()
        val text = ReadoutText(a).apply {
            cautionColor = 0xFFFF8800.toInt()
            softWrap = true
            maxShrinks = 0
            setText("WWWWWWWW WWWWWWWW")
        }
        text.measure(
            View.MeasureSpec.makeMeasureSpec(6, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertTrue("a width narrower than one character cannot fit", text.didNotFit)
        assertTrue(text.contentDescription.toString().startsWith("Text did not fit"))
        assertTrue(text.contentDescription.toString().contains("WWWWWWWW WWWWWWWW"))
    }

    @Test
    fun `text that fits has no failure state`() {
        val a = host()
        val h = Readout.create(a, "ls -la", Readout.Kind.COMMAND)
        a.place(h.root)
        assertFalse((h.text as ReadoutText).didNotFit)
        assertEquals(null, h.text.contentDescription)
    }

    private fun fakeMode(menu: Menu): android.view.ActionMode = object : android.view.ActionMode() {
        override fun setTitle(title: CharSequence?) = Unit
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) = Unit
        override fun setSubtitle(resId: Int) = Unit
        override fun setCustomView(view: View?) = Unit
        override fun invalidate() = Unit
        override fun finish() = Unit
        override fun getMenu(): Menu = menu
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater(): android.view.MenuInflater? = null
    }
}
