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
     * Whether the audit records the REQUEST body for this route.
     *
     * True for everything except a route that carries a file. An upload streams arbitrary bytes
     * and the audit stores the body verbatim (capped at 32 KiB) — so without this, the first
     * 32 KB of the user's document would be written permanently into an append-only log. That is
     * a content leak into a place nobody thinks to look, and Drive makes it happen with real
     * documents rather than test payloads.
     *
     * The mirror of [auditResponseBody], and deliberately a SEPARATE flag: one is about bytes
     * going out, the other about bytes coming in, and a single flag would force them to move
     * together when only one route needs either.
     */
    val auditRequestBody: Boolean = true,
    /**
     * A REDACTED COPY of the reply, for the audit only. Null means "store the reply as-is".
     *
     * **Why this exists rather than a flag.** `auditResponseBody` can only say keep or drop, and
     * contacts needs neither: the caller must receive real phone numbers (a search that returns
     * masked ones is useless), while the append-only log must not become an address book of people
     * who never installed this app. Drop-the-body would lose the evidence that the lookup happened;
     * keep-it loses the numbers. So the route hands the audit a DIFFERENT object — same shape, one
     * field renamed (`number` → `numberRedacted`, so nobody can mistake a mask for a real number).
     *
     * **The response is untouched.** The transform runs on a separate object built for the log;
     * the caller's bytes are never rewritten, which is the property that makes this safe to add to
     * one route without auditing what every other route does with its reply.
     *
     * Runs in the single dispatch site next to `auditResponseBody` (Mido, 2026-11-02: *"audit both
     * but keep number half redacted"*).
     */
    val auditResponseTransform: ((org.json.JSONObject) -> org.json.JSONObject)? = null,
    /**
     * Whether this route receives the raw request body as a STREAM instead of parsed JSON.
     *
     * **Independent of [auditRequestBody] on purpose.** This one is about MEMORY: the normal path
     * calls `readRawBody`, which buffers the entire body into a String — fine for a JSON request,
     * fatal for a 200 MB file. This flag says "do not buffer, hand the handler the input stream".
     * The audit flag says "do not record it". An upload wants both, for two unrelated reasons.
     *
     * A streaming route receives `Req.bodyStream` and an EMPTY `Req.body`; it must read from the
     * stream. Nothing parses it for you, which is the point — the caller's bytes are the payload.
     */
    val streamsRequest: Boolean = false,
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
    /**
     * Whether this request needs a password the human types at the modal.
     *
     * **The password never arrives over HTTP.** It is typed on the phone, held only in the
     * modal and the gate, and handed to the handler in memory. There is deliberately no
     * request field for it: a caller that could supply it would make it a credential in
     * transit, which is the exact thing this design exists to avoid.
     *
     * Dynamic like [verbFor], and for the same reason — one route can need a password for
     * some requests and not others. `POST /ssh/{name}` runs any command, so whether a
     * password is needed depends on whether the command invokes `sudo`, which is a property
     * of the payload, not the path.
     *
     * **Called pre-gate**, next to [validate], so the policy floor is decided before the
     * modal opens. Null means "never", which is right for every route but SSH.
     */
    val needsPassword: ((Req) -> Boolean)? = null,
    /**
     * Whether this route SKIPS the approval gate entirely.
     *
     * **The only such route in the app, and it should stay that way.** Every other route asks a
     * human first — that is the product. This exists for one case (Mido, 2026-10-31: *"no card or
     * even notification for those"*) where asking would defeat the purpose: an attention cue
     * whose whole job is to get someone to look at the phone cannot itself require them to look
     * at the phone.
     *
     * Because it bypasses the gate, the honest framing is that anyone on the tailnet can fire
     * it. That is acceptable ONLY because such a route can do exactly one thing — make a short
     * noise — and nothing else. If a future route wants this flag and can read, write or execute,
     * the flag is the wrong tool; the answer is a policy rule, not an exemption.
     *
     * It still goes through:
     *  - route resolution (unknown paths and cues 404 before anything happens),
     *  - `validate` (bad input costs nothing),
     *  - the audit, as a normal record with a normal outcome (Mido: *"it still logs as other
     *    requests"*) — no special outcome, no marker, because a unique outcome value would make
     *    it impossible to read the log as one list of requests.
     */
    val gateExempt: Boolean = false,
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
    val remote: String,
    /**
     * The raw body, for a route that declares `streamsRequest = true`.
     *
     * Null on every ordinary route — those get their body parsed into [body]. A route that reads
     * this must NOT also expect [body] to hold anything: the stream is the payload, and the JSON
     * object stays empty by design rather than by accident.
     */
    val bodyStream: java.io.InputStream? = null,
    /**
     * The declared `Content-Length` of the request body, or -1 when absent.
     *
     * A streaming route needs this: Drive's resumable upload requires the exact size on the PUT,
     * and the alternative is a `size` query parameter the caller must remember to send and keep
     * truthful. Reading what the transport already knows is one fewer thing to get wrong — and a
     * wrong length fails the upload rather than truncating it, which is the safe direction.
     */
    val bodyLength: Long = -1L
) {
    /**
     * The sudo password the human typed at the modal, or null.
     *
     * Set by the dispatcher immediately before the handler runs, and ZEROED immediately
     * after — see `BridgeService`'s single handler call site. A `CharArray` rather than a
     * `String` because a String cannot be wiped: it lives until the GC decides otherwise,
     * and its contents survive in the heap meanwhile.
     *
     * Mutable for one reason: the value does not exist when this request object is built.
     * The body is parsed and the gate opens AFTER construction, so the password arrives
     * later and is attached here rather than in a second Req.
     */
    var secretInput: CharArray? = null
}

