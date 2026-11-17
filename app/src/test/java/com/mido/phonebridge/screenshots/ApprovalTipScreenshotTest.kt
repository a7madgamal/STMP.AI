package com.mido.phonebridge.screenshots

import android.app.Activity
import android.widget.FrameLayout
import com.mido.phonebridge.ui.ApprovalTip
import com.mido.phonebridge.ui.ApprovalTipView
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/**
 * The resting approval tip, captured as pixels.
 *
 * This test exists because the tip was shipped twenty-one times on "the build is green" alone.
 * It is a purely visual element: a unit test can prove a triangle has three vertices and a 90°
 * corner, and it did — while the thing on the phone was the wrong shape, the wrong size and
 * clipped. Green tests are not evidence about how something LOOKS. A committed image is.
 *
 * Every state a human has ever complained about is a cell here:
 *  - `rest` — one queued request.
 *  - `stack2`, `stack3` — "notice the stacking"; three is the cap, so the third is the widest
 *    queue that can be drawn.
 *
 * The canvas is sized from [ApprovalTip.requiredSize], NOT chosen here. If the shape needs more
 * room than the window gives it, the capture shows a clipped tip instead of a passing test, which
 * is the whole point: the earlier clipping bugs ("bottom side is cut off") were invisible to
 * every assertion in the suite.
 *
 * The light and dark captures are IDENTICAL, byte for byte, and that is correct rather than a bug
 * in this test: [com.mido.phonebridge.ui.tokens.Palettes.of] currently returns the paper palette
 * whatever the device is set to, so the tip has one appearance. Do not "fix" the duplication by
 * deleting the dark cells — when a night palette lands, those cells become the only thing that
 * would notice the tip still being drawn in cream.
 */
class ApprovalTipScreenshotTest(variant: Variant) : ShotTest(variant) {

    /**
     * Lay the tip out at its derived size inside a hosted Activity and capture it.
     *
     * The tip is a system overlay on the device, but it is an ordinary [android.view.View] with an
     * `onDraw`, so it can be measured, laid out and drawn with no window at all. Roborazzi needs a
     * real Activity around a view it captures, hence the throwaway host — the FrameLayout keeps
     * the tip at exactly its own derived size instead of stretching it to the screen.
     *
     * `screenWidthPx` is set to the tip's own width: this captures the STRIP the tip draws in, and
     * [ApprovalTipView] sizes the paper against the screen unless told otherwise.
     */
    private fun captureTip(state: String, depth: Int) {
        val ctx = Fixtures.app
        val density = ctx.resources.displayMetrics.density
        val (wPx, hPx) = ApprovalTip.requiredSize(density)

        val view = ApprovalTipView(ctx)
        view.depth = depth
        view.screenWidthPx = wPx.toInt()
        view.layoutParams = FrameLayout.LayoutParams(wPx.toInt(), hPx.toInt())

        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        host.setContentView(
            FrameLayout(ctx).apply {
                addView(view)
            },
        )
        host.window.decorView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(wPx.toInt(), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(hPx.toInt(), android.view.View.MeasureSpec.EXACTLY),
        )
        host.window.decorView.layout(0, 0, wPx.toInt(), hPx.toInt())
        ShadowLooper.idleMainLooper()

        Shots.view(view, "approval_tip", state)
    }

    @Test
    fun approval_tip_rest() = captureTip("rest", depth = 1)

    @Test
    fun approval_tip_stack2() = captureTip("stack2", depth = 2)

    @Test
    fun approval_tip_stack3() = captureTip("stack3", depth = 3)
}
