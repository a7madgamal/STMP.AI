package com.mido.phonebridge.ai

/**
 * Whether the AI model is present and usable — the one decision that decides if the
 * bridge serves at all.
 *
 * **Why this is its own pure object.** Mido, 2026-10-19: the AI is core functionality,
 * so a missing model is a NOT-READY state, not a degraded one — every route answers
 * `503 model_not_ready` until it is provisioned. That makes readiness a decision that
 * gates what a caller may reach, and this project's rule is that such a decision lives
 * in a pure, unit-tested function rather than inline in the I/O path (the host
 * allowlist and the response classifier both paid for that lesson). So the rule is here,
 * with no `Context` and no `File`, and [ModelRegistry] is the thin Android wrapper —
 * the same split as `isAttended(ctx)` / `isAttended(screen, keyguard)`.
 *
 * **Size is the check, not the hash.** Verifying 474 MB of SHA-256 on every request
 * would cost seconds; the digest is paid ONCE, by [ModelProvisioner], before the file is
 * renamed into place. After that a truncated file can only appear through outside
 * tampering, and a length check catches the realistic failure (a partial write) for
 * free. The hash still guards the download, which is the moment the bytes are untrusted.
 */
object ModelReadiness {

    /** [ready] is the verdict; [detail] says why, and is what the 503 body carries. */
    class State(val ready: Boolean, val detail: String)

    /**
     * Plugins that must stay reachable even when the model is not, and that never use
     * the AI at all.
     *
     * `/debug` and `/logs` are the app's own diagnosis: they report WHY the model is not
     * ready, and blocking them would make a not-ready bridge undiagnosable from outside
     * — the exact dead end `GET /debug` was built to prevent. They are read-only and
     * open no decision, so serving them costs nothing and is never a way around the gate.
     *
     * **One concept, two consequences** (and they must not drift apart): a diagnostic
     * route needs no model to RUN, and it needs no model to DESCRIBE. The first version
     * of this exemption only covered the first, so `/debug` was served while not-ready and
     * then failed anyway — its card is built from an AI summary, so a broken model made
     * the diagnostic route answer `ai_unavailable` (reported on device 2026-10-19: *"debug
     * call waited for the model"*). The route that exists to explain a failure must not
     * depend on the thing that failed.
     */
    private val DIAGNOSTIC_PLUGINS = setOf("debug", "logs")

    /**
     * Is this plugin's whole purpose to diagnose a broken bridge?
     *
     * Kept for documentation and for the UI, NOT for the readiness decision any more:
     * that now follows the route's own `aiSummary` flag (see [serves]), so a plugin name
     * is never what decides whether a request may run.
     */
    fun isDiagnostic(pluginId: String): Boolean = pluginId in DIAGNOSTIC_PLUGINS

    /**
     * May this request be served at all?
     *
     * The fail-fast rule (Mido, 2026-10-19): the AI is core, so without the model the
     * bridge is not up — EXCEPT for a route that does not use the AI, which is the one
     * case where a missing model is not a missing capability. Expressed over
     * `aiRequired` rather than a plugin list so the exemption and the thing it exempts
     * cannot drift: a route with `aiSummary = false` is by definition a route that needs
     * no model, and the two are now the same fact rather than two lists to keep aligned.
     */
    fun serves(ready: Boolean, aiRequired: Boolean): Boolean = ready || !aiRequired

    /**
     * Must this request be described by the model? Always the route's own
     * `aiSummary` flag — never re-derived from a plugin name here.
     */
    fun requiresAi(aiSummary: Boolean): Boolean = aiSummary

    /**
     * The verdict for one file.
     *
     * @param exists whether the model file is on disk.
     * @param length its size in bytes (0 when absent).
     * @param expectedSize the size a complete, verified download has.
     */
    fun of(exists: Boolean, length: Long, expectedSize: Long): State = when {
        !exists -> State(false, "model not downloaded (expected ${mb(expectedSize)})")
        length == expectedSize -> State(true, "ready (${mb(length)})")
        length > expectedSize ->
            State(false, "model is ${mb(length)} but expected ${mb(expectedSize)} — wrong file")
        // The realistic incomplete case: a download that stopped, or a .part that was
        // renamed by hand. Saying how far it got is what makes the 503 actionable.
        else -> State(false, "model incomplete: ${mb(length)} of ${mb(expectedSize)}")
    }

    /** A size in whole MiB, for a human reading a 503 body. */
    fun mb(bytes: Long): String = "${bytes / 1024 / 1024} MB"
}
