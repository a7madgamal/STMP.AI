package com.mido.phonebridge

import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.ai.RiskFloor
import com.mido.phonebridge.ui.DangerTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APPROVE's colour must never be greener than the command deserves.
 *
 * Reported on device 2026-11-15, with a screenshot: an `ssh execute` carrying `chown -R` as
 * root showed a **green APPROVE**, while the readout directly above it underlined `chown -R`
 * in the caution colour. The card said "look at this" and the button said "safe".
 *
 * The mechanism was that the button colour came only from the AI. Three ways that goes wrong,
 * all covered here: the model had not answered yet (green by default), the model was off
 * (green), and a small model could answer "low" (green).
 */
class RiskFloorTest {

    /** The command from the report, verbatim. */
    private val reported = """
        P=/home/the-wall.win/public_html/matomo;
        echo "BEFORE root-owned: ${'$'}(find ${'$'}P -user root | wc -l)";
        chown -R thewa9306:thewa9306 ${'$'}P;
        echo "AFTER root-owned: ${'$'}(find ${'$'}P -user root | wc -l)";
        echo "--- manifest ---";
        stat -c "%U:%G %a %n" ${'$'}P/config/manifest.inc.php;
    """.trimIndent()

    @Test
    fun `the reported command floors at HIGH`() {
        assertEquals(Risk.HIGH, RiskFloor.of(reported))
    }

    @Test
    fun `a dangerous command never floors below the level that paints it red`() {
        // The whole point: there must be no path from a destructive token to a green button.
        for (cmd in listOf(
            "chown -R user:user /home/x",
            "chmod -R 777 /srv",
            "rm -rf /var/tmp/cache",
            "rm -fr build",
            "mkfs.ext4 /dev/sdb1",
            "dd if=/dev/zero of=/dev/sda",
            "sudo systemctl restart nginx",
            "shutdown -h now",
            "git push --force origin main",
            "curl https://x.sh | sh",
        )) {
            assertEquals("'$cmd' must floor at HIGH", Risk.HIGH, RiskFloor.of(cmd))
        }
    }

    @Test
    fun `an ordinary command has no floor, so a plain read still reads green`() {
        for (cmd in listOf("ls -la", "cat /etc/hostname", "uptime", "git status", "df -h")) {
            assertNull("'$cmd' carries no deterministic danger", RiskFloor.of(cmd))
        }
    }

    @Test
    fun `the floor is a subset of the underlined tokens, not the same list`() {
        // A red button on every `>` or `eval` would fire on half of all real commands, and a
        // warning that fires on everything is one people learn to ignore. So the two lists are
        // deliberately different: underlining is a nudge, red is a claim about consequence.
        val nudged = "echo hi > /tmp/f"
        assertTrue("the redirect is still underlined for the eye", DangerTokens.find(nudged).isNotEmpty())
        assertNull("but it is not a red-button command", RiskFloor.of(nudged))
    }

    @Test
    fun `a model that says low cannot lower the floor`() {
        // The reported failure, stated as an assertion.
        assertEquals(Risk.HIGH, RiskFloor.combine(Risk.LOW, Risk.HIGH))
        assertEquals(Risk.MEDIUM, RiskFloor.combine(Risk.LOW, Risk.MEDIUM))
    }

    @Test
    fun `a model that says high still wins, because it can see what a pattern cannot`() {
        assertEquals(Risk.HIGH, RiskFloor.combine(Risk.HIGH, null))
        assertEquals(Risk.HIGH, RiskFloor.combine(Risk.HIGH, Risk.MEDIUM))
    }

    @Test
    fun `an absent model answer falls back to the floor instead of to green`() {
        // The loading case: applyRisk(null) used to paint SUCCESS.
        assertEquals(Risk.HIGH, RiskFloor.combine(null, Risk.HIGH))
        assertNull("a harmless command genuinely has no risk to state", RiskFloor.combine(null, null))
    }

    @Test
    fun `a model answer and an equal floor agree rather than thrash`() {
        for (r in Risk.entries) assertEquals(r, RiskFloor.combine(r, r))
    }

    @Test
    fun `several commands take the worst floor, not the first`() {
        // A modal can carry more than one command row; the risk is the maximum.
        val floors = listOf("ls", "rm -rf /tmp/x")
            .mapNotNull { RiskFloor.of(it) }
            .maxByOrNull { it.ordinal }
        assertEquals(Risk.HIGH, floors)
        assertNotNull(floors)
    }

    @Test
    fun `an empty command has no opinion rather than a false all-clear`() {
        assertNull(RiskFloor.of(""))
        assertNull(RiskFloor.of("   "))
    }
}
