package com.mido.phonebridge.audit

import org.json.JSONObject

/**
 * The ONE decision about whether a handler's reply is kept in the audit.
 *
 * Why this is a shared function and not an if/else per call site: it *was* an if/else,
 * and the ungated path carried its own copy that always stored the body. So
 * `Route.auditResponseBody` was honoured under `SECURE` and silently ignored whenever
 * the gate was off, and `/debug` wrote its entire ~11.5 KiB report into the log on
 * every call — exactly the self-referential growth the flag exists to prevent.
 * Measured on the device: one `debug.read` record cost ~1 KB under `SECURE` and
 * ~13.7 KB under `OFF`.
 *
 * Two branches deciding the same thing is how a flag drifts, and an early return is
 * where it hides. This lives where a unit test can reach it — the first fix made it a
 * `private` method, which is the same defect in a different hat.
 *
 * [body] is null when the handler produced no JSON (a 204, or a streamed reply). With
 * [storesResponseBody] true that is an EMPTY body, not an absent one: the distinction
 * the record preserves is "nothing existed" versus "something existed and was not
 * kept", and only the flag can express the second.
 */
fun recordResponse(draft: AuditDraft, storesResponseBody: Boolean, status: Int, body: JSONObject?) {
    if (storesResponseBody) {
        draft.response(status, body?.toString())
    } else {
        draft.responseNotStored(status)
    }
}
