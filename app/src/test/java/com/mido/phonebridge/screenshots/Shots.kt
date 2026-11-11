package com.mido.phonebridge.screenshots

import android.app.Activity
import android.view.View
import android.view.View.MeasureSpec
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.robolectric.shadows.ShadowLooper

/**
 * Capture helpers. Every file is named from the screen, the state and [Matrix.current]:
 * `src/test/screenshots/<screen>/<screen>__<state>__<light|dark>__<100|135>[__full].png`.
 */
object Shots {

    const val ROOT = "src/test/screenshots"

    /** 0.5 % of pixels may differ (anti-aliasing between the Mac and Linux CI); half-size PNGs. */
    val OPTIONS = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005F),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
    )

    fun path(screen: String, state: String, full: Boolean = false): String {
        val v = Matrix.current
        val suffix = if (full) "__full" else ""
        return "$ROOT/$screen/${screen}__${state}__${v.themeName}__${v.scaleName}$suffix.png"
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
    }

    /** The whole window (decor view). */
    fun window(activity: Activity, screen: String, state: String) {
        settle()
        activity.window.decorView.captureRoboImage(path(screen, state), OPTIONS)
    }

    /** The scroll content at its full measured height; call only for screens that scroll. */
    fun full(scrollChild: View, screen: String, state: String) {
        settle()
        val width = scrollChild.width
        scrollChild.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        scrollChild.layout(0, 0, width, scrollChild.measuredHeight)
        scrollChild.captureRoboImage(path(screen, state, full = true), OPTIONS)
    }

    /** One view. */
    fun view(view: View, screen: String, state: String) {
        settle()
        view.captureRoboImage(path(screen, state), OPTIONS)
    }

    /** Whatever the screen shows, including dialogs and overlay windows. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun screen(screen: String, state: String) {
        settle()
        captureScreenRoboImage(path(screen, state), OPTIONS)
    }
}
