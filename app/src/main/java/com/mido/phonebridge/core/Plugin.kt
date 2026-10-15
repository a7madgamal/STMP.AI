package com.mido.phonebridge.core

import org.json.JSONObject

/**
 * A HTTP route exposed by a plugin.
 *
 * Gating is NOT a plugin choice: the server always runs every route through the
 * approval gate first. A plugin cannot opt out.
 */
data class Route(
    val method: String,          // "GET" | "POST"
    val path: String,            // e.g. "/notifications", "/gmail/messages/{id}"
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

    /** UI status card data. */
    fun status(): PluginStatus
}

/** What the home screen shows for a plugin. */
data class PluginStatus(
    val title: String,
    val state: State,
    val detail: String = ""
) {
    enum class State { OK, NEEDS_ATTENTION, OFF, ERROR }
}
