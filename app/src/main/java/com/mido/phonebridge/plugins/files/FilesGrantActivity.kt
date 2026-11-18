package com.mido.phonebridge.plugins.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.ui.Theme

/**
 * The only screen this plugin has: hand the human to the system folder picker and remember what
 * they chose.
 *
 * **A picker, not a text field.** The folder is chosen in the platform's own document UI, so the
 * app never invents a path and never has to validate one. What comes back is a URI grant, which is
 * the whole boundary — there is no root to escape because there is no path being joined.
 *
 * **Reachable only from the notification.** This activity has no launcher icon and is not part of
 * the app's navigation: the grant is asked for when a request needs it, never at setup.
 */
class FilesGrantActivity : Activity() {

    private val store by lazy { FilesStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            launchPicker()
        } else {
            // A recreated activity is the "back from the picker" case: the result is still
            // delivered to onActivityResult, so the screen only needs to stay alive.
            render("Choose the folder…")
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // The picker's own "create folder" affordance is what lets the human make STMP if it
            // does not exist yet, so this does not need to pre-create anything.
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        try {
            startActivityForResult(intent, REQUEST_TREE)
        } catch (e: Exception) {
            // A device with no document provider cannot grant anything. Say so rather than leaving
            // a blank screen; the 503 the caller already got names the same fix.
            render("This device has no folder picker available.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            render("No folder chosen — the next request will ask again.")
            return
        }
        try {
            store.accept(uri)
            FolderGrant.dismiss(this)
            render("Granted: ${displayPath(uri)}")
        } catch (e: Exception) {
            // `takePersistableUriPermission` throws when the provider did not offer a persistable
            // grant — some providers do not. That is a real failure and is said plainly.
            render("The folder could not be kept: ${e.javaClass.simpleName}")
        }
    }

    /** The provider's own name for the tree, falling back to the uri. */
    private fun displayPath(uri: Uri): String = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: Exception) {
        uri.toString()
    }

    private fun render(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
            setPadding(
                Theme.dp(this@FilesGrantActivity, 24),
                Theme.dp(this@FilesGrantActivity, 48),
                Theme.dp(this@FilesGrantActivity, 24),
                Theme.dp(this@FilesGrantActivity, 24),
            )
        }
        root.addView(
            TextView(this).apply {
                text = message
                textSize = Theme.TEXT_BODY_SP
                setTextColor(Theme.ON_SURFACE)
                typeface = Theme.bodyFont(this@FilesGrantActivity)
            }
        )
        setContentView(root)
    }

    private companion object {
        const val REQUEST_TREE = 41
    }
}
