package com.mido.phonebridge

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeServiceTeardownTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun reset() {
        BridgeService.serverUp = false
        BridgeService.serviceAlive = false
        BridgeService.bindAddress = ""
    }

    @Test
    fun `a refresh after the service is destroyed posts nothing`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        assertTrue(BridgeService.serviceAlive)
        controller.destroy()
        assertFalse(BridgeService.serviceAlive)
        nm.cancelAll()

        // A key no earlier post used, so only the running guard can stop the post.
        BridgeService.serverUp = true
        BridgeService.bindAddress = "100.64.${(1..250).random()}.${(1..250).random()}"
        // What a probe thread that outlived onDestroy would do.
        BridgeService.refreshForegroundNotice(ctx)
        BridgeService.refreshForegroundNotice(ctx, forceStarting = true)

        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `a watchdog probe that finishes after destroy neither refreshes nor reschedules`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val service = controller.get()
        controller.destroy()
        nm.cancelAll()

        service.finishWatchdogProbe(false)
        ShadowLooper.idleMainLooper()

        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(java.time.Duration.ZERO, shadowOf(android.os.Looper.getMainLooper()).nextScheduledTaskTime)
    }
}
