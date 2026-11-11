package com.mido.phonebridge.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.github.takahirom.roborazzi.captureRoboImage
import com.mido.phonebridge.ai.RequestFacts
import com.mido.phonebridge.ai.RequestSummary
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.notify.NotifyOverlay
import com.mido.phonebridge.ui.Theme
import org.junit.After
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/**
 * The Don't-disturb card (WP03b 6.1). Each state is the overlay window's view as
 * `NotifyOverlay.show` attaches it, drawn over the body colour with an 8dp margin. A screen
 * capture is not used: it shifts an overlay window by its x/y offset and crops it, and it drops
 * the window root's alpha.
 *
 * The AI states use `needsAi = false` with facts present: the card then shows its AI row without
 * starting a real fetch (the summariser runs on a worker thread with no seam), and
 * `showSummaryForTest` paints the result exactly as a finished fetch does.
 */
class NotifyCardScreenshotTest(variant: Variant) : ShotTest(variant) {

    private val facts = RequestFacts(
        plugin = "contacts", verb = "READ", routeLabel = "SEARCH CONTACTS",
        summary = "Search contacts for sara", rows = listOf("query" to "sara"),
    )

    @After
    fun clearCards() {
        NotifyOverlay.dismissAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
    }

    private fun show(
        verb: Verb = Verb.READ,
        summary: String = "Search contacts for sara",
        withAi: Boolean = false,
    ): View {
        ShadowSettings.setCanDrawOverlays(true)
        NotifyOverlay.show(
            ctx = Fixtures.app, id = ID, summary = summary, label = "LABEL",
            verb = verb, windowMs = 60_000L, facts = if (withAi) facts else null, needsAi = false,
            fetchCapMs = 0L, onOutcome = { },
        )
        ShadowLooper.idleMainLooper()
        val wm = Fixtures.app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return Shadow.extract<ShadowWindowManagerImpl>(wm).views.last()
    }

    private fun ready(risk: Risk, description: String): View {
        val card = show(withAi = true)
        NotifyOverlay.showSummaryForTest(ID, RequestSummary(description, risk, "fixture"))
        return card
    }

    /** Draws [card] at [alpha] over the body colour and records it as [state]. */
    private fun shoot(card: View, state: String, alpha: Float = 1f) {
        ShadowLooper.idleMainLooper()
        val pad = Theme.dp(card.context, MARGIN_DP)
        val bmp = Bitmap.createBitmap(card.width + 2 * pad, card.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Theme.palette(card.context).body)
        canvas.translate(pad.toFloat(), pad.toFloat())
        canvas.saveLayerAlpha(0f, 0f, card.width.toFloat(), card.height.toFloat(), (alpha * 255).toInt())
        card.draw(canvas)
        canvas.restore()
        bmp.captureRoboImage(Shots.path(SCREEN, state), Shots.OPTIONS)
    }

    @Test fun summarising() = shoot(show(withAi = true), "summarising")

    @Test fun ready_low() {
        shoot(ready(Risk.LOW, "Reads the contacts that match sara; nothing is changed."), "ready_low")
    }

    @Test fun ready_medium() {
        shoot(ready(Risk.MEDIUM, "Reads every contact's phone number and email address."), "ready_medium")
    }

    @Test fun ready_high() {
        shoot(ready(Risk.HIGH, "Reads all contacts, including notes, and returns them in full."), "ready_high")
    }

    @Test fun no_ai_read() = shoot(show(), "no_ai_read")

    @Test fun execute_chip() {
        shoot(show(Verb.EXECUTE, "Run uptime on kvm-box"), "execute_chip")
    }

    @Test fun delete_chip() {
        shoot(show(Verb.DELETE, "Delete the notification from Signal"), "delete_chip")
    }

    @Test fun long_summary() {
        shoot(show(Verb.EXECUTE, "Run journalctl --since today --unit nginx and return every line of the output on kvm-box"), "long_summary")
    }

    @Test fun obscured_line() {
        val card = show()
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 20f; y = 20f; pressure = 1f; size = 1f })
            val ev = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            card.dispatchTouchEvent(ev)
            ev.recycle()
        }
        shoot(card, "obscured_line")
    }

    /** The first second, dimmed to `ArmGuard.UNARMED_ALPHA`. */
    @Test fun unarmed_dim() {
        val card = show()
        // Past the 180 ms fade-in, inside the 1 s guard; the tick reads the real clock.
        Thread.sleep(UNARMED_AT_MS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        shoot(card, "unarmed_dim", alpha = card.alpha)
    }
}

private const val SCREEN = "notify_card"
private const val ID = 7001L
private const val UNARMED_AT_MS = 400L
private const val MARGIN_DP = 8
