package com.mido.phonebridge.plugins.drive

import android.content.Context
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.GoogleApi
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Google Drive: list, inspect, create, rename, move, trash, download, upload.
 *
 * **Scope is full `https://www.googleapis.com/auth/drive`** (Mido, 2026-10-21). `drive.file` was
 * the narrower option and it cannot SEE a file the app did not create — so "download my document"
 * would answer not-found for everything already in the account, which defeats the point. The trade
 * is stated plainly: the token lives on the phone, and the approval gate is the only thing between
 * a caller and every file in the account.
 *
 * **Two routes carry bytes, and both opt out of the audit body** — `streamsRequest` on upload (so
 * a 200 MB file is not buffered into a String), `auditRequestBody = false` (so the first 32 KB of
 * a document is not written permanently into an append-only log), and the mirror
 * `auditResponseBody = false` on download. Those flags exist because of this plugin; see
 * `Route` for why they are separate.
 *
 * Everything else is ordinary JSON, so list/metadata/create/rename/move/trash need none of the
 * streaming machinery.
 */
class DrivePlugin(private val ctx: Context) : Plugin {

    override val id = "drive"
    override val displayName = "Google Drive"

    override val scopes = listOf("https://www.googleapis.com/auth/drive")

    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"

    /**
     * The fields a human needs to judge a file, and nothing else.
     *
     * Asking for `*` would return every field Drive has, including capabilities and permissions —
     * more payload, more to leak into an approval card, and no more information for the decision.
     */
    private val FILE_FIELDS =
        "id,name,mimeType,size,modifiedTime,createdTime,parents,webViewLink,trashed,owners(displayName,emailAddress)"

    /**
     * The route table, declared WITHOUT a `Context`.
     *
     * **Context-free on purpose, so the audit-flag contract is unit-testable.** A test cannot
     * construct a `Context` on the JVM, and the one property that absolutely must be guarded is
     * "this route does not copy file bytes into the audit log" (Mido, 2026-10-21: streaming is
     * fine *"as long as it doesn't leak secrets"*). Putting the declarations here means
     * `DriveAuditFlagsTest` reads the real table instead of a copy of it — and a test asserting
     * against a copy passes no matter what the plugin does, which is a failure this project has
     * already paid for twice.
     */
    /**
     * The route table.
     *
     * **Every route is built through [route], which READS its audit/streaming flags from
     * [DriveRouteFlags].** That is not indirection for its own sake: a test cannot construct a
     * `Context`, so `routes()` is unreachable from the JVM suite, and the one property that must
     * never regress is "this route does not copy file bytes into the audit log" (Mido, 2026-10-21:
     * streaming is fine *"as long as it doesn't leak secrets"*).
     *
     * The alternative — writing the flags inline and having the test assert against a copy —
     * passes no matter what the plugin does. Deriving them means the test reads the same values
     * production uses, and a route added WITHOUT a flags entry fails loudly at registration
     * instead of silently defaulting to "audit everything".
     */
    override fun routes(): List<Route> = listOf(
        route("GET", "/drive/files", "List or search your Drive files", Verb.READ) { list(it) }
            .withValidation { requireMax(it) },
        route("GET", "/drive/files/{id}", "Show a Drive file's details", Verb.READ) { get(it) },
        route("GET", "/drive/files/{id}/content", "DOWNLOAD a Drive file", Verb.READ) { download(it) },
        route("POST", "/drive/files", "CREATE DRIVE FOLDER", Verb.WRITE) { createFolder(it) },
        route("POST", "/drive/files/{id}/rename", "RENAME DRIVE FILE", Verb.EDIT) { rename(it) },
        route("POST", "/drive/files/{id}/move", "MOVE DRIVE FILE", Verb.EDIT) { move(it) },
        route("POST", "/drive/files/{id}/trash", "TRASH DRIVE FILE", Verb.DELETE) { trash(it) },
        route("POST", "/drive/files/upload", "UPLOAD FILE TO DRIVE", Verb.WRITE) { uploadFile(it) }
    )

