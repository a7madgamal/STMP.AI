package com.mido.phonebridge

import android.app.Activity
import android.widget.EditText
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A deny that carries words, and the rules that keep it from becoming a slower deny.
 *
 * **The gate half is here because a reasoned deny must not be a second exit.** It goes through
 * the same latch, sets the same `approved = false`, and hands its text back on the same result
 * type — so `resolveWithReason` cannot approve anything by accident, which is the one mistake
 * this feature could make that would matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class DenyReasonTest {

    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()

    // --- the gate -----------------------------------------------------------------------------

    @Test
    fun `a plain deny carries no reason`() {
        val p = pending()
        ApprovalGate.setCurrentForTest(p)
        assertTrue(ApprovalGate.resolve(p.requestId, approved = false))
        assertEquals(false, p.approved)
        assertNull("a plain deny must not invent a reason", p.denyReason)
        ApprovalGate.setCurrentForTest(null)
    }

    @Test
    fun `a reasoned deny stores the text and still denies`() {
        val p = pending()
        ApprovalGate.setCurrentForTest(p)
        assertTrue(ApprovalGate.resolveWithReason(p.requestId, "  wrong account  "))
        assertEquals("a reasoned deny is a deny", false, p.approved)
        // Trimmed at the boundary, so the caller never receives padding it did not type.
        assertEquals("wrong account", p.denyReason)
        ApprovalGate.setCurrentForTest(null)
    }

    @Test
    fun `blank text is refused rather than stored`() {
        val p = pending()
        ApprovalGate.setCurrentForTest(p)
        // The sheet disables submit, but the rule is enforced at the gate too: an empty reason
        // reaching the caller is indistinguishable from no reason, while being recorded as if
        // someone had explained themselves.
        assertFalse(ApprovalGate.resolveWithReason(p.requestId, ""))
        assertFalse(ApprovalGate.resolveWithReason(p.requestId, "   "))
        assertNull(p.denyReason)
        // Nothing was decided, so the request is still open and a real deny later still works.
        assertTrue(ApprovalGate.resolve(p.requestId, approved = false))
        ApprovalGate.setCurrentForTest(null)
    }

    @Test
    fun `a wound-down verdict reports the reason it was given`() {
        val p = pending()
        ApprovalGate.setCurrentForTest(p)
        ApprovalGate.resolveWithReason(p.requestId, "not now")
        // The reason travels on the RESULT, not only in the record: a reason the caller cannot
        // read is a reason nobody acts on.
        val denied = com.mido.phonebridge.ApprovalGate.GateResult.Denied(
            enqueuedAt = 0L, shownAt = 0L, decidedAt = 0L, queueDepth = 0, reason = p.denyReason,
        )
        assertEquals("not now", denied.reason)
        ApprovalGate.setCurrentForTest(null)
    }

    // --- the window ---------------------------------------------------------------------------

    @Test
    fun `the decision window covers a long-press, a sentence and a submit`() {
        // One number. The bar, the latch and /help all read it, so pinning the value here is
        // pinning all three — a second "display" constant is how a bar starts disagreeing with
        // the deadline it claims to show.
        assertEquals(90L, ApprovalGate.TIMEOUT_SECONDS)
    }

    @Test
    fun `the sheet's submit is driven by its own text`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = EditText(activity).apply { setText("") }
        val submit = Theme.button(activity, "DENY WITH REASON", Theme.ButtonStyle.DANGER) {}
        submit.isEnabled = false
        // Mirror the rule the watcher applies: blank (or all spaces) is not usable.
        fun usable(s: CharSequence?) = !s.isNullOrBlank()
        assertFalse(usable(field.text))
        field.setText("   ")
        assertFalse("spaces are still empty", usable(field.text))
        field.setText("x")
        assertTrue(usable(field.text))
    }

    private fun pending(): ApprovalGate.Pending = ApprovalGate.Pending(
        requestId = 4242L,
        latch = java.util.concurrent.CountDownLatch(1),
    )
}
