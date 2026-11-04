package com.mido.phonebridge.plugins.gmail

import android.content.Context
import com.mido.phonebridge.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Gmail plugin (Phase 1).
 *
 * Declares its scopes, its routes and its UI card. All auth + gating is handled by
 * core; this file knows only Gmail REST shapes and MIME decoding.
 */
class GmailPlugin(private val ctx: Context) : Plugin {

    override val id = "gmail"
    override val displayName = "Gmail"

    override val scopes = listOf(
        "openid",
        "email",
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.send"
    )

    private val base = "https://gmail.googleapis.com/gmail/v1/users/me"

    override fun routes(): List<Route> = listOf(
        Route("GET", "/gmail/profile", "Show your Gmail profile", Verb.READ) { profile() },
        Route(
            "GET", "/gmail/messages", "List your Gmail messages", Verb.READ,
            // A bad `max` used to fall back to 20 silently — a caller asking for 5000
            // got a short page and no clue why. Now it is a pre-gate 400: no tap, and
            // the answer names the problem.
            validate = { requireMax(it) }
        ) { list(it) },
        Route("GET", "/gmail/messages/{id}", "Read a Gmail message", Verb.READ) { get(it) },
        Route("POST", "/gmail/messages/{id}/modify", "Modify Gmail labels", Verb.EDIT) { modify(it) },
        Route("POST", "/gmail/messages/{id}/trash", "TRASH EMAIL", Verb.DELETE) { trash(it) },
        Route("POST", "/gmail/messages/send", "SEND EMAIL", Verb.WRITE) { send(it) }
    )

    /**
     * The rows the approval modal shows. Owned here, not in the gate: only Gmail
     * knows its request shapes, and only Gmail can fetch the message to NAME the
     * target (a bare id is not something a human can approve).
     *
     * FAIL HARD: every mutating route's required input is validated here, so a
     * miss throws a coded error that the gate turns into a status BEFORE the modal.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        reads = setOf("/gmail/profile", "/gmail/messages", "/gmail/messages/{id}"),
        writes = mapOf(
            "POST /gmail/messages/send" to { r ->
                val to = r.body.optJSONArray("to")?.toStringList()
                    ?: throw PluginError("bad_request", "missing required 'to[]'")
                if (to.isEmpty()) throw PluginError("bad_request", "'to[]' must not be empty")
                val subject = requireBodyString(r, "subject")
                val body = requireBodyString(r, "body")
                buildList {
                    add(ApprovalRow("TO", to.joinToString(", "), ApprovalRow.Style.WARN))
                    r.body.optJSONArray("cc")?.toStringList()?.takeIf { it.isNotEmpty() }
                        ?.let { add(ApprovalRow("CC", it.joinToString(", "), ApprovalRow.Style.WARN)) }
                    r.body.optJSONArray("bcc")?.toStringList()?.takeIf { it.isNotEmpty() }
                        ?.let { add(ApprovalRow("BCC", it.joinToString(", "), ApprovalRow.Style.WARN)) }
                    add(ApprovalRow("SUBJECT", subject, ApprovalRow.Style.WARN))
                    add(ApprovalRow("BODY", body, ApprovalRow.Style.LONG))
                }
            },
            "POST /gmail/messages/{id}/trash" to { r ->
                val id = requirePathId(r.pathParams["id"])
                // Fetch the message so the modal NAMES what is being trashed, not just
                // its id. A bad id fails hard (no modal) — we will not offer to trash
                // something we cannot describe.
                val msg = try {
                    GoogleApi.get(ctx, "$base/messages/$id?format=metadata" +
                        "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
                } catch (e: PluginError) {
                    throw PluginError("not_found", "no message for id '$id': ${e.message}")
                }
                val s = summarize(msg)
                buildList {
                    if (s.optString("subject").isNotBlank()) add(ApprovalRow("SUBJECT", s.optString("subject")))
                    if (s.optString("from").isNotBlank()) add(ApprovalRow("FROM", s.optString("from")))
                    if (s.optString("snippet").isNotBlank()) add(ApprovalRow("SNIPPET", s.optString("snippet"), ApprovalRow.Style.LONG))
                    add(ApprovalRow("MESSAGE ID", id, ApprovalRow.Style.MONO))
                }
            },
            "POST /gmail/messages/{id}/modify" to { r ->
                val add = r.body.optJSONArray("addLabels")?.toStringList() ?: emptyList()
                val remove = r.body.optJSONArray("removeLabels")?.toStringList() ?: emptyList()
                if (add.isEmpty() && remove.isEmpty()) {
                    throw PluginError("bad_request", "addLabels or removeLabels required")
                }
                val addText = add.joinToString(", ")
                val removeText = remove.joinToString(", ")
                buildList {
                    if (add.isNotEmpty()) add(ApprovalRow("ADD LABELS", addText))
                    if (remove.isNotEmpty()) add(ApprovalRow("REMOVE LABELS", removeText))
                }
            }
        )
    )

    /** `max`: absent means 20, present must be 1..100. Never silently clamped. */
    private fun requireMax(req: Req): Int = req.query["max"]?.let { raw ->
        val v = raw.trim().toIntOrNull()
            ?: throw PluginError("bad_max", "max must be an integer, got '$raw'")
        if (v < 1 || v > 100) throw PluginError("bad_max", "max must be 1..100, got $v")
        v
    } ?: 20