    /**
     * Build one route, taking its byte-handling flags from [DriveRouteFlags].
     *
     * Throws when the table has no entry for `method path`. That is deliberate: a new route whose
     * audit behaviour nobody decided is a route that might leak a file into the log, so it must
     * fail at registration rather than inherit a default that happens to look safe.
     */
    private fun route(
        method: String,
        path: String,
        describe: String,
        verb: Verb,
        handler: suspend (Req) -> Resp,
    ): Route {
        val flags = DriveRouteFlags.forPath(method, path)
            ?: throw IllegalStateException(
                "route $method $path has no DriveRouteFlags entry; decide its audit/streaming " +
                    "behaviour in DriveRouteFlags.ALL before registering it"
            )
        return Route(
            method = method,
            path = path,
            describe = describe,
            verb = verb,
            auditResponseBody = flags.auditResponseBody,
            auditRequestBody = flags.auditRequestBody,
            streamsRequest = flags.streamsRequest,
            handler = handler,
        )
    }

    /**
     * Attach pre-gate validation.
     *
     * A copy rather than a mutable `validate` field on [Route]: `Route` is a data class shared by
     * every plugin, and making one of its fields settable so this plugin can post-configure would
     * let any code path mutate a route after registration.
     */
    private fun Route.withValidation(check: (Req) -> Unit): Route = copy(validate = check)

