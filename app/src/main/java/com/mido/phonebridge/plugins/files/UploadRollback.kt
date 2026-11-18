package com.mido.phonebridge.plugins.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.VisibleForTesting

/**
 * Remove a document this upload just created, when the transfer failed.
 *
 * **This is the only delete in the plugin, and it is not the DELETE route coming back.** The
 * promise `files` makes is about the human's files; a half-written document from a transfer that
 * stopped is not one they asked for. Leaving it is the worse failure: the Files app shows a
 * complete-looking file, its size is whatever arrived, and nothing marks it truncated. Rolling it
 * back means the notification can say "nothing was kept" and be telling the truth.
 *
 * **It can only ever remove something this call created.** The collision rule refuses an existing
 * name before the document is made, so the URI handed here did not exist a moment earlier.
 *
 * A rollback that itself fails is logged and swallowed: the transfer already failed, and throwing
 * from the cleanup would replace the real error (why the upload stopped) with a secondary one.
 * The response still says nothing was kept only when the removal succeeded — otherwise it says the
 * partial file may remain, which is the honest answer.
 */
internal object UploadRollback {

    /** @return true when the document is gone. False means a partial file may be on disk. */
    fun remove(ctx: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        com.mido.phonebridge.Debug.log(
            "files: could not remove a failed upload (${e.javaClass.simpleName}): ${e.message}"
        )
        false
    }

    /**
     * The sentence the failure notification shows.
     *
     * Two different facts, and conflating them is how a report becomes misleading: "nothing was
     * kept" is only true when the removal happened.
     */
    @VisibleForTesting
    fun failureMessage(written: Long, declared: Long, removed: Boolean): String {
        val head = "upload failed after $written of $declared bytes"
        return if (removed) "$head — nothing was kept"
        else "$head — a partial file may remain; delete it in Files"
    }
}
