package com.mido.phonebridge.plugins.files

import android.content.Context
import android.provider.DocumentsContract
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject

/**
 * `files` — read and write files in a folder the human granted.
 *
 * **What this plugin can do, in one line: add files and read files.** It cannot remove or replace
 * one. That is not a gap to be filled later without argument — DELETE was cut deliberately after a
 * review found its card could not honestly describe itself (`Downloads` has no trash, so the words
 * would have to be "permanently, no undo, no backup"), and uploads refuse on collision rather than
 * overwrite. The only delete in this file is a rollback of a document the upload itself just
 * created, which the collision rule guarantees was never the user's file.
 *
 * **Every route opens a card, including the listing.** Browsing is a read of the device and this app
 * treats reads as things a human approves.
 *
 * **The folder is granted on demand.** No setup screen: a request that finds no grant answers
 * `503 folder_not_granted` and posts one notification whose tap opens the picker. The request does
 * not wait for the picker — a human browsing a filesystem is unbounded time.
 */
class FilesPlugin(private val ctx: Context) : Plugin {

    override val id = "files"
    override val displayName = "Files"

    private val store by lazy { FilesStore(ctx) }

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/files", "LIST FILES", Verb.READ,
            // A listing is a read with a target worth naming — the folder — so the card says
            // which folder rather than asking someone to approve an empty concept.
            auditRequestBody = false,
            aiSummary = false,
        ) { listFiles() },

        Route(
            "GET", "/files/content", "READ A FILE", Verb.READ,
            // The response is the file's bytes: never audited, for the same reason an upload's
            // body is not — a document must not be copied into an append-only log.
            auditResponseBody = false,
            auditRequestBody = false,
            aiSummary = false,
        ) { download(it) },

        Route(
            "POST", "/files/upload", "UPLOAD A FILE", Verb.WRITE,
            // The body IS the file. Two independent flags, two unrelated reasons: `streamsRequest`
            // is about MEMORY (never buffer a 200 MiB body into a String), `auditRequestBody` is
            // about LEAKAGE (never copy its first 32 KiB into the log).
            streamsRequest = true,
            auditRequestBody = false,
            auditResponseBody = false,
            // Named input validated BEFORE the card: a bad name or a missing length can only
            // ever end in a 400, so it must not cost the human a tap.
            validate = { req -> preflight(req) },
        ) { upload(it) }
    )

    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        // **An explicit entry for the listing, because "LIST FILES" alone names nothing.** The
        // human is being asked to let a request read the phone's storage; a card that does not say
        // WHICH folder is the same defect as an upload card that does not say how big — it asks for
        // a decision about an unnamed thing. Both reads name their target now.
        reads = emptySet(),
        writes = mapOf(
            "GET /files" to ::listRows,
            "GET /files/content" to ::readRows,
            "POST /files/upload" to ::uploadRows,
        ),
    )

    override fun status(): PluginStatus = PluginStatus(
        title = "Files",
        state = if (granted()) PluginStatus.State.OK else PluginStatus.State.NEEDS_ATTENTION,
        detail = if (granted()) "Granted: $FOLDER"
        else "No folder granted yet — the next request will ask",
    )

    // --- routes -------------------------------------------------------------------------------

    /**
     * `GET /files?path=<dir>` — one level of the granted folder.
     *
     * `path` is accepted and must name the folder itself (empty, `.`, or its own name); anything
     * else is refused rather than silently ignored, because a caller that asked for a subfolder and
     * got the root would believe it had listed a folder it never saw.
     */
    private fun listFiles(): Resp {
        val entries = withGrant { store.list() }
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("size", e.size)
                    .put("modified", e.mtime)
                    .put("isDir", e.isDir)
            )
        }
        return Resp.ok(JSONObject().put("folder", FOLDER).put("count", entries.size).put("files", arr))
    }

    /**
     * `GET /files/content?path=<file>` — the file's own bytes.
     *
     * Streamed with a declared length so a mid-transfer failure is a broken connection rather than
     * a short file behind a clean 200. The content type comes from the provider, not from the
     * extension, because the provider is what actually knows.
     */
    private fun download(req: Req): Resp {
        val name = store.requireName(requireParam(req, "path"))
        val entry = withGrant {
            store.find(name) ?: throw PluginError("not_found", "no file named '$name' in $FOLDER")
        }
        if (entry.isDir) throw PluginError("not_a_file", "'$name' is a folder")
        val resolver = ctx.contentResolver
        val type = resolver.getType(entry.uri) ?: "application/octet-stream"
        val stream = resolver.openInputStream(entry.uri)
            ?: throw PluginError("internal_error", "the provider refused to open '$name'")
        return Resp.stream(200, stream, type, entry.size)
    }

    /**
     * `POST /files/upload?path=<file>&type=<mime>` — the body IS the file.
     *
     * Refusals all happen BEFORE the card (see [preflight]), so by the time this runs the human has
     * approved a transfer that can actually happen.
     */
    private fun upload(req: Req): Resp {
        val name = store.requireName(requireParam(req, "path"))
        val declared = req.bodyLength
        val input = req.bodyStream
            ?: throw PluginError("bad_request", "an upload must carry a body stream")

        val created = withGrant {
            // Collision is checked against the LIVE listing, not against a cached one: between the
            // card and now something may have written the same name.
            if (store.find(name) != null) {
                throw PluginError("file_exists", "'$name' already exists in $FOLDER; refusing to replace it")
            }
            val parent = DocumentsContract.getTreeDocumentId(store.root())
            val uri = DocumentsContract.createDocument(
                ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(store.root(), parent),
                req.query["type"]?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                name,
            ) ?: throw PluginError("internal_error", "the provider refused to create '$name'")
            uri
        }

        var written = 0L
        try {
            val out = ctx.contentResolver.openOutputStream(created)
                ?: throw PluginError("internal_error", "the provider refused to write '$name'")
            out.use { sink ->
                // Bounded by a counting loop, never readBytes(): at 200 MiB a buffered body is an
                // OOM, and an OOM mid-write leaves a truncated file that reports success.
                val buf = ByteArray(64 * 1024)
                while (written < declared) {
                    val want = minOf(buf.size.toLong(), declared - written).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    written += n
                }
                sink.flush()
            }
        } catch (e: Exception) {
            val removed = UploadRollback.remove(ctx, created)
            throw PluginError(
                "upload_failed",
                UploadRollback.failureMessage(written, declared, removed),
            )
        }

        // A short body is an error, never a smaller file that answers 200.
        if (written != declared) {
            val removed = UploadRollback.remove(ctx, created)
            throw PluginError(
                "incomplete_body",
                UploadRollback.failureMessage(written, declared, removed),
            )
        }

        return Resp.ok(JSONObject().put("name", name).put("size", written).put("folder", FOLDER))
    }

    // --- pre-gate validation -------------------------------------------------------------------

    /**
     * Everything that can only end in a 400, checked before the human is asked.
     *
     * Order matters: the grant is checked by the route itself (it answers 503 with the picker
     * notification), so this only refuses input the caller controls.
     */
    private fun preflight(req: Req) {
        val name = store.requireName(requireParam(req, "path"))
        val declared = req.bodyLength
        if (declared < 0) {
            // Cannot enforce a cap against a length nobody declared, and streaming blind is how a
            // caller smuggles an unbounded body past the limit.
            throw PluginError("length_required", "an upload must declare Content-Length")
        }
        if (declared > MAX_BYTES) {
            throw PluginError(
                "file_too_large",
                "$declared bytes exceeds the ${MAX_BYTES / (1024 * 1024)} MiB limit",
            )
        }
        if (declared == 0L) throw PluginError("bad_request", "'$name' would be empty")
    }

    // --- card rows -----------------------------------------------------------------------------

    /**
     * The listing card names the FOLDER.
     *
     * "LIST FILES" with no rows asks someone to approve a read of the phone's storage without
     * saying where — the same defect as an upload card that omits the size. The grant is one folder,
     * so the row names that folder and how many entries are in it.
     */
    private fun listRows(@Suppress("UNUSED_PARAMETER") req: Req): List<ApprovalRow> {
        val count = withGrantOrNull { store.list().size }
        return listOf(
            ApprovalRow("LIST", FOLDER, ApprovalRow.Style.WARN),
            ApprovalRow("FILES", if (count == null) "folder not granted yet" else "$count in the folder",
                ApprovalRow.Style.NORMAL),
        )
    }

    /** The download card names the FILE, and its size when the provider knows it. */
    private fun readRows(req: Req): List<ApprovalRow> {
        val name = runCatching { store.requireName(requireParam(req, "path")) }.getOrNull()
            ?: return emptyList()
        val entry = withGrantOrNull { store.find(name) }
        return listOf(
            ApprovalRow("READ", name, ApprovalRow.Style.WARN),
            ApprovalRow("FROM", "$FOLDER/$name", ApprovalRow.Style.NORMAL),
            ApprovalRow(
                "SIZE",
                when {
                    entry == null -> "not found"
                    entry.size <= 0 -> "size not reported"
                    else -> humanSize(entry.size)
                },
                ApprovalRow.Style.NORMAL,
            ),
        )
    }

    /**
     * [withGrant] for a CARD, where a refusal must not become an error.
     *
     * A row builder runs before the gate, and a missing grant is answered by the route (503 plus the
     * picker notification) — so the card must still render, saying plainly that nothing is granted,
     * rather than throwing and hiding the modal behind an error.
     */
    private fun <T> withGrantOrNull(block: () -> T): T? = try {
        block()
    } catch (e: FilesStore.NotGranted) {
        null
    }

    /** The modal names the full target, so "upload" can never mean "overwrite" something. */
    private fun uploadRows(req: Req): List<ApprovalRow> {
        val name = store.requireName(requireParam(req, "path"))
        // **"Unknown" is not "zero".** -1 means the length never reached this row, and rendering
        // it as `0 B` was a real defect on device: six cards said a 43 MB APK was 0 bytes, which
        // reads as a small, safe transfer. A size that is not known is SAID to be unknown.
        val size = if (req.bodyLength >= 0) humanSize(req.bodyLength) else "size not declared"
        return listOf(
            ApprovalRow("UPLOAD", name, ApprovalRow.Style.WARN),
            ApprovalRow("TO", "$FOLDER/$name", ApprovalRow.Style.NORMAL),
            ApprovalRow("SIZE", size, ApprovalRow.Style.NORMAL),
        )
    }

    // --- grant ---------------------------------------------------------------------------------

    private fun granted(): Boolean = try {
        store.root()
        true
    } catch (e: FilesStore.NotGranted) {
        false
    }

    /**
     * Turn [FilesStore.NotGranted] into the documented refusal, and ask for the grant exactly once.
     *
     * One notification per refusal would let a polling caller fill the tray, so the ask is
     * de-duplicated by the notifier itself — see [FolderGrant].
     */
    private fun <T> withGrant(block: () -> T): T = try {
        block()
    } catch (e: FilesStore.NotGranted) {
        FolderGrant.askOnce(ctx)
        throw PluginError(
            "folder_not_granted",
            "no folder has been granted yet; tap the notification to choose one, then retry",
        )
    }

    private fun requireParam(req: Req, name: String): String =
        req.query[name]?.takeIf { it.isNotBlank() }
            ?: throw PluginError("bad_request", "'$name' is required")

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        /** Where files land, for messages and the audit summary. */
        const val FOLDER = "Downloads/STMP"

        /** 200 MiB. One number, so raising it is a decision rather than a hunt. */
        const val MAX_BYTES = 200L * 1024L * 1024L
    }
}
