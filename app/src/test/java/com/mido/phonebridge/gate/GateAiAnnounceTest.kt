package com.mido.phonebridge.gate

import android.content.Context
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.ai.RequestSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The model's summary is untrusted text; what TalkBack is told must show hidden characters as tokens. */
@RunWith(RobolectricTestRunner::class)
class GateAiAnnounceTest {

    private class RecordingSlot(ctx: Context) : LinearLayout(ctx) {
        val announced = mutableListOf<CharSequence>()
        override fun announceForAccessibility(text: CharSequence) { announced += text }
    }

    @Test
    fun `the ready announcement escapes a right-to-left override in the summary`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val slot = RecordingSlot(ctx)
        val summary = RequestSummary("Runs rm‮ gpj.exe", Risk.LOW, "")
        GateAiPanel(ctx, slot).render(ApprovalGate.AiState.Ready(summary), reducedMotion = true)
        assertEquals(listOf<CharSequence>("AI summary: Runs rm\\u{202E} gpj.exe"), slot.announced)
    }

    @Test
    fun `the copy helper escapes on its own so no caller can pass raw text`() {
        assertEquals("AI summary: a\\u{200B}b", GateCopy.aiAnnounceReady("a​b"))
    }
}
