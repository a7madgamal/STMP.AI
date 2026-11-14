package com.mido.phonebridge.kit

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.DebugLogOpen
import com.mido.phonebridge.OptionsFragment
import com.mido.phonebridge.debug.ScreenshotHostActivity
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.ui.Theme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Show/Hide log key keeps its width and place when the log opens (Copy gets a slot, not the row). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class DebugKeysStableTest {
    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock() }

    @After fun after() = Fixtures.reset()

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun toggleKey(open: Boolean): Pair<Int, Int> {
        Fixtures.dropSingletons()
        Fixtures.debugLog(listOf("09:40:00  server started"))
        DebugLogOpen.open = open
        val intent = Intent(Fixtures.app, ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, OptionsFragment::class.java.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, Theme.Tab.SETTINGS.name)
        val a: FragmentActivity = Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup().get()
        val label = if (open) "Hide log" else "Show log"
        val key = a.window.decorView.all().filterIsInstance<TextView>().first { it.text.toString() == label }
        return key.width to key.left
    }

    private fun check(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val closed = toggleKey(false)
        val open = toggleKey(true)
        assertTrue("key laid out at $scale: $closed", closed.first > 0)
        assertEquals("width at font scale $scale", closed.first, open.first)
        assertEquals("left at font scale $scale", closed.second, open.second)
    }

    @Test fun `Show and Hide keep width and left at font scale 1_0`() = check(1.0f)

    @Test fun `Show and Hide keep width and left at font scale 1_35`() = check(1.35f)
}
