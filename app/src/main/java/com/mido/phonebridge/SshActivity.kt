package com.mido.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.plugins.ssh.SshConnection
import com.mido.phonebridge.plugins.ssh.SshKeygen
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.VaultModule
import com.mido.phonebridge.ui.kit.ActionRow
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.kit.Toasts
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

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
class SshActivity : FragmentActivity() {

    private lateinit var vaultSlot: FrameLayout
    private lateinit var errorSlot: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var addKey: View

    /** Why the last unlock or create attempt on this screen failed. Cleared on success and on leaving. */
    private var vaultError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A public key is not a secret, but the screen sits next to one and a screenshot of a
        // half-built connection dialog is not worth the risk.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val frame = VaultModule.frame(this, "SSH connections")
        val content = frame.content

        vaultSlot = FrameLayout(this)
        content.addView(vaultSlot, fullWidth())
        errorSlot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
        }
        content.addView(errorSlot, fullWidth())

        content.addView(SectionHeader.create(this, "SSH connections"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer, fullWidth())

        addKey = KeyButton.create(
            this, "ADD CONNECTION", KeyButton.Kind.INK, KeyButton.Size.REGULAR, describe = "Add an SSH connection",
        ) { addDialog() }
        content.addView(addKey, fullWidth().apply { topMargin = dp(Dimens.SPACE_M) })

        content.addView(
            Type.text(
                this,
                "Each connection generates its own ed25519 key on this phone. Paste the public key " +
                    "into the server's ~/.ssh/authorized_keys once. The private half never leaves " +
                    "the vault.",
                TypeRole.CAPTION, Theme.palette(this).etched,
            ),
            fullWidth().apply { topMargin = dp(Dimens.SPACE_L) },
        )

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        vaultError = null
    }

    /** Shows the card as it looks after a failed unlock or creation. Tests only. */
    @androidx.annotation.VisibleForTesting
    internal fun setVaultErrorForTest(reason: String?) {
        vaultError = reason
        render()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = Theme.dp(this, v)

    // --- render ---

    private fun render() {
        errorSlot.removeAllViews()
        val unlocked = SecretVault.isUnlocked()
        vaultSlot.removeAllViews()
        val model = VaultModule.model(SecretVault.state(this), unlocked, forSsh = true)
        vaultSlot.addView(VaultModule.create(this, model, vaultError, ::onVaultAction))
        VaultModule.setEnabled(addKey, unlocked)
        renderList(unlocked)
    }

    private fun onVaultAction(a: VaultModule.Action) {
        val ok = { vaultError = null; render() }
        val failed = { reason: String -> vaultError = reason; render() }
        when (a) {
            VaultModule.Action.UNLOCK -> VaultGate.unlock(this, ok, failed)
            VaultModule.Action.CREATE -> VaultGate.initialize(this, ok, failed)
            VaultModule.Action.RESET -> VaultUnlock.ensureUnlocked(this, force = true) { vaultError = null; render() }
        }
    }

    private fun renderList(unlocked: Boolean) {
        listContainer.removeAllViews()
        val names = try {
            SecretVault.names(this).filter { it.startsWith("ssh-") }
        } catch (e: Exception) {
            listContainer.addView(StateBlock.create(
                this, StateBlock.Kind.ERROR, "The vault list can't be read",
                "Close this screen and open it again. If it keeps happening, reset the vault in Settings.",
            ))
            listContainer.addView(
                Readout.create(this, e.message ?: e.javaClass.simpleName, Readout.Kind.BODY).root
            )
            return
        }
        if (names.isEmpty()) {
            listContainer.addView(StateBlock.create(
                this, StateBlock.Kind.EMPTY, "No connections yet",
                "Tap ADD CONNECTION to make a key for a server.",
            ))
        }
        for (vaultName in names) listContainer.addView(connectionCard(vaultName, unlocked))
    }

    private fun connectionCard(vaultName: String, unlocked: Boolean): View {
        val p = Theme.palette(this)
        val label = vaultName.removePrefix("ssh-")
        val delete = KeyButton.Spec("DELETE", "Delete $label") { confirmDelete(label, vaultName) }

        // Entries are encrypted: without the session they cannot be decoded, and calling
        // `SecretVault.get` would only report "locked" for every one of them. List the names.
        if (!unlocked) {
            return Panel.create(this, Panel.Tone.PLAIN).apply {
                addView(
                    ActionRow(this@SshActivity, label, "Unlock the vault to see this connection.", emptyList(), delete),
                    fullWidth(),
                )
            }
        }

        val conn = try {
            SshConnection.fromJson(SecretVault.get(this, vaultName))
        } catch (e: Exception) {
            return Panel.create(this, Panel.Tone.CAUTION).apply {
                addView(Type.text(this@SshActivity, label, TypeRole.ROW_LABEL, p.ink), fullWidth())
                addView(
                    Type.text(this@SshActivity, "Can't read this connection: ${e.message}", TypeRole.DETAIL, p.ink),
                    fullWidth().apply { topMargin = dp(Dimens.SPACE_XS) },
                )
            }
        }

        val card = Panel.create(this, Panel.Tone.PLAIN)
        card.addView(
            ActionRow(
                this, label, "${conn.user}@${conn.host}:${conn.port}",
                listOf(KeyButton.Spec("PUBLIC KEY", "Show the public key of $label") { copyPublicKey(label, conn) }),
                delete,
            ),
            fullWidth(),
        )

        // The key type is worth stating: it is the thing a server's authorized_keys cares
        // about, and an unexpected type is a clue something is wrong before a call fails.
        SshKeygen.keyTypeOf(conn.privateKey)?.let { addValue(card, "KEY TYPE", it) }

        // HOST KEY state. A connection with no pin has never been confirmed, and the next call
        // will refuse and report the fingerprint rather than running — saying so here means
        // that refusal is expected rather than surprising.
        val pinned = conn.hostKeyFingerprint
        card.addView(
            if (pinned == null) {
                StatusRow.create(
                    this, "Host key", "NOT TRUSTED YET", StatusRow.Tone.CAUTION,
                    "The first call will report the key and ask you before anything runs.", Led.State.CAUTION,
                )
            } else {
                StatusRow.create(this, "Host key", "PINNED", StatusRow.Tone.OK, "The server's key is trusted.", Led.State.OK)
            },
            fullWidth(),
        )
        if (pinned != null) addReadout(card, "HOST KEY FINGERPRINT", pinned)

        // --- the key's own identity, on the card ---
        //
        // "SSH needs more details when opened" (Mido, 2026-11-04): the public key line and its
        // fingerprint are the two things you check against a server, so they are on the card, not
        // one tap deep. Both are public by construction: the private half never leaves the vault.
        val pub = SshKeygen.publicKeyOf(conn.privateKey, comment = "stmp-$label")
        val fingerprint = pub?.let { SshKeygen.fingerprintOf(it) }
        if (fingerprint != null) addReadout(card, "KEY FINGERPRINT", fingerprint)
        if (pub != null) addReadout(card, "PUBLIC KEY", pub)
        // The vault entry name, so a duplicate label on two hosts is tellable apart.
        addValue(card, "VAULT ENTRY", vaultName)
        return card
    }

    private fun addValue(card: LinearLayout, label: String, value: String) {
        card.addView(Etched.label(this, label).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_M) })
        card.addView(Type.text(this, value, TypeRole.BODY, Theme.palette(this).ink), fullWidth())
    }

    private fun addReadout(card: LinearLayout, label: String, value: String) {
        card.addView(Etched.label(this, label).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_M) })
        card.addView(Readout.create(this, value, Readout.Kind.BODY).root)
    }

    /**
     * Show the public key in a selectable box.
     *
     * A DIALOG rather than a clipboard write on the spot: a key silently copied leaves no chance
     * to check it is the one you expect, and this is the one string a human has to carry to a
     * server by hand. The dialog has its own copy button for when that is what you want.
     */
    private fun copyPublicKey(label: String, conn: SshConnection) {
        errorSlot.removeAllViews()
        val pub = SshKeygen.publicKeyOf(conn.privateKey, comment = "stmp-$label")
        if (pub == null) {
            showError("Couldn't read the key", "No public key could be derived for '$label'.")
            return
        }
        StmpDialog.builder(this)
            .setTitle("Public key — $label")
            .setMessage("Add this line to ~/.ssh/authorized_keys on ${conn.host}.")
            .setView(VaultModule.dialogBody(this, Readout.create(this, pub, Readout.Kind.BODY).root))
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("STMP ssh public key", pub))
                Toasts.show(this, "Public key copied")
            }
            .setNegativeButton("Close", null)
            .showSecure()
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
            Debug.log("ssh screen: add refused, vault locked")
            return
        }
        errorSlot.removeAllViews()
        val nameField = Field.create(this, "NAME", "", "name (lowercase, e.g. prod)")
        val hostField = Field.create(this, "HOST", "", "host (e.g. box.example.com)")
        val portField = Field.create(this, "PORT", "22", "port (default 22)", Field.Kind.NUMBER)
        val userField = Field.create(this, "USER", "", "user (e.g. deploy)")

        // The reason a failure left the dialog empty, shown INSIDE it.
        val errorLine = Type.text(this, "", TypeRole.CAPTION, Theme.palette(this).cautionText).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (f in listOf(nameField, hostField, portField, userField)) {
                addView(f.root, fullWidth().apply { bottomMargin = dp(Dimens.SPACE_S) })
            }
            addView(errorLine, fullWidth().apply { topMargin = dp(Dimens.SPACE_S) })
        }

        // **A REAL dialog, not `setPositiveButton`.**
        //
        // Mido, 2026-11-04: *"the problem is the dialog is lost, a failed attempt should keep the
        // dialog and its data for easy fix"*.
        //
        // `setPositiveButton` ALWAYS dismisses when tapped — that is the contract. So a
        // `return` on a validation failure returned from a dialog Android had already decided to
        // close, and the name rule (lowercase `[a-z0-9_-]`) is the one people hit: all four fields
        // gone, retype from scratch. A validation failure must cost a keystroke, not the form.
        //
        // So the button is wired manually and the dialog closes ONLY on success. The error is
        // reported IN the dialog, where the fields it refers to still are.
        val dialog = StmpDialog.builder(this)
            .setTitle("Add SSH connection")
            .setView(VaultModule.dialogBody(this, box))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Generate key", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.edit.text.toString().trim().lowercase()
                val host = hostField.edit.text.toString().trim()
                val user = userField.edit.text.toString().trim()
                val port = portField.edit.text.toString().trim().toIntOrNull() ?: DEFAULT_PORT

                fun refuse(message: String, focus: android.widget.EditText) {
                    errorLine.text = message
                    errorLine.visibility = View.VISIBLE
                    focus.requestFocus()
                    Haptics.perform(focus, HapticEvent.REJECT)
                    Debug.log("ssh: add refused: $message")
                }

                if (!name.matches(Regex("[a-z0-9][a-z0-9_-]{0,48}"))) {
                    refuse(
                        "Name must be lowercase letters, digits, - or _ (1-49 chars). " +
                            "\"$name\" has something else.",
                        nameField.edit,
                    )
                    return@setOnClickListener
                }
                if (host.isBlank()) {
                    refuse("Host is required.", hostField.edit)
                    return@setOnClickListener
                }
                if (user.isBlank()) {
                    refuse("User is required.", userField.edit)
                    return@setOnClickListener
                }
                if (port !in 1..MAX_PORT) {
                    refuse("Port must be between 1 and 65535.", portField.edit)
                    return@setOnClickListener
                }
                if (SecretVault.has(this, "ssh-$name")) {
                    refuse("A connection named \"$name\" already exists. Pick another name.", nameField.edit)
                    return@setOnClickListener
                }

                errorLine.visibility = View.GONE
                try {
                    val key = SshKeygen.generate(comment = "stmp-$name")
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
                    refuse("${e.code}: ${e.message}", nameField.edit)
                } catch (e: Throwable) {
                    // **Throwable, not Exception.** The 0.40.3 failure was a
                    // `NoClassDefFoundError` from inside JSch — an `Error`, which an `Exception`
                    // catch does not see at all. Catching `Throwable` here is correct precisely
                    // because this is a UI boundary: the process must not die and the reason must
                    // reach the log.
                    Debug.log("ssh: create '$name' CRASHED: ${e.javaClass.name}: ${e.message}")
                    refuse("Could not create key: ${e.javaClass.simpleName}: ${e.message}", nameField.edit)
                }
            }
        }
        dialog.showSecure()
    }

    private fun confirmDelete(label: String, vaultName: String) {
        errorSlot.removeAllViews()
        // Deleting rewrites the encrypted blob, so it needs the open session even though the key
        // stays tappable while locked (the names are listed, so the way out has to be visible).
        if (!SecretVault.isUnlocked()) {
            showError("Can't delete $label", "Unlock the vault first. Deleting rewrites the stored secrets.")
            return
        }
        val dialog = StmpDialog.builder(this)
            .setTitle("Delete '$label'?")
            .setMessage(
                "This removes the connection and its private key from the vault. The public key " +
                    "stays in authorized_keys on the server — remove that line there too if you " +
                    "want to revoke it."
            )
            .setPositiveButton("Delete") { _, _ ->
                try {
                    SecretVault.delete(this, vaultName)
                    render()
                    Toasts.show(this, "Deleted ${label.take(NAME_IN_TOAST)}")
                } catch (e: Exception) {
                    showError("Couldn't delete $label", e.message ?: e.javaClass.simpleName)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        // The key that destroys data is never the one a stray tap lands on.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                // Focusable in touch mode too, or the request is ignored on a phone in use.
                isFocusableInTouchMode = true
                requestFocus()
            }
        }
        dialog.showSecure()
    }

    /** An error stays on the screen until the next action; a toast would be gone before it was read. */
    private fun showError(title: String, detail: String) {
        errorSlot.removeAllViews()
        errorSlot.addView(StateBlock.create(this, StateBlock.Kind.ERROR, title, detail))
        Debug.log("ssh screen: $title: $detail")
        errorSlot.requestFocus()
        errorSlot.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private companion object {
        const val DEFAULT_PORT = 22
        const val MAX_PORT = 65535
        const val NAME_IN_TOAST = 24
    }
}
