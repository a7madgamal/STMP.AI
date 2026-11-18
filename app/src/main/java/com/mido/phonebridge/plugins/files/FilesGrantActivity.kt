package com.mido.phonebridge.plugins.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.ui.Theme

/**
 * The folder prompt: a full-screen paper sheet that explains what is being asked and why, then hands
 * the human to the system picker.
 *
 * **A sheet, not a bare picker.** `ACTION_OPEN_DOCUMENT_TREE` is a filesystem browser with no
 * context — a human dropped into it cold has no idea which app asked, which folder to choose, or
 * what will happen afterwards. The vault unlock sets this precedent in this app: say what is needed
 * in the app's own voice, then open the system surface for the one step the app cannot do itself.
 *
 * **It cannot grant anything by itself.** The only way out with a grant is the picker returning a
 * tree URI; every other exit leaves the plugin exactly as it was, and the next request asks again.
 */
class FilesGrantActivity : Activity() {

    private val store by lazy { FilesStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            showPrompt()
        }
        // A recreated activity is the "returning from the picker" case; the result is still
        // delivered to onActivityResult, so the screen only has to exist.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleared here rather than in onActivityResult: a human who backs out or swipes the sheet
        // away must be askable again on the next request.
        FolderGrant.finished()
    }

    private fun showPrompt() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
            setPadding(dp(24), dp(40), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            text(
                "Choose a folder",
                Theme.TEXT_TITLE_SP,
                Theme.ON_SURFACE,
            )
        )
        root.addView(
            text(
                "A request wants to read or write files through STMP. It can only touch the one " +
                    "folder you pick — it cannot see anything else on the phone.",
                Theme.TEXT_BODY_SP,
                Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, dp(12), 0, 0) }
        )
        root.addView(
            text(
                "Pick ${FilesPlugin.FOLDER}.",
                Theme.TEXT_BODY_SP,
                Theme.ON_SURFACE,
            ).apply { setPadding(0, dp(16), 0, 0) }
        )
        root.addView(
            text(
                "STMP can add files and read files there. It cannot delete or replace one.",
                Theme.TEXT_CAPTION_SP,
                Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, dp(8), 0, 0) }
        )

        val choose = Theme.button(this, "CHOOSE FOLDER", Theme.ButtonStyle.PRIMARY) { launchPicker() }
        root.addView(
            choose,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )
        val cancel = Theme.button(this, "NOT NOW", Theme.ButtonStyle.NEUTRAL) { finish() }
        root.addView(
            cancel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        setContentView(root)
    }

    /**
     * The system picker.
     *
     * `ACTION_OPEN_DOCUMENT_TREE` is the only surface that can hand out a persistable grant for a
     * whole folder, which is why the app cannot do this step itself. The picker also has a "create
     * folder" affordance, so a phone without `Downloads/STMP` yet needs nothing pre-made.
     */
    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        try {
            startActivityForResult(intent, REQUEST_TREE)
        } catch (e: Exception) {
            // No document provider on this device: say so in the app's own words rather than
            // leaving a dead sheet. The caller's 503 named the same problem.
            renderError("This phone has no folder picker available.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) return
        val uri: Uri? = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            // Backing out is a decision, not a failure: close quietly and let the next request ask.
            finish()
            return
        }
        try {
            store.accept(uri)
            renderError(null, granted = true)
        } catch (e: PluginError) {
            renderError(e.message)
        } catch (e: Exception) {
            // `takePersistableUriPermission` throws when the provider offered no persistable grant.
            // That is a real limitation and is said plainly, because the plugin cannot work without
            // a grant that survives a reboot.
            renderError("That folder cannot be kept across restarts (${e.javaClass.simpleName}).")
        }
    }

    /** Terminal screen: success, or why it failed. Both close themselves. */
    private fun renderError(message: String?, granted: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
            setPadding(dp(24), dp(40), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            text(
                if (granted) "Folder granted" else "Could not use that folder",
                Theme.TEXT_TITLE_SP,
                if (granted) Theme.SUCCESS else Theme.DANGER,
            )
        )
        root.addView(
            text(
                if (granted) {
                    "STMP can now read and add files in ${FilesPlugin.FOLDER}. Retry the request."
                } else {
                    message ?: "Unknown problem."
                },
                Theme.TEXT_BODY_SP,
                Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, dp(12), 0, 0) }
        )
        root.addView(
            Theme.button(this, "DONE", Theme.ButtonStyle.PRIMARY) { finish() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )
        setContentView(root)
    }

    private fun text(body: String, size: Float, color: Int): TextView =
        TextView(this).apply {
            text = body
            textSize = size
            setTextColor(color)
            typeface = Theme.bodyFont(this@FilesGrantActivity)
        }

    private fun dp(v: Int): Int = Theme.dp(this, v)

    private companion object {
        const val REQUEST_TREE = 41
    }
}
