package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.ui.OutcomeCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word a person reads for an outcome, and the proof that no outcome falls through.
 *
 * The log, the activity list and the record detail all printed `outcome.name` — so the screens
 * showed `NOTIFY_APPROVED` and `DENIED_UNATTENDED`, the enum's identifier, which is a label for
 * the programmer and not for the reader. They now share one mapping; this asserts it stays total,
 * so adding an enum value cannot quietly render as its identifier on only one screen.
 */
class OutcomeCopyTest {

    @Test
    fun `every outcome has words, and none is its own identifier`() {
        for (o in AuditOutcome.values()) {
            val words = OutcomeCopy.words(o)
            assertTrue("${o.name} has no words", words.isNotBlank())
            assertNotEquals("${o.name} renders as its raw identifier", o.name, words)
        }
    }

    @Test
    fun `the two easily-confused outcomes stay distinguishable`() {
        // "he tapped no" and "nobody was there" are different facts, and the second is how a
        // mis-scoped rule announces itself. Collapsing the wording would hide that.
        assertNotEquals(
            OutcomeCopy.words(AuditOutcome.DENIED),
            OutcomeCopy.words(AuditOutcome.DENIED_UNATTENDED),
        )
        assertNotEquals(
            OutcomeCopy.words(AuditOutcome.APPROVED),
            OutcomeCopy.words(AuditOutcome.NOTIFY_APPROVED),
        )
    }

    @Test
    fun `the quiet approval says that silence approved it`() {
        // The single most surprising fact in the log: a request ran without a tap.
        assertEquals("Approved by silence", OutcomeCopy.words(AuditOutcome.NOTIFY_APPROVED))
    }

    @Test
    fun `only a handler failure counts as our fault`() {
        assertTrue(OutcomeCopy.isFault(AuditOutcome.HANDLER_FAILED))
        assertEquals(false, OutcomeCopy.isFault(AuditOutcome.DENIED))
        assertEquals(false, OutcomeCopy.isFault(AuditOutcome.REJECTED_INVALID))
    }
}
