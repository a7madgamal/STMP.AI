package com.mido.phonebridge.screenshots

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 24 dp white notification icon on a dark grey readout and on the accent circle of Android 12+. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class NotificationIconScreenshotTest {

    @Before fun light() { IconScreenshotSupport.recordOnce() }

    private fun shoot(dp: Int, onAccent: Boolean) {
        val p = Palettes.of(ApplicationProvider.getApplicationContext<Context>())
        val bg = if (onAccent) p.ledOk else p.readout
        IconScreenshotSupport.shoot(
            IconScreenshotSupport.tile(R.drawable.ic_stat_stmp, dp, bg),
            "notification_icon", "${dp}dp" + if (onAccent) "_accent" else "",
        )
    }

    @Test fun readout_24() = shoot(24, false)
    @Test fun accent_24() = shoot(24, true)
    @Test fun readout_48() = shoot(48, false)
    @Test fun accent_48() = shoot(48, true)
}
