package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretTemplate
import com.mido.phonebridge.core.SecretTemplates
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.ui.Theme

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
 * The screen assumes the vault is unlocked (the app lock on load did that). If it is
 * not, it says so rather than pretending — `secret_locked` is a real state.
 */
class SecretsActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no recents thumbnail of a secret.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
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

        content.addView(Theme.sectionHeader(this, "Secrets"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        val addBtn = Theme.button(this, "ADD SECRET", Theme.ButtonStyle.SUCCESS) { addSecretDialog() }
        addBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Theme.dp(this@SecretsActivity, 12) }
        content.addView(addBtn)

        // Templates are a separate entry point, not a change to the free-form dialog: manual
        // entry has to keep working exactly as it did, because most secrets are not on any list.
        val templateBtn = Theme.button(this, "ADD FROM TEMPLATE", Theme.ButtonStyle.NEUTRAL) {
            pickTemplate()
        }
        templateBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Theme.dp(this@SecretsActivity, 8) }
        content.addView(templateBtn)

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
            "Vault unlocked for this session. Values are revealed one at a time."
        } else {
            "Vault locked — reopen the app and scan your fingerprint."
        }

        listContainer.removeAllViews()
        val names = try {
            SecretVault.names(this)
        } catch (e: Exception) {
            listContainer.addView(row("vault unreadable", e.message ?: "", Theme.DANGER_SURFACE))
            emptyList()
        }
        if (names.isEmpty()) {
            listContainer.addView(row("no secrets yet", "tap ADD SECRET", Theme.SURFACE))
        }
        for (n in names) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Theme.dp(this@SecretsActivity, 14), Theme.dp(this@SecretsActivity, 12),
                    Theme.dp(this@SecretsActivity, 14), Theme.dp(this@SecretsActivity, 12))
                background = Theme.outlined(this@SecretsActivity, Theme.SURFACE, Theme.RADIUS_CARD)
            }
            rowView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Theme.dp(this@SecretsActivity, 10) }

            // Read the kind BEFORE deciding which controls to build: a private key must never be
            // offered a reveal button. Mido, 2026-10-21 — the key is generated on the phone so it
            // does not travel, and this screen is the one place that could undo that.
            val meta = try {
                SecretVault.meta(this, n)
            } catch (e: Exception) {
                SecretVault.SecretMeta()
            }
            val isSsh = meta.kind == SecretVault.SecretMeta.Kind.SSH

            rowView.addView(Theme.text(this, n, 16f))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (isSsh) {
                // No reveal, no replace — both would put the private key on screen or in a paste
                // box. Deleting is still here: removing a connection is legitimate, and it destroys
                // the key rather than exposing it.
                buttons.addView(small("delete") { confirmDelete(n) })
            } else {
                buttons.addView(small("reveal") { revealValue(n) })
                buttons.addView(small("replace") { addSecretDialog(n) })
                buttons.addView(small("delete") { confirmDelete(n) })
            }
            rowView.addView(buttons)

            if (isSsh) {
                // Say WHY there is no reveal, or the missing buttons read as a bug. The public key
                // is shown instead — that is the harmless half, and it is what you paste on the
                // server.
                rowView.addView(
                    Theme.text(
                        this,
                        "SSH key — the private half is never displayed. Manage connections on the " +
                            "SSH screen.",
                        12f, Theme.ON_SURFACE_VARIANT
                    ).apply { setPadding(0, Theme.dp(this@SecretsActivity, 6), 0, 0) }
                )
                // Skip the links/hosts block for an SSH entry: those are HTTP-broker concepts and
                // showing an empty "no hosts allowed — every call will be refused" would be a lie.
                listContainer.addView(rowView)
                // `continue`, not `return`: this is the body of a `for (n in names)` loop, so the
                // remaining link/host block is skipped for THIS entry and the loop carries on.
                continue
            }

            // The two links (plan: docs + key issuance). Plaintext, so they are readable
            // while the vault is shut — which is when you actually want them, right after
            // a call failed. Shown as their own row, above the actions, because "where do
            // I fix this key" is the question the row exists to answer.
            val links = try {
                SecretVault.meta(this, n)
            } catch (e: Exception) {
                SecretVault.SecretMeta()
            }
            val linkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            linkRow.addView(small(if (links.docs.isEmpty()) "+ docs" else "docs ↗") {
                openLink(n, "API docs", links.docs)
            })
            linkRow.addView(small(if (links.key.isEmpty()) "+ key link" else "key ↗") {
                openLink(n, "key page", links.key)
            })
            linkRow.addView(small("edit links") { editLinksDialog(n, links) })
            rowView.addView(linkRow)

            // Allowed hosts, PER SECRET. This is the security boundary: a secret may
            // only reach its own hosts. Shown per secret (not once globally) because
            // that is now the truth — the todoist key cannot be sent to a host that
            // some other secret happens to have approved.
            rowView.addView(Theme.text(
                this,
                if (links.hosts.isEmpty()) "no hosts allowed — every call will be refused"
                else "allowed: ${links.hosts.joinToString(", ")}",
                13f,
                if (links.hosts.isEmpty()) Theme.WARNING else Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@SecretsActivity, 6), 0, 0) })

            val hostRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            hostRow.addView(small("+ host") { addHostDialog(n) })
            for (h in links.hosts) {
                hostRow.addView(small("✕ $h") {
                    try {
                        SecretVault.revokeHost(this, n, h)
                    } catch (e: PluginError) {
                        toast("${e.code}: ${e.message}")
                    }
                    render()
                })
            }
            rowView.addView(hostRow)
            listContainer.addView(rowView)
        }
    }

    private fun row(title: String, detail: String, color: Int) = TextView(this).apply {
        text = "$title\n$detail"
        textSize = 15f
        setTextColor(Theme.ON_SURFACE)
        setPadding(Theme.dp(this@SecretsActivity, 12), Theme.dp(this@SecretsActivity, 10),
            Theme.dp(this@SecretsActivity, 12), Theme.dp(this@SecretsActivity, 10))
        background = Theme.surface(this@SecretsActivity, color, Theme.RADIUS_CONTROL)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(this@SecretsActivity, 6) }
    }

    private fun small(label: String, onClick: () -> Unit): MaterialButton =
        Theme.button(this, label, Theme.ButtonStyle.NEUTRAL, small = true, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Theme.dp(this@SecretsActivity, 6) }
        }

    // --- actions ---

    /**
     * Reveal one value. Requires an unlocked vault; the reveal is its own explicit tap,
     * and the value is shown in a dialog the user dismisses — never listed in the rows.
     */
    private fun revealValue(name: String) {
        val value = try {
            SecretVault.get(this, name)
        } catch (e: PluginError) {
            toast("cannot reveal: ${e.code}")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(value)
            .setPositiveButton("hide", null)
            .show()
    }

    /**
     * Open a stored link. Refuses an empty link with a plain message rather than
     * silently doing nothing, and never opens anything that is not https — the vault
     * already enforces that on write, this is the second gate.
     */
    private fun openLink(secret: String, what: String, url: String) {
        if (url.isEmpty()) {
            toast("no $what set for '$secret' — tap edit links")
            return
        }
        if (!url.startsWith("https://")) {
            toast("refusing to open a non-https link")
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun editLinksDialog(name: String, current: SecretVault.SecretMeta) {
        val docsField = EditText(this).apply {
            hint = "API docs (https://…)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(current.docs)
        }
        val keyField = EditText(this).apply {
            hint = "where to get the key (https://…)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(current.key)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(this@SecretsActivity, 16), Theme.dp(this@SecretsActivity, 8),
                Theme.dp(this@SecretsActivity, 16), 0)
            addView(docsField)
            addView(keyField)
        }
        AlertDialog.Builder(this)
            .setTitle("Links for '$name'")
            .setView(box)
            .setPositiveButton("save") { _, _ ->
                try {
                    SecretVault.setLinks(
                        this, name,
                        docsField.text.toString().trim(),
                        keyField.text.toString().trim()
                    )
                    render()
                } catch (e: PluginError) {
                    toast("${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .show()
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
            toast("vault locked — unlock the app first")
            return
        }
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
        AlertDialog.Builder(this)
            .setTitle("Add from template")
            .setItems(labels) { _, which ->
                val t = SecretTemplates.ALL[which]
                if (t.name in existing) {
                    toast("'${t.name}' already exists — use replace on its row")
                } else {
                    addSecretDialog(template = t)
                }
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun addSecretDialog(existing: String? = null, template: SecretTemplate? = null) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
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
        val nameField = EditText(this).apply {
            hint = "name (lowercase, e.g. todoist)"
            inputType = InputType.TYPE_CLASS_TEXT
            // Editable only when CONFIRMING a name the human typed. A template name is fixed:
            // the name is the plugin id, and the template's docs/hosts were chosen for that one
            // service, so renaming it to something else would attach Todoist's hosts to a secret
            // called "notion" and quietly mislabel the whole entry.
            isEnabled = existing == null && template == null
            setText(existing ?: template?.name ?: "")
        }
        val valueField = EditText(this).apply {
            hint = "secret value"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // The keyboard and any autofill service must never learn this.
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        // The two links are NOT secret (they are pointers, not credentials), so they are
        // ordinary text fields and they are stored in the plaintext index. Both optional:
        // a secret with no links is legal, it just cannot point you anywhere.
        val docsField = EditText(this).apply {
            hint = "API docs URL (optional)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(existingLinks.docs)
        }
        val keyField = EditText(this).apply {
            hint = "where to get the key (optional)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(existingLinks.key)
        }
        // Allowed hosts live in the SAME dialog as everything else about the secret:
        // a secret and its domains are one thing, so creating one is one form, not a
        // form plus a second trip to a host list. Comma-separated, prefilled from what
        // is already stored so a replace does not silently drop the rules.
        val hostsField = EditText(this).apply {
            hint = "allowed hosts (comma-separated, e.g. api.todoist.com)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existingLinks.hosts.joinToString(", "))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(this@SecretsActivity, 16), Theme.dp(this@SecretsActivity, 8),
                Theme.dp(this@SecretsActivity, 16), 0)
            addView(nameField)
            addView(valueField)
            addView(docsField)
            addView(keyField)
            addView(hostsField)
        }
        AlertDialog.Builder(this)
            .setTitle(
                when {
                    existing != null -> "Replace '$existing'"
                    template != null -> "Add ${template.displayName}"
                    else -> "Add secret"
                }
            )
            .setView(box)
            .setPositiveButton("save") { _, _ ->
                val n = (existing ?: template?.name ?: nameField.text.toString()).trim()
                val v = valueField.text.toString()
                try {
                    SecretVault.put(
                        this, n, v,
                        docsField.text.toString().trim(),
                        keyField.text.toString().trim(),
                        parseHosts(hostsField.text.toString())
                    )
                    // Registering the plugin makes it routable without a restart.
                    BridgeService.registerSecretPlugin(applicationContext, n)
                    toast("saved '$n'")
                    render()
                } catch (e: PluginError) {
                    toast("${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Delete '$name'?")
            .setMessage("The secret is removed from the vault and its ciphertext goes with the next write.")
            .setPositiveButton("delete") { _, _ ->
                try {
                    SecretVault.delete(this, name)
                    toast("deleted '$name'")
                    render()
                } catch (e: PluginError) {
                    toast("${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun addHostDialog(secret: String) {
        val field = EditText(this).apply {
            hint = "api.todoist.com or *.todoist.com"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = LinearLayout(this).apply {
            setPadding(Theme.dp(this@SecretsActivity, 16), Theme.dp(this@SecretsActivity, 8),
                Theme.dp(this@SecretsActivity, 16), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Allow a host for '$secret'")
            .setView(box)
            .setPositiveButton("allow") { _, _ ->
                try {
                    SecretVault.allowHost(this, secret, field.text.toString())
                    render()
                } catch (e: PluginError) {
                    toast("${e.code}: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        // Deliberately never logs the value — only the outcome code.
        Debug.log("secrets screen: $msg")
    }
}
