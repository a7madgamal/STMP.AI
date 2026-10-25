package com.mido.phonebridge

import com.mido.phonebridge.core.StartupRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry budget, as a pure object.
 *
 * **Why this is testable at all and why that matters.** The ladder is the one piece of the
 * auto-start release that could turn into a loop: a service that keeps rescheduling itself
 * forever for a condition only a human can fix (open Tailscale). Keeping the schedule in a pure
 * object — no `Handler`, no `Context` — is what lets this suite pin the boundaries, the same
 * extraction rule the project already applies to `HostAllowlist`, `ResponseBody` and
 * `VaultState`. A budget buried inside `postDelayed` calls is a budget nobody can assert on.
 */
class StartupRetryTest {

    @Test
    fun `every attempt has a delay and the budget ends`() {
        val attempts = StartupRetry.maxAttempts()
        assertTrue("a zero-length ladder would be the old stopSelf() bug", attempts > 0)
        for (i in 0 until attempts) {
            val delay = StartupRetry.delayFor(i)
            assertTrue("attempt $i must have a positive delay", delay != null && delay > 0)
        }
        // The boundary that matters: one past the end must be null, or the ladder never ends.
        assertNull(StartupRetry.delayFor(attempts))
        assertNull(StartupRetry.delayFor(attempts + 1))
    }

    @Test
    fun `the delays are strictly increasing`() {
        // Not cosmetic: a ladder that got shorter would spend its budget in the first few
        // seconds after boot, which is exactly when the tailnet address is least likely to
        // exist. Growth is what buys coverage of a slow Tailscale start.
        val delays = (0 until StartupRetry.maxAttempts()).map { StartupRetry.delayFor(it)!! }
        for (i in 1 until delays.size) {
            assertTrue(
                "delay[$i]=${delays[i]} must exceed delay[${i - 1}]=${delays[i - 1]}",
                delays[i] > delays[i - 1],
            )
        }
    }

    @Test
    fun `the total budget is long enough for Tailscale and short enough to notice`() {
        // The real constraint, stated as a range rather than a magic number: too short and the
        // common boot case fails (Tailscale needs seconds, not milliseconds); too long and the
        // human waits minutes before being told the bridge is down.
        assertTrue(
            "total ${StartupRetry.TOTAL_MS}ms is too short to outlast a Tailscale start",
            StartupRetry.TOTAL_MS >= 60_000L,
        )
        assertTrue(
            "total ${StartupRetry.TOTAL_MS}ms delays the reminder too long",
            StartupRetry.TOTAL_MS <= 10 * 60_000L,
        )
    }

    @Test
    fun `a negative attempt is treated as spent, not as the first delay`() {
        // `getOrNull` gives null, which is the give-up signal. The alternative — an index that
        // wraps or clamps — would silently restart the budget.
        assertNull(StartupRetry.delayFor(-1))
    }

    @Test
    fun `the ladder is exactly the documented four attempts`() {
        // Pinned so a future edit to the array has to come with a deliberate change here, and
        // so the notification copy ("retrying (n/4)") cannot drift from the real count.
        assertEquals(4, StartupRetry.maxAttempts())
    }
}
