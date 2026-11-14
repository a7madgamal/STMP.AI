package com.mido.phonebridge.screenshots

import android.content.Intent
import com.mido.phonebridge.ApprovalActivity
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.ai.RequestSummary
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** The approval modal in every state of WP00 section 5 that can be reached without a keystore. */
class ApprovalScreenshotTest(variant: Variant) : ShotTest(variant) {

    @After
    fun clearGate() {
        ApprovalGate.setCurrentForTest(null)
        queue().clear()
        val prefs = Fixtures.app.getSharedPreferences("phonebridge_vault", 0)
        prefs.edit().clear().commit()
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(): java.util.ArrayDeque<ApprovalGate.Pending> {
        val f = ApprovalGate::class.java.getDeclaredField("queue").apply { isAccessible = true }
        return f.get(ApprovalGate) as java.util.ArrayDeque<ApprovalGate.Pending>
    }

    private fun view(
        needsPassword: Boolean = false,
        offer: ApprovalGate.ExceptionOffer? = null,
        biometric: Boolean = false,
    ) = ApprovalGate.ApprovalView(
        requester = "Work laptop",
        account = "me@example.com",
        actionLabel = "SEARCH CONTACTS",
        appLabel = "",
        notifTitle = "",
        notifSender = "",
        notifText = "",
        messageCount = 0,
        replyText = null,
        sections = listOf(
            ApprovalGate.Section("", listOf(ApprovalGate.Row("SEARCH", "sara", ApprovalGate.Row.Style.MONO))),
        ),
        requiresBiometric = biometric,
        exceptionOffer = offer,
        needsPassword = needsPassword,
    )

    private fun show(
        state: String,
        ai: ApprovalGate.AiState = ApprovalGate.AiState.NotNeeded,
        view: ApprovalGate.ApprovalView = view(),
        queued: Int = 0,
        endpoint: String = "GET /contacts/search",
        openDetails: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val pending = ApprovalGate.Pending(
            requestId = 4242L,
            latch = CountDownLatch(1),
            detail = org.json.JSONObject().put("method", "GET").put("path", "/contacts/search").put("q", "sara"),
            view = view,
            remote = "100.64.0.7",
            endpoint = endpoint,
            summary = "Search contacts for sara",
            account = "me@example.com",
            createdAtMs = now,
            shownAtMs = now,
            aiState = ai,
            enabledAtMs = if (ai is ApprovalGate.AiState.Loading) 0L else now - 1,
            needsPassword = view.needsPassword,
        )
        ApprovalGate.setCurrentForTest(pending)
        queue().clear()
        queue().add(pending)
        repeat(queued) { i ->
            queue().add(ApprovalGate.Pending(requestId = 5000L + i, latch = CountDownLatch(1), createdAtMs = now))
        }
        val intent = Intent(Fixtures.app, ApprovalActivity::class.java)
            .putExtra(ApprovalActivity.EXTRA_ID, 4242L)
            .putExtra(ApprovalActivity.EXTRA_REMOTE, "100.64.0.7")
            .putExtra(ApprovalActivity.EXTRA_ENDPOINT, endpoint)
            .putExtra(ApprovalActivity.EXTRA_SUMMARY, "Search contacts for sara")
            .putExtra(ApprovalActivity.EXTRA_ACCOUNT, "me@example.com")
            .putExtra(ApprovalActivity.EXTRA_CREATED_AT, now)
        val a = org.robolectric.Robolectric.buildActivity(ApprovalActivity::class.java, intent).setup().get()
        if (openDetails) findByText(a.window.decorView, "▸ details")?.performClick()
        capture(a, "approval", state)
    }

    private fun summary(risk: Risk) = ApprovalGate.AiState.Ready(
        RequestSummary("Looks up one contact by name.", risk, "Read-only search; nothing is changed."),
    )

    @Test fun ai_loading() = show("ai_loading", ai = ApprovalGate.AiState.Loading)
    @Test fun ai_ready_low() = show("ai_ready_low", ai = summary(Risk.LOW))
    @Test fun ai_ready_medium() = show("ai_ready_medium", ai = summary(Risk.MEDIUM))
    @Test fun ai_ready_high() = show("ai_ready_high", ai = summary(Risk.HIGH))
    @Test fun ai_failed() = show("ai_failed", ai = ApprovalGate.AiState.Failed("the model did not answer in time"))
    @Test fun not_needed() = show("not_needed")
    @Test fun password() = show("password", view = view(needsPassword = true))

    // bio_not_enrolled and bio_locked_out are not captured: Robolectric 4.14.1's
    // ShadowBiometricManager only models "available" or "unavailable", so BIOMETRIC_ERROR_NONE_ENROLLED
    // and the lockout code (7) cannot be produced without changing production code.

    @Test fun vault_broken() {
        // A stored wrapped key makes `SecretVault.exists` true; Robolectric has no AndroidKeyStore,
        // so preparing the unlock cipher throws, which is exactly the VaultBroken state.
        Fixtures.app.getSharedPreferences("phonebridge_vault", 0).edit().putString("vault_dk_wrapped", "AAAA").commit()
        show("vault_broken", view = view(biometric = true))
    }

    @Test fun exception_offer() = show(
        "exception_offer",
        view = view(offer = ApprovalGate.ExceptionOffer(Rule("contacts", Verb.READ, Mode.DONT_DISTURB), "ALWAYS NOTIFY")),
    )

    /**
     * The SSH command modal, which is the one that carries a friendly name with the exact address
     * beneath it. Captured so the merged CONNECTION row and the smaller detail line are pinned:
     * the layout could regress to two equal-weight rows and every other fixture would stay green,
     * because no other route produces a connection name and an address together.
     */
    @Test
    fun ssh_command() = show(
        "ssh_command",
        endpoint = "POST /ssh/evox2",
        view = ApprovalGate.ApprovalView(
            requester = "OpenClaw",
            account = "me@example.com",
            actionLabel = "RUN COMMAND OVER SSH",
            appLabel = "",
            notifTitle = "",
            notifSender = "",
            notifText = "",
            messageCount = 0,
            replyText = null,
            sections = emptyList(),
            destructive = listOf(
                // The friendly name, with the exact target smaller beneath it.
                ApprovalGate.Row(
                    "CONNECTION",
                    "evox2",
                    ApprovalGate.Row.Style.MONO,
                    detail = "ahmed@100.99.67.14:22",
                ),
                ApprovalGate.Row(
                    "COMMAND",
                    "cd ~ && gh pr close 7 --repo a7madgamal/STMP.AI",
                    ApprovalGate.Row.Style.CODE,
                ),
            ),
        ),
    )

    @Test fun queued_2() = show("queued_2", queued = 2)
    @Test fun details_open() = show("details_open", openDetails = true)

    // approval_stamp (approved_200ms, denied_200ms) is not captured: under Robolectric the stamp's
    // AnimatorSet runs to its end inside the first animation frame, so the 200 ms impact frame
    // cannot be isolated; driving it would need a clock seam in StampOverlay (WP03a owns that file).
}
