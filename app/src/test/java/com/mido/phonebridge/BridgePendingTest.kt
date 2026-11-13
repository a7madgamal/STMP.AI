package com.mido.phonebridge

import com.mido.phonebridge.HomeFragment.Companion.PENDING_WINDOW_MS
import com.mido.phonebridge.HomeFragment.Companion.isBridgePending
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePendingTest {
    private val since = 1_000L

    @Test fun pending_while_window_open_and_not_there_yet() {
        assertTrue(isBridgePending(true, false, since, since + 500, ""))
        assertTrue(isBridgePending(false, true, since, since + 500, null))
    }

    @Test fun ends_when_window_closes() {
        assertFalse(isBridgePending(true, false, since, since + PENDING_WINDOW_MS, ""))
        assertFalse(isBridgePending(false, true, since, since + PENDING_WINDOW_MS + 1, null))
    }

    @Test fun ends_when_bridge_reached_asked_state() {
        assertFalse(isBridgePending(true, true, since, since + 10, ""))
    }

    @Test fun start_ends_at_once_when_an_error_was_set() {
        assertFalse(isBridgePending(true, false, since, since + 10, "bind failed"))
    }

    @Test fun no_tap_means_not_pending() {
        assertFalse(isBridgePending(null, false, 0L, 5_000L, ""))
        assertFalse(isBridgePending(true, false, 0L, 5_000L, ""))
    }
}