    /**
     * The rows the modal shows.
     *
     * **Every mutating route NAMES the file**, by fetching its metadata first. An id is not
     * something a human can approve, and this is the route family most likely to act on the wrong
     * item. A bad id throws `not_found` BEFORE the modal opens — we will not offer to rename or
     * trash something we cannot describe.
     */
    /**
     * The rows the modal shows.
     *
     * **Every route that acts on a file NAMES it in full** — path, name and size (Mido, 2026-10-21:
     * *"i need full file details: full path and name, size(if possible)"*). A bare name is not
     * enough: two files can be called `report.pdf` in different folders, and the card is the only
     * thing standing between a tap and the wrong document.
     *
     * **The download route declares rows even though it is a GET.** A read used to carry no target,
     * which was right for "list my messages" — there is nothing to name. `GET
     * /drive/files/{id}/content` downloads one specific file, so the human is deciding about that
     * file. See `Plugin.rowsFor`: an explicit entry now wins over the read default.
     *
     * A bad id throws `not_found` BEFORE the modal, which is the standing rule — never offer to
     * act on something we cannot describe.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        reads = setOf(
            "/drive/files",
            "/drive/files/{id}"
        ),
        writes = mapOf(
            // --- the byte-carrying routes, which are the ones worth naming ---
            "GET /drive/files/{id}/content" to { r ->
                val id = requirePathId(r.pathParams["id"])
                val f = detailsOf(id) ?: throw PluginError("not_found", "no file for id '$id'")
                // The destination changes what the human is agreeing to, so it says which one.
                val toPhone = r.query["to"]?.trim()?.equals("phone", ignoreCase = true) == true
                fileCard(
                    f,
                    headline = if (toPhone) "SAVE TO PHONE" else "DOWNLOAD",
                    extra = if (toPhone) "Downloads/${PhoneSave.RELATIVE_SUBDIR}" else null,
                )
            },
            "POST /drive/files/upload" to { r ->
                val name = r.query["name"]?.trim().orEmpty()
                if (name.isBlank()) {
                    throw PluginError("bad_request", "missing required 'name' query parameter")
                }
                val parent = r.query["parent"]?.trim().orEmpty()
                buildList {
                    add(ApprovalRow("UPLOAD", name, ApprovalRow.Style.WARN))
                    // `bodyLength` comes from Content-Length, which the route already requires —
                    // it refuses a streaming upload without one, so this is always known here.
                    val size = r.bodyLength
                    add(
                        ApprovalRow(
                            "SIZE",
                            if (size >= 0) DriveFormat.humanSize(size) else "unknown",
                            ApprovalRow.Style.NORMAL
                        )
                    )
                    add(
                        ApprovalRow(
                            "INTO",
                            if (parent.isBlank()) "My Drive"
                            else (pathOf(parent) ?: parent),
                            ApprovalRow.Style.NORMAL
                        )
                    )
                }
            },

            // --- metadata writes ---
            "POST /drive/files" to { r ->
                val name = requireBodyString(r, "name")
                buildList {
                    add(ApprovalRow("CREATE FOLDER", name, ApprovalRow.Style.WARN))
                    val parent = r.body.optString("parentId").trim()
                    add(
                        ApprovalRow(
                            "IN",
                            if (parent.isBlank()) "My Drive" else (pathOf(parent) ?: parent),
                            ApprovalRow.Style.NORMAL
                        )
                    )
                }
            },
            "POST /drive/files/{id}/rename" to { r ->
                val id = requirePathId(r.pathParams["id"])
                val newName = requireBodyString(r, "name")
                val f = detailsOf(id) ?: throw PluginError("not_found", "no file for id '$id'")
                buildList {
                    add(ApprovalRow("RENAME", f.name, ApprovalRow.Style.WARN))
                    add(ApprovalRow("TO", newName, ApprovalRow.Style.WARN))
                    // The path still shows the OLD name, which is what makes the rename legible:
                    // the human sees exactly which file moves, not just a before/after pair.
                    add(ApprovalRow("IN", DriveFormat.folderOf(f.path), ApprovalRow.Style.NORMAL))
                }
            },
            "POST /drive/files/{id}/move" to { r ->
                val id = requirePathId(r.pathParams["id"])
                val target = requireBodyString(r, "parentId")
                val f = detailsOf(id) ?: throw PluginError("not_found", "no file for id '$id'")
                buildList {
                    add(ApprovalRow("MOVE", f.name, ApprovalRow.Style.WARN))
                    add(ApprovalRow("FROM", DriveFormat.folderOf(f.path), ApprovalRow.Style.NORMAL))
                    add(ApprovalRow("TO", pathOf(target) ?: target, ApprovalRow.Style.NORMAL))
                }
            },
            "POST /drive/files/{id}/trash" to { r ->
                val id = requirePathId(r.pathParams["id"])
                val f = detailsOf(id) ?: throw PluginError("not_found", "no file for id '$id'")
                // Trash, not delete: recoverable for 30 days. The label says so, because
                // "TRASH" and "DELETE" lead a human to different decisions.
                fileCard(f, headline = "TRASH (recoverable 30 days)")
            }
        )
    )

    /**
     * The standard file card: what it is, where it lives, how big.
     *
     * One builder rather than four copies, because the whole point of the request was that these
     * details are consistent — a card that shows the size on trash but not on download is the same
     * bug the request was about, in a different place.
     */
    private fun fileCard(
        f: FileDetails,
        headline: String,
        extra: String? = null,
    ): List<ApprovalRow> = buildList {
        add(ApprovalRow(headline, f.name, ApprovalRow.Style.WARN))
        add(ApprovalRow("PATH", f.path, ApprovalRow.Style.LONG))
        // ALWAYS present, even when Drive reports no size: an absent row reads as "we did not
        // check", while "unknown" reads as "Drive does not report one for this type" — which is
        // the truth for folders and Google-native docs.
        add(ApprovalRow("SIZE", DriveFormat.humanSize(f.sizeBytes), ApprovalRow.Style.NORMAL))
        // The type is what decides whether the download will work at all, so it belongs on the
        // card rather than only in the failure message.
        if (f.mimeType.isNotBlank()) {
            add(ApprovalRow("TYPE", f.mimeType, ApprovalRow.Style.MONO))
        }
        if (extra != null) add(ApprovalRow("TO", extra, ApprovalRow.Style.NORMAL))
        add(ApprovalRow("FILE ID", f.id, ApprovalRow.Style.MONO))
    }

    // --- reads ---

    private suspend fun list(req: Req): Resp {
        val max = parseMax(req.query["max"]) ?: 25
        // Default excludes trash: a list that silently includes deleted files is actively
        // misleading about what exists. A caller wanting trash passes `q=trashed=true`.
        val q = req.query["q"]?.trim().takeUnless { it.isNullOrBlank() } ?: "trashed = false"
        val pageToken = req.query["pageToken"]?.trim().orEmpty()

        val url = buildString {
            append("$api/files?pageSize=$max&fields=nextPageToken,files($FILE_FIELDS)")
            append("&q=").append(enc(q))
            if (pageToken.isNotBlank()) append("&pageToken=").append(enc(pageToken))
        }
        val body = GoogleApi.get(ctx, url)
        return Resp.ok(
            JSONObject()
                .put("files", body.optJSONArray("files") ?: JSONArray())
                .put("nextPageToken", body.optString("nextPageToken", ""))
                .put("query", q)
        )
    }

