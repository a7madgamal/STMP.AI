package com.mido.phonebridge

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **A tap that was ever covered must not approve.**
 *
 * The card decided on `ACTION_UP` alone, which answers "is something over me *now*". A cover that
 * appears mid-tap and is pulled away just before release answers "no" at the moment of decision, so
 * a tap the human could not see still approved the request.
 *
 * Two things are asserted:
 *  - the gesture accumulates obscuring across its whole life, and only `DOWN` resets it, so a
 *    mid-gesture cover cannot be forgotten before `UP`;
 *  - the approve branch consults that flag before it settles.
 *
 * The behavioural half is separate and drives [obscuredAcrossGesture] directly, because the touch
 * listener lives inside a WindowManager-attached view that a unit test cannot build.
 */
class NotifyObscuredTapTest {

    private val overlay = File("src/main/java/com/mido/phonebridge/notify/NotifyOverlay.kt")

    private val source: String by lazy {
        assertTrue("source not found at ${overlay.absolutePath}", overlay.isFile)
        overlay.readText()
    }

    @Test
    fun `a mid-gesture cover is remembered until the tap ends`() {
        // Mirrors the listener: set on DOWN, accumulate on every event, read at UP.
        var obscured = false
        fun onEvent(flagSet: Boolean, action: Int) {
            if (flagSet) obscured = true
            if (action == MotionEvent.ACTION_DOWN) obscured = flagSet
        }

        onEvent(flagSet = false, action = MotionEvent.ACTION_DOWN)
        assertFalse("a clear DOWN must start out approvable", obscured)

        onEvent(flagSet = true, action = MotionEvent.ACTION_MOVE)
        onEvent(flagSet = false, action = MotionEvent.ACTION_MOVE)
        assertTrue(
            "a cover during the gesture must still veto the tap after it is pulled away",
            obscured,
        )

        onEvent(flagSet = false, action = MotionEvent.ACTION_DOWN)
        assertFalse("a fresh tap must be approvable again", obscured)
    }

    @Test
    fun `the approve branch refuses a gesture that was obscured`() {
        assertTrue(
            "the ACTION_UP branch must consult obscuredThisGesture before settling",
            Regex(
                """ACTION_UP\)\s*\{[\s\S]{0,600}?if \(obscuredThisGesture\)"""
            ).containsMatchIn(source),
        )
    }

    @Test
    fun `the flag accumulates on every event and resets only on DOWN`() {
        assertTrue(
            "obscuring must be sampled on every event, not only on UP",
            source.contains("if (isObscured(ev)) obscuredThisGesture = true"),
        )
        assertTrue(
            "DOWN must reset the flag so a later clear tap still works",
            Regex("""ACTION_DOWN\s*->\s*\{[\s\S]{0,400}?obscuredThisGesture = isObscured\(ev\)""")
                .containsMatchIn(source),
        )
    }

    @Test
    fun `only full obscuring counts, never partial`() {
        assertTrue(
            "the check must use FLAG_WINDOW_IS_OBSCURED",
            source.contains("MotionEvent.FLAG_WINDOW_IS_OBSCURED"),
        )
        assertFalse(
            "partial obscuring must NOT veto: a notification shade edge or a gesture bar is normal",
            source.contains("FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0"),
        )
    }
}
