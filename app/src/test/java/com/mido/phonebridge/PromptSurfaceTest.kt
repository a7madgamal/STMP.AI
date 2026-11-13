package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A prompt that cannot be shown must be REFUSED, immediately and by name.
 *
 * Reported 2026-11-11: *"i didn't get the modal!"*. `fullScreenIntent` was false and the phone
 * was in another app, so the Activity could not be raised. The request then waited the full 60
 * seconds and answered `approval_timeout` — the same answer a human declining would produce.
 * Two completely different situations collapsed into one code, and the wrong one was reported.
 *
 * `PromptSurface` exists so that decision is a pure function with a name, instead of a condition
 * inside the presentation path that only fails on a phone.
 */
class PromptSurfaceTest {

    @Test
    fun `both grants mean the modal can be raised`() {
        assertEquals(
            PromptSurface.Capability.CAN_RAISE,
            PromptSurface.of(fullScreenIntent = true, overlayGranted = true),
        )
    }

    @Test
    fun `either grant is enough to present`() {
        // Overlay alone: a background Activity launch is legal, which is what lifts the card.
        assertEquals(
            PromptSurface.Capability.CAN_RAISE,
            PromptSurface.of(fullScreenIntent = false, overlayGranted = true),
        )
        // Full-screen intent alone: the card may cross a locked screen and other apps.
        assertEquals(
            PromptSurface.Capability.CAN_RAISE,
            PromptSurface.of(fullScreenIntent = true, overlayGranted = false),
        )
    }

    @Test
    fun `neither grant is a refusal, never a wait`() {
        assertNull(
            "with no overlay and no full-screen intent the prompt cannot appear, " +
                "so the gate must refuse instead of timing out",
            PromptSurface.of(fullScreenIntent = false, overlayGranted = false),
        )
    }

    @Test
    fun `the remedy names both controls a human can tap`() {
        val remedy = PromptSurface.remedy()
        assertEquals(true, remedy.contains("Lock-screen popup"))
        assertEquals(true, remedy.contains("Draw over apps"))
    }
}