/**
 * A response body: either JSON, or raw bytes with their own content type.
 *
 * **Why a separate type instead of overloading `JSONObject`.** Every plugin route used to return
 * `Resp(status, JSONObject)` and the server wrote `application/json`. Drive is files, so a
 * download has to emit the file's own bytes and content type — which cannot be expressed as an
 * object without base64 and a 33% tax. An explicit sealed variant makes "this is a stream" a fact
 * the compiler checks, rather than a null field someone has to remember to test.
 */
sealed interface RespPayload {
    /** A JSON object. The shape every existing route returns. */
    class Json(val body: org.json.JSONObject) : RespPayload

    /**
     * Raw bytes.
     *
     * @param input the bytes. The HTTP layer closes it.
     * @param contentType the file's own type, e.g. `application/pdf`. Never guessed from the
     *   path — Drive tells us, and a wrong type makes a document unopenable on the caller side.
     * @param lengthBytes the exact length, or -1 when unknown (chunked). Drive reports a size for
     *   a download, so we send it; knowing the length is what lets a caller show a progress bar
     *   and detect a truncated transfer.
     */
    class Stream(
        val input: java.io.InputStream,
        val contentType: String,
        val lengthBytes: Long = -1L,
    ) : RespPayload
}

/** Response from a handler. Plugin never touches HTTP status juggling beyond this. */
class Resp private constructor(
    val status: Int,
    val payload: RespPayload,
) {
    /**
     * The JSON body, for the ordinary case.
     *
     * Returns an empty object for a [RespPayload.Stream]. That is deliberate and safe here only
     * because the caller checks [isStream] first: the audit records this value, and a streaming
     * route declares `auditResponseBody = false`, so nothing consumes it. Use [payload] when the
     * distinction matters.
     */
    val body: JSONObject
        get() = (payload as? RespPayload.Json)?.body ?: JSONObject()

    /** True when the response is raw bytes rather than JSON. */
    val isStream: Boolean get() = payload is RespPayload.Stream

    constructor(status: Int, body: JSONObject) : this(status, RespPayload.Json(body))

    companion object {
        fun ok(body: JSONObject = JSONObject()) = Resp(200, body.put("ok", true))
        fun error(status: Int, code: String, detail: String) =
            Resp(status, JSONObject().put("ok", false).put("error", code).put("detail", detail))

        /** Raw bytes with their own content type. See [RespPayload.Stream]. */
        fun stream(
            status: Int,
            input: java.io.InputStream,
            contentType: String,
            lengthBytes: Long = -1L,
        ): Resp = Resp(status, RespPayload.Stream(input, contentType, lengthBytes))
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

    /**
     * Whether this plugin is ON for a human who has never touched the toggle.
     *
     * Applies only to a FIRST run. Once a choice is stored that choice wins — including
     * "off" — so an update can never re-enable something the human deliberately turned off.
     *
     * Defaults to true: a plugin that arrives switched off is a plugin nobody finds. A plugin
     * whose default should be off says so here rather than having the UI decide.
     */
    val enabledByDefault: Boolean
        get() = true

    /**
     * Every plugin is toggleable; there is no "required" flag.
     *
     * Mido, 2026-11-04: *"all plugins should be toggleable no exceptions"*.
     *
     * The old rule carved out `help`, `debug` and `logs` on the argument that they are how a
     * broken bridge is diagnosed, so disabling them removes the tool that explains the failure.
     * That reasoning is real but it produced the worse outcome: a screen with switches that look
     * identical and four of them silently refuse to move. A control that cannot be used and does
     * not say why reads as a bug, and it was reported as one.
     *
     * The safety it was protecting is already carried elsewhere and does not depend on this:
     * a disabled plugin still ANSWERS, refusing with `plugin_disabled`, so turning off `help`
     * cannot make routes 404 — it makes them explain that they are off. That is recoverable from
     * the same screen, by the same switch.
     */
    /** OAuth scopes this plugin needs. Empty for local-only plugins. */
    val scopes: List<String>
        get() = emptyList()

    /**
     * The settings this plugin owns, as DATA for one shared renderer.
     *
     * Empty is the normal case: most plugins have nothing to configure. A plugin that returns
     * entries gets a settings screen without writing any UI — see [PluginSetting] for why these
     * are descriptors rather than views.
     */
    fun settings(): List<PluginSetting> = emptyList()

    /**
     * The Android runtime permissions this plugin needs, with the reason shown to the human.
     *
     * Declared rather than discovered, so the request can be raised the moment a route needs it
     * and the explanation comes from the same place as the requirement.
     */
    fun permissions(): List<PluginPermission> = emptyList()

    /**
     * System accesses this plugin needs that are NOT runtime permissions.
     *
     * Each of these is a screen in Android Settings, not a dialog, so a `requestPermissions`
     * call for one does nothing at all. Declared separately from [permissions] for exactly that
     * reason: the remedy differs, and a representation that could serve both would produce a
     * button that silently fails.
     */
    fun specialAccess(): List<SpecialAccess> = emptyList()

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
        // AN EXPLICIT ENTRY WINS, even for a GET.
        //
        // A read used to carry no target at all, which was right when reads were "list my
        // messages" — there is nothing to name. Drive broke that assumption: `GET
        // /drive/files/{id}/content` DOWNLOADS a specific file, and the human approving it is
        // deciding about that file, not about a listing. A card with no rows asked someone to
        // approve an opaque id (Mido, 2026-10-21: *"i need full file details: full path and name,
        // size"*).
        //
        // So the rule is now: if the plugin DECLARED rows for this route, render them. Only when
        // it did not does the read default apply. A forgotten entry for a WRITE still throws,
        // which is the guarantee that mattered — an approval nobody can judge must not exist.
        val builder = writes[key]
        if (builder != null) return builder(req)
        if (req.method.equals("GET", ignoreCase = true) || key in reads) {
            // A read with nothing declared has no target to approve. If a GET is NOT in `reads`
            // that is still not an error — GET routes never mutate, so empty is correct.
            return emptyList()
        }
        throw PluginError(
            "internal_error",
            "plugin '${id}' has no approval rows for $key (read '${req.path}'? " +
                "add it to reads, or add a write entry)"
        )
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
    enum class Style {
        NORMAL,
        /** Monospace + selectable — ids, addresses, raw targets. */
        MONO,
        /** Long free text: a message body, an event description. Full, wrapped. */
        LONG,
        /** A consequence the human must not miss (mutating target). */
        WARN,

        /**
         * A shell command: monospace, syntax-coloured, split at token boundaries.
         *
         * **Distinct from [LONG] on purpose.** `LONG` is prose and renders in the handwritten
         * face, which is right for a message body and unreadable for a command. This style is
         * the one the SSH card needs, and it is also what makes the render "the exact string
         * that will run" readable rather than merely present.
         */
        CODE,
    }
}

/** What the home screen shows for a plugin. */
data class PluginStatus(
    val title: String,
    val state: State,
    val detail: String = ""
) {
    enum class State { OK, NEEDS_ATTENTION, OFF, ERROR }
}
