package com.mido.phonebridge.gate

import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ApprovalGate.ApproveGesture
import com.mido.phonebridge.core.OutboundVerb
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D12/D13: the approve gesture for every gated route of WP03a section 11, and the edge rows. */
class ApproveGestureTest {

    private data class Case(
        val routeKey: String,
        val verb: Verb,
        val expected: ApproveGesture,
        val needsPassword: Boolean = false,
        val holdExempt: Boolean = false,
    )

    private val cases = listOf(
        Case("POST /ssh/{name}", Verb.EXECUTE, ApproveGesture.HOLD),
        Case("POST /ssh/{name}/detach", Verb.EXECUTE, ApproveGesture.HOLD),
        Case("GET /ssh/job/{id}", Verb.READ, ApproveGesture.TAP),
        Case("GET /secrets", Verb.READ, ApproveGesture.TAP),
        Case("GET /secrets/{name}", Verb.READ, ApproveGesture.TAP),
        Case("POST /gmail/messages/send", Verb.WRITE, ApproveGesture.TAP),
        Case("POST /gmail/messages/{id}/trash", Verb.DELETE, ApproveGesture.HOLD),
        Case("POST /gmail/messages/{id}/modify", Verb.EDIT, ApproveGesture.TAP),
        Case("GET /gmail/profile", Verb.READ, ApproveGesture.TAP),
        Case("GET /gmail/messages", Verb.READ, ApproveGesture.TAP),
        Case("GET /gmail/messages/{id}", Verb.READ, ApproveGesture.TAP),
        Case("GET /drive/files/{id}/content", Verb.READ, ApproveGesture.TAP),
        Case("POST /drive/files/{id}/trash", Verb.DELETE, ApproveGesture.HOLD),
        Case("POST /drive/files", Verb.WRITE, ApproveGesture.TAP),
        Case("POST /drive/files/{id}/rename", Verb.EDIT, ApproveGesture.TAP),
        Case("POST /drive/files/{id}/move", Verb.EDIT, ApproveGesture.TAP),
        Case("POST /drive/files/upload", Verb.WRITE, ApproveGesture.TAP),
        Case("GET /drive/files", Verb.READ, ApproveGesture.TAP),
        Case("GET /drive/files/{id}", Verb.READ, ApproveGesture.TAP),
        Case("POST /calendar/events", Verb.WRITE, ApproveGesture.TAP),
        Case("POST /calendar/events/{id}", Verb.EDIT, ApproveGesture.TAP),
        Case("DELETE /calendar/events/{id}", Verb.DELETE, ApproveGesture.HOLD),
        Case("POST /calendar/events/{id}/move", Verb.EDIT, ApproveGesture.TAP),
        Case("POST /calendar/quickadd", Verb.WRITE, ApproveGesture.TAP),
        Case("GET /calendar/events", Verb.READ, ApproveGesture.TAP),
        Case("GET /contacts", Verb.READ, ApproveGesture.TAP),
        Case("POST /notifications/{id}/action", Verb.EXECUTE, ApproveGesture.HOLD),
        Case("POST /notifications/{id}/dismiss", Verb.EXECUTE, ApproveGesture.TAP, holdExempt = true),
        Case("GET /notifications", Verb.READ, ApproveGesture.TAP),
        Case("DELETE /audit", Verb.DELETE, ApproveGesture.HOLD),
        Case("GET /debug", Verb.READ, ApproveGesture.TAP),
        Case("GET /logs", Verb.READ, ApproveGesture.TAP),
        Case("GET /probe", Verb.READ, ApproveGesture.TAP),
        Case("GET /help", Verb.READ, ApproveGesture.TAP),
        // sudo over SSH needs a password: HOLD.
        Case("POST /ssh/{name}", Verb.EXECUTE, ApproveGesture.HOLD, needsPassword = true),
    )

    private fun gesture(c: Case, biometric: Boolean) =
        ApprovalGate.approveGesture(c.verb, c.needsPassword, c.holdExempt, biometric)

    @Test
    fun `every gated route gets the gesture of the section 11 table`() {
        for (c in cases) assertEquals(c.routeKey, c.expected, gesture(c, biometric = false))
    }

    @Test
    fun `secrets calls are judged by the outbound method`() {
        val expected = mapOf(
            "GET" to ApproveGesture.TAP, "HEAD" to ApproveGesture.TAP, "OPTIONS" to ApproveGesture.TAP,
            "POST" to ApproveGesture.TAP, "PUT" to ApproveGesture.TAP, "PATCH" to ApproveGesture.TAP,
            "FOO" to ApproveGesture.TAP, "DELETE" to ApproveGesture.HOLD,
        )
        for ((method, gesture) in expected) {
            val verb = OutboundVerb.of(method)
            assertEquals(method, gesture, ApprovalGate.approveGesture(verb, false, false, false))
        }
        assertEquals(Verb.WRITE, OutboundVerb.of("FOO"))
    }

    @Test
    fun `the fingerprint replaces the hold on every route`() {
        for (c in cases) assertEquals(c.routeKey, ApproveGesture.TAP_THEN_BIOMETRIC, gesture(c, biometric = true))
    }

    @Test
    fun `the predicate edges`() {
        assertFalse(ApprovalGate.requiresHold(Verb.EXECUTE, needsPassword = false, holdExempt = true))
        assertTrue(ApprovalGate.requiresHold(Verb.READ, needsPassword = true, holdExempt = false))
        assertFalse(ApprovalGate.requiresHold(Verb.WRITE, needsPassword = false, holdExempt = false))
        assertTrue(ApprovalGate.requiresHold(Verb.DELETE, needsPassword = false, holdExempt = false))
    }

    @Test
    fun `the view derives its gesture from its own inputs`() {
        fun view(verb: Verb, bio: Boolean, exempt: Boolean = false) = ApprovalGate.ApprovalView(
            requester = "x", account = "—", actionLabel = "a", appLabel = "", notifTitle = "",
            notifSender = "", notifText = "", messageCount = 0, replyText = null,
            requiresBiometric = bio, verb = verb, routeKey = "POST /x", holdExempt = exempt,
        )
        assertEquals(ApproveGesture.HOLD, view(Verb.EXECUTE, bio = false).approveGesture)
        assertEquals(ApproveGesture.TAP, view(Verb.EXECUTE, bio = false, exempt = true).approveGesture)
        assertEquals(ApproveGesture.TAP_THEN_BIOMETRIC, view(Verb.EXECUTE, bio = true).approveGesture)
        assertEquals(ApproveGesture.TAP, view(Verb.READ, bio = false).approveGesture)
    }
}
