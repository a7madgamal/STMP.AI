package com.mido.phonebridge.ui

import android.content.Context

/**
 * Presentation-only motion choices. Nothing here may resolve a request or move its deadline.
 *
 * **There is no reduced-motion branch, and that is deliberate.** It used to read the system
 * `ANIMATOR_DURATION_SCALE` and collapse the stamp to a 110ms static fade with no contact frame.
 * Reported 2026-11-11: *"in paranoid mode i didn't see the stamp approval animation at all"* —
 * the confirmation had been deleted to avoid vestibular motion, which is the wrong trade. The
 * stamp is the app's receipt for a security decision; it must always play.
 */
object PaperMotion {
    const val PRESENT_MS = 300L
    const val STAMP_TOTAL_MS = 650L

    /**
     * How long the stamp may wait for the window to be visible before playing anyway.
     *
     * A biometric prompt is its own window and is still dismissing when the approve callback
     * fires, so the stamp's clock must not start until that window is gone. The ceiling exists
     * so a platform that never reports focus plays a late receipt instead of no receipt — and
     * every backstop that waits for the stamp must use it, or it fires mid-arm and kills it.
     */
    const val STAMP_ARM_FALLBACK_MS = 2_500L
    const val TEAR_MS = 300L
    const val PRESENT_TOP_WIDTH_FACTOR = 1.09f
    const val PRESENT_TILT_DEGREES = 2.5f

    /**
     * Camera distance is one sheet height. Projecting the two vertical edges at [tiltDegrees]
     * gives their width ratio; it cancels the common perspective scale. The positive X rotation
     * used by PaperEntranceView makes the top edge wider. At 2.5°, this is about 1.09.
     */
    fun projectedTopWidthRatio(tiltDegrees: Double = PRESENT_TILT_DEGREES.toDouble()): Double {
        val theta = Math.toRadians(tiltDegrees)
        return (kotlin.math.cos(theta) + kotlin.math.sin(theta)) /
            (kotlin.math.cos(theta) - kotlin.math.sin(theta))
    }

    fun dp(context: Context, value: Int): Float =
        value * context.resources.displayMetrics.density

    /** A request gets one entrance per id, across Activity recreation and re-show. */
    class EntranceRegistry {
        private val presented = HashSet<Long>()

        @Synchronized
        fun claim(requestId: Long): Boolean = presented.add(requestId)

        @Synchronized
        fun clear() = presented.clear()
    }

    data class Entrance(
        val perspective: Boolean,
        val durationMs: Long,
        val initialAlpha: Float,
    )

    fun entrance(alreadyPresented: Boolean): Entrance = when {
        // Re-shown after a return or a rotation: it is already on screen, so an entrance would
        // announce something nobody did.
        alreadyPresented -> Entrance(perspective = false, durationMs = 0L, initialAlpha = 1f)
        else -> Entrance(perspective = true, durationMs = PRESENT_MS, initialAlpha = 0f)
    }
}
