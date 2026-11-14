package com.mido.phonebridge.screenshots

import android.content.Intent
import androidx.fragment.app.Fragment
import com.mido.phonebridge.debug.ScreenshotHostActivity
import com.mido.phonebridge.ui.Theme
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base of every screenshot test class: runs each `@Test` once per [Matrix.ALL] cell, applies the
 * cell before the test body builds anything, and resets the fixtures afterwards.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
abstract class ShotTest(protected val variant: Variant) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Matrix.parameters()
    }

    @Before
    fun applyVariant() {
        Fixtures.dropSingletons()
        Fixtures.freezeClock()
        Fixtures.resetSystemState()
        variant.apply()
    }

    @After
    fun resetFixtures() = Fixtures.reset()

    /** A tab fragment inside the shell, resumed. */
    protected fun hostTab(fragment: Class<out Fragment>, tab: Theme.Tab): ScreenshotHostActivity {
        val intent = Intent(Fixtures.app, ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, fragment.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, tab.name)
        return Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup().get()
    }
}

/** The first view under [root] whose content description equals [description]. */
fun findByDescription(root: android.view.View, description: String): android.view.View? {
    if (root.contentDescription?.toString() == description) return root
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) findByDescription(root.getChildAt(i), description)?.let { return it }
    }
    return null
}

/** The first ScrollView under [root], or null. */
fun firstScroll(root: android.view.View): android.widget.ScrollView? {
    if (root is android.widget.ScrollView) return root
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) firstScroll(root.getChildAt(i))?.let { return it }
    }
    return null
}

/** Window capture, plus the full-height capture when the screen scrolls. */
fun capture(activity: android.app.Activity, screen: String, state: String) {
    Shots.window(activity, screen, state)
    val scroll = firstScroll(activity.window.decorView) ?: return
    val child = scroll.getChildAt(0) ?: return
    if (child.height > scroll.height) Shots.full(child, screen, state)
}

/** The first view under [root] that is a TextView showing exactly [text]. */
fun findByText(root: android.view.View, text: String): android.view.View? {
    if (root is android.widget.TextView && root.text?.toString() == text) return root
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) findByText(root.getChildAt(i), text)?.let { return it }
    }
    return null
}

/** Starts activity [cls] with [intent] extras and returns it resumed. */
fun <T : android.app.Activity> launch(cls: Class<T>, intent: android.content.Intent? = null): T {
    val i = (intent ?: android.content.Intent()).setClass(Fixtures.app, cls)
    return org.robolectric.Robolectric.buildActivity(cls, i).setup().get()
}

/** Like [findByText], ignoring case. */
fun findByTextIgnoreCase(root: android.view.View, text: String): android.view.View? {
    if (root is android.widget.TextView && root.text?.toString().equals(text, ignoreCase = true)) return root
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) findByTextIgnoreCase(root.getChildAt(i), text)?.let { return it }
    }
    return null
}
