package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.plugins.ssh.SshConnection
import com.mido.phonebridge.plugins.ssh.SshKeygen
import com.mido.phonebridge.ui.Theme

/**
 * SSH connections: one row per host, with its public key ready to copy.
 *
 * **Its own screen, not a section of Secrets** (Mido, 2026-10-21: *"for ssh connection it's own
 * screen because I will have a couple"*). An SSH connection is not a name and a value — it is a
 * host, a port, a user, a generated key and a pinned fingerprint, and the operations you want to
 * perform against it are nothing like "send this API a request".
 *
 * **The private key is never rendered here either.** It goes straight from [SshKeygen] into the
 * vault at creation and is never read back for display; only the public half is shown. The Secrets
 * screen refuses to reveal `kind = SSH` entries for the same reason.
 */
class SshActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A public key is not a secret, but the screen sits next to one and a screenshot of a
        // half-built connection dialog is not worth the risk.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Theme.sheet(Theme.BG)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        header = TextView(this).apply {
            textSize = Theme.TEXT_DETAIL_SP
            setTextColor(Theme.ON_SURFACE_VARIANT)
        }
        content.addView(header)

        content.addView(Theme.sectionHeader(this, "SSH connections"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        val addBtn = Theme.button(this, "ADD CONNECTION", Theme.ButtonStyle.SUCCESS) { addDialog() }
        addBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Theme.dp(this@SshActivity, 12) }
        content.addView(addBtn)

        content.addView(
            Theme.text(
                this,
                "Each connection generates its own ed25519 key on this phone. Paste the public key " +
                    "into the server's ~/.ssh/authorized_keys once — the private half never leaves " +
                    "the vault.",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@SshActivity, 14), 0, 0) }
        )

        root.addView(scroll)
        setContentView(Theme.inset(root))
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val unlocked = SecretVault.isUnlocked()
        header.text = if (unlocked) {
            "Vault unlocked for this session."
        } else {
            "Vault locked — reopen the app and scan your fingerprint."
        }

        listContainer.removeAllViews()
        val names = try {
            SecretVault.names(this).filter { it.startsWith("ssh-") }
        } catch (e: Exception) {
            listContainer.addView(row("vault unreadable", e.message ?: "", Theme.DANGER_SURFACE))
            emptyList()
        }
        if (names.isEmpty()) {
            listContainer.addView(row("no connections yet", "tap ADD CONNECTION", Theme.SURFACE))
        }

        for (vaultName in names) {
            val label = vaultName.removePrefix("ssh-")
            val conn = try {
                SshConnection.fromJson(SecretVault.get(this, vaultName))
            } catch (e: Exception) {
                listContainer.addView(
                    row(label, "unreadable: ${e.message}", Theme.DANGER_SURFACE)
                )
                continue
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Theme.dp(this@SshActivity, 14), Theme.dp(this@SshActivity, 12),
                    Theme.dp(this@SshActivity, 14), Theme.dp(this@SshActivity, 12)
                )
                background = Theme.outlined(this@SshActivity, Theme.SURFACE, Theme.RADIUS_CARD)
            }
            card.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Theme.dp(this@SshActivity, 10) }

            card.addView(Theme.text(this, label, 16f))
            card.addView(
                Theme.text(
                    this, "${conn.user}@${conn.host}:${conn.port}", 13f, Theme.ON_SURFACE_VARIANT
                )
            )

            // The key type is worth stating: it is the thing a server's authorized_keys cares
            // about, and an unexpected type is a clue something is wrong before a call fails.
            SshKeygen.keyTypeOf(conn.privateKey)?.let {
                card.addView(Theme.text(this, it, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT))
            }

            // HOST KEY state. A connection with no pin has never been confirmed, and the next call
            // will refuse and report the fingerprint rather than running — saying so here means
            // that refusal is expected rather than surprising.
            val pinned = conn.hostKeyFingerprint
            card.addView(
                Theme.text(
                    this,
                    if (pinned == null) "host key: not yet trusted — the first call will report it"
                    else "host key: $pinned",
                    12f,
                    if (pinned == null) Theme.WARNING else Theme.SUCCESS
                ).apply { setPadding(0, Theme.dp(this@SshActivity, 6), 0, 0) }
            )

            // --- the key's own identity, on the card ---
            //
            // "SSH needs more details when opened" (Mido, 2026-11-04). The card used to name the
            // key type and then offer a button whose only output was a dialog. That meant the two
            // things you actually check against a server — the public key line and its
            // fingerprint — were one tap deep, and the fingerprint was not shown anywhere at all.
            //
            // Both are public by construction: the private half never leaves the vault.
            val pub = SshKeygen.publicKeyOf(conn.privateKey, comment = "phonebridge-$label")
            val fingerprint = pub?.let { SshKeygen.fingerprintOf(it) }
            if (fingerprint != null) {
                card.addView(
                    Theme.text(this, "key fingerprint", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT)
                        .apply { setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0) }
                )
                card.addView(
                    Theme.text(this, fingerprint, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE).apply {
                        typeface = Theme.monoFont(this@SshActivity)
                        setTextIsSelectable(true)
                    }
                )
            }
            if (pub != null) {
                card.addView(
                    Theme.text(this, "public key", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT)
                        .apply { setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0) }
                )
                card.addView(
                    Theme.text(this, pub, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE).apply {
                        typeface = Theme.monoFont(this@SshActivity)
                        setTextIsSelectable(true)
                    }
                )
            }
            // The vault entry name, so a duplicate label on two hosts is tellable apart.
            card.addView(
                Theme.text(this, "vault entry: $vaultName", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0) }
            )

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(small("copy public key") { copyPublicKey(label, conn) })
            buttons.addView(small("delete") { confirmDelete(label, vaultName) })
            card.addView(buttons)

            listContainer.addView(card)
        }
    }

    /**
     * Show the public key in a selectable box.
     *
     * A DIALOG rather than a clipboard write on the spot: a key silently copied leaves no chance
     * to check it is the one you expect, and this is the one string a human has to carry to a
     * server by hand. The dialog has its own copy button for when that is what you want.
     */
    private fun copyPublicKey(label: String, conn: SshConnection) {
        val pub = SshKeygen.publicKeyOf(conn.privateKey, comment = "phonebridge-$label")
        if (pub == null) {
            toast("could not read the public key for '$label'")
            return
        }
        val box = TextView(this).apply {
            text = pub
            textSize = Theme.TEXT_CAPTION_SP
            typeface = Theme.monoFont(this@SshActivity)
            setTextIsSelectable(true)
            setTextColor(Theme.ON_SURFACE)
            setPadding(
                Theme.dp(this@SshActivity, 12), Theme.dp(this@SshActivity, 10),
                Theme.dp(this@SshActivity, 12), Theme.dp(this@SshActivity, 10)
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Public key — $label")
            .setMessage("Add this line to ~/.ssh/authorized_keys on ${conn.host}.")
            .setView(box)
            .setPositiveButton("copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ssh public key", pub))
                toast("public key copied")
            }
            .setNegativeButton("close", null)
            .show()
    }

    /**
     * Create a connection: generate the key, store it, done.
     *
     * **No key field.** Mido, 2026-10-21: *"generate, or use one if already generated"* — and since
     * there is no way to import one yet, this dialog only generates. The public half is shown
     * immediately afterwards, because a connection with no key installed on the server cannot do
     * anything and the human needs the line right then.
     */
    private fun addDialog() {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
        val nameField = field("name (lowercase, e.g. prod)")
        val hostField = field("host (e.g. box.example.com)")
        val portField = field("port (default 22)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("22")
        }
        val userField = field("user (e.g. deploy)")

        // The reason a failure left the dialog empty, shown INSIDE it.
        val errorLine = TextView(this).apply {
            textSize = Theme.TEXT_CAPTION_SP
            setTextColor(Theme.DANGER)
            visibility = android.view.View.GONE
            setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@SshActivity, 16), Theme.dp(this@SshActivity, 8),
                Theme.dp(this@SshActivity, 16), 0
            )
            addView(nameField)
            addView(hostField)
            addView(portField)
            addView(userField)
            addView(errorLine)
        }

        // **A REAL dialog, not `setPositiveButton`.**
        //
        // Mido, 2026-11-04: *"the problem is the dialog is lost, a failed attempt should keep the
        // dialog and its data for easy fix"*.
        //
        // `setPositiveButton` ALWAYS dismisses when tapped — that is the contract. So the old
        // `return@setPositiveButton` on a validation failure returned from a dialog Android had
        // already decided to close. The name rule is the one people hit (it demands lowercase
        // `[a-z0-9_-]`), and the result was: hint unreadable as a toast, all four fields gone,
        // retype from scratch. A validation failure must cost a keystroke, not the form.
        //
        // So the button is wired manually and the dialog closes ONLY on success. The error is
        // reported IN the dialog, where the fields it refers to still are.
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add SSH connection")
            .setView(box)
            .setNegativeButton("cancel", null)
            .setPositiveButton("generate key", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.text.toString().trim().lowercase()
                val host = hostField.text.toString().trim()
                val user = userField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull() ?: 22

                fun refuse(message: String, focus: android.widget.EditText) {
                    errorLine.text = message
                    errorLine.visibility = android.view.View.VISIBLE
                    focus.requestFocus()
                    Debug.log("ssh: add refused: $message")
                }

                if (!name.matches(Regex("[a-z0-9][a-z0-9_-]{0,48}"))) {
                    refuse(
                        "Name must be lowercase letters, digits, - or _ (1-49 chars). " +
                            "\"$name\" has something else.",
                        nameField,
                    )
                    return@setOnClickListener
                }
                if (host.isBlank()) {
                    refuse("Host is required.", hostField)
                    return@setOnClickListener
                }
                if (user.isBlank()) {
                    refuse("User is required.", userField)
                    return@setOnClickListener
                }
                if (port !in 1..65535) {
                    refuse("Port must be between 1 and 65535.", portField)
                    return@setOnClickListener
                }
                if (SecretVault.has(this, "ssh-$name")) {
                    refuse("A connection named \"$name\" already exists. Pick another name.", nameField)
                    return@setOnClickListener
                }

                errorLine.visibility = android.view.View.GONE
                try {
                    val key = SshKeygen.generate(comment = "phonebridge-$name")
                    val conn = SshConnection(
                        host = host,
                        port = port,
                        user = user,
                        privateKey = key.privateKey,
                        // Nothing pinned yet: the first call reports the fingerprint and refuses,
                        // which is the confirmation step.
                        hostKeyFingerprint = null
                    )
                    SecretVault.put(
                        this, "ssh-$name", conn.toJson().toString(),
                        kind = SecretVault.SecretMeta.Kind.SSH
                    )
                    // ONLY here does the dialog close: the connection exists.
                    dialog.dismiss()
                    render()
                    // Show the public key straight away — the connection is useless until the
                    // human installs it, so handing it over now is the only useful next step.
                    copyPublicKey(name, conn)
                } catch (e: PluginError) {
                    // Logged as well as shown: this is the one path whose failure the human
                    // reports as "it just doesn't work".
                    Debug.log("ssh: create '$name' failed: ${e.code}: ${e.message}")
                    refuse("${e.code}: ${e.message}", nameField)
                } catch (e: Throwable) {
                    // **Throwable, not Exception.** The 0.40.3 failure was a
                    // `NoClassDefFoundError` from inside JSch — an `Error`, which an `Exception`
                    // catch does not see at all. Catching `Throwable` here is correct precisely
                    // because this is a UI boundary: the process must not die and the reason must
                    // reach the log.
                    Debug.log("ssh: create '$name' CRASHED: ${e.javaClass.name}: ${e.message}")
                    refuse("Could not create key: ${e.javaClass.simpleName}: ${e.message}", nameField)
                }
            }
        }
        dialog.show()
    }

    /** A text field styled to match the paper palette. */
    private fun field(hintText: String) = EditText(this).apply {
        // Explicit colours: the platform default is white-on-cream here, and with a DayNight
        // parent it went the other way on a dark-mode phone — the mismatch Mido reported.
        setTextColor(Theme.ON_SURFACE)
        setHintTextColor(Theme.ON_SURFACE_VARIANT)
        highlightColor = Theme.ACCENT
        // A drawn background, so the field is a paper block rather than a Material underline
        // that carries its own theme colours.
        background = Theme.outlined(this@SshActivity, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL)
        setPadding(
            Theme.dp(this@SshActivity, 12), Theme.dp(this@SshActivity, 10),
            Theme.dp(this@SshActivity, 12), Theme.dp(this@SshActivity, 10)
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(this@SshActivity, 8) }
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun confirmDelete(label: String, vaultName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete '$label'?")
            .setMessage(
                "This removes the connection and its private key from the vault. The public key " +
                    "stays in authorized_keys on the server — remove that line there too if you " +
                    "want to revoke it."
            )
            .setPositiveButton("delete") { _, _ ->
                try {
                    SecretVault.delete(this, vaultName)
                    render()
                    toast("deleted '$label'")
                } catch (e: Exception) {
                    toast("could not delete: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun row(title: String, detail: String, color: Int) = TextView(this).apply {
        text = "$title\n$detail"
        textSize = Theme.TEXT_BODY_SP
        setTextColor(Theme.ON_SURFACE)
        setPadding(
            Theme.dp(this@SshActivity, 14), Theme.dp(this@SshActivity, 12),
            Theme.dp(this@SshActivity, 14), Theme.dp(this@SshActivity, 12)
        )
        background = Theme.surface(this@SshActivity, color, Theme.RADIUS_CARD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(this@SshActivity, 10) }
    }

    private fun small(label: String, onClick: () -> Unit) =
        Theme.button(this, label, Theme.ButtonStyle.NEUTRAL, small = true, onClick = onClick)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
