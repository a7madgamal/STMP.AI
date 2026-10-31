package com.mido.phonebridge.core

/**
 * The ONE place an error code becomes an HTTP status. Pure: no Android, no HTTP types.
 *
 * Why this is its own type and not a `when` inside the dispatcher: that `when` had an
 * `else -> 500` branch, and a new code that forgot to land there silently became a
 * SERVER fault. It happened for real — `bad_lines`, added with `GET /logs`, is the
 * caller's mistake (a number out of range) but answered `500` and was recorded as
 * `HANDLER_FAILED`, i.e. a non-noise server error. Two false faults in the audit log
 * from one missing string.
 *
 * The lesson is that "remember to update the map" is not a rule, it is a hope. So the
 * map is total over every code the app can throw, and [ErrorCodesTest] scans the main
 * source tree for `PluginError("...")` literals and fails if any code is missing. Add a
 * code without classifying it and the build goes red.
 *
 * The split that matters, and it is not cosmetic:
 *
 *  - **4xx — the caller's fault.** Bad input, unknown target. Recorded as noise, because
 *    a malformed request is not an event the human needs to review.
 *  - **5xx — ours.** The request was fine and we still failed. Never noise: this is the
 *    signal that something is actually wrong.
 *
 * Misclassifying across that line is how a real outage hides among typos, or a typo
 * gets escalated as an incident.
 */
object ErrorCodes {

    // Status integers, not NanoHTTPD types, so this file stays testable on the JVM.
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val GONE = 410
    const val INTERNAL = 500
    const val UNAVAILABLE = 503

