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
 */
data class Route(
    val method: String,          // "GET" | "POST"
    val path: String,            // e.g. "/notifications", "/gmail/messages/{id}"
    /** Human label for the approval modal, e.g. "List your calendar events". */
    val describe: String,
    val handler: suspend (Req) -> Resp
)

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
