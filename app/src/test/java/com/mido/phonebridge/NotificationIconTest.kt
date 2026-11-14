package com.mido.phonebridge

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The small icon is masked by alpha on Android, so every visible pixel must be white. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class NotificationIconTest {
    @Test
    fun `ic_stat_stmp is white only`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = checkNotNull(ResourcesCompat.getDrawable(ctx.resources, R.drawable.ic_stat_stmp, null))
        val size = 96 // 24 dp at 4 px per dp
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        var visible = 0
        for (y in 0 until size) for (x in 0 until size) {
            val c = bmp.getPixel(x, y)
            if ((c ushr 24) > 0) {
                visible++
                assertEquals("not white at $x,$y", 0xFFFFFF, c and 0xFFFFFF)
            }
        }
        assertTrue(visible > 100)
    }
}
