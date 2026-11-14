package com.mido.phonebridge.gate

import com.mido.phonebridge.ArmGuard

/** Where the gate's controls are in their life (WP03a 8.1). */
sealed interface ArmPhase {
    /** No arm time yet: the summary is still being fetched (`enabledAtMs == 0`). */
    data object SummaryPending : ArmPhase

    /** The misclick guard is running: `enabledAt > 0` and `now < enabledAt`. */
    data class Guard(val secondsLeft: Long) : ArmPhase

    /** The decision window is open: `now >= enabledAt`. */
    data class Armed(val secondsLeft: Long) : ArmPhase
}

/**
 * The arm phase and the countdown text, from the gate's arm time and the clock. Pure, so the
 * server latch and the UI provably share one arithmetic (the same as the gate's own window).
 */
object GateArming {

    /** While the fingerprint is locked out, the gate re-reads its status this often (D20, D46). */
    const val BIO_RECHECK_MS = 5_000L

    fun phase(enabledAtMs: Long, nowMs: Long, timeoutSeconds: Long): ArmPhase = when {
        enabledAtMs == 0L -> ArmPhase.SummaryPending
        !ArmGuard.isArmed(enabledAtMs, nowMs) -> ArmPhase.Guard(ArmGuard.remainingSeconds(enabledAtMs, nowMs))
        else -> ArmPhase.Armed((timeoutSeconds - (nowMs - enabledAtMs) / 1000L).coerceAtLeast(0L))
    }

    fun text(phase: ArmPhase): String = when (phase) {
        ArmPhase.SummaryPending -> GateCopy.ARM_SUMMARY_PENDING
        is ArmPhase.Guard -> GateCopy.armGuard(phase.secondsLeft)
        is ArmPhase.Armed -> GateCopy.armArmed(phase.secondsLeft)
    }
}

/** Clock seam for fixtures; production is System.currentTimeMillis. */
internal object GateClock {
    @androidx.annotation.VisibleForTesting
    @Volatile var nowMs: () -> Long = { System.currentTimeMillis() }
}
