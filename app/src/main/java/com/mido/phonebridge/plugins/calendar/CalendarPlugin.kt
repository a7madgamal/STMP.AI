package com.mido.phonebridge.plugins.calendar

import android.content.Context
import com.mido.phonebridge.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Calendar plugin (read + write).
 *
 * Declares its scopes, its routes and its UI card. All auth + gating is handled by
 * core; this file knows only Calendar REST shapes.
 */
class CalendarPlugin(private val ctx: Context) : Plugin {

    override val id = "calendar"
    override val displayName = "Calendar"

    override val scopes = listOf(
        "https://www.googleapis.com/auth/calendar.events"
    )

    private val base = "https://www.googleapis.com/calendar/v3"

    override fun routes(): List<Route> = listOf(
        Route("GET", "/calendar/events", "List your calendar events") { list(it) },
        Route("POST", "/calendar/events", "CREATE CALENDAR EVENT") { create(it) },
        Route("POST", "/calendar/events/{id}", "UPDATE CALENDAR EVENT") { update(it) },
        Route("DELETE", "/calendar/events/{id}", "DELETE CALENDAR EVENT") { delete(it) },
        Route("POST", "/calendar/events/{id}/move", "MOVE CALENDAR EVENT") { move(it) },
        Route("POST", "/calendar/quickadd", "QUICK-ADD CALENDAR EVENT") { quickAdd(it) }
    )

    /**
     * The rows the approval modal shows. Owned here, not in the gate.
     *
     * DELETE pre-fetches the live event so the modal NAMES what is being deleted
     * (a bare id is not something a human can approve) — and that same fetch
     * settles "stale app cache vs real value", since it reads Google directly.
     * A bad id fails hard (no modal, `not_found`): we will not offer to delete
     * something we cannot describe.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        reads = setOf("/calendar/events"),
        writes = mapOf(
            "POST /calendar/events" to { r ->
                eventRows(
                    summary = requireString(r, "summary"),
                    start = requireString(r, "start"),
                    end = requireString(r, "end"),
                    tz = requireString(r, "timeZone"),
                    location = r.body.optString("location"),
                    description = r.body.optString("description")
                )
            },
            "POST /calendar/events/{id}" to { r ->
                buildList {
                    add(ApprovalRow("EVENT ID", requirePathId(r.pathParams["id"]), ApprovalRow.Style.MONO))
                    addAll(
                        eventRows(
                            summary = requireString(r, "summary"),
                            start = requireString(r, "start"),
                            end = requireString(r, "end"),
                            tz = requireString(r, "timeZone"),
                            location = r.body.optString("location"),
                            description = r.body.optString("description")
                        )
                    )
                }
            },
            "DELETE /calendar/events/{id}" to { r ->
                val id = requirePathId(r.pathParams["id"])
                val calendarId = r.query["calendarId"]?.trim()?.takeIf { it.isNotBlank() } ?: "primary"
                val ev = try {
                    GoogleApi.get(ctx, "$base/calendars/${enc(calendarId)}/events/${enc(id)}")
                } catch (e: PluginError) {
                    throw PluginError("not_found", "no event for id '$id': ${e.message}")
                }
                val s = summarize(ev)
                buildList {
                    add(ApprovalRow("TITLE", requireNonBlank(s.optString("summary"), "summary")))
                    add(ApprovalRow("WHEN", whenText(s)))
                    if (s.optString("location").isNotBlank()) add(ApprovalRow("WHERE", s.optString("location")))
                    if (s.optString("description").isNotBlank()) add(ApprovalRow("DESCRIPTION", s.optString("description"), ApprovalRow.Style.LONG))
                    add(ApprovalRow("EVENT ID", id, ApprovalRow.Style.MONO))
                }
            },
            "POST /calendar/events/{id}/move" to { r ->
                buildList {
                    add(ApprovalRow("EVENT ID", requirePathId(r.pathParams["id"]), ApprovalRow.Style.MONO))
                    add(ApprovalRow("MOVE TO", requireString(r, "calendarId")))
                    r.body.optString("fromCalendarId").takeIf { it.isNotBlank() }
                        ?.let { add(ApprovalRow("FROM", it)) }
                }
            },
            "POST /calendar/quickadd" to { r ->
                listOf(ApprovalRow("TEXT", requireString(r, "text")))
            }
        )
    )

    private fun eventRows(
        summary: String, start: String, end: String, tz: String,
        location: String, description: String
    ): List<ApprovalRow> = buildList {
        add(ApprovalRow("TITLE", summary))
        add(ApprovalRow("WHEN", "$start → $end ($tz)"))
        if (location.isNotBlank()) add(ApprovalRow("WHERE", location))
        if (description.isNotBlank()) add(ApprovalRow("DESCRIPTION", description, ApprovalRow.Style.LONG))
    }

    private fun whenText(s: JSONObject): String {
        val st = s.optJSONObject("start") ?: JSONObject()
        val en = s.optJSONObject("end") ?: JSONObject()
        fun one(o: JSONObject): String {
            val dt = o.optString("dateTime")
            if (dt.isNotBlank()) return dt
            return o.optString("date")
        }
        val tz = st.optString("timeZone").ifBlank { en.optString("timeZone") }
        return if (tz.isNotBlank()) "${one(st)} → ${one(en)} ($tz)" else "${one(st)} → ${one(en)}"
    }

    private fun requireString(req: Req, key: String): String {
        val v = req.body.optString(key).trim()
        if (v.isBlank()) throw PluginError("bad_request", "missing required '$key'")
        return v
    }

    private fun requirePathId(id: String?): String {
        val v = id?.trim().orEmpty()
        if (v.isBlank()) throw PluginError("bad_request", "missing event id in path")
        return v
    }

    /**
     * A live-data field must be non-blank to be shown. Used where the row is not
     * optional decoration but the identity of the thing being acted on: a delete
     * that cannot name its event fails hard instead of showing an unnamed row.
     */
    private fun requireNonBlank(value: String, field: String): String {
        val v = value.trim()
        if (v.isBlank()) throw PluginError("not_found", "event has no '$field' to describe")
        return v
    }

