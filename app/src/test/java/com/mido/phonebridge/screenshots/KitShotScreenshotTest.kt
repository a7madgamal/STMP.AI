package com.mido.phonebridge.screenshots

import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mido.phonebridge.debug.KitHostActivity
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Base of the per-component kit screenshot tests: every state fixture of one component. */
abstract class KitShotTest(variant: Variant) : ShotTest(variant) {

    /** Captures each [KitFixtures] entry of [screen] as `<screen>__<state>__<theme>__<scale>.png`. */
    protected fun shootAll(screen: String) {
        val fixtures = KitFixtures.of(screen)
        check(fixtures.isNotEmpty()) { "no fixtures for $screen" }
        for (f in fixtures) {
            val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
            Settings.Global.putFloat(
                a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (f.reduced) 0f else 1f,
            )
            val view = f.build(a)
            a.column.addView(
                view,
                view.layoutParams ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            val looper = shadowOf(Looper.getMainLooper())
            looper.idle()
            f.drive?.invoke(view) { ms -> looper.idleFor(Duration.ofMillis(ms)) }
            looper.idle()
            Shots.view(a.column, f.screen, f.state)
        }
    }
}