    /**
     * Every code the app can throw, mapped to its status.
     *
     * Total on purpose. An unknown code reaching [statusFor] is a DEFECT (the test says
     * so), and it is answered 500 — the safe direction, because an unclassified failure
     * must not be recorded as noise. The log line makes it visible rather than silent.
     */
    private val MAP: Map<String, Int> = mapOf(
        // --- caller's fault: bad input, unknown target, nothing to approve ---
        "bad_request" to BAD_REQUEST,
        "bad_count" to BAD_REQUEST,
        "bad_package" to BAD_REQUEST,
        "bad_time_min" to BAD_REQUEST,
        "bad_time_max" to BAD_REQUEST,
        "bad_time_range" to BAD_REQUEST,
        "bad_timezone" to BAD_REQUEST,
        "bad_max" to BAD_REQUEST,
        "bad_lines" to BAD_REQUEST,
        // A sudo command that cannot read stdin can never succeed from here — sudo
        // answers "a terminal is required to authenticate". The caller's mistake, and
        // refused pre-gate, so it is noise like every other bad request.
        "sudo_needs_stdin" to BAD_REQUEST,
        "unsupported_action" to BAD_REQUEST,
        "missing_reply_text" to BAD_REQUEST,
        "decode_error" to BAD_REQUEST,
        "unknown_secret" to BAD_REQUEST,
        "host_not_allowed" to BAD_REQUEST,
        "redirect_refused" to BAD_REQUEST,
        "unsupported_content_type" to BAD_REQUEST,
        // "Modern only" for saving to the phone means MediaStore Downloads, which is API 29+.
        // Below that the route REFUSES rather than silently writing somewhere else: a caller
        // told "saved" when the file went to an app-private dir has been lied to, and a legacy
        // path would need a storage permission we deliberately do not ask for.
        "unsupported_storage" to BAD_REQUEST,
        "vault_exists" to CONFLICT,

        // --- the target is gone or not what the caller thinks it is ---
        "not_found" to NOT_FOUND,
        "notification_gone" to GONE,
        "not_clearable" to CONFLICT,

        // --- credentials ---
        "auth_required" to UNAUTHORIZED,
        "auth_expired" to UNAUTHORIZED,
        // The vault is shut. Not the caller's fault and not ours: it is a state the
        // human clears by unlocking the app. 403 ("not allowed right now"), never 500 —
        // a locked vault is a normal condition, not a server fault.
        "secret_locked" to FORBIDDEN,
        // A refusal that is not the human declining: the gate could not be presented (screen
        // off, device locked), or a route refused because nobody could perceive it. The gate
        // already answered 403 for this by constructing the response directly, so the code was
        // never in this map — which meant a PLUGIN throwing it would have answered 500 and been
        // logged as a server fault. Surfaced by the audio cue route, which refuses on a dark
        // screen (ErrorCodesTest caught it).
        "denied" to FORBIDDEN,

        // --- our side, or an upstream we depend on ---
        "auth_not_configured" to UNAVAILABLE,
        "listener_unavailable" to UNAVAILABLE,
        // The AI model is REQUIRED (Mido, 2026-10-19). Its absence is a not-ready state,
        // not a degraded one: every route answers 503 until it is provisioned, so a
        // caller sees "not ready" rather than a decision made without the model.
        "model_not_ready" to UNAVAILABLE,
        // The model is present but this call failed (cold load error, malformed reply,
        // over budget). A fault on our side, never noise — the request could not be
        // described, so it must not be approved.
        "ai_unavailable" to INTERNAL,
        // A cue could not be decoded or played. Same shape as ai_unavailable: the route exists
        // and was approved, and the fault is ours — never a silent no-op, because the caller
        // would otherwise believe the phone made a sound.
        "audio_unavailable" to INTERNAL,
        "vault_missing" to UNAVAILABLE,
        "vault_corrupt" to INTERNAL,
        "vault_unrecoverable" to INTERNAL,
        "vault_unwrap_failed" to INTERNAL,
        "allowlist_corrupt" to INTERNAL,
        "upstream_timeout" to UNAVAILABLE,
        "upstream_failed" to CONFLICT,
        "policy_unavailable" to UNAVAILABLE,
        "calendar_zone_unavailable" to UNAVAILABLE,
        "google_error" to CONFLICT,
        // --- SSH gate (v1) ---
        // A stored private key that cannot be parsed is OUR data being wrong, and the human has
        // to fix it: 409 says "your config disagrees with reality", which is exactly it.
        "ssh_bad_key" to CONFLICT,
        // Generating a keypair failed on this device. Our side (no CryptoMaterial), and the human
        // cannot fix it by changing the request — 500 is the honest status.
        "ssh_keygen_failed" to INTERNAL,
        // First connection to a host: nothing is pinned, so nothing runs until the human confirms
        // the fingerprint. 409 (a disagreement about state) rather than 403 (a refusal).
        "ssh_host_key_unknown" to CONFLICT,
        // The host key does not match the one pinned on first connect. 409 rather than 403
        // because it is not a permission verdict — it is a DISAGREEMENT that a human must look
        // at, and the message says so.
        "ssh_host_key_changed" to CONFLICT,
        // A host-key refusal whose exact JSch type we do not recognise. It is still a
        // host-key decision that a human must resolve, not a server fault — 409 keeps it out of
        // the 5xx bucket, where it would be logged as our bug and retried pointlessly.
        "ssh_host_key_refused" to CONFLICT,
        // A command still running at the deadline. 503: our side gave up, not the caller's fault.
        "ssh_timeout" to UNAVAILABLE,
        // A detached job that is not in the table: unknown id, or a finished job whose output
        // expired. The caller's mistake — the id is theirs to keep.
        "ssh_job_not_found" to NOT_FOUND,
        // Too many detached jobs already running. 503: the app is at its limit right now and the
        // same request may succeed once one finishes. Not the caller's fault.
        "ssh_job_limit" to UNAVAILABLE,
        // Auth rejected, host unreachable, algorithm mismatch. All are "the other end said no",
        // the same shape as google_error.
        "ssh_failed" to CONFLICT,
        "auth_error" to INTERNAL,
        "secure_storage_error" to INTERNAL,
        "email_lookup_failed" to INTERNAL,
        "no_scopes" to INTERNAL,
        "internal_error" to INTERNAL
    )

    /** The status for [code]. Unknown codes are a defect; see the class comment. */
    fun statusFor(code: String): Int {
        val known = MAP[code]
        if (known != null) return known
        // Loud, not silent. Answering 500 is the safe direction (an unclassified failure
        // is never noise), but a reader must be able to tell "we crashed" from "nobody
        // taught me this code" — so it is recorded, not hidden behind a bare 500.
        //
        // Reported through a hook rather than `android.util.Log` directly: this file must
        // stay loadable on the JVM, because [ErrorCodesTest] is what enforces the map's
        // totality. A class that cannot be loaded in a unit test cannot be guarded by one.
        unclassified?.invoke(code)
        return INTERNAL
    }

    /**
     * Where an unclassified code is reported. The app points this at `Debug.log`; tests
     * leave it null. Nullable rather than a default no-op so "nobody wired the alarm" is
     * visible as a choice rather than hidden as a silent fallback.
     */
    @Volatile
    var unclassified: ((String) -> Unit)? = null

    /** Every classified code. Used by the totality test; not for runtime decisions. */
    fun classified(): Set<String> = MAP.keys
}
