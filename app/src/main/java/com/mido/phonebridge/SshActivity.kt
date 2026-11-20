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
import com.mido.phonebridge.ui.showDestructive
import com.mido.phonebridge.ui.PaperDialog
import com.mido.phonebridge.ui.labelledField
import com.mido.phonebridge.ui.showSecure

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

        val pad = Theme.dp(this, Theme.SCREEN_GUTTER_DP)
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
            SecretVault.ownedBy(this, "ssh")
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
                com.mido.phonebridge.ui.Theme.padCard(this@SshActivity, this)
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
        PaperDialog.show(
            ctx = this,
            title = "Public key — $label",
            body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(PaperDialog.caption(this@SshActivity, "Add this line to ~/.ssh/authorized_keys on ${conn.host}."))
                addView(
                    box,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(this@SshActivity, 10) },
                )
            },
            actions = listOf(
                "copy" to {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ssh public key", pub))
                    toast("public key copied")
                },
                "close" to {},
            ),
            style = { index -> if (index == 0) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL },
        )
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
        // The reason a refusal left the form intact, shown inside it, next to the fields.
        val errorLine = TextView(this).apply {
            textSize = Theme.TEXT_CAPTION_SP
            setTextColor(Theme.DANGER)
            visibility = android.view.View.GONE
            setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0)
        }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // **Required first.** Everything here is required except the port, which is prefilled — so
        // the order is the order the connection is described in: what to call it, where it is, as
        // whom, and then the value that is already filled in.
        val nameField = labelledField(
            box, "NAME",
            "What you will call this connection. It becomes part of the route " +
                "(POST /ssh/<name>) and of the policy key, so it is lowercase letters, digits, " +
                "- or _ only. Pick something you will recognise on an approval card.",
            example = "prod",
            required = true,
        )
        val hostField = labelledField(
            box, "HOST",
            "The address of the machine to connect to — a hostname or an IP. This is what the host " +
                "key is pinned against on the first connection, so a name that resolves differently " +
                "later will be refused rather than trusted quietly.",
            example = "box.example.com",
            required = true,
        )
        val userField = labelledField(
            box, "USER",
            "The account to log in as. The generated key is installed in THIS user's " +
                "~/.ssh/authorized_keys, so the commands you run are that user's commands.",
            example = "deploy",
            required = true,
        )
        val portField = labelledField(
            box, "PORT",
            "The SSH port on that machine. Almost always 22; change it only if the server listens " +
                "somewhere else, or if it sits behind a port forward.",
            example = "22",
            inputType = InputType.TYPE_CLASS_NUMBER,
        ).apply { setText("22") }
        box.addView(errorLine)

        // Save, returning "refused?" so the sheet stays open with every field still typed in.
        // Mido, 2026-11-04: *"a failed attempt should keep the dialog and its data for easy fix"*.
        fun attemptCreate(): Boolean {
            val name = nameField.text.toString().trim().lowercase()
            val host = hostField.text.toString().trim()
            val user = userField.text.toString().trim()
            val port = portField.text.toString().trim().toIntOrNull() ?: 22

            fun refuse(message: String, focus: android.widget.EditText): Boolean {
                errorLine.text = message
                errorLine.visibility = android.view.View.VISIBLE
                focus.requestFocus()
                Debug.log("ssh: add refused: $message")
                return true
            }

            // The rule matches the one the screen uses elsewhere (49 chars here, 48 after the
            // leading character), so a name accepted in one place is accepted everywhere.
            if (!name.matches(Regex("[a-z0-9][a-z0-9_-]{0,48}"))) {
                return refuse(
                    "Name must be lowercase letters, digits, - or _ (1-49 chars). " +
                        "\"$name\" has something else.",
                    nameField,
                )
            }
            if (host.isBlank()) return refuse("Host is required.", hostField)
            if (user.isBlank()) return refuse("User is required.", userField)
            if (port !in 1..65535) return refuse("Port must be between 1 and 65535.", portField)
            if (SecretVault.has(this, "ssh-$name")) {
                return refuse("A connection named \"$name\" already exists. Pick another name.", nameField)
            }

            errorLine.visibility = android.view.View.GONE
            try {
                val key = SshKeygen.generate(comment = "phonebridge-$name")
                val conn = SshConnection(
                    host = host,
                    port = port,
                    user = user,
                    privateKey = key.privateKey,
                    // Nothing pinned yet: the first call reports the fingerprint and refuses, which
                    // is the confirmation step.
                    hostKeyFingerprint = null
                )
                SecretVault.put(
                    this, "ssh-$name", conn.toJson().toString(),
                    kind = SecretVault.SecretMeta.Kind.SSH,
                    // Declared ownership: the SSH screen lists `ownedBy("ssh")` rather than
                    // pattern-matching the `ssh-` prefix, which is what let this name convention
                    // leak into other screens.
                    owner = "ssh",
                )
                render()
                // Show the public key straight away — the connection is useless until the human
                // installs it, so handing it over now is the only useful next step. Posted, so it
                // opens after this sheet closes rather than behind it.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    copyPublicKey(name, conn)
                }
                return false
            } catch (e: PluginError) {
                // Logged as well as shown: this is the one path whose failure the human reports as
                // "it just doesn't work".
                Debug.log("ssh: create '$name' failed: ${e.code}: ${e.message}")
                return refuse("${e.code}: ${e.message}", nameField)
            } catch (t: Throwable) {
                // **Throwable, not Exception.** The 0.40.3 failure was a `NoClassDefFoundError` from
                // inside JSch — an `Error`, which an `Exception` catch does not see at all. Correct
                // here precisely because this is a UI boundary: the process must not die and the
                // reason must reach the log.
                Debug.log("ssh: create '$name' CRASHED: ${t.javaClass.name}: ${t.message}")
                return refuse("Could not create key: ${t.javaClass.simpleName}: ${t.message}", nameField)
            }
        }

        PaperDialog.show(
            ctx = this,
            title = "Add SSH connection",
            body = box,
            actions = listOf("generate key" to {}, "cancel" to {}),
            style = { index -> if (index == 0) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL },
            refuse = { label -> if (label == "generate key") attemptCreate() else false },
        )
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
            .showDestructive()
    }

    private fun row(title: String, detail: String, color: Int) = TextView(this).apply {
        text = "$title\n$detail"
        textSize = Theme.TEXT_BODY_SP
        setTextColor(Theme.ON_SURFACE)
        com.mido.phonebridge.ui.Theme.padCard(this@SshActivity, this)
        background = com.mido.phonebridge.ui.Theme.card(this@SshActivity, color)
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
