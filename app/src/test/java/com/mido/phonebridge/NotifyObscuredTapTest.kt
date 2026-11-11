package com.mido.phonebridge

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.notify.NotifyOverlay
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The Don't-disturb card refuses an approve tap while another window fully covers it (D17, D60),
 * and its ✕ keeps working regardless (D55).
 *
 * The card reads `System.currentTimeMillis()` for its misclick guard. Robolectric's paused looper
 * does not advance that clock (verified for WP03b: `idleFor(5 s)` moved it by 0 ms), so the one
 * wait past the guard is a real `Thread.sleep`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class NotifyObscuredTapTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val outcomes = ArrayList<NotifyOverlay.Reason>()

    @Before
    fun allowOverlays() = ShadowSettings.setCanDrawOverlays(true)

    @After
    fun clearOverlay() {
        NotifyOverlay.dismissAll()
        idleFor(3_000)
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun showCard(id: Long): View {
        NotifyOverlay.show(
            ctx = ctx, id = id, summary = "Search contacts for sara", label = "SEARCH CONTACTS",
            verb = Verb.READ, windowMs = 60_000L, facts = null, needsAi = false, fetchCapMs = 0L,
            onOutcome = { outcomes.add(it.reason) },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views.last()
    }

    private fun waitPastGuard() {
        Thread.sleep(ArmGuard.MISCLICK_GUARD_MS + 100)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(view: View, flags: Int) = gesture(view, MotionEvent.ACTION_DOWN to flags, MotionEvent.ACTION_UP to flags)

    /** One gesture, each event with its own flags, so a cover can come and go mid-gesture. */
    private fun gesture(view: View, vararg events: Pair<Int, Int>) {
        val t = SystemClock.uptimeMillis()
        for ((action, flags) in events) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 20f; y = 20f; pressure = 1f; size = 1f })
            val ev = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
            view.dispatchTouchEvent(ev)
            ev.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun obscuredLine(card: View): TextView? = card.findText(ObscuredTouchGuard.BANNER_TEXT)

    @Test
    fun `an obscured approve tap decides nothing and shows the cover line`() {
        val card = showCard(9101L)
        waitPastGuard()
        val line = obscuredLine(card)
        assertNotNull("the card must carry the cover line, hidden until needed", line)
        line!!
        assertEquals(View.GONE, line.visibility)

        tap(card, MotionEvent.FLAG_WINDOW_IS_OBSCURED)

        assertTrue("an obscured tap must not decide: $outcomes", outcomes.isEmpty())
        assertEquals(View.VISIBLE, line.visibility)
    }

    @Test
    fun `a tap whose press was obscured decides nothing even if the cover is gone at release`() {
        val card = showCard(9105L)
        waitPastGuard()

        gesture(
            card,
            MotionEvent.ACTION_DOWN to MotionEvent.FLAG_WINDOW_IS_OBSCURED,
            MotionEvent.ACTION_UP to 0,
        )

        assertTrue("a press under a cover must not approve on a clear release: $outcomes", outcomes.isEmpty())
        assertEquals(View.VISIBLE, obscuredLine(card)!!.visibility)
    }

    @Test
    fun `a tap obscured while the finger moved decides nothing`() {
        val card = showCard(9106L)
        waitPastGuard()

        gesture(
            card,
            MotionEvent.ACTION_DOWN to 0,
            MotionEvent.ACTION_MOVE to MotionEvent.FLAG_WINDOW_IS_OBSCURED,
            MotionEvent.ACTION_UP to 0,
        )

        assertTrue("a cover during the gesture must not approve: $outcomes", outcomes.isEmpty())
    }

    @Test
    fun `a clear approve tap after the guard approves`() {
        val card = showCard(9102L)
        waitPastGuard()

        tap(card, 0)

        assertEquals(listOf(NotifyOverlay.Reason.APPROVED), outcomes)
    }

    @Test
    fun `partial cover does not block the approve tap`() {
        val card = showCard(9103L)
        waitPastGuard()

        tap(card, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)

        assertEquals(listOf(NotifyOverlay.Reason.APPROVED), outcomes)
    }

    @Test
    fun `the close key denies even after obscured taps`() {
        val card = showCard(9104L)
        tap(card, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        val close = card.findDescription("Deny: Search contacts for sara")
        assertNotNull("the ✕ must be described by the summary", close)

        close!!.performClick()

        assertEquals(listOf(NotifyOverlay.Reason.TAPPED), outcomes)
    }

    private fun View.findText(text: String): TextView? {
        if (this is TextView && this.text?.toString() == text) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findText(text)?.let { return it }
        return null
    }

    private fun View.findDescription(text: String): View? {
        if (contentDescription?.toString() == text) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findDescription(text)?.let { return it }
        return null
    }
}
