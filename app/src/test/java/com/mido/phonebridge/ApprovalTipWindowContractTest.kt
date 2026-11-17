package com.mido.phonebridge

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.mido.phonebridge.notify.ApprovalTipOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * The tip window's CONTRACT with the system.
 *
 * **Why these assertions exist.** Three reports in a row said "touches are blocked even on the
 * transparent parts", and the cause was not the view at all: from Android 12, a touch that would
 * reach another app through an untrusted overlay is refused unless the overlay's combined opacity
 * is <= 0.8. A full-height window counts as a big, mostly-transparent sheet over the screen.
 *
 * Nothing in a normal unit test notices that, because it is a WindowManager policy, not app logic.
 * So these tests pin the two properties that decide it: the window must not span the display, and
 * it must not hide what is under it. If someone later makes the tip a full-screen window again,
 * this fails here instead of on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalTipWindowContractTest {

    private fun lp(): WindowManager.LayoutParams {
        val ctx = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        ApprovalTipOverlay.hide()
        ApprovalTipOverlay.show(ctx, depth = 1)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val field = org.robolectric.shadows.ShadowWindowManager::class.java
        // The overlay is the last added view; read its layout params from the shadow registry.
        val impl = org.robolectric.Shadows.shadowOf(wm)
        val views = impl::class.java.methods.firstOrNull { it.name == "getViews" }
            ?.invoke(impl) as? List<*>
        val last = views?.lastOrNull() as? android.view.View
        return requireNotNull(last) { "the tip window was never added" }.let {
            it.layoutParams as WindowManager.LayoutParams
        }
    }

    @Test
    fun `the tip window is an untrusted overlay, so its size is the contract`() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val params = lp()

        assertTrue(
            "the tip must be a system overlay, or the app behind it gets paused",
            params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )
        assertTrue(
            "the window must not span the display width (${params.width} of ${dm.widthPixels})",
            params.width < dm.widthPixels,
        )
        assertTrue(
            "the window must not span the display height (${params.height} of ${dm.heightPixels}) — " +
                "a full-height untrusted overlay has the system block every touch beneath it",
            params.height < dm.heightPixels,
        )
    }

    @Test
    fun `the window is sized to the paper, not to a column`() {
        // Robolectric's default display is small, so the comparison is against the WINDOW's own
        // aspect rather than the screen's: a column is taller than it is wide, a sheet is a sheet.
        val params = lp()
        assertTrue(
            "height ${params.height} is a column, not a sheet (width ${params.width})",
            params.height < params.width * 4f,
        )
        assertTrue("width ${params.width} is too small to read", params.width > 100)
    }

    @Test
    fun `the window does not take focus or dim what is behind it`() {
        val params = lp()
        assertTrue(
            "NOT_FOCUSABLE: a tip must never take the keyboard from the app in use",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
        assertTrue(
            "NOT_TOUCH_MODAL: touches outside the window must reach the app below",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0,
        )
        assertEquals("a tip must not dim the screen behind it", 0f, params.dimAmount, 0f)
        assertTrue("the window must not be opaque", params.format == PixelFormat.TRANSLUCENT)
        assertFalse(
            "NOT_TOUCHABLE would make the tip unusable — it must be tappable",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
        assertEquals(
            "the tip belongs against the top-right edge",
            Gravity.END or Gravity.TOP,
            params.gravity,
        )
    }
}
