package com.mido.phonebridge.ai

import android.content.Context

/**
 * A risk assessment for one request. Deliberately three coarse buckets: the model
 * is a small local LLM, and a finer scale would be false precision. The badge
 * renders this; nothing gates on it (display-only, Mido's choice 2026-10-18).
 */
enum class Risk { LOW, MEDIUM, HIGH }

/**
 * What a summariser is asked about.
 *
 * Deterministic facts only — the route's own label, the existing summary, and the
 * labelled rows the modal already shows. The raw JSON is NOT handed over: it is
 * unbounded, it contains caller-authored strings that could steer a small model,
 * and the rows are already the human-readable projection of it. Feeding the model
 * the same facts the human sees keeps the summary honest about what is being
 * approved.
 */
data class RequestFacts(
    val plugin: String,
    val verb: String,
    /** The route's own human label, e.g. "SEND EMAIL". */
    val routeLabel: String,
    /** The deterministic summary the audit already records. */
    val summary: String,
    /** label -> value, exactly as the modal renders them. */
    val rows: List<Pair<String, String>>,
    /**
     * The request body EXACTLY as it arrived, byte for byte.
     *
     * **Nothing is derived from this — it is passed through.** Mido, 2026-10-20: *"no fucking
     * processing of any kind, the full json as is + plugin name + url + our system prompt"*.
     *
     * Every previous attempt summarised the payload first (field names, then quoted values, then
     * a JSON shape), and each one gave the model something other than the request. This field
     * exists so the model reads what the caller actually sent, and any judgement about what
     * matters is the MODEL's to make rather than ours to pre-chew.
     *
     * Empty for a GET — there is no body. That is a fact, not a truncation.
     */
    val rawBody: String = "",
    /** The outbound URL for a brokered call; empty for native routes. */
    val url: String = ""
)

/**
 * The model's answer: one plain-language line, a risk bucket, and why.
 *
 * [description] is what the human reads on the card or in the modal. It must be
 * short enough to fit a ~216dp card at 11sp over two lines, which is the real
 * constraint on the prompt, not a token budget.
 */
data class RequestSummary(
    val description: String,
    val risk: Risk,
    val reason: String
)

/**
 * A thing that can turn [RequestFacts] into a [RequestSummary].
 *
 * This interface is the swap seam Mido asked for ("make switching later easy").
 * Two rules make it worth having:
 *
 *  - **`summarize` never throws and never blocks forever.** It returns null on any
 *    failure (missing model, cold start, timeout, malformed output). The caller
 *    treats null as "no summary" and falls back to the deterministic rows it
 *    already has — so approval is NEVER blocked by a summariser. A clarity feature
 *    that can deny a request is a regression, not a feature.
 *  - **`isAvailable` is cheap and honest.** It answers whether the model file is
 *    present and loadable, without loading it.
 */
interface SummaryModel {
    /** Stable id, for the audit record and the debug log. */
    val id: String

    /** Is the model present and loadable right now? Cheap; no inference. */
    fun isAvailable(ctx: Context): Boolean

    /**
     * Produce a summary, or null. Blocking — call off the main thread.
     * [budgetMs] is the caller's patience; a model that cannot answer inside it
     * should give up rather than overrun a prompt window.
     */
    fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary?
}