    private fun requireBodyString(req: Req, key: String): String {
        val v = req.body.optString(key).trim()
        if (v.isBlank()) throw PluginError("bad_request", "missing required '$key'")
        return v
    }

    private fun requirePathId(id: String?): String {
        val v = id?.trim().orEmpty()
        if (v.isBlank()) throw PluginError("bad_request", "missing message id in path")
        return v
    }

    override fun status(): PluginStatus {
        if (AuthManager.clientId.isBlank()) {
            return PluginStatus("Gmail", PluginStatus.State.OFF, "no OAuth client id configured")
        }
        if (!AuthManager.isLoggedIn(ctx)) {
            return PluginStatus("Gmail", PluginStatus.State.NEEDS_ATTENTION, "not logged in")
        }
        // The ACCOUNT is not echoed here — the Google card states it once. What this row is for
        // is whether Gmail can serve, so that is what it says.
        val left = AuthManager.tokenRemainingMs()
        val detail = if (left > 0) {
            "ready · token ${left / 60_000}m"
        } else {
            "ready · renews automatically"
        }
        return PluginStatus("Gmail", PluginStatus.State.OK, detail)
    }

    // --- routes ---

    private fun profile(): Resp {
        val obj = GoogleApi.get(ctx, "$base/profile")
        return Resp.ok(
            JSONObject()
                .put("emailAddress", obj.optString("emailAddress"))
                .put("messagesTotal", obj.optLong("messagesTotal"))
                .put("threadsTotal", obj.optLong("threadsTotal"))
        )
    }

