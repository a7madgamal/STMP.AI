package com.mido.phonebridge

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * The modal must not render the exception button beside a password field.
 *
 * The gate already refuses to build the offer for a password request
 * ([ApprovalPasswordGateTest]); this pins the second layer: even if a view arrives carrying
 * BOTH an offer and `needsPassword`, the Activity drops the offer, so there is no button
 * that could approve a sudo request without the password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalActivityPasswordOfferTest {

    @After
    fun clear() {
        ApprovalGate.setCurrentForTest(null)
    }

    private fun view(needsPassword: Boolean) = ApprovalGate.ApprovalView(
        requester = "test",
        account = "—",
        actionLabel = "RUN COMMAND OVER SSH",
        appLabel = "",
        notifTitle = "",
        notifSender = "",
        notifText = "",
        messageCount = 0,
        replyText = null,
        requiresBiometric = false,
        exceptionOffer = ApprovalGate.ExceptionOffer(
            Rule("ssh", Verb.EXECUTE, Mode.DONT_DISTURB), "ALWAYS NOTIFY"
        ),
        needsPassword = needsPassword
    )

    private fun texts(v: View): List<String> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> listOf(v.text.toString())
        else -> emptyList()
    }

    private fun renderedTexts(needsPassword: Boolean): List<String> {
        val id = 777L
        ApprovalGate.setCurrentForTest(
            ApprovalGate.Pending(
                requestId = id,
                latch = CountDownLatch(1),
                view = view(needsPassword),
                needsPassword = needsPassword
            )
        )
        val intent = Intent(
            RuntimeEnvironment.getApplication(), ApprovalActivity::class.java
        ).putExtra(ApprovalActivity.EXTRA_ID, id)
        val activity = Robolectric.buildActivity(ApprovalActivity::class.java, intent)
            .create().get()
        return texts(activity.window.decorView)
    }

    @Test
    fun `a password request renders no exception button`() {
        val shown = renderedTexts(needsPassword = true)
        assertFalse(
            "the exception button must not render on a password request; saw $shown",
            shown.any { it.equals("ALWAYS NOTIFY", ignoreCase = true) }
        )
    }

    @Test
    fun `a request without a password still renders the exception button`() {
        val shown = renderedTexts(needsPassword = false)
        assertTrue(
            "the offer must still render where it is allowed; saw $shown",
            shown.any { it.equals("ALWAYS NOTIFY", ignoreCase = true) }
        )
    }
}
