package com.mido.phonebridge

import com.mido.phonebridge.ui.CallerCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Biometric codes become words (WP09 3.4.2, D49). */
class CallerCopyTest {

    @Test
    fun `the four known codes get their own sentence`() {
        assertEquals("No fingerprint or screen lock is set up on this phone, so nothing was decided.", CallerCopy.cannotConfirm(11))
        assertEquals("This phone has no fingerprint sensor, so nothing was decided.", CallerCopy.cannotConfirm(12))
        assertEquals("The fingerprint sensor is busy right now, so nothing was decided.", CallerCopy.cannotConfirm(1))
        assertEquals(
            "A security update is needed before this phone can confirm it's you, so nothing was decided.",
            CallerCopy.cannotConfirm(15),
        )
    }

    @Test
    fun `any other code is named in the sentence`() {
        assertTrue(CallerCopy.cannotConfirm(-1).contains("code -1"))
        assertTrue(CallerCopy.cannotConfirm(99).contains("code 99"))
    }

    @Test
    fun `every sentence ends by saying nothing was decided`() {
        for (code in listOf(1, 11, 12, 15, 0, 7, 99)) {
            assertTrue(CallerCopy.cannotConfirm(code).endsWith("nothing was decided."))
        }
    }
}
