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
        "bad_max" to BAD_REQUEST,
        "bad_lines" to BAD_REQUEST,
        "unsupported_action" to BAD_REQUEST,
        "missing_reply_text" to BAD_REQUEST,
        "decode_error" to BAD_REQUEST,

        // --- the target is gone or not what the caller thinks it is ---
        "not_found" to NOT_FOUND,
        "notification_gone" to GONE,
        "not_clearable" to CONFLICT,

        // --- credentials ---
        "auth_required" to UNAUTHORIZED,
        "auth_expired" to UNAUTHORIZED,

        // --- our side, or an upstream we depend on ---
        "auth_not_configured" to UNAVAILABLE,
        "listener_unavailable" to UNAVAILABLE,
        "policy_unavailable" to UNAVAILABLE,
        "google_error" to CONFLICT,
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