    private suspend fun get(req: Req): Resp {
        val id = requirePathId(req.pathParams["id"])
        val body = GoogleApi.get(ctx, "$api/files/$id?fields=$FILE_FIELDS")
        return Resp.ok(JSONObject().put("file", body))
    }

    /**
     * Stream the file through to the caller.
     *
     * The metadata is fetched FIRST, so a bad id or an expired token fails with a coded status
     * before any bytes are promised. Once the response starts there is no way to report an error —
     * a mid-transfer failure is inherently unreportable, which is why nothing is left to chance up
     * front.
     */
    private suspend fun download(req: Req): Resp {
        val id = requirePathId(req.pathParams["id"])
        // ONE destination parameter, one fetch path, two sinks. `?to=phone` saves to
        // Downloads/PhoneBridge; anything else streams the bytes to the caller. Merging them is
        // the point: the metadata lookup, the type resolution and the failure paths are identical,
        // and two separate routes would be two copies that drift.
        val toPhone = req.query["to"]?.trim()?.equals("phone", ignoreCase = true) == true

        val meta = GoogleApi.get(ctx, "$api/files/$id?fields=id,name,mimeType,size")
        val mimeType = meta.optString("mimeType")
        val name = meta.optString("name")

        // REFUSED BEFORE ANY BYTES. A Google-native doc has no binary payload — `alt=media` answers
        // 403 "cannotDownloadFile" — and a folder is not a file. Both would otherwise surface as an
        // opaque `google_error` after a human had already approved.
        if (!PhoneSave.isDownloadable(mimeType)) {
            throw PluginError(
                "bad_request",
                "'$name' is a ${mimeType.substringAfterLast('.')} file, which has no downloadable " +
                    "bytes. Use Drive's export, or download a regular file."
            )
        }

        val (conn, stream) = GoogleApi.openStream(ctx, "$api/files/$id?alt=media")
        val contentType = mimeType.ifBlank {
            // A file with no type is not something to guess at: `application/octet-stream` makes
            // the caller save it rather than open it wrongly.
            "application/octet-stream"
        }
        // `size` arrives as a STRING in Drive's JSON, and a folder has none. A known length lets
        // the caller show progress and detect truncation; unknown falls back to chunked rather
        // than to a wrong fixed length.
        val length = meta.optString("size").toLongOrNull() ?: -1L

        if (!toPhone) {
            // The connection is closed by the HTTP layer once the body is written, via the stream
            // it wraps. Nothing here can close it early: the bytes are not read yet.
            return Resp.stream(200, stream, contentType, length)
        }

        // --- save to the phone ---
        // MediaStore Downloads is API 29+. Below that there is no permission-free way to put a file
        // somewhere a human can open, and a legacy path would need a storage permission we
        // deliberately do not ask for. Refuse rather than silently write to an app-private dir and
        // call it "saved".
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            conn.disconnect()
            throw PluginError(
                "unsupported_storage",
                "saving to Downloads needs Android 10 (API 29); this device is API " +
                    "${android.os.Build.VERSION.SDK_INT}. Use the streaming download instead."
            )
        }

