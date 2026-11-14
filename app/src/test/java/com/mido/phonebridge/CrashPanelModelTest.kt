package com.mido.phonebridge

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashPanelModelTest {

    private val crash = "at=1\nversion=1\nthread=main\njava.lang.IllegalStateException: boom\n    at a.B.c(B.kt:1)"
    private val stall = "at=2\nversion=1\nthread=stall-watch\nfrozen thread=main\n    at a.B.d(B.kt:2)"

    @Test fun `nothing recorded shows no panel`() {
        assertNull(CrashPanelModel.of(null, null))
    }

    @Test fun `both records show the crash for label detail and clip name`() {
        val m = CrashPanelModel.of(crash, stall)!!
        assertFalse(m.isStall)
        assertEquals("The app crashed", m.label)
        assertEquals(crash, m.detail)
        assertEquals("STMP crash", m.clipName)
        assertTrue(m.headline.contains("IllegalStateException"))
    }

    @Test fun `a stall alone is labelled and copied as a stall`() {
        val m = CrashPanelModel.of(null, stall)!!
        assertTrue(m.isStall)
        assertEquals("The screen froze", m.label)
        assertEquals(stall, m.detail)
        assertEquals("STMP stall", m.clipName)
    }

    @Test fun `a stall headline drops the package path`() {
        val long = "at=2\nfrozen thread=main\n    at com.mido.phonebridge.HomeFragment.render(HomeFragment.kt:377)"
        assertEquals("Frozen in HomeFragment.render (HomeFragment.kt:377)", CrashPanelModel.of(null, long)!!.headline)
        assertEquals("Frozen in B.d (B.kt:2)", CrashPanelModel.of(null, stall)!!.headline)
    }

    @Test fun `a recorded stall is read through the evidence rule and clear removes it`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "last-stall.txt").writeText(stall)
        assertNotNull(Stall.last(ctx))
        Stall.clear(ctx)
        assertNull(Stall.last(ctx))
    }

    @Test fun `a stall written before the install is dropped like a crash`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPm = org.robolectric.Shadows.shadowOf(ctx.packageManager)
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        info.lastUpdateTime = 5_000L
        shadowPm.installPackage(info)
        File(ctx.filesDir, "last-stall.txt").writeText("at=1000\n$stall")
        assertNull(Stall.last(ctx))
        assertFalse(File(ctx.filesDir, "last-stall.txt").exists())
    }
}
