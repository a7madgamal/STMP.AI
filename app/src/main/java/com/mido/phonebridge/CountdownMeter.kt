package com.mido.phonebridge

import android.graphics.drawable.ClipDrawable

/**
 * How full the DENY button's drain is, in `ClipDrawable` levels.
 *
 * **Pure and Context-free, for the same reason [ArmGuard] is.** The mapping from "the gate's
 * clock" to "a number the drawable can paint" is a decision a test can check in microseconds,
 * and it had already drifted once in a way only pixels could show: the level was computed inline
 * in the tick, the drawable it fed was silently repainted by a background tint, and the bar was
 * invisible on the phone while every line of code looked right.
 *
 * Extracting it does not fix a tint. It makes the *number* checkable, so the next failure is
 * somewhere the number is not.
 */
object CountdownMeter {

    /** Full window: `ClipDrawable` treats 10000 as "not clipped at all". */
    const val FULL = 10_000

    /**
     * The level for the current moment.
     *
     * **Three phases, and the first two are EMPTY on purpose.**
     *
     * - No arm time yet (`<= 0`): the summary is still being fetched, so no window has opened.
     * - Before the misclick guard: `ArmGuard` has not released the controls. Showing a draining
     *   bar here would claim time the human has not been given, and a full bar before the clock
     *   starts reads as "you have all the time in the world".
     * - Armed: the proportion of the window that is LEFT.
     *
     * @param armAtMs the gate's arm time (`ApprovalGate.currentEnabledAtMs()`), not `shownAt`.
     * @param nowMs the current clock, passed in so the rule is not a hidden `System.currentTimeMillis`.
     * @param windowMs the auto-deny window in milliseconds.
     */
    fun level(armAtMs: Long, nowMs: Long, windowMs: Long): Int {
        if (armAtMs <= 0L) return 0
        if (!ArmGuard.isArmed(armAtMs, nowMs)) return 0
        if (windowMs <= 0L) return 0
        val leftMs = (armAtMs + windowMs - nowMs).coerceAtLeast(0L)
        // Integer maths on purpose: a float here would put a rounding question between the
        // deadline and the bar, and the two are meant to agree to the frame.
        return ((leftMs * FULL) / windowMs).coerceIn(0L, FULL.toLong()).toInt()
    }

    /** The whole seconds left in the window, for the accessibility carrier. */
    fun secondsLeft(armAtMs: Long, nowMs: Long, windowMs: Long): Long =
        if (armAtMs <= 0L || !ArmGuard.isArmed(armAtMs, nowMs)) 0L
        else ((armAtMs + windowMs - nowMs) / 1000L).coerceAtLeast(0L)

    /** Convenience for a caller that holds a `ClipDrawable` and nothing else. */
    fun applyTo(drain: ClipDrawable?, level: Int) {
        if (drain != null) drain.level = level
    }
}