        val saved = try {
            saveToDownloads(stream, PhoneSave.fileName(name, id), contentType)
        } finally {
            // The bytes are on the phone now, and the Drive connection is finished either way.
            conn.disconnect()
        }
        return Resp.ok(
            JSONObject()
                .put("saved", saved.uri)
                .put("name", saved.name)
                .put("size", saved.size)
                .put("mimeType", contentType)
                .put("folder", "Downloads/${PhoneSave.RELATIVE_SUBDIR}")
        )
    }

    /** What a successful phone save produced, for the JSON response. */
    private class Saved(val uri: String, val name: String, val size: Long)

    /**
     * Copy [stream] into `Downloads/PhoneBridge/<name>` through MediaStore.
     *
     * **No runtime permission is involved, and that is not an oversight.** Contributing a NEW file
     * to a MediaStore collection is always allowed; the restriction is on modifying files you do
     * not own, which this never does. That is why "modern only" costs the user no dialog.
     *
     * The insert is rolled back on failure: a half-written row would appear in the Files app as a
     * truncated file with no indication it was our fault. `delete` on the URI we just created is
     * the only cleanup that cannot misfire on someone else's file.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun saveToDownloads(stream: java.io.InputStream, name: String, mimeType: String): Saved {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
            // RELATIVE_PATH is what places the file in Downloads/PhoneBridge rather than at the
            // collection root. A trailing slash is required by the platform.
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/${PhoneSave.RELATIVE_SUBDIR}/"
            )
            // Not IS_PENDING: this is a single synchronous copy of a file we already have a stream
            // for, so the row goes visible once the bytes are written below. Marking it pending and
            // forgetting to clear it is how a file becomes invisible in the Files app.
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw PluginError("internal_error", "MediaStore refused the insert for '$name'")

        var written = 0L
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw PluginError("internal_error", "could not open an output stream for '$name'")
            out.use { sink ->
                // No declared length: Drive's `size` is advisory and the stream is the truth. Copy
                // to EOF here is CORRECT — this is our own output stream, not an HTTP body that a
                // client is holding open, which is the distinction that broke the upload.
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    written += n
                }
                sink.flush()
            }
        } catch (e: Exception) {
            // Roll back the row, so a failure does not leave a truncated file in Downloads.
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is PluginError) e else PluginError(
                "internal_error",
                "could not write '$name' to Downloads: ${e.message}"
            )
        }
        return Saved(uri.toString(), name, written)
    }

    // --- writes ---

    private suspend fun createFolder(req: Req): Resp {
        val name = requireBodyString(req, "name")
        val parent = req.body.optString("parentId").trim()
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
        if (parent.isNotBlank()) metadata.put("parents", JSONArray().put(parent))

        val created = GoogleApi.post(ctx, "$api/files?fields=$FILE_FIELDS", metadata.toString())
        return Resp.ok(JSONObject().put("file", created))
    }

    private suspend fun rename(req: Req): Resp {
        val id = requirePathId(req.pathParams["id"])
        val name = requireBodyString(req, "name")
        // PATCH with only `name`: Drive treats omitted fields as unchanged, so this cannot
        // accidentally move or re-parent the file.
        val updated = GoogleApi.patch(
            ctx,
            "$api/files/$id?fields=$FILE_FIELDS",
            JSONObject().put("name", name).toString()
        )
        return Resp.ok(JSONObject().put("file", updated))
    }

    private suspend fun move(req: Req): Resp {
        val id = requirePathId(req.pathParams["id"])
        val parent = requireBodyString(req, "parentId")
        // A move is addParents + removeParents on the QUERY STRING, not a body field. The old
        // parent must be removed explicitly or the file ends up in both.
        val current = GoogleApi.get(ctx, "$api/files/$id?fields=parents")
        val oldParents = current.optJSONArray("parents")?.toStringList().orEmpty()
        val url = buildString {
            append("$api/files/$id?fields=$FILE_FIELDS")
            append("&addParents=").append(enc(parent))
            if (oldParents.isNotEmpty()) {
                append("&removeParents=").append(enc(oldParents.joinToString(",")))
            }
        }
        val moved = GoogleApi.patch(ctx, url, JSONObject().toString())
        return Resp.ok(JSONObject().put("file", moved))
    }

    private suspend fun trash(req: Req): Resp {
        val id = requirePathId(req.pathParams["id"])
        // TRASH, not DELETE. Drive's `DELETE` is permanent and unrecoverable; `trashed=true` goes
        // to the bin for 30 days. A gate that offers permanent deletion needs a much louder
        // confirmation than a rename, so the recoverable one is what this route does.
        val trashed = GoogleApi.patch(
            ctx,
            "$api/files/$id?fields=$FILE_FIELDS",
            JSONObject().put("trashed", true).toString()
        )
        return Resp.ok(JSONObject().put("file", trashed))
    }

    /**
     * Stream the request body into Drive.
     *
     * Two calls, because Drive's resumable protocol is two: the first sends metadata and answers a
     * session URI, the second PUTs the bytes at that URI. Nothing is buffered — the copy goes
     * straight from the socket to Drive's socket.
     *
     * The length must be real. It comes from `Content-Length`, and an absent one is refused rather
     * than guessed: a wrong length truncates the upload, and a truncated document that reports
     * success is worse than a refusal.
     */
    private suspend fun uploadFile(req: Req): Resp {
        val name = req.query["name"]?.trim().orEmpty()
        if (name.isBlank()) throw PluginError("bad_request", "missing required 'name' query parameter")
        val parent = req.query["parent"]?.trim().orEmpty()
        val stream = req.bodyStream
            ?: throw PluginError("bad_request", "this route requires a raw request body")
        val length = req.bodyLength
        if (length < 0) {
            throw PluginError("bad_request", "missing Content-Length; a streamed upload needs the exact size")
        }
        val contentType = req.query["type"]?.trim().takeUnless { it.isNullOrBlank() }
            ?: "application/octet-stream"

        val metadata = JSONObject().put("name", name)
        if (parent.isNotBlank()) metadata.put("parents", JSONArray().put(parent))

        // Step 1: create the session. Metadata only.
        val sessionUri = GoogleApi.postJsonForLocation(
            ctx,
            "$upload/files?uploadType=resumable&fields=$FILE_FIELDS",
            metadata.toString()
        )
        // Step 2: the bytes.
        val created = GoogleApi.putStream(ctx, sessionUri, stream, length, contentType)
        return Resp.ok(JSONObject().put("file", created))
    }

    // --- helpers ---

    /**
     * Everything the approval card needs to identify one file.
     *
     * @param name the Drive display name, verbatim.
     * @param mimeType Drive's MIME type, which is also what decides "has bytes or not".
     * @param sizeBytes Drive's reported size, or -1 when it does not report one (folders,
     *   Google-native docs). NEVER defaulted to 0: an unreported size and an empty file lead a
     *   human to different conclusions and must not look the same on an approval card.
     * @param ancestors folder names from the file's own parent UP to the root.
     */
    private class FileDetails(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val ancestors: List<String>,
        val id: String,
    ) {
        /** `My Drive / Folder / name.pdf`, for the card. */
        val path: String get() = DriveFormat.fullPath(ancestors, name)
    }

    /**
     * Fetch a file's details, walking up its parent chain for the real path.
     *
     * **Why the walk exists.** Drive has no path string — a file carries only `parents` ids — so
     * "full path" means resolving each ancestor's name. Mido, 2026-10-21: *"i need full file
     * details: full path and name, size(if possible)"*. Two files can share a name in different
     * folders, and a card that shows only `report.pdf` cannot tell them apart.
     *
     * Bounded at [MAX_ANCESTORS] because a malformed parent chain could loop forever, and a stuck
     * approval modal is worse than a slightly short path. Each hop is one gated-free Google call —
     * this runs inside `describeRows`, before the modal, so no extra human tap is involved.
     *
     * Returns null when the file cannot be read at all; callers turn that into `not_found` BEFORE
     * the modal, which is the standing rule: never offer to act on something we cannot name.
     */
    private fun detailsOf(id: String): FileDetails? = try {
        val obj = GoogleApi.get(ctx, "$api/files/$id?fields=id,name,mimeType,size,parents")
        val name = obj.optString("name")
        if (name.isBlank()) {
            null
        } else {
            FileDetails(
                name = name,
                // An absent MIME type is not "unknown" for our purposes: it means Drive will send
                // bytes, so it is treated as a regular file rather than refused.
                mimeType = obj.optString("mimeType"),
                // Drive returns `size` as a STRING (it can exceed Int), and omits it entirely for
                // folders and Google-native types. -1 keeps "absent" distinct from "zero".
                sizeBytes = obj.optString("size").toLongOrNull() ?: -1L,
                ancestors = ancestorNames(obj.optJSONArray("parents")?.toStringList().orEmpty()),
                id = id,
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Folder names from a file's immediate parent up to the root.
     *
     * Returns the immediate parent FIRST, because that is the order the walk produces; `fullPath`
     * reverses it when rendering. Keeping the natural walk order here means a caller never has to
     * think about direction twice.
     */
    private fun ancestorNames(startParents: List<String>): List<String> {
        val names = ArrayList<String>()
        var parents = startParents
        var depth = 0
        // A cycle is not supposed to be possible, but a bounded loop is cheaper than trusting
        // Drive's invariants on a path that blocks a human's approval modal.
        while (parents.isNotEmpty() && depth < MAX_ANCESTORS) {
            val parentId = parents.first()
            val parent = try {
                GoogleApi.get(ctx, "$api/files/$parentId?fields=id,name,parents")
            } catch (e: Exception) {
                // A missing ancestor (deleted, or invisible to this token) truncates the path
                // rather than failing the whole card: a partial path still beats a bare name.
                break
            }
            val parentName = parent.optString("name")
            if (parentName.isNotBlank()) names.add(parentName)
            // My Drive's root has no `parents`, which is what ends the walk naturally.
            parents = parent.optJSONArray("parents")?.toStringList().orEmpty()
            depth++
        }
        return names
    }

    /** The file's display name, or null when it cannot be read. */
    private fun nameOf(id: String): String? = detailsOf(id)?.name

    /**
     * The full path of a folder (or file) by id, for a card that says where something goes.
     *
     * `detailsOf` already treats a folder as a "file whose name is the last path segment", so the
     * path it builds is exactly what the card needs: `My Drive / Projects / 2026`. Returns null when
     * the id cannot be read, and callers fall back to the raw id — a move card that shows an id is
     * worse than one showing a path, but far better than one showing nothing.
     */
    private fun pathOf(id: String): String? = detailsOf(id)?.path

    private fun parseMax(raw: String?): Int? {
        val v = raw?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val n = v.toIntOrNull()
            ?: throw PluginError("bad_max", "max must be an integer, got '$raw'")
        // Drive's own ceiling is 1000; above that it silently clamps, which is the
        // "asked for 5000, got 100, no clue why" trap this project already paid for once.
        if (n < 1 || n > 1000) throw PluginError("bad_max", "max must be 1..1000, got $n")
        return n
    }

    /** Pre-gate: a bad `max` must never cost an approval tap. */
    private fun requireMax(req: Req) {
        parseMax(req.query["max"])
    }

    private fun requirePathId(id: String?): String {
        val v = id?.trim().orEmpty()
        if (v.isBlank()) throw PluginError("bad_request", "missing file id in path")
        return v
    }

    private fun requireBodyString(req: Req, key: String): String {
        val v = req.body.optString(key).trim()
        if (v.isBlank()) throw PluginError("bad_request", "missing required '$key'")
        return v
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * A JSON array of strings as a Kotlin list.
     *
     * Local to this plugin, like the Gmail and Calendar copies: the three plugins share no code
     * today, and a shared helper would be the first thing in a "plugins-common" module that
     * otherwise does not exist. Blank entries are dropped because Drive returns `[""]` for a file
     * with no parents, and an empty segment in `removeParents=` would be a malformed request.
     */
    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out.filter { it.isNotBlank() }
    }

    override fun status(): PluginStatus {
        if (AuthManager.clientId.isBlank()) {
            return PluginStatus("Google Drive", PluginStatus.State.OFF, "no OAuth client id configured")
        }
        if (!AuthManager.isLoggedIn(ctx)) {
            return PluginStatus("Google Drive", PluginStatus.State.NEEDS_ATTENTION, "not logged in")
        }
        val hasScope = AuthManager.grantedScopes.contains("https://www.googleapis.com/auth/drive")
        if (!hasScope && AuthManager.grantedScopes.isNotEmpty()) {
            // The grant set is only known when Google reported it; an empty set means "unknown",
            // not "missing", so this warning fires only when we positively know it is absent.
            return PluginStatus(
                "Google Drive",
                PluginStatus.State.NEEDS_ATTENTION,
                "re-login needed to grant Drive access"
            )
        }
        return PluginStatus("Google Drive", PluginStatus.State.OK, AuthManager.accountEmail ?: "(account unknown)")
    }

    private companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /**
         * How far up the parent chain the path walk will go.
         *
         * Deep enough for any real folder (Drive itself allows ~100 levels but nobody nests that
         * far), and finite so a malformed chain cannot spin forever inside an approval modal.
         */
        const val MAX_ANCESTORS = 12
    }
}
