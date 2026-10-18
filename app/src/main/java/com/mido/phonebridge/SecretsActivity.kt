package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretVault

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

        val pad = UiKit.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        header = TextView(this).apply {
            textSize = 13f
            setTextColor(UiKit.TEXT_MUTED)
        }
        content.addView(header)

        content.addView(sectionTitle("Secrets"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        val addBtn = Button(this).apply {
            text = "ADD SECRET"
            background = UiKit.pill(UiKit.GREEN_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            setOnClickListener { addSecretDialog() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@SecretsActivity, 12) }
        }
        content.addView(addBtn)

        root.addView(scroll)
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase()
        textSize = 11f
        letterSpacing = 0.15f
        setTextColor(0xFF78909C.toInt())
        setPadding(0, UiKit.dp(this@SecretsActivity, 20), 0, UiKit.dp(this@SecretsActivity, 6))
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
            listContainer.addView(row("vault unreadable", e.message ?: "", UiKit.RED_BG))
            emptyList()
        }
        if (names.isEmpty()) {
            listContainer.addView(row("no secrets yet", "tap ADD SECRET", UiKit.NEUTRAL_BG))
        }
        for (n in names) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, UiKit.dp(this@SecretsActivity, 10), 0, UiKit.dp(this@SecretsActivity, 10))
            }
            rowView.addView(TextView(this).apply {
                text = n
                textSize = 16f
                setTextColor(UiKit.TEXT_ON_DARK)
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(small("reveal") { revealValue(n) })
            buttons.addView(small("replace") { addSecretDialog(n) })
            buttons.addView(small("delete") { confirmDelete(n) })
            rowView.addView(buttons)

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
            rowView.addView(TextView(this).apply {
                text = if (links.hosts.isEmpty()) "no hosts allowed — every call will be refused"
                else "allowed: ${links.hosts.joinToString(", ")}"
                textSize = 13f
                setTextColor(if (links.hosts.isEmpty()) 0xFFFFB74D.toInt() else UiKit.TEXT_MUTED)
                setPadding(0, UiKit.dp(this@SecretsActivity, 6), 0, 0)
            })
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
        setTextColor(UiKit.TEXT_ON_DARK)
        setPadding(UiKit.dp(this@SecretsActivity, 10), UiKit.dp(this@SecretsActivity, 10),
            UiKit.dp(this@SecretsActivity, 10), UiKit.dp(this@SecretsActivity, 10))
        background = UiKit.pill(color)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(this@SecretsActivity, 6) }
    }

    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        background = UiKit.pill(UiKit.NEUTRAL_BG)
        setTextColor(UiKit.TEXT_ON_DARK)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = UiKit.dp(this@SecretsActivity, 6) }
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
            setPadding(UiKit.dp(this@SecretsActivity, 16), UiKit.dp(this@SecretsActivity, 8),
                UiKit.dp(this@SecretsActivity, 16), 0)
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

    private fun addSecretDialog(existing: String? = null) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
        val existingLinks = try {
            if (existing != null) SecretVault.meta(this, existing) else SecretVault.SecretMeta()
        } catch (e: Exception) {
            SecretVault.SecretMeta()
        }
        val nameField = EditText(this).apply {
            hint = "name (lowercase, e.g. todoist)"
            inputType = InputType.TYPE_CLASS_TEXT
            isEnabled = existing == null
            setText(existing ?: "")
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
            setPadding(UiKit.dp(this@SecretsActivity, 16), UiKit.dp(this@SecretsActivity, 8),
                UiKit.dp(this@SecretsActivity, 16), 0)
            addView(nameField)
            addView(valueField)
            addView(docsField)
            addView(keyField)
            addView(hostsField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add secret" else "Replace '$existing'")
            .setView(box)
            .setPositiveButton("save") { _, _ ->
                val n = (existing ?: nameField.text.toString()).trim()
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
            setPadding(UiKit.dp(this@SecretsActivity, 16), UiKit.dp(this@SecretsActivity, 8),
                UiKit.dp(this@SecretsActivity, 16), 0)
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
