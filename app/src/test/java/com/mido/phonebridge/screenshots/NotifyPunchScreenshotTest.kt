package com.mido.phonebridge.screenshots

import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.PunchView
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The punch the Don't-disturb card shows in its own window (WP03b 6): no scrim, not held, no meta
 * line. Captured in `KitHostActivity` (TESTING 5 "Stamp / punch").
 */
class NotifyPunchScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun punch(verdict: PunchView.Verdict, atMs: Long, state: String, reduced: Boolean = false) {
        val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
        Settings.Global.putFloat(a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (reduced) 0f else 1f)
        val view = PunchView(a, scrimAlpha = 0).apply {
            soundHook = {}
            holdAfterSettle = false
            meta = ""
        }
        a.column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, PUNCH_HEIGHT_DP)))
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        view.stamp(verdict)
        if (atMs > 0) looper.idleFor(Duration.ofMillis(atMs))
        Shots.view(a.column, "notify_punch", state)
    }

    @Test fun approved_200ms() = punch(PunchView.Verdict.APPROVED, 200, "approved_200ms")
    @Test fun denied_200ms() = punch(PunchView.Verdict.DENIED, 200, "denied_200ms")
    @Test fun reduced_motion_0ms() = punch(PunchView.Verdict.DENIED, 0, "reduced_motion_0ms", reduced = true)
}

private const val PUNCH_HEIGHT_DP = 400
