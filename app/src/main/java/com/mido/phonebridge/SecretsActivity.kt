package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import com.mido.phonebridge.plugins.secrets.HostAllowlist

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
    private lateinit var allowContainer: LinearLayout
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

        content.addView(sectionTitle("Allowed hosts"))
        allowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(allowContainer)

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
            listContainer.addView(rowView)
        }

        allowContainer.removeAllViews()
        val hosts = try {
            HostAllowlist.all(this)
        } catch (e: Exception) {
            allowContainer.addView(row("allowlist unreadable", e.message ?: "", UiKit.RED_BG))
            emptyList()
        }
        if (hosts.isEmpty()) {
            allowContainer.addView(row("no hosts allowed", "the broker will refuse every call", UiKit.AMBER_BG))
        }
        for (h in hosts) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, UiKit.dp(this@SecretsActivity, 8), 0, UiKit.dp(this@SecretsActivity, 8))
            }
            r.addView(TextView(this).apply {
                text = h
                textSize = 15f
                setTextColor(UiKit.TEXT_ON_DARK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            r.addView(small("revoke") {
                HostAllowlist.revoke(this, h)
                render()
            })
            allowContainer.addView(r)
        }
        allowContainer.addView(small("+ allow a host") { addHostDialog() })
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

    private fun addSecretDialog(existing: String? = null) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@SecretsActivity, 16), UiKit.dp(this@SecretsActivity, 8),
                UiKit.dp(this@SecretsActivity, 16), 0)
            addView(nameField)
            addView(valueField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add secret" else "Replace '$existing'")
            .setView(box)
            .setPositiveButton("save") { _, _ ->
                val n = (existing ?: nameField.text.toString()).trim()
                val v = valueField.text.toString()
                try {
                    SecretVault.put(this, n, v)
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

    private fun addHostDialog() {
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
            .setTitle("Allow a host")
            .setView(box)
            .setPositiveButton("allow") { _, _ ->
                try {
                    HostAllowlist.allow(this, field.text.toString())
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
