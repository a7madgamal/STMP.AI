package com.mido.phonebridge.plugins.files

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.mido.phonebridge.core.PluginError

/**
 * The one folder the human granted, and every operation that touches it.
 *
 * **A URI grant, not a path.** `Downloads/STMP` is where the files land, but nothing here builds a
 * filesystem path: the tree URI the picker returned is the boundary, and every child is found by
 * querying the provider. That removes the path-arithmetic class of bug entirely — there is no `..`
 * to escape from because there is no path being joined.
 *
 * **The grant is asked for when a request needs it.** No setup screen: a route that finds no grant
 * throws [NotGranted], which the plugin answers as `503 folder_not_granted` plus one notification
 * whose tap opens the picker. The HTTP call does not wait for the picker — a human browsing a
 * filesystem is unbounded time and often on another app's screen.
 */
class FilesStore(private val ctx: Context) {

    /** No grant, or one the system has since revoked. The plugin turns this into 503. */
    class NotGranted : Exception("no folder has been granted")

    /** One file in the granted folder, as the provider reports it. */
    data class Entry(
        val name: String,
        val size: Long,
        /** Last modified, millis. 0 when the provider does not know. */
        val mtime: Long,
        val isDir: Boolean,
        val uri: Uri,
    )

    /**
     * The persisted grant.
     *
     * Read from `persistedUriPermissions` rather than from our own string: the system is the
     * authority, and a uri kept in prefs can outlive the permission it described. Checking the live
     * list is what makes a revocation in system settings show up as [NotGranted] rather than as a
     * confusing read failure later.
     *
     * Ours is identified by the uri we stored when the picker returned, so a second tree the user
     * granted to some other app cannot be mistaken for this one.
     */
    fun root(): Uri {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: throw NotGranted()
        val uri = Uri.parse(stored)
        val live = ctx.contentResolver.persistedUriPermissions
        if (live.none { it.uri == uri && it.isReadPermission }) throw NotGranted()
        // **A live permission is not a live FOLDER.** Delete or rename `Downloads/STMP` and the
        // persisted grant survives — the system still reports it, and every query against it then
        // fails with a provider error that reads like a bug rather than like "the folder is gone".
        // Probing once here turns that into the state the human can actually act on.
        if (!folderReachable(uri)) throw NotGranted()
        return uri
    }

    /**
     * Whether the granted tree still answers.
     *
     * One cheap child query: `listChildren` on a missing tree throws or returns nothing, and either
     * way this is the only way to find out. It is deliberately a liveness probe and not a listing —
     * the result is discarded, so a folder with no files still counts as reachable.
     */
    private fun folderReachable(uri: Uri): Boolean = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { it.moveToFirst(); true } ?: false
    } catch (e: Exception) {
        // A revoked, deleted or unmounted tree throws here — including a provider that is no longer
        // installed. All of those mean the same thing to the caller: ask again.
        com.mido.phonebridge.Debug.log(
            "files: granted folder is unreachable (${e.javaClass.simpleName}): ${e.message}"
        )
        false
    }

    /**
     * Remember the tree the picker returned, and ask the system to keep it.
     *
     * **The previous grant is released first.** Android caps how many persistable grants one app
     * may hold, so keeping an old folder forever is not just untidy — it is a limit that eventually
     * refuses the next grant. Only OUR stored uri is released: releasing every persisted permission
     * would revoke grants some other part of the app may hold.
     */
    fun accept(treeUri: Uri) {
        val previous = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (previous != null && previous != treeUri) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // Releasing something already gone throws. Nothing to do and nothing to tell the
                // human: the grant they just made is what matters.
                com.mido.phonebridge.Debug.log("files: could not release the old grant: ${e.message}")
            }
        }
        // Read AND write: uploads add documents, and the grant must survive a reboot.
        ctx.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URI, treeUri.toString()).apply()
    }

    /** The root's own document id, which every child query is built from. */
    private fun rootDocId(): String = DocumentsContract.getTreeDocumentId(root())

    /**
     * Every file in the granted folder.
     *
     * One level only, no recursion: nesting would need a second grant per folder and a path to
     * traverse, and this plugin deliberately has neither.
     */
    fun list(): List<Entry> {
        val root = root()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, rootDocId())
        val out = mutableListOf<Entry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                // The cursor can report null size/mtime; 0 is "unknown" rather than a guess.
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                out.add(
                    Entry(
                        name = name,
                        size = size,
                        mtime = mtime,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        uri = DocumentsContract.buildDocumentUriUsingTree(root, docId),
                    )
                )
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Find a file by NAME.
     *
     * **A named lookup, not a path join.** The caller passes what it read from [list]. A name
     * containing a separator is refused outright: SAF would treat it as a nested document, and on
     * the MediaStore path this same input was a directory traversal — one rule, both cases.
     *
     * Returns null when nothing in the folder has that name; the caller decides whether that is
     * `404` (a read) or a free name (an upload).
     */
    fun find(name: String): Entry? {
        val clean = requireName(name)
        return list().firstOrNull { it.name == clean }
    }

    /**
     * Refuse anything that is not a plain name.
     *
     * Not a sanitisation: a refused name is answered, never rewritten. Rewriting would mean the
     * file the human approved and the file on disk could differ, which is the one thing a gate
     * must never allow.
     */
    fun requireName(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) throw PluginError("bad_name", "a file name is required")
        if (clean.contains('/') || clean.contains('\\')) {
            throw PluginError("bad_name", "a file name cannot contain a path separator")
        }
        if (clean == "." || clean == "..") {
            throw PluginError("bad_name", "'$clean' is not a file name")
        }
        return clean
    }

    private companion object {
        const val PREFS = "files_grant"
        const val KEY_URI = "tree_uri"
    }
}
