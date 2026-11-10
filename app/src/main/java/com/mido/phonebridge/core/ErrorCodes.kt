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
        // A plugin that exists but is switched off. 409 CONFLICT rather than 404: the route is
        // real, the bridge knows exactly what it is, and saying "not found" would send a caller
        // looking for a typo in a path that is spelled correctly. Caller-actionable, so it sits
        // on the 4xx side and stays out of the incident list.
        "plugin_disabled" to CONFLICT,
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
        // An address nobody has named. Refused at the TOP of dispatch, before policy and before
        // the gate, because it is not a question about the request — it is a question about who
        // is asking. The first call from a new machine is also the onboarding trigger.
        "caller_unverified" to FORBIDDEN,
        // A human explicitly blocked this address. Refused silently with no prompt, so a blocked
        // caller cannot make the phone light up again.
        "caller_denied" to FORBIDDEN,

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
        // --- TTS ---
        // Over the cap is the caller's mistake, and it is refused pre-gate so it costs no work.
        "text_too_long" to BAD_REQUEST,
        // The line is full. The request was fine and nothing is broken, so 503 says "try again",
        // not "you did something wrong" and not "we are at fault".
        "tts_busy" to UNAVAILABLE,
        // No engine / no voice installed. Our side or the device's, and the caller cannot fix it
        // by changing the request.
        "tts_unavailable" to UNAVAILABLE,
        // The engine accepted the text and then failed to say it — a real fault, never a silent
        // success, because the caller would otherwise believe the human heard it.
        "tts_failed" to INTERNAL,
        // **The phone was on a call, so the sentence would have gone nowhere.**
        //
        // 409 CONFLICT rather than 500: nothing is broken and nothing needs fixing. The device is
        // simply in a state that makes speech impossible, and the same request will succeed later.
        // 503 would say "this service is unavailable", which invites the caller to treat a
        // transient device state as a service outage.
        "tts_in_call" to CONFLICT,
        // --- Contacts ---
        // The permission was never granted (or was revoked). 503 because the route EXISTS and the
        // device CAN do it — the missing thing is a human tap. 403 would say "you may not", which
        // is a different and permanent-sounding claim.
        "contacts_unavailable" to UNAVAILABLE,
        // The contacts provider itself failed: a corrupt database, a vendor ROM bug, a cursor
        // that came back null. Ours to fix, never the caller's.
        "contacts_query_failed" to INTERNAL,
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

    /**
     * What each code MEANS, in one line, for a caller that has never seen this app.
     *
     * **Facts only, never advice.** "`denied` — no approval was recorded" is a fact about the
     * code. "Do not retry a `denied`" is judgment, and it belongs in the agent skill, not here.
     * Keeping that line clean is what stops `/help` from becoming a second, diverging skill.
     *
     * This exists because `MAP` alone is not enough to use the API: knowing `denied` is 403 does
     * not tell a caller whether a human refused or the phone was simply in a pocket, and those
     * two demand opposite responses.
     *
     * Total over [MAP], enforced by `ErrorCodesTest` — a code without a meaning fails the build
     * exactly as an unclassified code does. A hand-maintained map that nothing checks is a map
     * that is wrong within two releases.
     */
    private val MEANINGS: Map<String, String> = mapOf(
        // --- caller's fault ---
        "bad_request" to "Required input is missing or malformed; nothing ran.",
        "bad_count" to "`count` was negative or not an integer.",
        "bad_package" to "`package` was present but empty.",
        "bad_time_min" to "`timeMin` is missing, malformed, or not a real instant.",
        "bad_time_max" to "`timeMax` is missing, malformed, or not a real instant.",
        "bad_time_range" to "`timeMin` is not before `timeMax`.",
        "bad_timezone" to "`timeZone` is missing or not a recognised zone id.",
        "bad_max" to "`max` is outside the range this route accepts.",
        "bad_lines" to "`lines` is outside 1..500.",
        "sudo_needs_stdin" to "The command needs a TTY for sudo, which this route cannot provide.",
        "unsupported_action" to "The notification has no action with that index or label.",
        "missing_reply_text" to "A Reply action needs `replyText` and none was sent.",
        "decode_error" to "The request body is not valid JSON.",
        "unknown_secret" to "No secret is stored under that name.",
        "host_not_allowed" to "The outbound host is not on that secret's allowlist; nothing was sent.",
        "redirect_refused" to "The upstream tried to redirect; the broker does not follow redirects.",
        "unsupported_content_type" to "The upstream body is not a JSON object (array, HTML, binary, scalar).",
        "unsupported_storage" to "Saving to the phone needs API 29+; this device is older.",
        "vault_exists" to "A vault is already set up; it cannot be created twice.",

        // --- target gone or misidentified ---
        // Facts only, and it names the remedy: the human can turn it back on, and the caller
        // cannot, so "enable it in the app" is the whole truth.
        "plugin_disabled" to "The plugin serving this route is switched off. It must be enabled in the STMP app's Plugins screen before these calls can run.",
        "not_found" to "No route matches that method and path, or the target id does not exist.",
        "notification_gone" to "The notification is no longer present; it was dismissed or cleared.",
        "not_clearable" to "That notification cannot be dismissed.",

        // --- credentials ---
        "auth_required" to "The Google account is not signed in.",
        "auth_expired" to "The OAuth token expired and needs a re-login on the phone.",
        "secret_locked" to "The vault is shut. A human unlocks it by opening the app.",
        "denied" to "No approval was recorded: the human declined, a NOTIFY card was rejected, or the gate could not be presented (screen off or locked).",
        "caller_unverified" to "This address has never been named. A human must approve it on the phone (with a name) before any route will answer.",
        "caller_denied" to "A human blocked this address. No route will answer it until that block is removed on the phone.",

        // --- our side ---
        "auth_not_configured" to "This build has no OAuth credentials; it cannot serve Google routes.",
        "listener_unavailable" to "The notification listener is not bound (permission or service killed).",
        "model_not_ready" to "The AI model is not provisioned, so every described route answers 503.",
        "ai_unavailable" to "The model exists but could not describe this request in budget.",
        "audio_unavailable" to "The cue could not be decoded or played.",
        "text_too_long" to "The text is longer than the configured cap. Shorten it; it is never truncated.",
        "tts_busy" to "Too many utterances are already queued or speaking. Retry shortly.",
        "tts_unavailable" to "No usable text-to-speech engine or voice is installed on the device.",
        "tts_failed" to "The engine accepted the text and then failed to speak it.",
        "tts_in_call" to "The phone is on a call, so speech would be inaudible. Nothing is broken; retry when the call ends.",
        "contacts_unavailable" to "READ_CONTACTS is not granted. A human must allow it on the phone.",
        "contacts_query_failed" to "The contacts provider refused or failed the query.",
        "vault_missing" to "No vault exists on this device yet.",
        "vault_corrupt" to "The vault or its index cannot be read.",
        "vault_unrecoverable" to "The vault was written without its wrap IV; its secrets cannot be recovered.",
        "vault_unwrap_failed" to "The vault key could not be unwrapped (invalidated or wrong key).",
        "allowlist_corrupt" to "The per-secret host allowlist cannot be read.",
        "upstream_timeout" to "The outbound call exceeded the broker's 60s limit.",
        "upstream_failed" to "The outbound call failed; the response exceeded the size cap or the transport broke.",
        "policy_unavailable" to "The gate policy file cannot be read, so no request may run.",
        "calendar_zone_unavailable" to "The calendar's own time zone could not be read (needed by quick-add).",
        "google_error" to "Google rejected the call; see the message for its reason.",
        "ssh_bad_key" to "The stored SSH private key cannot be parsed.",
        "ssh_keygen_failed" to "Generating an ed25519 keypair failed on this device.",
        "ssh_host_key_unknown" to "Nothing is pinned for this host yet. Repeat the call with `trustHostKey` to confirm the fingerprint shown.",
        "ssh_host_key_changed" to "The host key does not match the pinned one. Only a human can decide whether that is expected.",
        "ssh_host_key_refused" to "The host key was refused for a reason the app could not name.",
        "ssh_timeout" to "The command was still running at its deadline and was stopped.",
        "ssh_job_not_found" to "No detached job has that id (unknown, or its output expired).",
        "ssh_job_limit" to "Too many detached jobs are already running.",
        "ssh_failed" to "The SSH connection was refused: auth rejected, host unreachable, or algorithm mismatch.",
        "auth_error" to "The OAuth exchange failed.",
        "secure_storage_error" to "A Keystore operation failed.",
        "email_lookup_failed" to "The signed-in address could not be read.",
        "no_scopes" to "The account granted none of the scopes this plugin needs.",
        "internal_error" to "An unexpected fault on the phone; see `GET /debug`.",
    )

    /** The one-line meaning for [code], or null for a code this build does not know. */
    fun meaningOf(code: String): String? = MEANINGS[code]

    /** Every code that carries a meaning. Used by the totality test. */
    fun meanings(): Set<String> = MEANINGS.keys
}
