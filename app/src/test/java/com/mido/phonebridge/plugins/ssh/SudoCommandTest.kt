package com.mido.phonebridge.plugins.ssh

import com.mido.phonebridge.core.PluginError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The predicate that decides whether a password is handed to a command's stdin.
 *
 * **This is the security boundary, so the tests are asymmetric on purpose.** A false
 * POSITIVE means the password is written to a process that is not sudo — `cat` would
 * print it into the HTTP response — so every test that pins a positive is a leak test.
 * A false NEGATIVE only means sudo refuses for lack of a terminal, which fails safe.
 */
class SudoCommandTest {

    // --- invokes(): the leak direction ---

    @Test
    fun `plain sudo is detected`() {
        assertTrue(SudoCommand.invokes("sudo id"))
    }

    @Test
    fun `absolute path is detected`() {
        assertTrue(SudoCommand.invokes("/usr/bin/sudo id"))
    }

    @Test
    fun `env assignments before the command do not hide it`() {
        assertTrue(SudoCommand.invokes("FOO=1 BAR=2 sudo id"))
    }

    @Test
    fun `sudo after a semicolon is a segment head`() {
        assertTrue(SudoCommand.invokes("echo hi; sudo id"))
    }

    @Test
    fun `sudo after and-and is a segment head`() {
        assertTrue(SudoCommand.invokes("true && sudo id"))
    }

    @Test
    fun `sudo after a pipe is a segment head`() {
        assertTrue(SudoCommand.invokes("echo x | sudo tee /tmp/f"))
    }

    @Test
    fun `sudo in the middle of a command is NOT sudo`() {
        // The whole point: `echo sudo hi` runs echo, and echo would print the password.
        assertFalse(SudoCommand.invokes("echo sudo hi"))
    }

    @Test
    fun `sudo inside a quoted string is text, not a command`() {
        // A naive split on ';' reads this as two segments and calls the second sudo.
        assertFalse(SudoCommand.invokes("echo 'x; sudo id'"))
    }

    @Test
    fun `a path containing sudo is not sudo`() {
        assertFalse(SudoCommand.invokes("ls /var/lib/sudo"))
    }

    @Test
    fun `a filename containing sudo is not sudo`() {
        assertFalse(SudoCommand.invokes("cat sudofile.txt"))
    }

    @Test
    fun `no sudo at all`() {
        assertFalse(SudoCommand.invokes("ls -la"))
    }

    @Test
    fun `empty command`() {
        assertFalse(SudoCommand.invokes(""))
    }

    @Test
    fun `quoted sudo as a command name still counts`() {
        // 'sudo' and sudo are the same command; quotes are not a hiding place.
        assertTrue(SudoCommand.invokes("'sudo' id"))
    }

    // --- hasStdinFlag() ---

    @Test
    fun `short flag S is found`() {
        assertTrue(SudoCommand.hasStdinFlag("sudo -S id"))
    }

    @Test
    fun `long flag stdin is found`() {
        assertTrue(SudoCommand.hasStdinFlag("sudo --stdin id"))
    }

    @Test
    fun `a cluster containing S is found`() {
        assertTrue(SudoCommand.hasStdinFlag("sudo -nS id"))
    }

    @Test
    fun `no stdin flag`() {
        assertFalse(SudoCommand.hasStdinFlag("sudo id"))
    }

    @Test
    fun `a different flag is not S`() {
        assertFalse(SudoCommand.hasStdinFlag("sudo -n id"))
    }

    // --- requireFeedable(): the pre-gate refusal ---

    @Test
    fun `sudo without -S is refused before the gate`() {
        try {
            SudoCommand.requireFeedable("sudo id")
            fail("a sudo command without -S can never succeed and must be refused")
        } catch (e: PluginError) {
            assertTrue(e.code == "sudo_needs_stdin")
        }
    }

    @Test
    fun `sudo with -S passes`() {
        SudoCommand.requireFeedable("sudo -S id")
    }

    @Test
    fun `a non-sudo command passes untouched`() {
        SudoCommand.requireFeedable("ls -la")
    }
}
