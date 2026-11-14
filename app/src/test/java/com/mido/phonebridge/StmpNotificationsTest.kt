package com.mido.phonebridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.BridgeStatusNotice
import com.mido.phonebridge.core.StmpNotifications
import com.mido.phonebridge.policy.GlobalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StmpNotificationsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun pi(code: Int, intent: Intent = Intent(ctx, MainActivity::class.java)): PendingIntent =
        PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE)

    private fun stopPi() = PendingIntent.getService(
        ctx, 3, Intent(ctx, BridgeService::class.java).setAction(StmpNotifications.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `running shows address, port and mode with Open and Stop bridge`() {
        val n = StmpNotifications.running(ctx, "100.101.7.22", 8787, GlobalMode.PARANOID, pi(0), stopPi())
        assertEquals("STMP · open on 100.101.7.22:8787", title(n))
        assertEquals("Paranoid mode", text(n))
        assertEquals(listOf("Open", "Stop bridge"), n.actions.map { it.title.toString() })
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(R.drawable.ic_stat_stmp, n.smallIcon.resId)
        assertEquals(BridgeService.CHANNEL_ID, n.channelId)
    }

    @Test
    fun `the stop action starts the bridge service with ACTION_STOP`() {
        val n = StmpNotifications.running(ctx, null, 8787, null, pi(0), stopPi())
        val saved = shadowOf(n.actions[1].actionIntent)
        assertTrue(saved.isService)
        assertEquals(StmpNotifications.ACTION_STOP, saved.savedIntent.action)
        assertEquals(BridgeService::class.java.name, saved.savedIntent.component?.className)
    }

    @Test
    fun `the lock screen version has no address, mode or actions`() {
        val n = StmpNotifications.running(ctx, "100.101.7.22", 8787, GlobalMode.WHITELIST, pi(0), stopPi())
        val pub = checkNotNull(n.publicVersion)
        assertEquals("STMP is running", title(pub))
        assertNull(pub.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(pub.actions == null || pub.actions.isEmpty())
    }

    @Test
    fun `status copy and flags follow BridgeStatusNotice for every kind`() {
        for (k in BridgeStatusNotice.Kind.entries) {
            val n = StmpNotifications.status(ctx, k, 2, 4, pi(1))
            assertEquals(BridgeStatusNotice.title(k), title(n))
            assertEquals(BridgeStatusNotice.text(k, 2, 4), text(n))
            assertEquals(R.drawable.ic_stat_stmp, n.smallIcon.resId)
            assertEquals(BridgeStatusNotice.ongoing(k), n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(BridgeStatusNotice.autoCancel(k), n.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(BridgeService.STATUS_CHANNEL_ID, n.channelId)
        }
    }

    @Test
    fun `the caller prompt names the next step and hides the address on a locked screen`() {
        val n = StmpNotifications.callerPrompt(ctx, "100.64.0.9", pi(2))
        assertEquals("New device wants to connect", title(n))
        assertTrue(text(n).contains("100.64.0.9"))
        assertTrue(text(n).contains("Name it or block it."))
        assertNotNull(n.fullScreenIntent)
        assertEquals(R.drawable.ic_stat_stmp, n.smallIcon.resId)
        val pub = checkNotNull(n.publicVersion)
        assertEquals("New device wants to connect", title(pub))
        assertFalse(pub.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains("100.64.0.9") ?: false)
    }

    @Test
    fun `the caller channel id matches CallerPrompt`() {
        val src = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }
            .resolve("com/mido/phonebridge/callers/CallerPrompt.kt").readText()
        assertTrue(src.contains("CHANNEL_ID = \"${StmpNotifications.CALLER_CHANNEL_ID}\""))
    }
}
