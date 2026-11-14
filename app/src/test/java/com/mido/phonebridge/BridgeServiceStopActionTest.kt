package com.mido.phonebridge

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.StmpNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeServiceStopActionTest {

    @Test
    fun `ACTION_STOP stops the service and does not start foreground`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val service = controller.get()
        val intent = Intent(ApplicationProvider.getApplicationContext(), BridgeService::class.java)
            .setAction(StmpNotifications.ACTION_STOP)
        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(null, shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `isStop matches only the stop action`() {
        assertTrue(StmpNotifications.isStop(Intent(StmpNotifications.ACTION_STOP)))
        assertFalse(StmpNotifications.isStop(Intent("other")))
        assertFalse(StmpNotifications.isStop(null))
    }

    @Test
    fun `onStartCommand checks for the stop action before anything else`() {
        val src = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }
            .resolve("com/mido/phonebridge/BridgeService.kt").readText()
        val body = src.substringAfter("override fun onStartCommand")
        assertTrue(body.indexOf("StmpNotifications.isStop(intent)") in 0 until body.indexOf("startForegroundCompat()"))
    }
}
