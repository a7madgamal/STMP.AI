package com.mido.phonebridge

import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * A request that needs a password can only be approved WITH the password.
 *
 * WHY THIS EXISTS
 *   The modal's exception button ("ALWAYS NOTIFY" / "ALWAYS ASK") approved through
 *   [ApprovalGate.resolveWithException], which carries no password, and it armed on the same
 *   signal as APPROVE without checking the password field. On a sudo request that meant one
 *   tap approved with an empty field: the command reached sudo with nothing on stdin, the
 *   FLAG_SECURE/fingerprint password path was skipped, and a rule relaxing the whole
 *   `ssh · execute` pair was written from a prompt opened for one sudo command.
 *
 * WHAT THIS PINS
 *   (a) The gate never builds an exception offer for a password request.
 *   (b) The gate refuses every approval of a password request that does not carry the
 *       password, whichever button sent it — and still accepts a DENY.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalPasswordGateTest {

    @After
    fun clear() {
        ApprovalGate.setCurrentForTest(null)
    }

    private fun pending(needsPassword: Boolean): ApprovalGate.Pending =
        ApprovalGate.Pending(
            requestId = 4242L,
            latch = CountDownLatch(1),
            needsPassword = needsPassword,
        ).also { ApprovalGate.setCurrentForTest(it) }

    private val rule = Rule("ssh", Verb.EXECUTE, Mode.DONT_DISTURB)

    // ---- (a) the offer is never built for a password request ----

    @Test
    fun `no exception offer is built for a request that needs a password`() {
        for (mode in listOf(Mode.PARANOID, Mode.DONT_DISTURB)) {
            assertNull(
                "a password request must never be offered the exception button (mode $mode)",
                ApprovalGate.exceptionOfferFor(
                    GlobalMode.WHITELIST, "ssh", Verb.EXECUTE, mode, needsPassword = true
                )
            )
        }
    }

    @Test
    fun `the exception offer is unchanged for a request without a password`() {
        val offer = ApprovalGate.exceptionOfferFor(
            GlobalMode.WHITELIST, "ssh", Verb.EXECUTE, Mode.PARANOID, needsPassword = false
        )
        assertNotNull(offer)
        assertEquals("ALWAYS NOTIFY", offer!!.label)
        assertEquals(Rule("ssh", Verb.EXECUTE, Mode.DONT_DISTURB), offer.rule)

        val back = ApprovalGate.exceptionOfferFor(
            GlobalMode.WHITELIST, "gmail", Verb.DELETE, Mode.DONT_DISTURB, needsPassword = false
        )
        assertEquals("ALWAYS ASK", back!!.label)
        assertEquals(Rule("gmail", Verb.DELETE, Mode.PARANOID), back.rule)
    }

    @Test
    fun `no exception offer outside AUTOMATIC, as before`() {
        for (g in GlobalMode.values().filter { it != GlobalMode.WHITELIST }) {
            assertNull(
                ApprovalGate.exceptionOfferFor(g, "ssh", Verb.EXECUTE, Mode.PARANOID, false)
            )
        }
    }

    // ---- (b) the gate refuses a password request approved without the password ----

    @Test
    fun `the exception tap cannot approve a request that needs a password`() {
        val p = pending(needsPassword = true)

        assertFalse(ApprovalGate.resolveWithException(p.requestId, rule))

        assertNull("the request must stay undecided", p.approved)
        assertNull("no rule may be carried back for the caller to write", p.exceptionRule)
        assertEquals("the latch must not release", 1L, p.latch.count)
        assertTrue(ApprovalGate.isCurrent(p.requestId))
    }

    @Test
    fun `a plain approve cannot approve a request that needs a password`() {
        val p = pending(needsPassword = true)

        assertFalse(ApprovalGate.resolve(p.requestId, approved = true))

        assertNull(p.approved)
        assertNull(p.password)
        assertEquals(1L, p.latch.count)
    }

    @Test
    fun `a password request can still be denied`() {
        val p = pending(needsPassword = true)

        assertTrue(ApprovalGate.resolve(p.requestId, approved = false))

        assertEquals(false, p.approved)
        assertEquals(0L, p.latch.count)
    }

    @Test
    fun `a password request approves through the password path`() {
        val p = pending(needsPassword = true)
        val typed = "hunter2".toCharArray()

        assertTrue(ApprovalGate.resolveWithPassword(p.requestId, typed))

        assertEquals(true, p.approved)
        assertArrayEquals("hunter2".toCharArray(), p.password)
        assertNull(p.exceptionRule)
        assertEquals(0L, p.latch.count)
    }

    @Test
    fun `requests without a password keep both approve paths`() {
        val plain = pending(needsPassword = false)
        assertTrue(ApprovalGate.resolve(plain.requestId, approved = true))
        assertEquals(true, plain.approved)

        val withRule = pending(needsPassword = false)
        assertTrue(ApprovalGate.resolveWithException(withRule.requestId, rule))
        assertEquals(true, withRule.approved)
        assertEquals(rule, withRule.exceptionRule)
    }
}
