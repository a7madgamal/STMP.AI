package com.mido.phonebridge

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.PolicyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForegroundRefreshTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun reset() {
        BridgeService.serverUp = false
        BridgeService.bindAddress = ""
    }

    private fun setMode(mode: GlobalMode) {
        val store = PolicyStore.from(ctx)
        store.set(store.get().withGlobal(mode))
    }

    @Test
    fun `from the app it posts nothing while the bridge is down`() {
        BridgeService.serverUp = false
        BridgeService.refreshForegroundNotice(ctx, fromApp = true)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `a mode change re-posts at once and an unchanged state posts nothing`() {
        BridgeService.serverUp = true
        BridgeService.bindAddress = "100.101.7.22"
        // A distinct address makes the dedupe key differ from any earlier test's.
        BridgeService.bindAddress = "100.101.7.${(100..250).random()}"
        setMode(GlobalMode.WHITELIST)
        BridgeService.refreshForegroundNotice(ctx, fromApp = true)
        val first = shadowOf(nm).getNotification(BridgeService.NOTIF_ID)
        assertEquals("Whitelist mode", first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        setMode(GlobalMode.PARANOID)
        BridgeService.refreshForegroundNotice(ctx, fromApp = true)
        val second = shadowOf(nm).getNotification(BridgeService.NOTIF_ID)
        assertEquals("Paranoid mode", second.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        nm.cancel(BridgeService.NOTIF_ID)
        BridgeService.refreshForegroundNotice(ctx, fromApp = true)
        assertTrue("unchanged state must not re-post", shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `applyGate refreshes the notice after saving the mode`() {
        val src = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }
            .resolve("com/mido/phonebridge/HomeFragment.kt").readText()
        val body = src.substringAfter("private fun applyGate").substringBefore("// --- recent requests")
        assertTrue(body.indexOf("refreshForegroundNotice(") > body.indexOf("withGlobal("))
    }
}
