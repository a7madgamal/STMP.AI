package com.mido.phonebridge.notify

import android.content.Context
import android.util.Log
import com.mido.phonebridge.ai.RequestFacts
import com.mido.phonebridge.ai.SummaryProvider
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.notify.NotifyOverlay.Reason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Blocks a server thread while the overlay decides. The `NOTIFY` counterpart to
 * [com.mido.phonebridge.ApprovalGate].
 *
 * Returns a result rather than throwing, for the same reason the modal gate does:
 * every path has to write exactly one audit record, and an exception path is where a
 * record gets forgotten.
 *
 * Deliberately NOT the modal gate with a shorter clock. Three differences matter:
 *
 *  - **FIFO, in order.** One card on screen at a time; the rest wait invisibly. This
 *    was reversed after a device run: showing every prompt at once produced ONE visible
 *    card with ONE countdown, so a queued request's window must not start until it is
 *    actually on screen. Order lives in [NotifyQueue].
 *  - **No biometric.** A 5s silence-approve that demands a fingerprint is a
 *    contradiction, and there is no Activity to host one.
 *  - **A short window.** 5s flat (Q8, decided by Mido 2026-10-17). Predictable beats
 *    forgiving: a countdown you can learn beats a window that varies.
 *
 * **Since the AI summary, a card's total life is fetch + window.** The card appears
 * instantly, the summary is fetched (capped), and only then does the 5s decision clock
 * start (Mido, 2026-10-18: "fetch first then start counting"). So the worst legitimate
 * time for ONE card is [FETCH_CAP_MS] + [WINDOW_MS], and every wait in this file is
 * derived from those two constants rather than from a hardcoded total.
 */
object NotifyGate {

    private const val TAG = "NotifyGate"

    /** How long a card stays up once its clock starts. Visual drain and clock are one value. */
    const val WINDOW_MS = 5_000L

    /**
     * How long the summary fetch may take before the clock starts regardless.
     *
     * Mido's number (2026-10-18): 10s, because the card waits for the summary before its
     * clock starts, so this is time the human spends looking at a card with a live
     * "summarising…" line. A fetch that overruns is abandoned and the card shows the
     * deterministic rows — never an error, never a longer wait.
     */
    const val FETCH_CAP_MS = SummaryProvider.NOTIFY_BUDGET_MS

    /** The longest a single card can legitimately be on screen. */
    private const val CARD_LIFETIME_MS = FETCH_CAP_MS + WINDOW_MS

    /**
     * Extra time the server waits beyond a full line's worth of card lifetimes before
     * giving up on the overlay.
     *
     * Not a second window — a deadlock escape hatch. A request can legitimately wait
     * behind the whole queue, so the bound is derived from the queue's own capacity
     * ([NotifyOverlay.MAX_IN_FLIGHT]) and each card's real lifetime, rather than
     * guessed: too small and a request that is merely waiting its turn gets denied as
     * "never arrived", which would look exactly like a wedged main thread. If the main
     * thread genuinely never runs the callback (process dead, window service gone), the
     * request must still resolve rather than pin a server thread forever. Hitting this
     * IS a defect and is recorded as such: it denies, and it says so in the log.
     */
    private val ESCAPE_MS = NotifyOverlay.MAX_IN_FLIGHT * CARD_LIFETIME_MS + 5_000L

    private val seq = AtomicLong(5000)

    sealed class Result {
        /** Window elapsed in silence, with a human present. */
        class Approved(val shownAt: Long?, val decidedAt: Long) : Result()

        /**
         * The card was TAPPED — a deliberate approval that skipped the wait.
         *
         * Separate from [Approved] because the log must distinguish "he read it and
         * said yes" from "he never touched it": both approve, but only one is evidence
         * the human actually looked. (Silence-approve stays the fallback; this is the
         * stronger signal when it happens.)
         */
        class Tapped(val shownAt: Long?, val decidedAt: Long) : Result()

        /** The ✕ was tapped. */
        class Rejected(val shownAt: Long?, val decidedAt: Long) : Result()

        /**
         * The AI could not describe the request. NOT a human decision and NOT a
         * not-ready bridge (that is the 503 gate): the model is present but failed, so
         * this is our fault and it fails the request rather than approving blind.
         */
        class AiFailed(val detail: String) : Result()

        /**
         * Nobody could have seen it: screen off, locked, refused when the line was full,
         * or no permission to draw.
         */
        class Unavailable(val shownAt: Long?, val decidedAt: Long, val detail: String) : Result()
    }

    /**
     * Post one item and wait for its outcome.
     *
     * The caller has already checked [com.mido.phonebridge.policy.isAttended] and
     * chosen `NOTIFY` for this request; this only shows the prompt and reports what
     * happened. The overlay re-checks attendance at expiry, because that is the moment
     * consent is being inferred.
     *
     * [facts] drives the AI summary. Null skips inference entirely (no model configured
     * for this path), and the card then shows exactly the pre-AI content.
     */
    fun await(
        ctx: Context,
        summary: String,
        label: String,
        verb: Verb,
        facts: RequestFacts?,
        needsAi: Boolean,
    ): Result {
        val id = seq.incrementAndGet()
        val latch = CountDownLatch(1)
        val decided = java.util.concurrent.atomic.AtomicReference<NotifyOverlay.Outcome?>(null)

        NotifyOverlay.show(
            ctx = ctx,
            id = id,
            summary = summary,
            label = label,
            verb = verb,
            windowMs = WINDOW_MS,
            facts = facts,
            needsAi = needsAi,
            fetchCapMs = FETCH_CAP_MS,
        ) { outcome ->
            decided.set(outcome)
            latch.countDown()
        }

        val completed = try {
            latch.await(WINDOW_MS + ESCAPE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        val outcome = decided.get()
        if (!completed || outcome == null) {
            // The overlay never called back. Deny, and make the defect visible rather
            // than pretending the window simply elapsed: those are different facts.
            Log.e(TAG, "notify outcome never arrived for id=$id (waited ${WINDOW_MS + ESCAPE_MS}ms)")
            NotifyOverlay.dismissAll()
            return Result.Unavailable(
                null,
                System.currentTimeMillis(),
                "notify outcome never arrived; treated as deny"
            )
        }

        // `0` means the item was never attached, i.e. it was never shown at all.
        val shownAt: Long? = outcome.shownAt.takeIf { it > 0 }

        return when (outcome.reason) {
            Reason.EXPIRED -> Result.Approved(shownAt, outcome.decidedAt)
            Reason.APPROVED -> Result.Tapped(shownAt, outcome.decidedAt)
            Reason.TAPPED -> Result.Rejected(shownAt, outcome.decidedAt)
            Reason.AI_FAILED -> Result.AiFailed(outcome.detail)
            Reason.UNATTENDED ->
                Result.Unavailable(shownAt, outcome.decidedAt, "screen off or device locked")
            Reason.REFUSED ->
                Result.Unavailable(shownAt, outcome.decidedAt, outcome.detail)
            Reason.UNAVAILABLE ->
                Result.Unavailable(shownAt, outcome.decidedAt, outcome.detail)
        }
    }
}
