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
 * missing, or broken yields null and the caller falls back to the deterministic
 * description it already has. That is the invariant that makes the AI a clarity
 * feature: it can be absent and everything still works.
 */
object SummaryProvider {

    private const val TAG = "SummaryProvider"

    /** Patience on the full-screen modal: the human is already looking at it. */
    const val SECURE_BUDGET_MS = 3_000L

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
                Log.i(TAG, "${model.id} produced no summary; falling back")
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
