package com.mido.phonebridge.ai

import android.content.Context
import com.mido.phonebridge.policy.PolicyStore

/**
 * The ONE door the rest of the app goes through to reach the AI.
 *
 * **Why this exists.** `Route.aiSummary` used to carry two meanings at once — "this route does
 * not want a summary" AND "this route is exempt from the model-readiness gate". Two meanings in
 * one flag is why a *global* off switch had nowhere to live: the gate consulted the route, the
 * caller consulted the route, and `ModelRegistry` was reached directly from six places. Turning
 * the AI off globally meant finding all six and hoping none was missed.
 *
 * Now there is one seam. A route keeps a single honest meaning — *does this route want a
 * description* — and every question about whether an AI EXISTS is answered here, in one place,
 * from one setting. Mido, 2026-10-20: *"off means every damn AI feature completely off... free
 * memory and just bypass any AI related function"*.
 *
 * **What "off" guarantees, by construction rather than by remembering:**
 *
 *  - [serves] is always true, so no route ever answers `503 model_not_ready`.
 *  - [requiresAi] is always false, so no facts are built and no prompt is rendered.
 *  - [warm] does nothing, so the service never loads an engine it will not use.
 *  - [summarize] returns null, so both gate surfaces show their deterministic content and their
 *    decision clock starts immediately.
 *  - [release] still runs, so flipping the switch frees whatever was already resident.
 *
 * Every one of those is a single branch on the same boolean, which is the point: there is no
 * second place for the answer to drift out of step.
 *
 * **A garbled policy must not take the bridge down here.** [enabled] is read through the same
 * lenient path `Policy.parse` uses for preference fields, and an unreadable store is treated as
 * "off" with a log rather than an exception — a preference is not worth a dead server, which is
 * the whole reason these two settings live as lenient fields rather than strict ones.
 */
object AiGateway {

    /**
     * Is the AI switched on?
     *
     * Read per call rather than cached: `PolicyStore` caches the parsed file, so this is a map
     * lookup, and going through the store every time means flipping the toggle in Options takes
     * effect on the very next request with no invalidation path to get wrong — the same reasoning
     * as the gate's own switch read.
     */
    fun enabled(ctx: Context): Boolean = try {
        PolicyStore.from(ctx).get().aiEnabled
    } catch (e: Exception) {
        // Fail toward OFF. A policy that cannot be read already refuses to start the server, so
        // this path is only reached by a store that broke after boot; answering "off" keeps the
        // failure contained instead of throwing from inside a request.
        com.mido.phonebridge.Debug.log("ai: policy unreadable (${e.message}); treating AI as off")
        false
    }

    /**
     * May a request be served?
     *
     * With the AI off the answer is unconditionally yes — there is no model to be missing, so
     * `503 model_not_ready` can never be the reason a caller is refused.
     */
    fun serves(ctx: Context, routeWantsSummary: Boolean): Boolean =
        !enabled(ctx) || ModelRegistry.readiness(ctx).ready || !routeWantsSummary

    /**
     * Should this request be described?
     *
     * Both halves matter: the route must want it, AND the AI must exist. A route that wants a
     * summary on a switched-off bridge gets its deterministic rows instead — never a 503, never
     * a blank card.
     */
    fun requiresAi(ctx: Context, routeWantsSummary: Boolean): Boolean =
        routeWantsSummary && enabled(ctx)

    /**
     * Load the engine ahead of the first request.
     *
     * A no-op when the AI is off, which is what "free memory" means in practice: the service
     * starts, binds, and holds nothing.
     */
    fun warm(ctx: Context) {
        if (!enabled(ctx)) {
            com.mido.phonebridge.Debug.log("ai: off — not warming the model")
            return
        }
        if (!ModelRegistry.readiness(ctx).ready) return
        (ModelRegistry.active(ctx) as? LiteRtLm)?.warm(ctx)
    }

    /**
     * Give the engine back.
     *
     * Deliberately NOT gated on [enabled]: this is called when the service dies and when the
     * toggle flips off, and in both cases the engine may be resident from a moment when the AI
     * was on. Gating it would leave exactly the memory the switch exists to free.
     */
    fun release() {
        ModelRegistry.release()
    }

    /**
     * Summarise, or return null.
     *
     * Null is the honest answer in two situations that a caller must not tell apart: the AI is
     * off, or the model did not answer in time. Both mean "show the deterministic content and
     * start the clock", so the gate does not need to know which.
     */
    fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary? {
        if (!enabled(ctx)) return null
        return SummaryProvider.run(ctx, facts, budgetMs)
    }

    /** For the home screen and `/debug`: what the AI is doing, in one line. */
    fun status(ctx: Context): String {
        if (!enabled(ctx)) return "off — no model is loaded and no request is described"
        return ModelRegistry.status(ctx)
    }

    /** Whether an engine is resident right now, for `/debug`. */
    fun isEngineLoaded(): Boolean = ModelRegistry.isEngineLoaded()

    /** The context window of the running build, for `/debug`. */
    fun contextTokens(): Int = ModelRegistry.contextTokens()
}