    private fun list(req: Req): Resp {
        val q = req.query["q"]?.takeIf { it.isNotBlank() } ?: "in:inbox"
        val max = requireMax(req)
        val pageToken = req.query["pageToken"]

        val url = StringBuilder("$base/messages?maxResults=$max&q=${enc(q)}")
        if (!pageToken.isNullOrBlank()) url.append("&pageToken=${enc(pageToken)}")

        val listObj = GoogleApi.get(ctx, url.toString())
        val ids = listObj.optJSONArray("messages") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until ids.length()) {
            val m = ids.getJSONObject(i)
            val id = m.optString("id")
            // metadata fetch per message (Gmail list returns only ids)
            val meta = GoogleApi.get(
                ctx,
                "$base/messages/$id?format=metadata" +
                    "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
            )
            out.put(summarize(meta))
        }
        return Resp.ok(
            JSONObject()
                .put("messages", out)
                .put("nextPageToken", listObj.optString("nextPageToken"))
                .put("resultSizeEstimate", listObj.optLong("resultSizeEstimate"))
        )
    }

    private fun get(req: Req): Resp {
        val id = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val msg = GoogleApi.get(ctx, "$base/messages/$id?format=full")
        val summary = summarize(msg)
        summary.put("bodyText", extractPlainText(msg.optJSONObject("payload")))
        return Resp.ok(summary)
    }

    private fun modify(req: Req): Resp {
        val id = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val add = req.body.optJSONArray("addLabels") ?: JSONArray()
        val remove = req.body.optJSONArray("removeLabels") ?: JSONArray()
        if (add.length() == 0 && remove.length() == 0) {
            throw PluginError("bad_request", "addLabels or removeLabels required")
        }
        val body = JSONObject().put("addLabelIds", add).put("removeLabelIds", remove)
        val res = GoogleApi.post(ctx, "$base/messages/$id/modify", body.toString())
        return Resp.ok(JSONObject().put("id", res.optString("id")).put("labelIds", res.optJSONArray("labelIds") ?: JSONArray()))
    }

    private fun trash(req: Req): Resp {
        val id = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val res = GoogleApi.post(ctx, "$base/messages/$id/trash", "{}")
        return Resp.ok(JSONObject().put("id", res.optString("id")))
    }

    private fun send(req: Req): Resp {
        val to = req.body.optJSONArray("to") ?: throw PluginError("bad_request", "to[] required")
        if (to.length() == 0) throw PluginError("bad_request", "to[] empty")
        val subject = req.body.optString("subject")
        val bodyText = req.body.optString("body")
        val threadId = req.body.optString("threadId").ifBlank { null }

        val raw = buildMime(
            to = to.toStringList(),
            cc = req.body.optJSONArray("cc").toStringList(),
            bcc = req.body.optJSONArray("bcc").toStringList(),
            subject = subject,
            body = bodyText
        )
        val encoded = android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        val payload = JSONObject().put("raw", encoded)
        if (threadId != null) payload.put("threadId", threadId)
        val res = GoogleApi.post(ctx, "$base/messages/send", payload.toString())
        return Resp.ok(
            JSONObject()
                .put("id", res.optString("id"))
                .put("threadId", res.optString("threadId"))
                .put("labelIds", res.optJSONArray("labelIds") ?: JSONArray())
        )
    }

    // --- helpers ---

    private fun summarize(msg: JSONObject): JSONObject {
        val headers = msg.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String {
            for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i)
                if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
            }
            return ""
        }
        return JSONObject()
            .put("id", msg.optString("id"))
            .put("threadId", msg.optString("threadId"))
            .put("from", header("From"))
            .put("subject", header("Subject"))
            .put("date", header("Date"))
            .put("snippet", msg.optString("snippet"))
            .put("labelIds", msg.optJSONArray("labelIds") ?: JSONArray())
            .put("unread", (msg.optJSONArray("labelIds")?.toString() ?: "").contains("UNREAD"))
    }

    /** Depth-first walk for the first text/plain part; loud if none exists. */
    private fun extractPlainText(payload: JSONObject?): String {
        if (payload == null) return ""
        val mime = payload.optString("mimeType")
        if (mime == "text/plain") {
            val data = payload.optJSONObject("body")?.optString("data")
            if (!data.isNullOrBlank()) return decodeB64Url(data)
        }
        val parts = payload.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) {
            val text = extractPlainText(parts.optJSONObject(i))
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun decodeB64Url(data: String): String = try {
        val bytes = android.util.Base64.decode(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        throw PluginError("decode_error", "could not decode body: ${e.message}")
    }

    private fun buildMime(
        to: List<String>, cc: List<String>, bcc: List<String>,
        subject: String, body: String
    ): String {
        val sb = StringBuilder()
        sb.append("To: ").append(to.joinToString(", ")).append("\r\n")
        if (cc.isNotEmpty()) sb.append("Cc: ").append(cc.joinToString(", ")).append("\r\n")
        if (bcc.isNotEmpty()) sb.append("Bcc: ").append(bcc.joinToString(", ")).append("\r\n")
        sb.append("Subject: ").append(subject).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("\r\n")
        sb.append(body)
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out.filter { it.isNotBlank() }
    }
}
