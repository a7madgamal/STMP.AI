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

    /**
     * The whole screen with its scroll content at full height: the window is re-laid-out taller by
     * exactly what the content overflows by, so the tab bar and header stay where they belong.
     * Call only for screens that scroll, and last (it leaves the window at the taller size).
     */
    fun full(scrollChild: View, screen: String, state: String) {
        settle()
        val scroll = scrollChild.parent as View
        val overflow = (scrollChild.height - scroll.height).coerceAtLeast(0)
        val root = scrollChild.rootView
        val width = root.width
        val height = root.height + overflow
        root.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        settle()
        root.captureRoboImage(path(screen, state, full = true), OPTIONS)
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
