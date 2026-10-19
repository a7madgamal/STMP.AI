package com.mido.phonebridge.ai

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a [SummaryModel] off the caller's thread and enforces the budget.
 *
 * The single place latency is bounded, so the two prompts cannot disagree about
 * patience: SECURE allows [SECURE_BUDGET_MS] (a 60s window and a full screen),
 * NOTIFY allows [NOTIFY_BUDGET_MS] (Mido's call, 2026-10-18 — the card waits for the
 * summary before its clock starts, so this is time the human spends looking at a
 * card, and it is capped).
 *
 * **Never blocks past the budget, never throws.** A model that is cold, slow,
 * missing, or broken yields null. What the CALLER does with that null is the fail-fast
 * decision (Mido, 2026-10-19): the AI is core, so a null is a fault the request fails
 * on, not a silent fallback to rows-only. This object only bounds the wait; it does not
 * decide whether a null is tolerable.
 */
object SummaryProvider {

    private const val TAG = "SummaryProvider"

    /**
     * Patience on the full-screen modal: the human is already looking at it.
     *
     * **10s, raised from 3s on measured evidence (Mido, 2026-10-19).** The 0.6B model on
     * the S22 Ultra's GPU takes ~3.2s per summary — measured on device, twice (3194ms,
     * 3176ms) — so the original 3s budget discarded EVERY answer as "too late" and the
     * whole AI appeared broken when it was merely 0.2s over. The budget is not a
     * performance target, it is the point at which a late answer stops being worth having;
     * 10s clears the measured cost with room for a colder GPU and a longer request.
     *
     * The cost is real and accepted: the modal is built AFTER the summary, so the human now
     * waits up to 10s before the prompt appears. The 60s decision window starts when it is
     * shown, so that wait is not taken out of the human's time to decide.
     */
    const val SECURE_BUDGET_MS = 10_000L

    /** Patience on the NOTIFY card, before its decision clock starts. */
    const val NOTIFY_BUDGET_MS = 10_000L

    /**
     * One small pool. Inference is CPU-heavy and this app runs one request at a time
     * through the gate, so a single thread is the honest size — a second thread would
     * only contend for the same cores.
     */
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "summary").apply { isDaemon = true }
    }

    /**
     * Summarise [facts], or return null. Blocking for at most [budgetMs].
     *
     * The model call itself is also given the budget (a model may check it and give
     * up early); this adds a hard wall so a wedged native call cannot pin the caller.
     * A wall-clock overrun discards the result rather than using a late answer.
     */
    fun run(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary? {
        val model = ModelRegistry.active(ctx)
        if (!model.isAvailable(ctx)) return null
        return try {
            val future = pool.submit<RequestSummary?> { model.summarize(ctx, facts, budgetMs) }
            val result = future.get(budgetMs + SLACK_MS, TimeUnit.MILLISECONDS)
            if (result == null) {
                Log.w(TAG, "${model.id} produced no summary; the caller fails the request")
            }
            result
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "${model.id} exceeded ${budgetMs}ms wall; abandoning")
            null
        } catch (e: Exception) {
            Log.w(TAG, "summary failed: ${e.message}")
            null
        }
    }

    /**
     * A little extra past the model's own budget before the wall drops. Without it a
     * model that answers at exactly the budget would be killed by scheduling jitter.
     */
    private const val SLACK_MS = 500L
}
