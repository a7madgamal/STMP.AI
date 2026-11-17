package com.mido.phonebridge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The DENY drain: the mapping that drives it, and the pixels that must actually come out.
 *
 * **Why the pixels are here and not only the level.** Both halves failed independently once: the
 * level was computed inline where no test could reach it, and the drawable it fed was silently
 * repainted by the button's `backgroundTintList`, so a correct level produced a beige bar. A test
 * that asserts only `drain.level == 4400` would have passed on that shipped-and-invisible build.
 * So one test checks the arithmetic and one checks that red lands on the screen.
 *
 * The bitmap draw is why this class is `NATIVE` graphics: the default LEGACY mode draws
 * `GradientDrawable` as nothing at all, and a "no red pixels" assertion would then pass for the
 * wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DenyDrainTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    // --- the meter ---------------------------------------------------------------------------

    private val arm = 1_000_000L
    private val window = 60_000L

    @Test
    fun `no arm time leaves the bar empty`() {
        assertEquals(0, CountdownMeter.level(0L, arm, window))
    }

    @Test
    fun `the guard window leaves the bar empty`() {
        // One millisecond before the guard releases the controls: the window has not started, so a
        // full bar here would claim time the human was never given.
        assertEquals(0, CountdownMeter.level(arm, arm - 1L, window))
    }

    @Test
    fun `a full window is full, and the deadline is empty`() {
        assertEquals(CountdownMeter.FULL, CountdownMeter.level(arm, arm, window))
        assertEquals(0, CountdownMeter.level(arm, arm + window, window))
        // Past the deadline is still empty, not negative.
        assertEquals(0, CountdownMeter.level(arm, arm + window * 2, window))
    }

    @Test
    fun `halfway is half, and it counts DOWN`() {
        val half = CountdownMeter.level(arm, arm + window / 2, window)
        assertEquals(CountdownMeter.FULL / 2, half)
        val quarter = CountdownMeter.level(arm, arm + window * 3 / 4, window)
        assertTrue("time left must shrink the bar", quarter < half)
    }

    @Test
    fun `seconds left agrees with the bar`() {
        assertEquals(60L, CountdownMeter.secondsLeft(arm, arm, window))
        assertEquals(30L, CountdownMeter.secondsLeft(arm, arm + 30_000L, window))
        assertEquals(0L, CountdownMeter.secondsLeft(arm, arm + window, window))
    }

    // --- the pixels --------------------------------------------------------------------------

    /**
     * The fill must exist ON SCREEN, at the level the meter asks for.
     *
     * This is the assertion the invisible build could not survive. It renders the control that
     * `Theme.countdownButton` builds — the same factory `ApprovalActivity` calls — and compares the
     * left third of the button (inside the fill) against the right third (past the boundary). A
     * tint-repainted fill makes the two identical.
     */
    @Test
    fun `the drain paints red on the left and trough on the right`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val wash = Theme.washOverTrough(Theme.DANGER, 0.40f)
        val control = Theme.countdownButton(activity, "DENY", wash) {}
        control.setLevel(CountdownMeter.FULL / 2)

        val w = 400
        val h = Theme.dp(activity, Theme.MIN_TARGET_DP)
        control.button.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Theme.BG)
        control.button.draw(canvas)

        val left = bitmap.getPixel(w / 8, h / 2)
        val right = bitmap.getPixel(w - w / 8, h / 2)

        // The fill is warmer than the trough on the red channel. A tinted-over fill collapses this.
        assertTrue(
            "the fill's boundary is invisible: left=$left right=$right — the drain is not being drawn",
            Color.red(left) - Color.blue(left) > Color.red(right) - Color.blue(right) + 20
        )
        bitmap.recycle()
    }

    @Test
    fun `a level on the fill alone paints nothing`() {
        // The trap this control was rebuilt around: a `ClipDrawable` inside a `LayerDrawable` gets
        // its level from the layer at draw time, so the obvious `drain.level = n` is a no-op. Pinning
        // it means the next person to "simplify" the handle finds out here rather than on a phone.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val control = Theme.countdownButton(activity, "DENY", Theme.washOverTrough(Theme.DANGER, 0.40f)) {}
        control.drain.level = CountdownMeter.FULL / 2
        assertEquals(0, control.layer.level)
        control.setLevel(CountdownMeter.FULL / 2)
        assertEquals(CountdownMeter.FULL / 2, control.layer.level)
    }

    /**
     * A button from this factory must carry NO background tint.
     *
     * Asserted on the property that caused the bug rather than on the drawing alone: a tint would
     * re-apply itself the next time the background is swapped, and the next swap is exactly what a
     * future change to this control would do.
     */
    @Test
    fun `the countdown button carries no background tint`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val control = Theme.countdownButton(activity, "DENY", Theme.washOverTrough(Theme.DANGER, 0.40f)) {}
        assertNull(control.button.backgroundTintList)
    }

    /**
     * The label must be legible against BOTH ends of the drain.
     *
     * The wash exists because no single colour clears every level on a solid fill: ink on the stamp
     * red is 2.1:1, far under the 4.5:1 floor. If someone deepens the fill to make the boundary
     * louder, this is what stops the label quietly going unreadable.
     */
    @Test
    fun `the label clears contrast at full and at empty`() {
        val wash = Theme.washOverTrough(Theme.DANGER, 0.40f)
        val onFill = contrast(Theme.ON_SURFACE, wash)
        val onTrough = contrast(Theme.ON_SURFACE, Theme.SURFACE_VARIANT)
        assertTrue("label on the fill is $onFill:1, under 4.5", onFill >= 4.5)
        assertTrue("label on the trough is $onTrough:1, under 4.5", onTrough >= 4.5)
    }

    private fun contrast(fg: Int, bg: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val c = value / 255.0
                return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
