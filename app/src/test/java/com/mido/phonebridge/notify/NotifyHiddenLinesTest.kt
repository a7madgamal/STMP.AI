package com.mido.phonebridge.notify

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ArmGuard
import com.mido.phonebridge.core.Verb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * FIX4: silence approves only what was on screen. A card that cuts summary lines off ("+N more
 * lines") must not approve when the window runs out; a tap still does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class NotifyHiddenLinesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val outcomes = ArrayList<NotifyOverlay.Reason>()
    private val short = "Search contacts for sara"
    private val long = "Delete the notification from Signal for the work-company account now. ".repeat(12).trim()

    @Before
    fun allowOverlays() = ShadowSettings.setCanDrawOverlays(true)

    @After
    fun clear() {
        NotifyOverlay.dismissAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show(id: Long, summary: String, windowMs: Long): View {
        NotifyOverlay.show(
            ctx = ctx, id = id, summary = summary, label = "SEARCH CONTACTS", verb = Verb.READ,
            windowMs = windowMs, facts = null, needsAi = false, fetchCapMs = 0L,
            requester = "Work laptop", onOutcome = { outcomes.add(it.reason) },
        )
        idle()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views.last()
        // Robolectric may not lay a window out: do it at the card's real width so the line counter runs.
        val width = (ctx.resources.displayMetrics.widthPixels - 2 * ctx.resources.displayMetrics.density * 20).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.layout(0, 0, width, card.measuredHeight)
        idle()
        return card
    }

    private fun View.texts(): List<TextView> =
        (this as? TextView)?.let { listOf(it) }.orEmpty() +
            ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).texts() } }.orEmpty())

    private fun waitWindow(ms: Long) {
        // The card reads the wall clock; the paused looper runs the tick: sleep for real, then run it.
        Thread.sleep(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test
    fun `a card that hides lines says silence will not approve`() {
        val card = show(9201L, long, windowMs = 60_000L)
        val more = card.texts().firstOrNull { it.text.startsWith("+") && it.text.contains("more line") }
        assertTrue("expected a more-lines caption", more != null)
        assertTrue(more!!.text.toString(), more.text.contains("silence will not approve"))
        assertTrue(card.contentDescription.toString().contains("silence denies"))
    }

    @Test
    fun `silence on a card that hides lines denies instead of approving`() {
        show(9202L, long, windowMs = 300L)
        waitWindow(700)
        assertEquals(listOf(NotifyOverlay.Reason.HIDDEN_TEXT), outcomes)
    }

    @Test
    fun `silence on a card that shows everything keeps its old meaning`() {
        show(9203L, short, windowMs = 300L)
        waitWindow(700)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() != NotifyOverlay.Reason.HIDDEN_TEXT && outcomes.single() != NotifyOverlay.Reason.APPROVED)
    }

    @Test
    fun `an explicit tap still approves a card that hides lines`() {
        val card = show(9204L, long, windowMs = 60_000L)
        Thread.sleep(ArmGuard.MISCLICK_GUARD_MS + 100)
        idle()
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, 20f, 20f, 0)
            card.dispatchTouchEvent(ev)
            ev.recycle()
        }
        idle()
        assertEquals(listOf(NotifyOverlay.Reason.APPROVED), outcomes)
    }

    @Test
    fun `the gate maps a hidden-text expiry to a denial`() {
        val src = java.io.File("src/main/java/com/mido/phonebridge/notify/NotifyGate.kt").readText()
        val branch = src.substringAfter("Reason.HIDDEN_TEXT ->").substringBefore("Reason.UNATTENDED")
        assertTrue(branch, branch.contains("Result.Unavailable"))
        assertTrue(!branch.contains("Result.Approved"))
    }

    @Test
    fun `copy for a hidden-lines card is singular and plural`() {
        assertEquals("+1 more line · silence will not approve this", NotifyCardCopy.moreLines(1))
        assertEquals("+4 more lines · silence will not approve this", NotifyCardCopy.moreLines(4))
    }
}
