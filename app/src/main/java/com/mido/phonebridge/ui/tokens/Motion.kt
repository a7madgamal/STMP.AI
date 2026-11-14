package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.provider.Settings
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

object Motion {
    const val DURATION_PRESS = 60L
    const val DURATION_FAST = 120L
    const val DURATION_TAB = 180L
    const val DURATION_SHARED_AXIS = 250L
    const val DURATION_LED_BREATH = 1600L
    const val HOLD_TO_APPROVE_MS = 1200L
    const val HOLD_TICK_EVERY_MS = 300L
    const val PUNCH_DROP_MS = 180L
    const val PUNCH_JOLT_MS = 220L
    const val PUNCH_SETTLED_MS = 720L
    const val PUNCH_FADE_MS = 160L
    const val TAPE_STEP_MS = 60L
    const val TAPE_STEPS = 4
    const val RECEIPT_SHOW_MS = 1500L
    const val ROCKER_MAX_MS = 140L

    val EASE_STANDARD: PathInterpolator get() = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    val EASE_DECELERATE: PathInterpolator get() = PathInterpolator(0f, 0f, 0.2f, 1f)
    val EASE_ACCELERATE: AccelerateInterpolator get() = AccelerateInterpolator(1.5f)
    val LINEAR: LinearInterpolator get() = LinearInterpolator()

    const val SPRING_ROCKER_STIFFNESS = 3000f
    const val SPRING_ROCKER_DAMPING = 0.9f
    const val SPRING_HOLD_RETURN_STIFFNESS = 1500f
    const val SPRING_HOLD_RETURN_DAMPING = 1.0f
    const val SPRING_PUNCH_STIFFNESS = 2500f
    const val SPRING_PUNCH_DAMPING = 0.5f

    /** True when the user turned animations off (animator duration scale 0). */
    fun reduced(ctx: Context): Boolean = Settings.Global.getFloat(
        ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f

    /** Position at time t (s) of a spring from x0 to target, mass 1, at rest at t = 0. */
    fun springAt(t: Float, x0: Float, target: Float, stiffness: Float, damping: Float): Float {
        val w0 = kotlin.math.sqrt(stiffness)
        val d = x0 - target
        return if (damping < 1f) {
            val root = kotlin.math.sqrt(1f - damping * damping)
            val wd = w0 * root
            val decay = kotlin.math.exp(-damping * w0 * t)
            target + d * decay * (kotlin.math.cos(wd * t) + (damping / root) * kotlin.math.sin(wd * t))
        } else {
            target + d * (1f + w0 * t) * kotlin.math.exp(-w0 * t)
        }
    }
}
