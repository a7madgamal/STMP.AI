package com.mido.phonebridge.policy

/**
 * Which summariser the bridge uses, if any.
 *
 * Three values rather than a boolean because "AI on" stopped being one thing the moment a
 * remote backend became an option: OFF frees the memory and needs no model, LOCAL runs the
 * on-device `.litertlm`, and EXTERNAL asks a bigger model over the tailnet. A boolean would
 * have to mean either "not OFF" (so EXTERNAL would be indistinguishable from LOCAL) or
 * "LOCAL" (so turning the box on would require a second flag to say which) — and two flags
 * that can disagree is how a server ends up refusing every request for a reason nobody set.
 *
 * **OFF is the default**, preserving the decision recorded on the old boolean (Mido,
 * 2026-10-20: *"off means every damn AI feature completely off... free memory and just bypass
 * any AI related function"*).
 */
enum class AiBackend {
    /** No model, no summary, no risk. Deterministic rows only, and no route is ever 503'd for a missing model. */
    OFF,

    /** The on-device `.litertlm`. Needs the model file provisioned. */
    LOCAL,

    /**
     * A model server on the tailnet (Ollama / unsloth).
     *
     * **Unreachable is a failure, not a degradation** (Mido, 2026-10-31: *"fail"*). The AI is
     * core, so a null summary already fails the request; pointing that at a box that is asleep
     * would otherwise mean quietly approving without a description — the silent fallback this
     * project forbids. The refusal names the backend so the human sees why, and the fix is
     * starting the server or switching back to LOCAL.
     */
    EXTERNAL,
}
