package com.mido.phonebridge.ui

import android.content.Context
import android.provider.Settings

/**
 * Presentation-only motion choices. Nothing here may resolve a request or move its deadline.
 *
 * System animation scale is the platform's reduced-motion signal. A direct setting read is
 * used because it is available from minSdk 26 and makes the branch testable without inventing
 * an app-specific reduced-motion preference.
 */
object PaperMotion {
    const val PRESENT_MS = 300L
    const val REDUCED_FADE_MS = 110L
    const val STAMP_TOTAL_MS = 650L
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

    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

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

    fun entrance(reduced: Boolean, alreadyPresented: Boolean): Entrance = when {
        alreadyPresented -> Entrance(perspective = false, durationMs = 0L, initialAlpha = 1f)
        reduced -> Entrance(perspective = false, durationMs = REDUCED_FADE_MS, initialAlpha = 0f)
        else -> Entrance(perspective = true, durationMs = PRESENT_MS, initialAlpha = 0f)
    }
}
