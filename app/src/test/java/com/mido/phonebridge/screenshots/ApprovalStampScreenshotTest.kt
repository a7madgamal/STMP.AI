package com.mido.phonebridge.screenshots

import android.os.Looper
import com.mido.phonebridge.ApprovalActivity
import com.mido.phonebridge.core.Verb
import org.junit.After
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The punch and the receipt on the gate (WP03a 13, approval_stamp/), driven by the looper clock. */
class ApprovalStampScreenshotTest(variant: Variant) : ShotTest(variant) {

    @After
    fun clearGate() = GateFixtures.reset()

    private fun decide(approve: Boolean): ApprovalActivity {
        val a = GateFixtures.launch(GateFixtures.view(Verb.READ, "GET /probe", actionLabel = "Check that the bridge answers"), "GET /probe")
        val key = findByText(a.window.decorView, if (approve) "APPROVE" else "DENY")!!
        key.performClick()
        // One frame so the punch is laid out and its clock starts.
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun at(a: ApprovalActivity, ms: Long, state: String) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        Shots.window(a, "approval_stamp", state)
    }

    @Test fun approved_200ms() = at(decide(true), 200, "approved_200ms")
    @Test fun denied_200ms() = at(decide(false), 200, "denied_200ms")
    @Test fun approved_720ms() = at(decide(true), 719, "approved_720ms")
    @Test fun denied_720ms() = at(decide(false), 719, "denied_720ms")
    @Test fun receipt_approved_960ms() = at(decide(true), 960, "receipt_approved_960ms")
    @Test fun receipt_denied_960ms() = at(decide(false), 960, "receipt_denied_960ms")

    @Test fun reduced_motion_receipt_0ms() {
        GateFixtures.reducedMotion(true)
        // The punch is final at once; the receipt appears without a feed when the punch settles.
        at(decide(true), 721, "reduced_motion_receipt_0ms")
    }
}