    override fun status(): PluginStatus {
        if (AuthManager.clientId.isBlank()) {
            return PluginStatus("Calendar", PluginStatus.State.OFF, "no OAuth client id configured")
        }
        if (!AuthManager.isLoggedIn(ctx)) {
            return PluginStatus("Calendar", PluginStatus.State.NEEDS_ATTENTION, "not logged in")
        }
        val email = AuthManager.accountEmail ?: "(account unknown)"
        return PluginStatus("Calendar", PluginStatus.State.OK, email)
    }

    // --- routes ---

    /**
     * List events in an explicit window.
     *
     * REQUIRED input: timeMin, timeMax (RFC3339). A missing or malformed value
     * fails the request with a coded 400 BEFORE any approval modal is shown —
     * there is NO implicit "today" and NO guessed timezone. The caller's window
     * is the contract.
     */
    private fun list(req: Req): Resp {
        val timeMin = requireRfc3339(req, "timeMin")
        val timeMax = requireRfc3339(req, "timeMax")
        require(timeMin < timeMax) { "timeMin must be before timeMax" }

        val max = req.query["max"]?.let { raw ->
            val v = raw.trim().toIntOrNull()
                ?: throw PluginError("bad_max", "max must be an integer, got '$raw'")
            if (v < 1) throw PluginError("bad_max", "max must be >= 1, got $v")
            v.coerceAtMost(2500)
        } ?: 250

        // Defaults chosen for the common case; overridable, never guessed.
        val singleEvents = req.query["singleEvents"]?.trim()?.let { parseBool("singleEvents", it) } ?: true
        val orderBy = req.query["orderBy"]?.trim()?.takeIf { it.isNotBlank() } ?: "startTime"
        val pageToken = req.query["pageToken"]?.takeIf { it.isNotBlank() }
        val q = req.query["q"]?.takeIf { it.isNotBlank() }
        val timeZone = req.query["timeZone"]?.trim()?.takeIf { it.isNotBlank() }
        val calendarId = req.query["calendarId"]?.trim()?.takeIf { it.isNotBlank() } ?: "primary"

        val url = StringBuilder("$base/calendars/${enc(calendarId)}/events")
            .append("?timeMin=${enc(timeMin)}")
            .append("&timeMax=${enc(timeMax)}")
            .append("&maxResults=$max")
            .append("&singleEvents=$singleEvents")
        if (orderBy.isNotBlank()) url.append("&orderBy=${enc(orderBy)}")
        if (q != null) url.append("&q=${enc(q)}")
        if (timeZone != null) url.append("&timeZone=${enc(timeZone)}")
        if (pageToken != null) url.append("&pageToken=${enc(pageToken)}")

        val res = GoogleApi.get(ctx, url.toString())
        val items = res.optJSONArray("items") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            out.put(summarize(items.getJSONObject(i)))
        }
        return Resp.ok(
            JSONObject()
                .put("events", out)
                .put("nextPageToken", res.optString("nextPageToken"))
                .put("timeZone", res.optString("timeZone"))
        )
    }

    /**
     * Create an event. REQUIRED: summary, start, end, timeZone. A miss fails with
     * a coded 400 BEFORE any modal — no default zone, no "now + 1h" guessing.
     * Returns the FULL event read back (server-side GET inside the same request).
     */
    private fun create(req: Req): Resp {
        val summary = requireBodyString(req, "summary")
        val start = requireBodyString(req, "start")
        val end = requireBodyString(req, "end")
        val timeZone = requireBodyString(req, "timeZone")
        requireInstant("start", start)
        requireInstant("end", end)
        val calendarId = req.body.optString("calendarId").trim().ifBlank { "primary" }

        val payload = JSONObject()
            .put("summary", summary)
            .put("start", JSONObject().put("dateTime", start).put("timeZone", timeZone))
            .put("end", JSONObject().put("dateTime", end).put("timeZone", timeZone))
        // Optional fields render only when present; absent means no field, never blank.
        req.body.optString("description").takeIf { it.isNotBlank() }?.let { payload.put("description", it) }
        req.body.optString("location").takeIf { it.isNotBlank() }?.let { payload.put("location", it) }
        req.body.optJSONArray("attendees")?.toStringList()?.takeIf { it.isNotEmpty() }?.let { list ->
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("email", it)) }
            payload.put("attendees", arr)
        }

        val res = GoogleApi.post(ctx, "$base/calendars/${enc(calendarId)}/events", payload.toString())
        // Read back the full resulting event (choice B).
        val id = res.optString("id")
        if (id.isBlank()) throw PluginError("google_error", "create returned no id: $res")
        return Resp.ok(JSONObject().put("event", summarize(res)))
    }

    /**
     * Update an event (PATCH: only the fields sent change). REQUIRED: summary,
     * start, end, timeZone — same contract as create, per the standing rule.
     */
    private fun update(req: Req): Resp {
        val id = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val summary = requireBodyString(req, "summary")
        val start = requireBodyString(req, "start")
        val end = requireBodyString(req, "end")
        val timeZone = requireBodyString(req, "timeZone")
        requireInstant("start", start)
        requireInstant("end", end)
        val calendarId = req.body.optString("calendarId").trim().ifBlank { "primary" }

        val payload = JSONObject()
            .put("summary", summary)
            .put("start", JSONObject().put("dateTime", start).put("timeZone", timeZone))
            .put("end", JSONObject().put("dateTime", end).put("timeZone", timeZone))
        req.body.optString("description").takeIf { it.isNotBlank() }?.let { payload.put("description", it) }
        req.body.optString("location").takeIf { it.isNotBlank() }?.let { payload.put("location", it) }

        val res = GoogleApi.patch(
            ctx, "$base/calendars/${enc(calendarId)}/events/${enc(id)}", payload.toString()
        )
        return Resp.ok(JSONObject().put("event", summarize(res)))
    }

    /** Delete an event. Returns the full (now cancelled) event read back. */
    private fun delete(req: Req): Resp {
        val id = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val calendarId = req.query["calendarId"]?.trim()?.takeIf { it.isNotBlank() } ?: "primary"
        // DELETE returns 204 with no body; read the event back BEFORE deleting so
        // the caller still gets the object it asked to remove (choice B), then delete.
        val before = GoogleApi.get(ctx, "$base/calendars/${enc(calendarId)}/events/${enc(id)}")
        GoogleApi.delete(ctx, "$base/calendars/${enc(calendarId)}/events/${enc(id)}")
        val ev = summarize(before)
        ev.put("deleted", true)
        return Resp.ok(JSONObject().put("event", ev))
    }

    /**
     * Move an event to a different calendar.
     * REQUIRED: calendarId (destination). Source defaults to `primary`.
     */
    private fun move(req: Req): Resp {
        val id = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val dest = requireBodyString(req, "calendarId")
        val from = req.body.optString("fromCalendarId").trim().ifBlank { "primary" }
        val res = GoogleApi.post(
            ctx,
            "$base/calendars/${enc(from)}/events/${enc(id)}/move?destination=${enc(dest)}",
            "{}"
        )
        return Resp.ok(JSONObject().put("event", summarize(res)))
    }

    /**
     * quickAdd: Google parses natural language ("Lunch Friday 1pm").
     * REQUIRED: text. This is the ONE explicitly opt-in guesser — Google decides
     * the date/duration. Isolated from the strict routes; the modal shows the raw
     * text verbatim so the human sees exactly what is being guessed from.
     */
    private fun quickAdd(req: Req): Resp {
        val text = requireBodyString(req, "text")
        val calendarId = req.body.optString("calendarId").trim().ifBlank { "primary" }
        val url = "$base/calendars/${enc(calendarId)}/events/quickAdd?text=${enc(text)}"
        val res = GoogleApi.post(ctx, url, "{}")
        return Resp.ok(JSONObject().put("event", summarize(res)))
    }

    // --- helpers ---

    /** A required non-blank body string; absent/blank fails the request. */
    private fun requireBodyString(req: Req, key: String): String {
        val v = req.body.optString(key).trim()
        if (v.isBlank()) throw PluginError("bad_request", "missing required '$key'")
        return v
    }

    /** A body timestamp must be a real RFC3339 instant; fail hard otherwise. */
    private fun requireInstant(key: String, v: String) {
        if (!isRfc3339(v)) throw PluginError("bad_request", "'$key' is not RFC3339: '$v'")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out.filter { it.isNotBlank() }
    }
    private fun requireRfc3339(req: Req, key: String): String {
        val raw = req.query[key]
            ?: throw PluginError(
                if (key == "timeMin") "bad_time_min" else "bad_time_max",
                "missing required '$key' (RFC3339, e.g. 2026-10-16T00:00:00+02:00)"
            )
        val v = raw.trim()
        if (v.isBlank()) {
            throw PluginError(
                if (key == "timeMin") "bad_time_min" else "bad_time_max",
                "'$key' must not be blank"
            )
        }
        if (!isRfc3339(v)) {
            throw PluginError(
                if (key == "timeMin") "bad_time_min" else "bad_time_max",
                "'$key' is not RFC3339: '$v'"
            )
        }
        return v
    }

    /**
     * Strict RFC3339 check: `YYYY-MM-DDThh:mm:ss` with an optional fractional
     * part and a mandatory offset (`Z` or `±hh:mm`). Parsed with the platform
     * formatter, so a value that merely looks right but is not a real instant
     * (2026-02-30) is rejected too.
     */
    private fun isRfc3339(s: String): Boolean {
        val re = Regex(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$"
        )
        if (!re.matches(s)) return false
        return try {
            java.time.OffsetDateTime.parse(s)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parseBool(key: String, raw: String): Boolean = when (raw.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw PluginError("bad_request", "'$key' must be true or false, got '$raw'")
    }

    private fun summarize(ev: JSONObject): JSONObject {
        val start = ev.optJSONObject("start")
        val end = ev.optJSONObject("end")
        return JSONObject()
            .put("id", ev.optString("id"))
            .put("status", ev.optString("status"))
            .put("summary", ev.optString("summary"))
            .put("description", ev.optString("description"))
            .put("location", ev.optString("location"))
            .put("htmlLink", ev.optString("htmlLink"))
            .put("start", start?.let { field(it) } ?: JSONObject())
            .put("end", end?.let { field(it) } ?: JSONObject())
            .put("allDay", start?.has("date") == true)
            .put("organizer", ev.optJSONObject("organizer")?.optString("email") ?: "")
            .put("attendees", attendees(ev))
    }

    /** A start/end field is either {dateTime} (timed) or {date} (all-day). */
    private fun field(obj: JSONObject): JSONObject =
        JSONObject()
            .put("dateTime", obj.optString("dateTime"))
            .put("date", obj.optString("date"))
            .put("timeZone", obj.optString("timeZone"))

    private fun attendees(ev: JSONObject): JSONArray {
        val arr = ev.optJSONArray("attendees") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            out.put(
                JSONObject()
                    .put("email", a.optString("email"))
                    .put("responseStatus", a.optString("responseStatus"))
            )
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
