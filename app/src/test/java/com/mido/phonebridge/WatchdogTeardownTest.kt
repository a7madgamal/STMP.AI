package com.mido.phonebridge

import android.os.Looper
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

/**
 * A watchdog probe that outlives the service must not re-arm it.
 *
 * **The race, stated once.** `watchdogTick` probes on a background thread and the probe can take
 * up to `PROBE_TIMEOUT_MS` (1500 ms). `onDestroy` clears the handler's callbacks — but a probe
 * already in flight returns AFTER that clear and calls `postDelayed(watchdogTick, 60_000)`.
 * Clearing callbacks cannot stop it, because the post has not happened yet when the clear runs.
 *
 * The symptom is a stopped bridge that keeps waking every minute to probe a server it already
 * closed, for as long as the process lives. Nothing on screen says so.
 *
 * `FinishWatchdogProbe` is driven directly: the outcome is what matters, and a real socket would
 * only make the test slower and flakier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchdogTeardownTest {

    private fun scheduledNextTask(): java.time.Duration =
        shadowOf(Looper.getMainLooper()).nextScheduledTaskTime

    @Test
    fun `a probe that finishes after destroy does not reschedule the watchdog`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val service = controller.get()
        controller.destroy()
        // Drain anything teardown itself queued, so the assertion below can only be about the probe.
        ShadowLooper.idleMainLooper()

        service.finishWatchdogProbe(ok = false)
        ShadowLooper.idleMainLooper()

        assertEquals(
            "a late probe must not leave a watchdog tick scheduled on a dead service",
            java.time.Duration.ZERO,
            scheduledNextTask(),
        )
    }

    @Test
    fun `a probe on a live service does reschedule, so the guard is not simply always-off`() {
        // Pins the other side. Without this, deleting the whole watchdog would pass the test above.
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val service = controller.get()

        service.finishWatchdogProbe(ok = true)

        assertTrue(
            "a live service must keep probing; otherwise a dead server is never rebound",
            scheduledNextTask() > java.time.Duration.ZERO,
        )
        controller.destroy()
    }

    @Test
    fun `a destroyed service reports itself destroyed`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        assertFalse("a created service is alive", controller.get().isDestroyedForTest())
        controller.destroy()
        assertTrue("a destroyed service must know it", controller.get().isDestroyedForTest())
    }
}
