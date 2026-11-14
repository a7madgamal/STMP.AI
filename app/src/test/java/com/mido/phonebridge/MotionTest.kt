package com.mido.phonebridge

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.tokens.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MotionTest {

    @Test
    fun `a spring starts at x0`() {
        assertEquals(0f, Motion.springAt(0f, 0f, 1f, 2500f, 0.5f), 1e-6f)
    }

    @Test
    fun `a spring settles on the target after two seconds`() {
        for (damping in listOf(0.5f, 0.9f, 1f)) {
            assertEquals(1f, Motion.springAt(2f, 0f, 1f, 2500f, damping), 1e-3f)
        }
    }

    @Test
    fun `an underdamped spring overshoots`() {
        val peak = (1..200).maxOf { Motion.springAt(it / 1000f, 0f, 1f, 2500f, 0.5f) }
        assertTrue("damping 0.5 must pass the target, peak=$peak", peak > 1.01f)
    }

    @Test
    fun `a critically damped or overdamped spring never overshoots`() {
        for (damping in listOf(0.9999f, 1f)) {
            val peak = (0..400).maxOf { Motion.springAt(it / 1000f, 0f, 1f, 3000f, damping) }
            assertTrue("damping $damping peak=$peak", peak <= 1.0005f)
        }
    }

    @Test
    fun `reduced is true only when the animator scale is zero`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(Motion.reduced(ctx))
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(Motion.reduced(ctx))
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        assertFalse(Motion.reduced(ctx))
    }
}
