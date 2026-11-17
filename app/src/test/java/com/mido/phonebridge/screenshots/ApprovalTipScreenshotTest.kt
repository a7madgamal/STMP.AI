package com.mido.phonebridge.screenshots

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.FrameLayout
import com.mido.phonebridge.ui.ApprovalTip
import com.mido.phonebridge.ui.ApprovalTipView
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/**
 * The tip as it lands on a PHONE, not as a crop of itself.
 *
 * The first version of this test captured the tip view alone, so every golden was a 125px square
 * of paper with no context. That can show the SHAPE and nothing about how big the shape is on a
 * screen — which is the exact question behind "too fucking big". Worse, it cannot show the bug
 * that keeps recurring: the paper clipped at its window's bottom edge, because a view-sized crop
 * has no phone around the paper to be clipped against.
 *
 * So this captures the whole display: a stand-in app surface, the tip positioned the way its
 * overlay window is (`Gravity.END or Gravity.TOP`, at the real derived size), and a centimetre
 * ruler drawn down the right edge. The ruler is the point — it turns "is that 1cm?" into something
 * measurable in every image, with no device involved.
 */
class ApprovalTipScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun captureTipOnScreen(state: String, depth: Int, countdown: Float, controller: ActivityController<android.app.Activity>) {
        val ctx = Fixtures.app
        val dm = ctx.resources.displayMetrics
        val density = dm.density

        // Same arithmetic as ApprovalTipOverlay.addWindow. Mirrored deliberately but minimised: if
        // these ever disagree with production the golden shows the wrong window, so the numbers
        // that matter (the shape's own size) still come from ApprovalTip.
        val (needW, needH) = ApprovalTip.requiredSize(density)
        val winW = (needW + SHADOW_ROOM_DP * density).toInt().coerceAtMost(dm.widthPixels)
        val winH = (needH + SHADOW_ROOM_DP * density).toInt()
            .coerceAtMost((dm.heightPixels * MAX_HEIGHT_FRACTION).toInt())

        val root = PhoneFrame(ctx, winW)
        val tip = ApprovalTipView(ctx).apply {
            screenWidthPx = dm.widthPixels
            this.depth = depth
            this.countdown = countdown
            // Mirror production EXACTLY: the overlay window is `Gravity.END` with x =
            // edgeInsetPx. A LAYOUT_MARGIN_END inset reproduces that same gutter. This is the
            // line that was wrong before: the test used a positive `translationX`, which moves the
            // view right (past the edge) while a positive window `x` under END gravity moves it
            // LEFT (away from the edge). The two moved in opposite directions, so the goldens
            // showed paper flush to the edge while the phone showed it 32dp in from it.
            layoutParams = FrameLayout.LayoutParams(
                winW, winH,
                android.view.Gravity.END or android.view.Gravity.TOP,
            ).apply {
                marginEnd = ApprovalTip.edgeInsetPx(density)
            }
        }
        root.addView(
            tip,
            tip.layoutParams as FrameLayout.LayoutParams,
        )

        val activity = controller.get()
        activity.setContentView(root)
        val decor = activity.window.decorView
        val w = android.view.View.MeasureSpec.makeMeasureSpec(decor.width, android.view.View.MeasureSpec.EXACTLY)
        val h = android.view.View.MeasureSpec.makeMeasureSpec(decor.height, android.view.View.MeasureSpec.EXACTLY)
        decor.measure(w, h)
        decor.layout(0, 0, decor.width, decor.height)
        ShadowLooper.idleMainLooper()

        Shots.view(decor, "approval_tip", state)
    }

    @Test
    fun tip_on_screen_rest() {
        captureTipOnScreen("screen_rest", 1, 1f, Robolectric.buildActivity(android.app.Activity::class.java).setup())
    }

    @Test
    fun tip_on_screen_stack3() {
        captureTipOnScreen("screen_stack3", 3, 1f, Robolectric.buildActivity(android.app.Activity::class.java).setup())
    }

    @Test
    fun tip_on_screen_stack2() {
        captureTipOnScreen("screen_stack2", 2, 1f, Robolectric.buildActivity(android.app.Activity::class.java).setup())
    }

    /**
     * The countdown dial at a part-spent window.
     *
     * Asserted as pixels because the dial is a claim about time: a refactor that silently made it
     * draw full forever would pass every unit test of the arithmetic while lying on the phone. The
     * fraction is fed directly — the clock itself is covered by ApprovalTipCountdownTest, which
     * tests the gate's real deadline rather than a timer this view invents.
     */
    @Test
    fun tip_on_screen_midway() {
        captureTipOnScreen("screen_midway", 1, 0.5f, Robolectric.buildActivity(android.app.Activity::class.java).setup())
    }

    /** Nearly expired: the wedge must still be visible, not thinned to nothing. */
    @Test
    fun tip_on_screen_almost_gone() {
        captureTipOnScreen("screen_almost", 1, 0.08f, Robolectric.buildActivity(android.app.Activity::class.java).setup())
    }

    /**
     * A stand-in display: a flat surface where the app would be, with a 1cm-tick ruler down the
     * right edge so the paper's size can be read off the image instead of argued about.
     */
    private class PhoneFrame(context: Context, private val tipWindowWidthPx: Int) : FrameLayout(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8F805F.toInt() }
        private val cm = 160f / 2.54f * resources.displayMetrics.density

        init {
            setBackgroundColor(0xFFF5EEDF.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Ruler immediately left of the tip's window: 1cm ticks, every 5th long.
            val x = (width - tipWindowWidthPx).toFloat()
            var y = 0f
            var i = 0
            while (y < height) {
                val len = if (i % 5 == 0) cm * 0.6f else cm * 0.3f
                canvas.drawLine(x - len, y, x, y, ink)
                y += cm
                i++
            }
        }
    }

    private companion object {
        const val SHADOW_ROOM_DP = 4f
        const val MAX_HEIGHT_FRACTION = 0.8f
    }
}
