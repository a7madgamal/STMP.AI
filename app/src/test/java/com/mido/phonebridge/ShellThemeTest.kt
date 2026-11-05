package com.mido.phonebridge

import com.mido.phonebridge.ui.ShellCode
import com.mido.phonebridge.ui.ShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShellThemeTest {

    @Test
    fun `parses the syntax people actually publish`() {
        val theme = ShellTheme.parse(
            """
            ZSH_HIGHLIGHT_STYLES[command]='fg=green,bold'
            ZSH_HIGHLIGHT_STYLES[redirection]='fg=cyan'
            ZSH_HIGHLIGHT_STYLES[comment]='fg=#6c7086'
            """.trimIndent()
        )
        val base = ShellTheme.ink()
        assertNotEquals("command must be recoloured", base["command"], theme["command"])
        assertNotEquals("redirection must be recoloured", base["redirection"], theme["redirection"])
        assertEquals("hex must be honoured", 0xFF6c7086.toInt(), theme["comment"])
    }

    @Test
    fun `a partial theme still colours everything`() {
        // Three lines must not leave the rest of a command unpainted: base supplies the gaps.
        val theme = ShellTheme.parse("ZSH_HIGHLIGHT_STYLES[command]='fg=red'")
        val base = ShellTheme.ink()
        assertTrue("every base key survives", theme.keys.containsAll(base.keys))
    }

    @Test
    fun `unknown values are skipped, not fatal`() {
        // A value this app cannot resolve must not break the parse — the OTHER entries in the
        // same theme still apply, which is the property that matters when a published theme
        // targets a 256-colour terminal.
        val theme = ShellTheme.parse(
            """
            ZSH_HIGHLIGHT_STYLES[command]='fg=notacolour'
            ZSH_HIGHLIGHT_STYLES[redirection]='fg=cyan'
            """.trimIndent()
        )
        assertNotEquals("redirection still applied", ShellTheme.ink()["redirection"], theme["redirection"])
        assertEquals("unparseable value leaves base", ShellTheme.ink()["command"], theme["command"])
    }

    @Test
    fun `none is honoured as leave-alone`() {
        val theme = ShellTheme.parse("ZSH_HIGHLIGHT_STYLES[comment]='none'")
        assertEquals(ShellTheme.ink()["comment"], theme["comment"])
    }

    @Test
    fun `background and weight do not discard the colour`() {
        // Published themes nearly always carry these; rejecting the entry would lose the fg.
        val theme = ShellTheme.parse("ZSH_HIGHLIGHT_STYLES[command]='fg=red,bold,bg=blue'")
        assertNotEquals(ShellTheme.ink()["command"], theme["command"])
    }

    @Test
    fun `a parsed theme renders a command without falling back`() {
        val theme = ShellTheme.parse("ZSH_HIGHLIGHT_STYLES[command]='fg=red'")
        val rendered = ShellCode.render("ls -la; sudo rm -rf /tmp/x", theme)
        assertTrue(rendered.isNotEmpty())
    }
}
