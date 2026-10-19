package com.mido.phonebridge.core

import org.json.JSONObject

/**
 * A HTTP route exposed by a plugin.
 *
 * Gating is NOT a plugin choice: the server always runs every route through the
 * approval gate first. A plugin cannot opt out.
 *
 * [describe] is the human label the approval modal shows for this route. It is
 * REQUIRED, not defaulted: a route the gate cannot describe must not exist, so
 * making it a construction-time requirement turns "I forgot the modal branch"
 * into a compile error instead of a runtime `undescribed_route` 500 (that is
 * exactly how `/calendar/events` shipped — the route existed, the description
 * did not, and the gate refused it only when a request arrived).
 *
 * [verb] classifies what the route DOES. REQUIRED and for the same reason: a
 * route nobody classified cannot be labelled, cannot be audited, and cannot be
 * governed. Deliberately NOT derived from the HTTP method — `POST
 * /notifications/{id}/action` is EXECUTE, not write, and `POST
 * /gmail/messages/{id}/trash` is DELETE, not write. Inferring was the bug class
 * the old gate-side table already cost this project.
 */
data class Route(
    val method: String,          // "GET" | "POST" | "DELETE"
    val path: String,            // e.g. "/notifications", "/gmail/messages/{id}"
    /** Human label for the approval modal, e.g. "List your calendar events". */
    val describe: String,
    /** What this route does: the audit label, and (R2) the policy key. */
    val verb: Verb,
    /**
     * Whether the audit records the RESPONSE body for this route.
     *
     * True for everything except `/debug`, which returns the log itself. Recording
     * that would copy the log into the log on every call — self-referential, and at
     * the 32 KiB blob cap it quietly eats a third of the 20 MB retention budget in
     * under a hundred calls. The record still keeps the outcome, status and summary;
     * its `responseBody` is simply empty, and `storedResponseBody` says so.
     */
    val auditResponseBody: Boolean = true,
    /**
     * Whether this route's approval is DESCRIBED by the on-device model.
     *
     * True for everything that asks a human to decide something, because the whole
     * point of the AI is to say what is being approved in plain words. False for
     * routes where inference adds nothing and its absence must not block the route:
     * the diagnostic endpoints (`/debug`, `/logs`) exist to explain a broken bridge,
     * so depending on the model — the thing most likely to be broken — makes the
     * route useless exactly when it is needed (reported on device 2026-10-19: *"debug
     * call waited for the model"*).
     *
     * **This is also the model-readiness exemption.** A route that needs no AI needs
     * no model, so it is served while the bridge is otherwise not-ready — which is
     * why `debug`/`logs` no longer need a separate hard-coded plugin list. One flag,
     * one consequence, nothing to keep in sync (Mido, 2026-10-19: *"we need to be able
     * to disable AI summary for some endpoints"*).
     */
    val aiSummary: Boolean = true,
    /**
     * Pre-gate validation, run BEFORE the approval modal can open.
     *
     * Input that can never succeed must never cost the human a tap: `count=-1`
     * on a read is the caller's mistake, and asking someone to approve it is
     * noise in the strict sense — it can only end in a 400.
     *
     * Throwing [PluginError] here is recorded as `REJECTED_INVALID` (a 4xx, so
     * noise) and answers immediately. A route with nothing to check leaves it
     * null; a route whose input lives in the body is already covered because
     * [Plugin.describeRows] runs pre-gate too.
     */
    val validate: ((Req) -> Unit)? = null,
    /**
     * Resolve the verb from the REQUEST, for routes whose meaning depends on the payload
     * rather than the path.
     *
     * The broker is the reason this exists. One route — `POST /secrets/{name}/call` — carries
     * every third-party call, so a static [verb] of `EXECUTE` keyed policy, the audit label,
     * the exception button and the AI facts all on `todoist/EXECUTE`. That made "reads only"
     * inexpressible: an exception tapped while reading also silenced DELETEs, because the pair
     * had no read in it. Mido, 2026-10-19: *"I clicked meaning it as a read only operation."*
     *
     * Null means "the static [verb] is the answer", which is right for every route whose path
     * already names the effect (`/gmail/messages/{id}/trash` is DELETE, not WRITE).
     *
     * **Called once per request, and its result is used everywhere** — policy, audit, label,
     * exception offer, AI facts. Six call sites reading one value is the whole point: they
     * cannot disagree about what the request was.
     */
    val verbFor: ((Req) -> Verb)? = null,
    val handler: suspend (Req) -> Resp
) {
    /**
     * The verb this request actually carries.
     *
     * Defaults to [verb] so a route with no resolver behaves exactly as before — the change is
     * opt-in per route, not a rewrite of the gate.
     */
    fun effectiveVerb(req: Req): Verb = verbFor?.invoke(req) ?: verb
}

