package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.core.Updater
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.HomeCopy
import com.mido.phonebridge.ui.kit.Led
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCopyTest {

    private fun header(id: String, ts: Long, verb: String = "READ", noise: Boolean = false) = AuditHeader(
        id = id, ts = ts, outcome = AuditOutcome.APPROVED, label = "l", plugin = "p", verb = verb,
        summary = "s", durationMs = 1L, responseStatus = 200, noise = noise,
    )

    private fun available(beta: Boolean = false) = Updater.Outcome.Available(
        version = "0.50.0", versionCode = 400, notes = "", apkUrl = "u", sha256 = null,
        sizeBytes = 1L, isBeta = beta,
    )

    @After fun reset() { Updater.installing = false }

    @Test fun `recent drops noise and sorts newest first`() {
        val out = HomeCopy.recent(
            listOf(header("a", 1), header("b", 3), header("n", 9, noise = true), header("c", 2)), 3,
        )
        assertEquals(listOf("b", "c", "a"), out.map { it.first.id })
    }

    @Test fun `recent drops unparseable verbs and caps at n`() {
        val out = HomeCopy.recent(
            listOf(header("a", 1), header("x", 5, verb = "POLICY_CHANGED"), header("b", 2), header("c", 3)), 2,
        )
        assertEquals(listOf("c", "b"), out.map { it.first.id })
        assertEquals(Verb.READ, out.first().second)
    }

    @Test fun `recent of nothing is nothing`() {
        assertTrue(HomeCopy.recent(emptyList(), 3).isEmpty())
    }

    @Test fun `checking beats an available outcome`() {
        val l = HomeCopy.updateLine(true, available(), "0.49.4")
        assertEquals("Checking…", l.text)
        assertEquals(Led.State.PENDING, l.led)
    }

    @Test fun `available says ready, with the beta mark`() {
        assertEquals("v0.50.0 is ready to install", HomeCopy.updateLine(false, available(), "0.49.4").text)
        val beta = HomeCopy.updateLine(false, available(beta = true), "0.49.4")
        assertEquals("v0.50.0 is ready to install · beta", beta.text)
        assertEquals(Led.State.OK, beta.led)
        assertEquals(HomeCopy.UpdateTone.OK, beta.tone)
    }

    @Test fun `installing shows the restart note`() {
        Updater.installing = true
        val l = HomeCopy.updateLine(false, available(), "0.49.4")
        assertEquals("Installing v0.50.0… the app restarts when it finishes", l.text)
        assertEquals(Led.State.PENDING, l.led)
    }

    @Test fun `up to date names the installed version`() {
        val l = HomeCopy.updateLine(false, Updater.Outcome.UpToDate(1L), "0.49.4")
        assertEquals("Up to date — v0.49.4", l.text)
        assertEquals(Led.State.OK, l.led)
        assertEquals(HomeCopy.UpdateTone.NEUTRAL, l.tone)
    }

    @Test fun `unavailable carries its reason`() {
        val l = HomeCopy.updateLine(false, Updater.Outcome.Unavailable("offline"), "0.49.4")
        assertEquals("Can't check: offline", l.text)
        assertEquals(Led.State.CAUTION, l.led)
        assertEquals(HomeCopy.UpdateTone.CAUTION, l.tone)
    }

    @Test fun `never checked is off`() {
        val l = HomeCopy.updateLine(false, null, "0.49.4")
        assertEquals("Not checked yet", l.text)
        assertEquals(Led.State.OFF, l.led)
    }
}
