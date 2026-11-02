package com.mido.phonebridge.ai

import android.content.Context
import com.mido.phonebridge.policy.AiBackend
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
 * from one setting.
 *
 * **Three backends, one seam.** [AiBackend] replaced the old boolean because "the AI is on"
 * stopped being one thing: LOCAL is the on-device model file, EXTERNAL is a box on the tailnet.
 * They differ in what can be missing (a file vs. a reachable host) and in what a failure means,
 * so [serves] asks the BACKEND rather than the model registry — otherwise EXTERNAL would be
 * refused forever with `model_not_ready` for a model file it never needs.
 *
 * **What OFF guarantees, by construction rather than by remembering:**
 *
 *  - [serves] is always true, so no route ever answers `503` for a missing AI.
 *  - [requiresAi] is always false, so no facts are built and no prompt is rendered.
 *  - [warm] does nothing, so the service never loads an engine it will not use.
 *  - [summarize] returns null, so both gate surfaces show their deterministic content.
 *  - [release] still runs, so switching off frees whatever was already resident.
 *
 * **A garbled policy must not take the bridge down here.** [backend] is read through the same
 * lenient path `Policy.parse` uses for preference fields, and an unreadable store is treated as
 * OFF with a log rather than an exception — a preference is not worth a dead server.
 */
object AiGateway {

    /**
     * Which backend is configured.
     *
     * Read per call rather than cached: `PolicyStore` already caches the parsed file, so this is
     * a map lookup, and going through the store every time means changing the setting takes
     * effect on the very next request with no invalidation path to get wrong.
     */
    fun backend(ctx: Context): AiBackend = try {
        PolicyStore.from(ctx).get().aiBackend
    } catch (e: Exception) {
        // Fail toward OFF. A policy that cannot be read already refuses to start the server, so
        // this path is only reached by a store that broke after boot; answering OFF keeps the
        // failure contained instead of throwing from inside a request.
        com.mido.phonebridge.Debug.log("ai: policy unreadable (${e.message}); treating AI as off")
        AiBackend.OFF
    }

    /** Is any backend configured? Convenience for callers that only care whether AI exists. */
    fun enabled(ctx: Context): Boolean = backend(ctx) != AiBackend.OFF

    /**
     * May a request be served?
     *
     * The question is "does the configured backend exist and answer", which is why this
     * dispatches instead of asking [ModelRegistry] unconditionally. With OFF there is nothing to
     * be missing, so the answer is yes.
     *
     * **EXTERNAL probes reachability here** (cached for a few seconds, see [ExternalModel]), so an
     * asleep box refuses the request BEFORE a modal is built. That is the fail behaviour chosen on
     * 2026-10-31: an unreachable summariser is a fault to report, not a reason to show the human a
     * prompt with no description and approve it anyway.
     */
    fun serves(ctx: Context, routeWantsSummary: Boolean): Boolean = when (backend(ctx)) {
        AiBackend.OFF -> true
        AiBackend.LOCAL -> ModelRegistry.readiness(ctx).ready || !routeWantsSummary
        AiBackend.EXTERNAL -> ExternalModel.isAvailable(ctx) || !routeWantsSummary
    }

    /**
     * Why the bridge is refusing, as a detail string that NAMES the backend.
     *
     * The old code always reported `ModelRegistry.readiness().detail`, which for an unreachable
     * tailnet box would have said "the model file is missing" about a build that never expected
     * one. A refusal that misdescribes the cause is worse than the refusal.
     */
    fun notReadyDetail(ctx: Context): String = when (backend(ctx)) {
        AiBackend.OFF -> "the AI is switched off, so nothing can be missing" // unreachable by design
        AiBackend.LOCAL -> ModelRegistry.readiness(ctx).detail
        // NOT "not reachable" unconditionally. Three different faults live behind EXTERNAL —
        // no address, never listed, no model picked — and only the fourth is a dead server.
        // Reporting all four as "unreachable" is how someone ends up rebooting a box that was
        // answering fine while the real problem was a spinner nobody had tapped.
        AiBackend.EXTERNAL -> ExternalModel.problem(ctx)
            ?: "the external AI backend (${ExternalModel.endpointLabel(ctx)}) is not reachable; " +
            "start it, or switch the AI backend to LOCAL or OFF in Options"
    }

    /** Should this request be described? The route must want it AND a backend must exist. */
    fun requiresAi(ctx: Context, routeWantsSummary: Boolean): Boolean =
        routeWantsSummary && enabled(ctx)

    /**
     * Load the model ahead of the first request.
     *
     * A no-op when the AI is off, which is what "free memory" means in practice. EXTERNAL warms
     * nothing: there is no local engine to load, and polling the remote box on service start
     * would only produce a log line about a machine we may not want to touch yet.
     */
    fun warm(ctx: Context) {
        when (backend(ctx)) {
            AiBackend.OFF -> {
                com.mido.phonebridge.Debug.log("ai: off — not warming the model")
                return
            }
            AiBackend.EXTERNAL -> {
                com.mido.phonebridge.Debug.log(
                    "ai: external (${ExternalModel.endpointLabel(ctx)}) — no local engine to warm"
                )
                return
            }
            AiBackend.LOCAL -> Unit
        }
        if (!ModelRegistry.readiness(ctx).ready) return
        (ModelRegistry.active(ctx) as? LiteRtLm)?.warm(ctx)
    }

    /**
     * Give the engine back.
     *
     * Deliberately NOT gated on [backend]: this is called when the service dies and when the
     * setting changes, and in both cases the engine may be resident from a moment when LOCAL was
     * configured. Gating it would leave exactly the memory the switch exists to free.
     */
    fun release() {
        ModelRegistry.release()
    }

    /**
     * Summarise, or return null.
     *
     * Null is the honest answer when the AI is off or the call did not land in budget. What the
     * CALLER does with a null stays the fail-fast decision: with the AI enabled a null is a fault
     * the request fails on, never a silent drop to rows-only.
     */
    fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary? =
        when (backend(ctx)) {
            AiBackend.OFF -> null
            AiBackend.LOCAL -> SummaryProvider.run(ctx, ModelRegistry.active(ctx), facts, budgetMs)
            AiBackend.EXTERNAL -> SummaryProvider.run(ctx, ExternalModel, facts, budgetMs)
        }

    /** For the home screen and `/debug`: what the AI is doing, in one line. */
    fun status(ctx: Context): String = when (backend(ctx)) {
        AiBackend.OFF -> "off — no model is loaded and no request is described"
        AiBackend.LOCAL -> ModelRegistry.status(ctx)
        AiBackend.EXTERNAL -> ExternalModel.status(ctx)
    }

    /** Whether an engine is resident right now, for `/debug`. Never true for EXTERNAL. */
    fun isEngineLoaded(): Boolean = ModelRegistry.isEngineLoaded()

    /** The context window of the running build, for `/debug`. 0 when there is no local engine. */
    fun contextTokens(): Int = ModelRegistry.contextTokens()
}
