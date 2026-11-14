package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.AuditCopy
import com.mido.phonebridge.ui.AuditCopy.Tone
import com.mido.phonebridge.screenshots.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditCopyTest {
    @After fun after() = Fixtures.clearPlugins()

    private val expected = mapOf(
        AuditOutcome.APPROVED to Triple("Approved", Tone.OK, true),
        AuditOutcome.NOTIFY_APPROVED to Triple("Approved by silence", Tone.OK, true),
        AuditOutcome.DENIED to Triple("Denied", Tone.NEUTRAL, true),
        AuditOutcome.NOTIFY_REJECTED to Triple("Denied from the card", Tone.NEUTRAL, true),
        AuditOutcome.TIMEOUT to Triple("Timed out", Tone.CAUTION, true),
        AuditOutcome.DENIED_UNATTENDED to Triple("Denied, phone unattended", Tone.NEUTRAL, true),
        AuditOutcome.HANDLER_FAILED to Triple("Failed", Tone.CAUTION, true),
        AuditOutcome.CLIENT_GONE to Triple("Caller left", Tone.NEUTRAL, true),
        AuditOutcome.POLICY_CHANGED to Triple("Gate changed", Tone.NEUTRAL, false),
        AuditOutcome.REJECTED_INVALID to Triple("Bad request", Tone.NOISE, false),
        AuditOutcome.UNROUTED to Triple("Unknown path", Tone.NOISE, false),
    )

    @Test fun `every outcome has the word, tone and chip flag of the table`() {
        for (o in AuditOutcome.values()) {
            val want = expected[o] ?: error("no expectation for $o: add it to the WP08 table")
            val got = AuditCopy.outcome(o)
            assertEquals(o.name, want, Triple(got.word, got.tone, got.allowsChip))
        }
    }

    @Test fun `modes are words`() {
        assertEquals("Paranoid", AuditCopy.modeWords("SECURE"))
        assertEquals("Paranoid", AuditCopy.modeWords("PARANOID"))
        assertEquals("Don't disturb", AuditCopy.modeWords("NOTIFY"))
        assertEquals("Don't disturb", AuditCopy.modeWords("DONT_DISTURB"))
        assertEquals("Whitelist", AuditCopy.modeWords("WHITELIST"))
        assertEquals("Weird value", AuditCopy.modeWords("weird_value"))
        assertEquals("Unknown", AuditCopy.modeWords(""))
        assertEquals("Unknown", AuditCopy.modeWords("  "))
    }

    @Test fun `sources and rules are words`() {
        assertEquals("The gate mode decided", AuditCopy.sourceWords("GLOBAL"))
        assertEquals("An exception decided", AuditCopy.sourceWords("RULE"))
        assertEquals("No exception applied, so the default (ask for your stamp)", AuditCopy.sourceWords("DEFAULT"))
        assertEquals("Gmail · any action", AuditCopy.ruleWords("gmail/ANY"))
        assertEquals("Gmail · edit", AuditCopy.ruleWords("gmail/EDIT"))
    }

    @Test fun `verbs and origins`() {
        assertEquals("any action", AuditCopy.verbWords(null))
        assertEquals("execute", AuditCopy.verbWords(Verb.EXECUTE))
        assertEquals("This phone", AuditCopy.originWords("LOCAL"))
        assertEquals("Tailnet", AuditCopy.originWords("TAILNET"))
        assertEquals("Unknown address", AuditCopy.originWords("UNKNOWN"))
        assertNull(AuditCopy.originWords(" "))
    }

    @Test fun `durations`() {
        val us = Locale.US
        assertNull(AuditCopy.duration(0, us))
        assertEquals("340 ms", AuditCopy.duration(340, us))
        assertEquals("2.8 s", AuditCopy.duration(2_811, us))
        assertEquals("60.0 s", AuditCopy.duration(59_999, us))
        assertEquals("1 min 1 s", AuditCopy.duration(61_000, us))
        assertEquals("40,112 bytes", AuditCopy.bytes(40_112, us))
    }

    @Test fun `a fingerprint-only change names the fingerprint and never reads as X to X`() {
        assertEquals("Fingerprint for approvals: on", AuditCopy.policySummary("fingerprint: on (settings)"))
        assertEquals("Fingerprint for approvals: off", AuditCopy.policySummary("fingerprint: off (home)"))
        val noOp = AuditCopy.policySummary("switch: WHITELIST → WHITELIST (settings)")
        assertFalse(noOp, noOp.contains("→"))
    }

    @Test fun `home and the log read one formatter`() {
        val raw = "fingerprint: on (settings)"
        assertEquals("Fingerprint for approvals: on", AuditCopy.summary(AuditOutcome.POLICY_CHANGED, raw))
        assertEquals("GET /x", AuditCopy.summary(AuditOutcome.APPROVED, "GET /x"))
        assertEquals("gmail.read", AuditCopy.summary(AuditOutcome.APPROVED, "", "gmail.read"))
    }

    @Test fun `a policy change has no verb chip and every refusal is in the denied filter`() {
        assertFalse(AuditCopy.outcome(AuditOutcome.POLICY_CHANGED).allowsChip)
        for (o in listOf(AuditOutcome.DENIED, AuditOutcome.NOTIFY_REJECTED, AuditOutcome.DENIED_UNATTENDED)) {
            assertTrue(o.name, AuditCopy.isDenial(o))
        }
        assertFalse(AuditCopy.isDenial(AuditOutcome.TIMEOUT))
    }

    @Test fun `policy summaries are shown in words`() {
        assertEquals(
            "Gate mode Whitelist → Paranoid, changed in Settings",
            AuditCopy.policySummary("switch: WHITELIST → PARANOID (settings)"),
        )
        assertEquals(
            "Exception Gmail · edit: Default (ask) → Don't disturb, changed in Settings",
            AuditCopy.policySummary("rule: gmail/EDIT default → DONT_DISTURB (settings; switch WHITELIST)"),
        )
        assertEquals(
            "Settings saved, gate mode stays Paranoid",
            AuditCopy.policySummary("switch: PARANOID → PARANOID (options)"),
        )
        assertEquals("something else entirely", AuditCopy.policySummary("something else entirely"))
        for (raw in listOf(
            "switch: WHITELIST → PARANOID (options)",
            "switch: PARANOID → PARANOID (options)",
            "rule: gmail/ANY PARANOID → default (settings; switch DONT_DISTURB)",
        )) {
            val out = AuditCopy.policySummary(raw)
            assertFalse(out, out.contains("WHITELIST") || out.contains("DONT_DISTURB") || out.contains("options"))
        }
    }
}
