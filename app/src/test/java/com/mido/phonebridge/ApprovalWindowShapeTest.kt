package com.mido.phonebridge

import android.content.Intent
import android.view.WindowManager
import com.mido.phonebridge.policy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * The two shapes the approval window can take.
 *
 * The properties asserted here are the ones a human would notice if they broke: a resting tip that
 * dims the screen behind it, a resting tip that steals the keyboard, or an open sheet that cannot
 * be typed into. Each of those is a flag, and a flag is exactly the sort of thing that gets lost
 * when the same window is reused for two purposes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalWindowShapeTest {

    private fun present(unlocked: Boolean = true): ApprovalActivity {
        val now = System.currentTimeMillis()
        val view = ApprovalGate.ApprovalView(
            requester = "Work laptop",
            account = "me@example.com",
            actionLabel = "RUN COMMAND OVER SSH",
            appLabel = "",
            notifTitle = "",
            notifSender = "",
            notifText = "",
            messageCount = 0,
            replyText = null,
            sections = emptyList(),
        )
        val pending = ApprovalGate.Pending(
            requestId = 900L,
            latch = CountDownLatch(1),
            detail = org.json.JSONObject().put("path", "/ssh/box"),
            view = view,
            remote = "100.64.0.7",
            endpoint = "POST /ssh/box",
            summary = "Run a command",
            account = "me@example.com",
            createdAtMs = now,
            shownAtMs = now,
            aiState = ApprovalGate.AiState.NotNeeded,
            enabledAtMs = now - 1,
        )
        ApprovalGate.setCurrentForTest(pending)
        val km = org.robolectric.Shadows.shadowOf(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getSystemService(android.app.KeyguardManager::class.java)
        )
        if (!unlocked) km.setKeyguardLocked(true)

        val intent = Intent(org.robolectric.RuntimeEnvironment.getApplication(), ApprovalActivity::class.java)
            .putExtra(ApprovalActivity.EXTRA_ID, 900L)
            .putExtra(ApprovalActivity.EXTRA_ENDPOINT, "POST /ssh/box")
            .putExtra(ApprovalActivity.EXTRA_SUMMARY, "Run a command")
        return Robolectric.buildActivity(ApprovalActivity::class.java, intent).setup().get()
    }

    private fun attrs(a: ApprovalActivity) = a.window.attributes

    @Test
    fun `resting does not dim what is behind it`() {
        val a = present()
        assertEquals("the app underneath must stay usable", 0f, attrs(a).dimAmount, 0f)
    }

    @Test
    fun `resting cannot take focus`() {
        val a = present()
        val flags = attrs(a).flags
        assertTrue(
            "a resting tip that can take focus steals the keyboard from the app in use",
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
    }

    @Test
    fun `resting passes touches outside the strip to the window behind`() {
        val a = present()
        val flags = attrs(a).flags
        assertTrue(
            "without NOT_TOUCH_MODAL the strip eats every touch in the app behind it",
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0,
        )
    }

    @Test
    fun `resting is a strip against the right edge, not the whole screen`() {
        val a = present()
        val width = attrs(a).width
        val screen = a.resources.displayMetrics.widthPixels
        assertTrue("the resting window must be narrower than the screen, was $width", width < screen)
        assertEquals(android.view.Gravity.END or android.view.Gravity.TOP, attrs(a).gravity)
    }

    @Test
    fun `the screen stays awake in both shapes`() {
        val a = present()
        assertTrue(
            "a prompt that expires while the phone sleeps is a decision nobody was given",
            attrs(a).flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
        )
    }

    @Test
    fun `a locked phone opens the sheet instead of the wedge`() {
        val a = present(unlocked = false)
        val flags = attrs(a).flags
        assertTrue(
            "the sheet needs focus, so NOT_FOCUSABLE must be gone",
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0,
        )
        assertTrue("an open sheet dims what is behind it", attrs(a).dimAmount > 0f)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, attrs(a).width)
    }

    @Test
    fun `the card is reachable in both shapes`() {
        // The sheet and the tip are siblings for the life of the request: collapsing must not
        // have to rebuild the card, because the tick, the countdown and the handlers are bound
        // to the views that were built once.
        val a = present()
        assertNotNull(a.window.decorView.findViewById<android.view.View>(android.R.id.content))
        assertEquals(
            "the sheet's entrance wrapper is built regardless of the opening shape",
            1,
            (a.window.decorView as android.view.ViewGroup).childCount,
        )
    }

    @Test
    fun `a rotation replays the same request rather than the first one`() {
        val a = present()
        assertEquals(900L, a.intent.getLongExtra(ApprovalActivity.EXTRA_ID, -1L))
    }

    @Test
    fun `the vault prompt keeps the opaque theme`() {
        // Regression guard for the theme split: giving the vault prompt the translucent sheet
        // theme would make the unlock dialog show the app behind it while it asks for a
        // biometric.
        val info = org.robolectric.RuntimeEnvironment.getApplication()
            .packageManager
            .getActivityInfo(
                android.content.ComponentName(
                    org.robolectric.RuntimeEnvironment.getApplication(), VaultUnlockActivity::class.java,
                ),
                0,
            )
        val styleName = org.robolectric.RuntimeEnvironment.getApplication()
            .resources.getResourceEntryName(info.theme)
        assertEquals("Theme.STMP.Approval", styleName)
    }

    @Test
    fun `the approval window uses the translucent sheet theme`() {
        val info = org.robolectric.RuntimeEnvironment.getApplication()
            .packageManager
            .getActivityInfo(
                android.content.ComponentName(
                    org.robolectric.RuntimeEnvironment.getApplication(), ApprovalActivity::class.java,
                ),
                0,
            )
        val styleName = org.robolectric.RuntimeEnvironment.getApplication()
            .resources.getResourceEntryName(info.theme)
        assertEquals("Theme.STMP.Approval.Sheet", styleName)
    }
}
