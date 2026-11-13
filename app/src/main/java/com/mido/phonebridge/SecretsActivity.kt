package com.mido.phonebridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretTemplate
import com.mido.phonebridge.core.SecretTemplates
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.VaultModule
import com.mido.phonebridge.ui.showSecure
import com.mido.phonebridge.ui.kit.ActionRow
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.ErrorBlock
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
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The secret CRUD screen (plan D1/D7, plus the hardening list).
 *
 * Hardening that is not optional here:
 *  - `FLAG_SECURE`: no screenshots, and the value never appears in the recents
 *    thumbnail. This is the one screen where a screenshot is a credential leak.
 *  - names are listed, values are NOT: revealing a value is its own explicit tap.
 *  - the value field is password-style with autofill off, so neither the keyboard
 *    nor an autofill service learns it.
 *  - nothing here is logged. A throw in this path must not carry the value.
 *
 * The vault card at the top says what state the vault is really in and carries the key that
 * fixes it; `secret_locked` is a real state and the screen says so rather than pretending.
 */
class SecretsActivity : FragmentActivity() {

    private lateinit var vaultSlot: FrameLayout
    private lateinit var errorSlot: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var addKey: View
    private lateinit var templateKey: View

    /** Why the last unlock or create attempt on this screen failed. Cleared on success and on leaving. */
    private var vaultError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no recents thumbnail of a secret.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val frame = VaultModule.frame(this, "Secrets")
        val content = frame.content

