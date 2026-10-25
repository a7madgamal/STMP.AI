package com.mido.phonebridge.core

/**
 * How long to wait before re-checking for a Tailscale address.
 *
 * **Why this exists at all.** `BridgeService` refuses to start when the phone has no `100.x`
 * address, and it used to `stopSelf()` on that condition. That is correct for a manual start —
 * a human just tapped the button, so a refusal is the honest answer. It is WRONG for a start
 * triggered by `BOOT_COMPLETED`, where Tailscale is a separate app that has almost certainly not
 * established its address yet. The old behaviour turned "booted a few seconds early" into "the
 * bridge never comes up at all", which is exactly the failure observed on 2026-10-25.
 *
 * So a boot start gets a budget of retries instead of an immediate death. This object is the
 * budget, kept PURE and separate so the schedule can be asserted without an Android runtime —
 * the same extraction rule the project already applies to `HostAllowlist`, `ResponseBody` and
 * `VaultState`. A retry policy buried in a `Handler.postDelayed` chain is a policy nobody can
 * test.
 *
 * **Bounded on purpose.** Four attempts over ~5 minutes total. An unbounded retry would be a
 * loop that keeps a foreground service alive forever for a condition only the human can fix
 * (open Tailscale), and the whole point of the notification is to hand that decision back.
 */
object StartupRetry {

    /**
     * Delay BEFORE each attempt, in order. Attempt 0 waits 10s, attempt 1 waits 30s, and so on.
     *
     * The first delay is short because the common case is a tailnet that comes up within
     * seconds of boot; the later gaps grow because a tailnet that is still missing after a
     * minute is usually waiting on something slower (radio, VPN handshake, the user unlocking
     * the phone), not on a few more milliseconds.
     */
    val DELAYS_MS: LongArray = longArrayOf(10_000L, 30_000L, 90_000L, 180_000L)

    /** Total wall-clock budget across every attempt, for user-facing copy. */
    val TOTAL_MS: Long = DELAYS_MS.sum()

    /** How many retries are available. Attempts are 0-based, so this is also the count. */
    fun maxAttempts(): Int = DELAYS_MS.size

    /**
     * The delay for [attempt], or `null` when the budget is spent and the caller must give up.
     *
     * Null is the "stop retrying" signal rather than a sentinel duration: a caller that gets a
     * number always schedules, and a caller that gets null always notifies. Collapsing those two
     * into one value is how a retry loop ends up either infinite or silent.
     */
    fun delayFor(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)
}
