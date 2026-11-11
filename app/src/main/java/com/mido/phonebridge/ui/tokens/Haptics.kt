package com.mido.phonebridge.ui.tokens

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticEvent { TOGGLE, SEGMENT, KEY_DOWN, KEY_UP, HOLD_TICK, HOLD_CONFIRM, HOLD_CANCEL, COUNTDOWN_TICK, TAPE_STEP, REJECT }

object Haptics {
    /** The constant to perform for [event] on this API level, or null when there is none. */
    fun constantFor(event: HapticEvent): Int? {
        return when (event) {
            HapticEvent.TOGGLE, HapticEvent.SEGMENT ->
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HapticEvent.KEY_DOWN -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.KEY_UP ->
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.VIRTUAL_KEY_RELEASE else null
            HapticEvent.HOLD_TICK, HapticEvent.COUNTDOWN_TICK, HapticEvent.TAPE_STEP ->
                HapticFeedbackConstants.CLOCK_TICK
            HapticEvent.HOLD_CONFIRM ->
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
            HapticEvent.HOLD_CANCEL ->
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.REJECT ->
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }

    /** Tests set this to record haptics instead of performing them. */
    @androidx.annotation.VisibleForTesting
    internal var sink: ((View, Int) -> Unit)? = null

    fun perform(view: View, event: HapticEvent) {
        val constant = constantFor(event) ?: return
        val test = sink
        if (test != null) test(view, constant) else view.performHapticFeedback(constant)
    }
}
