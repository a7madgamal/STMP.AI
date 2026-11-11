package com.mido.phonebridge.screenshots

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.HomeFragment
import com.mido.phonebridge.debug.ScreenshotHostActivity
import com.mido.phonebridge.ui.Theme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class BaselineSmokeScreenshotTest(private val variant: Variant) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Matrix.parameters()
    }

    @Test
    fun home_default() {
        variant.apply()
        val intent = Intent(ApplicationProvider.getApplicationContext(), ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, HomeFragment::class.java.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, Theme.Tab.HOME.name)
        val activity = Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup().get()
        Shots.window(activity, "home", "default")
    }
}