        vaultSlot = FrameLayout(this)
        content.addView(vaultSlot, fullWidth())
        errorSlot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
        }
        content.addView(errorSlot, fullWidth())

        content.addView(SectionHeader.create(this, "Secrets"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer, fullWidth())

        addKey = KeyButton.create(
            this, "ADD SECRET", KeyButton.Kind.INK, KeyButton.Size.REGULAR, describe = "Add a secret",
        ) { addSecretDialog() }
        content.addView(addKey, fullWidth().apply { topMargin = Theme.dp(this@SecretsActivity, Dimens.SPACE_M) })

        // Templates are a separate entry point, not a change to the free-form dialog: manual
        // entry has to keep working exactly as it did, because most secrets are not on any list.
        templateKey = KeyButton.create(
            this, "ADD FROM TEMPLATE", KeyButton.Kind.PANEL, KeyButton.Size.REGULAR,
            describe = "Add a secret from a template",
        ) { pickTemplate() }
        content.addView(templateKey, fullWidth().apply { topMargin = Theme.dp(this@SecretsActivity, Dimens.SPACE_S) })

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
        renderVault(unlocked)
        VaultModule.setEnabled(addKey, unlocked)
        VaultModule.setEnabled(templateKey, unlocked)
        renderList(unlocked)
    }

    private fun renderVault(unlocked: Boolean) {
        vaultSlot.removeAllViews()
        val model = VaultModule.model(SecretVault.state(this), unlocked, forSsh = false)
        vaultSlot.addView(VaultModule.create(this, model, vaultError, ::onVaultAction))
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
        // **SSH connections are NOT listed here.**
        //
        // Mido, 2026-11-04: *"move ssh entries from the secrets screen to the ssh screen where
        // they belong"*. They are vault entries, which is why they appeared — but a connection is
        // managed by generating and installing a key, not by pasting a value, and the SSH screen
        // already owns that flow. Listing them here offered a "delete" button and a sentence
        // pointing elsewhere: a screen showing something it cannot edit.
        //
        // `ssh-` is the prefix `SshConnection.vaultName()` writes, so this filter matches the
        // same set the SSH screen loads.
        val names = try {
            SecretVault.names(this).filterNot { it.startsWith("ssh-") }
        } catch (e: Exception) {
            listContainer.addView(ErrorBlock.create(
                this, "The vault list can't be read",
                "If it keeps happening, reset the vault in Settings.",
                details = e.message ?: e.javaClass.simpleName,
                recovery = KeyButton.Spec("Try again", "Read the vault list again") { renderList(unlocked) },
            ))
            return
        }
        if (names.isEmpty()) {
            listContainer.addView(StateBlock.create(
                this, StateBlock.Kind.EMPTY, "No secrets yet",
                VaultModule.emptyBody(unlocked, "ADD SECRET", " to store an API key.") + " SSH keys live on the SSH screen.",
            ))
        }
        for (n in names) listContainer.addView(secretCard(n, unlocked))
    }

    private fun secretCard(n: String, unlocked: Boolean): View {
        val p = Theme.palette(this)
        val card = Panel.create(this, Panel.Tone.PLAIN)

        // Read the kind BEFORE deciding which controls to build: a private key must never be
        // offered a reveal button. Mido, 2026-10-21 — the key is generated on the phone so it
        // does not travel, and this screen is the one place that could undo that.
        val meta = try {
            SecretVault.meta(this, n)
        } catch (e: Exception) {
            SecretVault.SecretMeta()
        }
        val delete = KeyButton.Spec("DELETE", "Delete $n") { confirmDelete(n) }
        // Filtered above, so this is always false. Kept as a guard rather than deleted:
        // a future entry could carry Kind.SSH under a different name prefix, and the
        // reveal button must never appear for a private key.
        if (meta.kind == SecretVault.SecretMeta.Kind.SSH) {
            // No reveal, no replace — both would put the private key on screen or in a paste
            // box. Deleting is still here: removing a connection is legitimate, and it destroys
            // the key rather than exposing it.
            card.addView(ActionRow(this, n, null, emptyList(), delete), fullWidth())
            // Say WHY there is no reveal, or the missing keys read as a bug.
            card.addView(
                Type.text(
                    this,
                    "SSH key. The private half is never displayed. Manage connections on the SSH screen.",
                    TypeRole.CAPTION, p.etched,
                ),
                fullWidth().apply { topMargin = dp(Dimens.SPACE_XS) },
            )
            // Skip the links/hosts block for an SSH entry: those are HTTP-broker concepts and
            // showing an empty "no hosts allowed" row would be a lie.
            return card
        }

        val header = ActionRow(
            this, n, null,
            listOf(
                KeyButton.Spec("REVEAL", "Reveal $n") { revealValue(n) },
                KeyButton.Spec("REPLACE", "Replace $n") { addSecretDialog(n) },
            ),
            null,
        )
        card.addView(header, fullWidth())
        VaultModule.setKeyEnabled(header, "Reveal $n", unlocked)
        VaultModule.setKeyEnabled(header, "Replace $n", unlocked)

        addLinks(card, n, meta)
        addHosts(card, n, meta)
        // The destructive key closes the card, away from the keys that only read or change a value.
        card.addView(
            KeyButton.create(
                this, delete.label, KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL, describe = delete.describe,
                onClick = delete.onClick,
            ),
            endAligned().apply { topMargin = dp(Dimens.SPACE_M) },
        )
        return card
    }

    /**
     * The two links, one URL per line (plaintext, so readable while the vault is shut — which is
     * when you actually want them, right after a call failed), and the single way to edit them.
     */
    private fun addLinks(card: LinearLayout, n: String, meta: SecretVault.SecretMeta) {
        val p = Theme.palette(this)
        if (meta.docs.isEmpty() && meta.key.isEmpty()) {
            card.addView(
                Type.text(this, "No links set.", TypeRole.DETAIL, p.etched),
                fullWidth().apply { topMargin = dp(Dimens.SPACE_S) },
            )
        }
        if (meta.docs.isNotEmpty()) addLink(card, "API DOCS", meta.docs) { openLink(meta.docs) }
        if (meta.key.isNotEmpty()) addLink(card, "WHERE TO GET THE KEY", meta.key) { openLink(meta.key) }
        card.addView(
            KeyButton.create(
                this, "EDIT LINKS", KeyButton.Kind.PANEL, KeyButton.Size.SMALL, describe = "Edit links for $n",
            ) { editLinksDialog(n, meta) },
            endAligned(),
        )
    }

    private fun addLink(card: LinearLayout, label: String, url: String, onClick: () -> Unit) {
        card.addView(
            Etched.label(this, label).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_S) }
        )
        card.addView(linkLine(url, onClick), fullWidth().apply { topMargin = dp(Dimens.SPACE_XS); bottomMargin = dp(Dimens.SPACE_XS) })
    }

    private fun endAligned() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = android.view.Gravity.END
        topMargin = dp(Dimens.SPACE_S)
    }

    /**
     * Allowed hosts, PER SECRET. This is the security boundary: a secret may only reach its own
     * hosts. Shown per secret (not once globally) because that is the truth — the todoist key
     * cannot be sent to a host that some other secret happens to have approved.
     */
    private fun addHosts(card: LinearLayout, n: String, meta: SecretVault.SecretMeta) {
        card.addView(
            Etched.label(this, "ALLOWED HOSTS").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_M) }
        )
        if (meta.hosts.isEmpty()) {
            card.addView(
                StatusRow.create(
                    this, label = "No hosts allowed", value = "", tone = StatusRow.Tone.CAUTION,
                    detail = "Every call with this secret is refused until you allow one.",
                    led = Led.State.CAUTION,
                ),
                fullWidth(),
            )
        }
        for (h in meta.hosts) {
            card.addView(
                ActionRow(this, h, null, emptyList(), KeyButton.Spec("REMOVE", "Remove host $h from $n") { revokeHost(n, h) }),
                fullWidth(),
            )
        }
        card.addView(
            KeyButton.create(
                this, "ALLOW A HOST", KeyButton.Kind.PANEL, KeyButton.Size.SMALL, describe = "Allow a host for $n",
            ) { addHostDialog(n) },
            endAligned(),
        )
    }

    /**
     * One URL, on its own line, and tappable. Underlined, with an etched label above it, so it
     * reads as a link rather than as a value (the colour is ink in both themes).
     */
    private fun linkLine(url: String, onClick: () -> Unit): TextView {
        val p = Theme.palette(this)
        return Type.text(this, url, TypeRole.BODY, p.ink).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            // A long URL must WRAP, not be split mid-token by hyphenation: a broken URL cannot be
            // copied, which defeats the point of showing it.
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            minHeight = dp(Dimens.MIN_TARGET)
            gravity = android.view.Gravity.CENTER_VERTICAL
            contentDescription = "Open $url"
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // --- actions ---

    private fun revokeHost(n: String, h: String) {
        errorSlot.removeAllViews()
        try {
            SecretVault.revokeHost(this, n, h)
            render()
        } catch (e: PluginError) {
            showError("Couldn't remove that host", "${e.code}: ${e.message}")
        }
    }

    /**
     * Reveal one value. Requires an unlocked vault; the reveal is its own explicit tap,
     * and the value is shown in a dialog the user dismisses — never listed in the rows.
     */
    private fun revealValue(name: String) {
        errorSlot.removeAllViews()
        val value = try {
            SecretVault.get(this, name)
        } catch (e: PluginError) {
            showError("Can't reveal $name", e.message ?: e.code)
            return
        }
        val readout = Readout.create(this, value, Readout.Kind.BODY)
        // The value must not be copyable by long-press.
        readout.text.setTextIsSelectable(false)
        StmpDialog.builder(this)
            .setTitle(name)
            .setView(VaultModule.dialogBody(this, readout.root))
            .setPositiveButton("Hide", null)
            .showSecure()
    }

    /** Opens a stored link. Never opens anything that is not https; the vault enforces that on write too. */
    private fun openLink(url: String) {
        errorSlot.removeAllViews()
        if (!url.startsWith("https://")) {
            showError("Can't open that link", "Only https links are opened.")
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun editLinksDialog(name: String, current: SecretVault.SecretMeta) {
        errorSlot.removeAllViews()
        val docs = Field.create(this, "API DOCS", current.docs, "API docs (https://…)", Field.Kind.URL)
        val key = Field.create(this, "WHERE TO GET THE KEY", current.key, "Where to get the key (https://…)", Field.Kind.URL)
        StmpDialog.builder(this)
            .setTitle("Links for '$name'")
            .setView(VaultModule.dialogBody(this, column(docs, key)))
            .setPositiveButton("Save") { _, _ ->
                try {
                    SecretVault.setLinks(this, name, docs.edit.text.toString().trim(), key.edit.text.toString().trim())
                    render()
                } catch (e: PluginError) {
                    showError("Couldn't save links", "${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    /** Stacks [fields] with 8dp between them. */
    private fun column(vararg fields: Field.Handle): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (f in fields) addView(f.root, fullWidth().apply { bottomMargin = dp(Dimens.SPACE_S) })
    }

    /**
     * Pick a template, then open the normal add dialog prefilled from it.
     *
     * Deliberately a thin chooser rather than a separate form: the save path, the validation and
     * the vault write are all the SAME code as manual entry. A template is just five prefilled
     * strings, so there is no second way for a secret to be created and therefore no second set
     * of bugs.
     */
    private fun pickTemplate() {
        if (!SecretVault.isUnlocked()) {
            Debug.log("secrets screen: add refused, vault locked")
            return
        }
        errorSlot.removeAllViews()
        // A template for a name that already exists would open a "Replace" flow without saying
        // so, and could wipe a host list the human had narrowed. Say it in the picker instead.
        val existing = try {
            SecretVault.names(this).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val labels = SecretTemplates.ALL.map { t ->
            if (t.name in existing) "${t.displayName} — already added" else "${t.displayName} — ${t.note}"
        }.toTypedArray()
        StmpDialog.builder(this)
            .setTitle("Add from template")
            .setItems(labels) { _, which ->
                val t = SecretTemplates.ALL[which]
                if (t.name in existing) {
                    Toasts.show(this, "Already added. Use Replace.")
                } else {
                    addSecretDialog(template = t)
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    private fun addSecretDialog(existing: String? = null, template: SecretTemplate? = null) {
        if (!SecretVault.isUnlocked()) {
            Debug.log("secrets screen: add refused, vault locked")
            return
        }
        errorSlot.removeAllViews()
        val existingLinks = when {
            // A template supplies the meta for a NEW secret. Editing an existing one always reads
            // what is stored — a template must never overwrite a host list someone already
            // narrowed, because that list is the security boundary.
            template != null && existing == null ->
                SecretVault.SecretMeta(template.docs, template.key, template.hosts)
            existing != null -> try {
                SecretVault.meta(this, existing)
            } catch (e: Exception) {
                SecretVault.SecretMeta()
            }
            else -> SecretVault.SecretMeta()
        }
        val nameField = Field.create(this, "NAME", existing ?: template?.name ?: "", "Name (lowercase, e.g. todoist)")
        // Editable only when CONFIRMING a name the human typed. A template name is fixed:
        // the name is the plugin id, and the template's docs/hosts were chosen for that one
        // service, so renaming it to something else would attach Todoist's hosts to a secret
        // called "notion" and quietly mislabel the whole entry.
        nameField.setEnabled(existing == null && template == null)
        // Password-style, autofill off (Field.Kind.SECRET): the keyboard and any autofill
        // service must never learn this.
        val valueField = Field.create(this, "VALUE", "", "Secret value", Field.Kind.SECRET)
        // The two links are NOT secret (they are pointers, not credentials), so they are
        // ordinary text fields and they are stored in the plaintext index. Both optional.
        val docsField = Field.create(this, "API DOCS", existingLinks.docs, "API docs URL (optional)", Field.Kind.URL)
        val keyField = Field.create(this, "WHERE TO GET THE KEY", existingLinks.key, "Where to get it (optional)", Field.Kind.URL)
        // Allowed hosts live in the SAME dialog as everything else about the secret: a secret and
        // its domains are one thing. Prefilled from what is stored so a replace does not silently
        // drop the rules.
        val hostsField = Field.create(
            this, "ALLOWED HOSTS", existingLinks.hosts.joinToString(", "),
            "api.todoist.com, comma-separated",
        )
        StmpDialog.builder(this)
            .setTitle(
                when {
                    existing != null -> "Replace '$existing'"
                    template != null -> "Add ${template.displayName}"
                    else -> "Add secret"
                }
            )
            .setView(VaultModule.dialogBody(this, column(nameField, valueField, docsField, keyField, hostsField)))
            .setPositiveButton("Save") { _, _ ->
                val n = (existing ?: template?.name ?: nameField.edit.text.toString()).trim()
                val v = valueField.edit.text.toString()
                try {
                    SecretVault.put(
                        this, n, v,
                        docsField.edit.text.toString().trim(),
                        keyField.edit.text.toString().trim(),
                        parseHosts(hostsField.edit.text.toString())
                    )
                    // Registering the plugin makes it routable without a restart.
                    BridgeService.registerSecretPlugin(applicationContext, n)
                    Toasts.show(this, "Saved ${n.take(NAME_IN_TOAST)}")
                    render()
                } catch (e: PluginError) {
                    showError("Couldn't save $n", "${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    /**
     * Split the hosts field into rules. Commas and whitespace both separate, because
     * people type both; empties are dropped so a trailing comma is not an error. The
     * rules themselves are validated by the vault, which is the single authority.
     */
    private fun parseHosts(raw: String): List<String> =
        raw.split(',', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun confirmDelete(name: String) {
        errorSlot.removeAllViews()
        // Deleting rewrites the encrypted blob, so it needs the open session even though the key
        // stays tappable while locked (the names are listed, so the way out has to be visible).
        if (!SecretVault.isUnlocked()) {
            showError("Can't delete $name", "Unlock the vault first. Deleting rewrites the stored secrets.")
            return
        }
        StmpDialog.confirmDelete(
            this,
            title = "Delete '$name'?",
            message = "The secret is removed from the vault. Agents can no longer use it.",
            deleteLabel = "Delete",
        ) {
            try {
                SecretVault.delete(this, name)
                Toasts.show(this, "Deleted ${name.take(NAME_IN_TOAST)}")
                render()
            } catch (e: PluginError) {
                showError("Couldn't delete $name", "${e.code}: ${e.message}")
            }
        }.showSecure()
    }

    private fun addHostDialog(secret: String) {
        errorSlot.removeAllViews()
        val host = Field.create(this, "HOST", "", "api.todoist.com or *.todoist.com")
        StmpDialog.builder(this)
            .setTitle("Allow a host for '$secret'")
            .setView(VaultModule.dialogBody(this, column(host)))
            .setPositiveButton("Allow") { _, _ ->
                try {
                    SecretVault.allowHost(this, secret, host.edit.text.toString())
                    render()
                } catch (e: PluginError) {
                    showError("Couldn't allow that host", "${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    /** An error stays on the screen until the next action; a toast would be gone before it was read. */
    private fun showError(title: String, detail: String) {
        errorSlot.removeAllViews()
        errorSlot.addView(ErrorBlock.create(
            this, title, "That didn't go through.", details = detail,
            recovery = KeyButton.Spec("Dismiss", "Dismiss this message") { errorSlot.removeAllViews() },
        ))
        // Deliberately never logs the value — only the outcome code.
        Debug.log("secrets screen: $title: $detail")
        errorSlot.requestFocus()
        errorSlot.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private companion object {
        const val NAME_IN_TOAST = 24
    }
}
