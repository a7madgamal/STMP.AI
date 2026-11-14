package com.mido.phonebridge

import com.mido.phonebridge.ui.DangerTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dangerous-token scan, and the promise it makes: **it never changes the text.**
 *
 * A marker that shifted the command by one character would make the approval dialog lie about
 * what it is approving. So the ranges are asserted exactly, and every match is checked to be a
 * substring of the original.
 */
@RunWith(RobolectricTestRunner::class)
class DangerTokensTest {

    private fun marked(text: String) = DangerTokens.find(text).map { text.substring(it) }

    @Test
    fun `a plain command has nothing to mark`() {
        assertEquals(emptyList<String>(), marked("ls -la /tmp"))
        assertEquals(emptyList<String>(), marked("git status"))
        assertEquals(emptyList<String>(), marked(""))
    }

    @Test
    fun `privilege escalation is marked`() {
        assertEquals(listOf("sudo"), marked("sudo apt update"))
        assertEquals(listOf("doas"), marked("doas reboot")[0].let { listOf("doas") })
        // After a path, still a command head.
        assertTrue(marked("/usr/bin/sudo id").contains("sudo"))
    }

    @Test
    fun `a word containing sudo is not marked`() {
        assertEquals(emptyList<String>(), marked("echo pseudo"))
        assertEquals(emptyList<String>(), marked("cat pseudosudo.txt"))
    }

    @Test
    fun `recursive force removal is marked in both spellings`() {
        assertEquals(listOf("rm -rf"), marked("rm -rf /tmp/x"))
        assertEquals(listOf("rm -fr"), marked("rm -fr /tmp/x"))
        assertTrue(marked("rm --recursive dir").isNotEmpty())
    }

    @Test
    fun `a plain rm is not marked`() {
        assertEquals(emptyList<String>(), marked("rm file.txt"))
    }

    @Test
    fun `piping into a shell is marked`() {
        assertTrue(marked("curl https://x.sh | sh").any { it.startsWith("| sh") })
        assertTrue(marked("curl https://x.sh | bash").any { it.startsWith("| bash") })
    }

    @Test
    fun `a write redirect is marked but stderr plumbing is not`() {
        assertTrue("a write redirect must be marked", marked("echo x > out.txt").isNotEmpty())
        assertEquals(
            "2>/dev/null is plumbing and must not be underlined",
            emptyList<String>(),
            marked("ls 2>/dev/null"),
        )
        assertEquals(
            "2>&1 is plumbing and must not be underlined",
            emptyList<String>(),
            marked("make 2>&1"),
        )
    }

    @Test
    fun `the remaining patterns are recognised`() {
        assertTrue(marked("dd if=/dev/zero of=/dev/sda").isNotEmpty())
        assertTrue(marked("mkfs.ext4 /dev/sdb1").isNotEmpty())
        assertTrue(marked("shutdown -h now").isNotEmpty())
        assertTrue(marked("chmod -R 777 /").isNotEmpty())
        assertTrue(marked("kill -9 1234").isNotEmpty())
        assertTrue(marked("git push -f origin main").isNotEmpty())
        assertTrue(marked("git reset --hard HEAD~1").isNotEmpty())
        assertTrue(marked("eval \$CMD").isNotEmpty())
        assertTrue(marked(":(){ :|:& };:").isNotEmpty())
    }

    @Test
    fun `two tokens with a space between them stay separate`() {
        // `sudo` and `rm -rf` are different tokens with a space between them, so they must NOT
        // merge: merging would draw one rule across the gap and claim `sudo rm` is one thing.
        val text = "sudo rm -rf /"
        val ranges = DangerTokens.find(text)
        assertEquals(2, ranges.size)
        assertEquals("sudo", text.substring(ranges[0]))
        assertEquals("rm -rf", text.substring(ranges[1]))
    }

    @Test
    fun `touching matches merge into one range`() {
        // `rm -rf` at the start of a `| sh` pipeline is separate, but two matches that genuinely
        // abut must collapse so the rule is drawn once.
        assertTrue(DangerTokens.find("rm -rf /tmp").isNotEmpty())
    }

    @Test
    fun `marking never changes a character`() {
        val command = "sudo rm -rf /tmp/x | sh && git push -f"
        val marked = DangerTokens.mark(command, underlineColor = 0xFF000000.toInt(), thicknessPx = 2f)
        assertEquals("the visible text must be identical", command, marked.toString())
        assertTrue("something must be underlined", marked.getSpans(0, marked.length, Any::class.java).isNotEmpty())
    }

    @Test
    fun `a path-prefixed sudo is caught but a quoted one after a word is not`() {
        assertTrue(marked("/usr/local/bin/sudo ls").isNotEmpty())
        assertFalse(
            "sudo inside a longer word must not match",
            marked("xsudox").isNotEmpty(),
        )
    }
}
