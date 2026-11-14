package com.mido.phonebridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/** D26, D53: adaptive, vector, inside the 66 dp safe zone, with a white-only monochrome layer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class LauncherIconTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val px = 432
    private val safeRadius = 132f // 33 dp at 4 px per dp

    private fun render(d: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun layer(id: Int): Drawable =
        checkNotNull(ResourcesCompat.getDrawable(ctx.resources, id, null))

    private fun forEachVisible(bmp: Bitmap, f: (x: Int, y: Int, argb: Int) -> Unit) {
        for (y in 0 until px) for (x in 0 until px) {
            val c = bmp.getPixel(x, y)
            if ((c ushr 24) > 0) f(x, y, c)
        }
    }

    @Test
    fun `the launcher icon is adaptive with all three layers`() {
        val d = ResourcesCompat.getDrawable(ctx.resources, R.mipmap.ic_launcher, null)
        assertTrue(d is AdaptiveIconDrawable)
        d as AdaptiveIconDrawable
        assertNotNull(d.background)
        assertNotNull(d.foreground)
        assertNotNull(d.monochrome)
    }

    @Test
    fun `the foreground lies inside the safe zone and shows the green LED`() {
        val bmp = render(layer(R.drawable.ic_launcher_foreground))
        var outside = 0
        var led = 0
        forEachVisible(bmp) { x, y, c ->
            if (hypot(x - px / 2f, y - px / 2f) > safeRadius) outside++
            if ((c and 0xFFFFFF) == 0x20B26A && (c ushr 24) == 0xFF) led++
        }
        assertEquals("pixels outside the 66 dp safe zone", 0, outside)
        assertTrue("no LED pixels", led > 100)
    }

    @Test
    fun `the monochrome layer is white-only and inside the safe zone`() {
        val bmp = render(layer(R.drawable.ic_launcher_monochrome))
        var visible = 0
        forEachVisible(bmp) { x, y, c ->
            visible++
            assertEquals("not white at $x,$y", 0xFFFFFF, c and 0xFFFFFF)
            assertTrue(hypot(x - px / 2f, y - px / 2f) <= safeRadius)
        }
        assertTrue(visible > 1000)
    }

    @Test
    fun `no bitmap launcher layers remain`() {
        val res = listOf("src/main/res", "app/src/main/res").map { File(it) }.first { it.exists() }
        val left = res.walkTopDown().filter { it.isFile && it.name.startsWith("ic_launcher") }.map { it.name }.toSet()
        assertEquals(setOf("ic_launcher.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"), left)
    }
}