/** Incoming request handed to a route handler (post-approval). */
class Req(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val pathParams: Map<String, String>,
    val body: JSONObject,
    val remote: String
)

/** Response from a handler. Plugin never touches HTTP status juggling beyond this. */
class Resp(
    val status: Int,
    val body: JSONObject
) {
    companion object {
        fun ok(body: JSONObject = JSONObject()) = Resp(200, body.put("ok", true))
        fun error(status: Int, code: String, detail: String) =
            Resp(status, JSONObject().put("ok", false).put("error", code).put("detail", detail))
    }
}

/** Thrown by handlers to signal a loud, coded failure. */
class PluginError(val code: String, message: String) : Exception(message)

/**
 * A plugin bundles a Google service (or any capability): its scopes, its routes,
 * and its UI status card. Adding a service later = adding one plugin.
 */
interface Plugin {
    /** Stable id, e.g. "notifications", "gmail". */
    val id: String

    /** Human label for the UI. */
    val displayName: String

    /** OAuth scopes this plugin needs. Empty for local-only plugins. */
    val scopes: List<String>
        get() = emptyList()

    /** The routes this plugin contributes. */
    fun routes(): List<Route>

    /**
     * The verbatim, human-readable target rows the approval modal shows for a
     * request to this plugin — the facts the human must see to decide.
     *
     * Owned by the PLUGIN, not the gate: only the plugin knows its request shapes
     * and can (if needed) fetch live data to name the target. The gate calls this
     * and renders whatever it returns — it never hard-codes a route's rows, which
     * is what let two routes sharing a path template be confused for each other.
     *
     * Implement this by calling [rowsFor]. Do NOT hand-write a `when` with an
     * `else -> emptyList()` fallback: that single value used to mean both "this is
     * a read, correctly empty" AND "I forgot this route", and the second silently
     * emptied the modal (shipped that way from v0.2.48 to v0.2.50). [rowsFor]
     * makes the two cases distinct and throws on the second.
     */
    fun describeRows(req: Req): List<ApprovalRow> = emptyList()

    /**
     * Shared dispatch for [describeRows]. Enforces the one rule every plugin obeys:
     *
     *  - a READ route (see [reads]) returns no rows — correctly empty, not a failure;
     *  - a WRITE route returns the rows that name its target;
     *  - ANYTHING ELSE throws: not a read and not a write the plugin recognizes is a
     *    server defect (a route must have been added without its rows, or the key is
     *    wrong), so it fails loud and coded — `internal_error` → 500 — BEFORE any
     *    modal, rather than rendering an empty approval the human cannot judge.
     *
     * Keyed on `method to route-path-template` (NOT the concrete path): the plugin
     * knows its routes by the template it registered, e.g. `/calendar/events/{id}`.
     *
     * @param reads route-path templates this plugin serves as reads (empty rows).
     * @param writes map of `"METHOD /template"` to a row builder.
     */
    fun rowsFor(
        req: Req,
        reads: Set<String>,
        writes: Map<String, (Req) -> List<ApprovalRow>>
    ): List<ApprovalRow> {
        val key = "${req.method.uppercase()} ${req.path}"
        if (req.method.equals("GET", ignoreCase = true) || key in reads) {
            // A read carries no target to approve. If a GET is NOT in `reads` that is
            // still not an error here — GET routes never mutate, so empty is correct.
            return emptyList()
        }
        val builder = writes[key]
            ?: throw PluginError(
                "internal_error",
                "plugin '${id}' has no approval rows for $key (read '${req.path}'? " +
                    "add it to reads, or add a write entry)"
            )
        return builder(req)
    }

    /** UI status card data. */
    fun status(): PluginStatus
}

/**
 * One labelled row in the approval modal, as produced by a plugin.
 *
 * [label] is the short caption (e.g. "TITLE"), [value] the fact itself, and
 * [style] a hint for how to render it (see the Activity's row renderer).
 */
data class ApprovalRow(
    val label: String,
    val value: String,
    val style: Style = Style.WARN
) {
    enum class Style { NORMAL, MONO, LONG, WARN }
}

/** What the home screen shows for a plugin. */
data class PluginStatus(
    val title: String,
    val state: State,
    val detail: String = ""
) {
    enum class State { OK, NEEDS_ATTENTION, OFF, ERROR }
}
