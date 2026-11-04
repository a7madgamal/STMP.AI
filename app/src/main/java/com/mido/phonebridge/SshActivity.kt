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
            textSize = 13f
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
                card.addView(Theme.text(this, it, 12f, Theme.ON_SURFACE_VARIANT))
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
                    Theme.text(this, "key fingerprint", 11f, Theme.ON_SURFACE_VARIANT)
                        .apply { setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0) }
                )
                card.addView(
                    Theme.text(this, fingerprint, 12f, Theme.ON_SURFACE).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    }
                )
            }
            if (pub != null) {
                card.addView(
                    Theme.text(this, "public key", 11f, Theme.ON_SURFACE_VARIANT)
                        .apply { setPadding(0, Theme.dp(this@SshActivity, 8), 0, 0) }
                )
                card.addView(
                    Theme.text(this, pub, 11f, Theme.ON_SURFACE).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    }
                )
            }
            // The vault entry name, so a duplicate label on two hosts is tellable apart.
            card.addView(
                Theme.text(this, "vault entry: $vaultName", 11f, Theme.ON_SURFACE_VARIANT)
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
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
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
        val nameField = EditText(this).apply {
                // Explicit colours: the platform default is white-on-cream here.
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
            hint = "name (lowercase, e.g. prod)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val hostField = EditText(this).apply {
                // Explicit colours: the platform default is white-on-cream here.
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
            hint = "host (e.g. box.example.com)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val portField = EditText(this).apply {
                // Explicit colours: the platform default is white-on-cream here.
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
            hint = "port (default 22)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("22")
        }
        val userField = EditText(this).apply {
                // Explicit colours: the platform default is white-on-cream here.
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
            hint = "user (e.g. deploy)"
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
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
        }

        AlertDialog.Builder(this)
            .setTitle("Add SSH connection")
            .setView(box)
            .setPositiveButton("generate key") { _, _ ->
                val name = nameField.text.toString().trim().lowercase()
                val host = hostField.text.toString().trim()
                val user = userField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull() ?: 22

                if (!name.matches(Regex("[a-z0-9][a-z0-9_-]{0,48}"))) {
                    toast("name must be lowercase [a-z0-9_-]")
                    return@setPositiveButton
                }
                if (host.isBlank() || user.isBlank()) {
                    toast("host and user are required")
                    return@setPositiveButton
                }
                if (port !in 1..65535) {
                    toast("port must be 1..65535")
                    return@setPositiveButton
                }
                if (SecretVault.has(this, "ssh-$name")) {
                    toast("'$name' already exists")
                    return@setPositiveButton
                }

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
                    render()
                    // Show the public key straight away — the connection is useless until the
                    // human installs it, so handing it over now is the only useful next step.
                    copyPublicKey(name, conn)
                } catch (e: PluginError) {
                    // Logged as well as toasted: a toast disappears, and this is the one path whose
                    // failure the human reports as "it just doesn't work".
                    Debug.log("ssh: create '$name' failed: ${e.code}: ${e.message}")
                    toast("${e.code}: ${e.message}")
                } catch (e: Throwable) {
                    // **Throwable, not Exception.** The 0.40.3 failure was a
                    // `NoClassDefFoundError` from inside JSch — an `Error`, which an `Exception`
                    // catch does not see at all. So the dialog threw, nothing was caught, nothing
                    // was logged, and the only symptom was a key that never appeared. Catching
                    // `Throwable` here is correct precisely because this is a UI boundary: the
                    // process must not die and the reason must reach the log.
                    Debug.log("ssh: create '$name' CRASHED: ${e.javaClass.name}: ${e.message}")
                    toast("could not create '$name': ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .show()
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
        textSize = 14f
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
