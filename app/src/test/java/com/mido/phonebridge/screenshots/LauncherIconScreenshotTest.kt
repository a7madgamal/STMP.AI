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

/** The launcher icon: composed, under three launcher masks, small, and the themed (monochrome) layer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LauncherIconScreenshotTest {

    private val bg = 0xFFE4E2DD.toInt() // stmp_launcher_bg == light body
    private val screen = "launcher_icon"

    @Before fun light() { IconScreenshotSupport.recordOnce() }

    private fun iconBitmap() = IconScreenshotSupport.composed(bg, R.drawable.ic_launcher_foreground)

    @Test fun composed() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.withSafeZone(iconBitmap()), screen, "composed",
    )

    @Test fun mask_circle() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.masked(iconBitmap(), IconScreenshotSupport::circle), screen, "mask_circle")

    @Test fun mask_squircle() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.masked(iconBitmap(), IconScreenshotSupport::squircle), screen, "mask_squircle")

    @Test fun mask_rounded_square() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.masked(iconBitmap(), IconScreenshotSupport::roundedSquare), screen, "mask_rounded_square")

    /** The launcher shows the central 72 dp of the 108 dp icon, here at 48 dp. */
    @Test fun small_48() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.tile(R.drawable.ic_launcher_foreground, 48, bg, drawDp = 72), screen, "small_48")

    @Test fun small_72() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.tile(R.drawable.ic_launcher_foreground, 72, bg, drawDp = 108), screen, "small_72")

    @Test fun mono() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = Palettes.of(ctx)
        IconScreenshotSupport.shoot(
            IconScreenshotSupport.tile(R.drawable.ic_launcher_monochrome, 108, p.body, tint = p.ink),
            screen, "mono",
        )
    }

    @Test fun mono_dark() = IconScreenshotSupport.shoot(
        IconScreenshotSupport.tile(R.drawable.ic_launcher_monochrome, 108, 0xFF151518.toInt(), tint = 0xFFECEBE7.toInt()),
        screen, "mono_dark",
    )
}
