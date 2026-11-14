package com.mido.phonebridge

import android.view.HapticFeedbackConstants as H
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The per-API table of TOKENS 10. */
@RunWith(RobolectricTestRunner::class)
class HapticsTest {

    @Config(sdk = [34])
    @Test
    fun `api 34`() {
        assertEquals(H.SEGMENT_TICK, Haptics.constantFor(HapticEvent.TOGGLE))
        assertEquals(H.SEGMENT_TICK, Haptics.constantFor(HapticEvent.SEGMENT))
        assertEquals(H.VIRTUAL_KEY, Haptics.constantFor(HapticEvent.KEY_DOWN))
        assertEquals(H.VIRTUAL_KEY_RELEASE, Haptics.constantFor(HapticEvent.KEY_UP))
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.HOLD_TICK))
        assertEquals(H.CONFIRM, Haptics.constantFor(HapticEvent.HOLD_CONFIRM))
        assertEquals(H.REJECT, Haptics.constantFor(HapticEvent.HOLD_CANCEL))
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.COUNTDOWN_TICK))
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.TAPE_STEP))
        assertEquals(H.REJECT, Haptics.constantFor(HapticEvent.REJECT))
    }

    @Config(sdk = [30])
    @Test
    fun `api 30`() {
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.TOGGLE))
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.SEGMENT))
        assertEquals(H.VIRTUAL_KEY_RELEASE, Haptics.constantFor(HapticEvent.KEY_UP))
        assertEquals(H.CONFIRM, Haptics.constantFor(HapticEvent.HOLD_CONFIRM))
        assertEquals(H.REJECT, Haptics.constantFor(HapticEvent.HOLD_CANCEL))
        assertEquals(H.REJECT, Haptics.constantFor(HapticEvent.REJECT))
    }

    @Config(sdk = [27])
    @Test
    fun `api 27`() {
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.TOGGLE))
        assertEquals(H.VIRTUAL_KEY_RELEASE, Haptics.constantFor(HapticEvent.KEY_UP))
        assertEquals(H.LONG_PRESS, Haptics.constantFor(HapticEvent.HOLD_CONFIRM))
        assertEquals(H.VIRTUAL_KEY, Haptics.constantFor(HapticEvent.HOLD_CANCEL))
        assertEquals(H.LONG_PRESS, Haptics.constantFor(HapticEvent.REJECT))
    }

    @Config(sdk = [26])
    @Test
    fun `api 26`() {
        assertEquals(H.CLOCK_TICK, Haptics.constantFor(HapticEvent.TOGGLE))
        assertEquals(H.VIRTUAL_KEY, Haptics.constantFor(HapticEvent.KEY_DOWN))
        assertNull(Haptics.constantFor(HapticEvent.KEY_UP))
        assertEquals(H.LONG_PRESS, Haptics.constantFor(HapticEvent.HOLD_CONFIRM))
        assertEquals(H.VIRTUAL_KEY, Haptics.constantFor(HapticEvent.HOLD_CANCEL))
        assertEquals(H.LONG_PRESS, Haptics.constantFor(HapticEvent.REJECT))
    }
}
