package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.plugins.jwt.JwtEntry
import com.mido.phonebridge.plugins.jwt.JwtRules
import com.mido.phonebridge.plugins.jwt.JwtSigners
import com.mido.phonebridge.plugins.jwt.JwtSpec
import com.mido.phonebridge.plugins.jwt.JwtStore
import com.mido.phonebridge.plugins.jwt.JwtTokenCache
import com.mido.phonebridge.plugins.secrets.HostAllowlist
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.PaperDialog
import com.mido.phonebridge.ui.fieldHeader
import com.mido.phonebridge.ui.labelledField
import com.mido.phonebridge.ui.showDestructive
import com.mido.phonebridge.ui.showSecure

/**
 * JWT credentials: one row per credential, with its issuer and what it can reach.
 *
 * **Its own screen, like SSH's.** A JWT credential is not a name and a value: it is an issuer, an
 * algorithm, a lifetime, an optional key id, extra claims and a host allowlist, and the operation you
 * want against it — rotate the signing secret — is nothing like "send this API a request".
 *
 * **The signing secret is never rendered here.** It goes from the dialog straight into the vault under
 * `jwt-<name>` marked `Kind.JWT`, and neither this screen nor `SecretsActivity` offers a reveal for
 * that kind. It is the signing key for a bearer credential: reading it off a screen is equivalent to
 * handing over every token it can mint.
 */
class JwtActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A signing secret is typed on this screen, so no screenshots and no recents thumbnail — the
        // same reason `SecretsActivity` sets this.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
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

        content.addView(Theme.sectionHeader(this, "JWT credentials"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        val addBtn = Theme.button(this, "ADD CREDENTIAL", Theme.ButtonStyle.SUCCESS) { addDialog() }
        addBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(this@JwtActivity, 12) }
        content.addView(addBtn)

        content.addView(
            Theme.text(
                this,
                "The signing secret stays in the vault. A caller sends a request to " +
                    "/jwt/<name>/call and writes *** where the token belongs — the phone signs and " +
                    "makes the call, so its own logs never hold the token.",
                12f, Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, Theme.dp(this@JwtActivity, 14), 0, 0) },
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
            "Vault locked — open the app and scan your fingerprint to add or rotate a secret. " +
                "Existing credentials are listed below."
        }

        listContainer.removeAllViews()
        val names = try {
            JwtStore.names(this)
        } catch (e: Exception) {
            listContainer.addView(row("config unreadable", e.message ?: "", Theme.DANGER_SURFACE))
            emptyList()
        }
        if (names.isEmpty()) {
            listContainer.addView(row("no credentials yet", "tap ADD CREDENTIAL", Theme.SURFACE))
        }

        for (name in names) {
            val entry = try {
                JwtStore.get(this, name)
            } catch (e: Exception) {
                listContainer.addView(row(name, "unreadable: ${e.message}", Theme.DANGER_SURFACE))
                continue
            }
            listContainer.addView(credentialCard(name, entry))
        }
    }

    private fun credentialCard(name: String, entry: JwtEntry): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            Theme.padCard(this@JwtActivity, this)
            background = Theme.outlined(this@JwtActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Theme.dp(this@JwtActivity, 10) }

        card.addView(Theme.text(this, name, 16f))
        card.addView(
            Theme.text(this, entry.issuer, 13f, Theme.ON_SURFACE_VARIANT, mono = true)
                .apply { setTextIsSelectable(true) }
        )
        card.addView(
            Theme.text(
                this,
                "${entry.alg} · valid ${entry.ttlSeconds}s · ${entry.scheme} header",
                Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, Theme.dp(this@JwtActivity, 6), 0, 0) }
        )

        // The allowed hosts are the security boundary this credential is bound by, so they are on the
        // card rather than one tap deep: "it says host not allowed" is answered by looking here.
        val hosts = try {
            SecretVault.meta(this, JwtStore.vaultName(name)).hosts
        } catch (e: Exception) {
            emptyList()
        }
        card.addView(
            Theme.text(
                this,
                if (hosts.isEmpty()) {
                    "hosts: none — every call is refused until one is added"
                } else {
                    "hosts: ${hosts.joinToString(", ")}"
                },
                Theme.TEXT_CAPTION_SP,
                if (hosts.isEmpty()) Theme.WARNING else Theme.SUCCESS,
            ).apply { setPadding(0, Theme.dp(this@JwtActivity, 6), 0, 0) }
        )

        if (entry.kid.isNotBlank()) {
            card.addView(
                Theme.text(
                    this, "kid: ${entry.kid}", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
                )
            )
        }
        if (entry.extraClaims.isNotEmpty()) {
            card.addView(
                Theme.text(
                    this,
                    "claims: ${entry.extraClaims.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                    Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
                )
            )
        }

        // The route, spelled out. A caller looking at this screen needs the exact path, and it is not
        // guessable from the name alone.
        card.addView(
            Theme.text(
                this, "POST /jwt/$name/call", Theme.TEXT_CAPTION_SP, Theme.ACCENT, mono = true,
            ).apply { setPadding(0, Theme.dp(this@JwtActivity, 8), 0, 0) }
        )

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(small("hosts") { hostsDialog(name) })
        buttons.addView(small("settings") { addDialog(name) })
        buttons.addView(small("rotate secret") { rotateDialog(name) })
        card.addView(buttons)

        // Delete alone on its own line, away from the buttons that can be backed out of: rotating can
        // be undone with the old secret, deleting cannot be undone at all.
        val destructive = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        destructive.addView(
            Theme.button(this, "delete", Theme.ButtonStyle.GHOST, small = true) {
                confirmDelete(name)
            }
        )
        card.addView(
            destructive,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(this@JwtActivity, 4) },
        )
        return card
    }

    // --- add / edit --------------------------------------------------------

    /**
     * Create a credential, or edit one when [existingName] is given.
     *
     * **One form, two modes.** The fields are the same either way, and keeping them in one place is
     * what stops "the add dialog validates but the edit dialog does not" — a difference nobody would
     * notice until a broken credential was saved through the edit path.
     *
     * **The secret field is absent when editing.** Rotating is its own explicit action because it is
     * destructive-ish (every in-flight cache entry for this credential dies) and because requiring a
     * re-paste to change a TTL would be absurd.
     */
    private fun addDialog(existingName: String? = null) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
        val existing = existingName?.let { runCatching { JwtStore.get(this, it) }.getOrNull() }
        val creating = existingName == null

        // **Required first, optional after.** Reading top-down, the fields the credential cannot work
        // without come before the ones with a working default — so a form that is scrolled to the
        // bottom (or cut off on a short screen) never hides the inputs that matter.
        //
        // The form is built into `box`, which PaperDialog puts inside its own OverflowScrollView, so a
        // long list scrolls and announces that it scrolls.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val nameField = labelledField(
            box, "NAME",
            "What you will call this credential. It becomes part of the route " +
                "(POST /jwt/<name>/call) and of the policy key, so it is lowercase letters, " +
                "digits, - or _ only. Pick something you will recognise on an approval card.",
            example = "amo",
            required = creating,
        ).apply {
            setText(existingName ?: "")
            isEnabled = creating
        }

        val issuerField = labelledField(
            box, "ISSUER / API KEY",
            "The `iss` claim — it says WHOSE account the call is made as, and it is the value the " +
                "provider's credentials page calls the API key. It must be copied exactly: on " +
                "Mozilla it is three parts (user:<number>:<number>), and a bare account number is " +
                "refused here because it would make every call fail with a plain 401.",
            example = "user:123456:7",
            required = true,
        ).apply {
            setText(existing?.issuer ?: "")
            typeface = Theme.monoFont(this@JwtActivity)
        }

        // Only on create: rotating has its own action.
        val secretField = if (creating) {
            labelledField(
                box, "SIGNING SECRET",
                "The secret from the provider's credentials page — the value the token is signed " +
                    "with. It goes into the vault, is never shown again, and never leaves the " +
                    "phone. It must be at least 32 bytes for HS256, so if it is refused as too " +
                    "short, part of it was not copied.",
                required = true,
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        } else null

        // --- everything below has a working default ---

        val schemeField = labelledChoice(
            box, "HEADER SCHEME",
            "How the token is presented in the Authorization header. Mozilla wants JWT; most " +
                "other APIs want Bearer. The value only decides the header — the token itself is " +
                "identical either way.",
            JwtSpec.SCHEMES, existing?.scheme ?: "JWT",
        )

        val ttlField = labelledField(
            box, "TOKEN LIFETIME (SECONDS)",
            "How long each minted token stays valid. Short is better: a token is a live bearer " +
                "credential, and it is reused from memory until it is nearly expired. Mozilla " +
                "rejects anything over 300.",
            example = "60",
            inputType = InputType.TYPE_CLASS_NUMBER,
        ).apply { setText((existing?.ttlSeconds ?: 60).toString()) }

        val skewField = labelledField(
            box, "CLOCK SKEW (SECONDS)",
            "How far in the PAST the issue time is placed. A phone clock a few seconds fast " +
                "otherwise produces a token that is not yet valid, which strict servers answer with " +
                "a 401 and no explanation. 30 is plenty.",
            example = "30",
            inputType = InputType.TYPE_CLASS_NUMBER,
        ).apply { setText((existing?.skewSeconds ?: 30).toString()) }

        val kidField = labelledField(
            box, "KEY ID (OPTIONAL)",
            "An identifier the server can use to pick which key signed the token, for providers " +
                "that rotate keys. Leave it empty unless the API's documentation asks for one — " +
                "sending an id the server does not know is a rejection.",
        ).apply { setText(existing?.kid ?: "") }

        val claimsField = labelledField(
            box, "EXTRA CLAIMS (OPTIONAL, JSON)",
            "Additional claims to include, as a JSON object — for example an audience or a scope " +
                "the API requires. Plain values only (string, number, boolean). iss, iat, exp, jti " +
                "and nbf cannot be set here because this plugin owns them.",
            example = "{\"aud\":\"api\"}",
        ).apply {
            setText(
                existing?.extraClaims?.takeIf { it.isNotEmpty() }
                    ?.let { org.json.JSONObject(it as Map<*, *>).toString() } ?: ""
            )
            typeface = Theme.monoFont(this@JwtActivity)
        }

        // The reason a refusal left the form intact, shown inside it, next to the fields.
        val errorLine = TextView(this).apply {
            textSize = Theme.TEXT_CAPTION_SP
            setTextColor(Theme.DANGER)
            visibility = View.GONE
            setPadding(0, Theme.dp(this@JwtActivity, 8), 0, 0)
        }
        box.addView(errorLine)

        // The save logic, run from the dialog's action. Returns "was it refused?" so PaperDialog can
        // keep the sheet open — a validation failure must cost a keystroke, not the whole form.
        fun attemptSave(): Boolean {
            fun refuse(message: String, focus: EditText): Boolean {
                errorLine.text = message
                errorLine.visibility = View.VISIBLE
                focus.requestFocus()
                Debug.log("jwt: save refused: $message")
                return true
            }

            val name = nameField.text.toString().trim().lowercase()
            if (creating) {
                if (!com.mido.phonebridge.core.SecretRules.isValidName(name)) {
                    return refuse(
                        "Name must be lowercase letters, digits, - or _ (1-64 chars), " +
                            "starting with a letter or digit. \"$name\" is not.",
                        nameField,
                    )
                }
                if (JwtStore.has(this, name)) {
                    return refuse("A credential named \"$name\" already exists.", nameField)
                }
            }

            val issuer = try {
                JwtRules.requireIssuer(issuerField.text.toString())
            } catch (e: PluginError) {
                return refuse(e.message ?: "issuer is invalid", issuerField)
            }

            val ttl = ttlField.text.toString().trim().toIntOrNull()
            if (ttl == null || ttl !in JwtSpec.MIN_TTL_SECONDS..JwtSpec.MAX_TTL_SECONDS) {
                return refuse(
                    "Lifetime must be a whole number of seconds, " +
                        "${JwtSpec.MIN_TTL_SECONDS}..${JwtSpec.MAX_TTL_SECONDS}.",
                    ttlField,
                )
            }
            val skew = skewField.text.toString().trim().toIntOrNull() ?: 0
            if (skew !in 0..JwtSpec.MAX_SKEW_SECONDS) {
                return refuse("Clock skew must be 0..${JwtSpec.MAX_SKEW_SECONDS} seconds.", skewField)
            }
            val extraClaims = try {
                JwtRules.parseExtraClaims(claimsField.text.toString())
            } catch (e: PluginError) {
                return refuse(e.message ?: "extra claims are invalid", claimsField)
            }

            val entry = JwtEntry(
                issuer = issuer,
                alg = existing?.alg ?: "HS256",
                ttlSeconds = ttl,
                skewSeconds = skew,
                scheme = schemeField.tag as String,
                kid = kidField.text.toString().trim(),
                extraClaims = extraClaims,
            )

            if (secretField != null) {
                val secret = secretField.text.toString()
                if (secret.isBlank()) return refuse("The signing secret is required.", secretField)
                try {
                    JwtRules.requireKeyLength(entry.alg, secret)
                } catch (e: PluginError) {
                    return refuse(e.message ?: "secret is too short", secretField)
                }
                try {
                    SecretVault.put(
                        this, JwtStore.vaultName(name), secret,
                        kind = SecretVault.SecretMeta.Kind.JWT,
                        // This plugin's own id. The Secrets screen lists only unowned entries, so
                        // declaring ownership here is what keeps a signing key off that screen —
                        // no prefix, no Kind check on the other side.
                        owner = "jwt",
                        // An empty allowlist refuses every call, which is the right default for a
                        // credential nobody has decided about. The card says so, and the hosts
                        // sheet opens next.
                        hosts = emptyList(),
                    )
                    JwtStore.put(this, name, entry)
                    BridgeService.registerJwtPlugin(this, name)
                } catch (e: PluginError) {
                    return refuse("${e.code}: ${e.message}", nameField)
                } catch (t: Throwable) {
                    // A UI boundary: the process must not die and the reason must reach the log.
                    Debug.log("jwt: create '$name' CRASHED: ${t.javaClass.name}: ${t.message}")
                    return refuse("Could not save: ${t.javaClass.simpleName}: ${t.message}", nameField)
                }
                // The form's work is done.
                render()
                // Hosts are the next thing the credential needs to work at all, so asking straight
                // away is the only useful next step. Posted, so it opens after this sheet closes —
                // a dialog shown from inside another dialog's action lands behind it.
                android.os.Handler(android.os.Looper.getMainLooper()).post { hostsDialog(name) }
                return false
            }

            val finalName = existingName ?: name
            try {
                JwtStore.put(this, finalName, entry)
                // A changed ttl/scheme/claims means the cached token no longer describes the config.
                JwtTokenCache.forget(finalName)
            } catch (t: Throwable) {
                Debug.log("jwt: edit '$finalName' CRASHED: ${t.javaClass.name}: ${t.message}")
                return refuse("Could not save: ${t.javaClass.simpleName}: ${t.message}", nameField)
            }
            render()
            return false
        }

        // **A paper dialog, like every other sheet in the app** (Mido, 2026-11-10: *"not in a boring
        // system dialog use a paperlike one"*). Its body scrolls, so eight fields on a short screen
        // are reachable and the overflow cue says so.
        PaperDialog.show(
            ctx = this,
            title = if (creating) "Add JWT credential" else "Edit '$existingName'",
            body = box,
            actions = listOf(
                (if (creating) "save" else "update") to {},
                "cancel" to {},
            ),
            style = { index -> if (index == 0) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL },
            refuse = { label -> if (label == "save" || label == "update") attemptSave() else false },
        )
    }

    /**
     * Replace the signing secret.
     *
     * Its own dialog because it is the one field that must never be shown again after it is saved: a
     * combined form would have to either pre-fill it (rendering the current secret on screen) or leave
     * it blank (ambiguous — does blank mean "keep" or "clear"?). Neither is acceptable for a signing
     * key, so the operation is explicit and the field always starts empty.
     */
    private fun rotateDialog(name: String) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
        val entry = runCatching { JwtStore.get(this, name) }.getOrNull()
        if (entry == null) {
            toast("'$name' is unreadable — fix its settings first")
            return
        }
        val errorLine = TextView(this).apply {
            textSize = Theme.TEXT_CAPTION_SP
            setTextColor(Theme.DANGER)
            visibility = View.GONE
            setPadding(0, Theme.dp(this@JwtActivity, 8), 0, 0)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val secretField = labelledField(
            box, "NEW SIGNING SECRET",
            "Replaces the value this credential signs with. Any token already minted keeps working " +
                "upstream until it expires — revoke the old key on the provider's site if you want " +
                "it dead immediately. The allowed hosts are kept.",
            required = true,
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        box.addView(errorLine)

        fun attemptRotate(): Boolean {
            val secret = secretField.text.toString()
            fun refuse(msg: String): Boolean {
                errorLine.text = msg
                errorLine.visibility = View.VISIBLE
                secretField.requestFocus()
                return true
            }
            if (secret.isBlank()) return refuse("The new signing secret is required.")
            try {
                JwtRules.requireKeyLength(entry.alg, secret)
            } catch (e: PluginError) {
                return refuse(e.message ?: "secret is too short")
            }
            try {
                val hosts = SecretVault.meta(this, JwtStore.vaultName(name)).hosts
                SecretVault.put(
                    this, JwtStore.vaultName(name), secret,
                    kind = SecretVault.SecretMeta.Kind.JWT,
                    // Re-declared on rotation. `put` resolves a null to the existing owner, so this
                    // is belt-and-braces rather than required — but an entry written by a build
                    // before ownership existed gets its owner recorded here the first time its key
                    // is rotated.
                    owner = "jwt",
                    // Preserved, not reset: rotating a key must not silently revoke the host rules,
                    // which would turn every call into `host_not_allowed`.
                    hosts = hosts,
                )
                // Any cached token was signed with the old secret and is now worthless.
                JwtTokenCache.forget(name)
                render()
                toast("secret rotated for '$name'")
                return false
            } catch (t: Throwable) {
                Debug.log("jwt: rotate '$name' failed: ${t.javaClass.name}: ${t.message}")
                return refuse("Could not save: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        PaperDialog.show(
            ctx = this,
            title = "Rotate secret — $name",
            body = box,
            actions = listOf("save" to {}, "cancel" to {}),
            style = { index -> if (index == 0) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL },
            refuse = { label -> if (label == "save") attemptRotate() else false },
        )
    }

    /**
     * Add or remove the hosts this credential may reach.
     *
     * Same rule and same matcher as the secret broker's (`HostAllowlist`), because it is the same
     * enforcement point — [com.mido.phonebridge.plugins.secrets.SecretBroker] reads this list. Adding
     * a host here is what makes `POST /jwt/<name>/call` able to reach it at all.
     */
    private fun hostsDialog(name: String) {
        val vaultName = JwtStore.vaultName(name)
        val errorLine = TextView(this).apply {
            textSize = Theme.TEXT_CAPTION_SP
            setTextColor(Theme.DANGER)
            visibility = View.GONE
            setPadding(0, Theme.dp(this@JwtActivity, 8), 0, 0)
        }
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun rebuild() {
            listBox.removeAllViews()
            val hosts = runCatching { SecretVault.meta(this, vaultName).hosts }
                .getOrDefault(emptyList())
            if (hosts.isEmpty()) {
                listBox.addView(
                    Theme.text(this, "no hosts — every call is refused", Theme.TEXT_CAPTION_SP, Theme.WARNING)
                )
                return
            }
            for (h in hosts) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(
                    Theme.text(this, h, 13f, Theme.ON_SURFACE, mono = true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                row.addView(Theme.button(this, "remove", Theme.ButtonStyle.GHOST, small = true) {
                    try {
                        SecretVault.revokeHost(this, vaultName, h)
                        rebuild()
                    } catch (e: Exception) {
                        toast("could not remove: ${e.message}")
                    }
                })
                listBox.addView(row)
            }
        }
        rebuild()

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // What is already allowed comes FIRST: the question "what can this reach" is answered before
        // the control that changes it, and the empty state says what empty means.
        box.addView(PaperDialog.heading(this, "Allowed now"))
        box.addView(listBox)

        val hostField = labelledField(
            box, "ADD A HOST",
            "Where this credential's token may be sent. A bare hostname matches exactly; " +
                "*.domain matches its subdomains but NOT the bare domain. No scheme, no port, " +
                "no path — those would look like they narrow the rule while the matcher ignores them.",
            example = "addons.mozilla.org",
        )
        box.addView(errorLine)

        fun attemptAdd(): Boolean {
            val raw = hostField.text.toString().trim()
            try {
                val rule = HostAllowlist.requireRule(raw)
                SecretVault.allowHost(this, vaultName, rule)
                hostField.setText("")
                errorLine.visibility = View.GONE
                rebuild()
                render()
                return false
            } catch (e: PluginError) {
                errorLine.text = e.message
                errorLine.visibility = View.VISIBLE
                hostField.requestFocus()
                return true
            } catch (t: Throwable) {
                errorLine.text = "Could not add: ${t.javaClass.simpleName}: ${t.message}"
                errorLine.visibility = View.VISIBLE
                return true
            }
        }

        PaperDialog.show(
            ctx = this,
            title = "Hosts — $name",
            body = box,
            actions = listOf("add" to {}, "close" to {}),
            style = { index -> if (index == 0) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL },
            refuse = { label -> if (label == "add") attemptAdd() else false },
        )
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete '$name'?")
            .setMessage(
                "This removes the signing secret and its configuration. Any token already minted " +
                    "stays valid upstream until it expires (at most " +
                    "${runCatching { JwtStore.get(this, name).ttlSeconds }.getOrDefault(60)}s) — " +
                    "revoke the API key on the provider's site if you want it dead immediately."
            )
            .setPositiveButton("delete") { _, _ ->
                try {
                    JwtTokenCache.forget(name)
                    JwtStore.remove(this, name)
                    if (SecretVault.has(this, JwtStore.vaultName(name))) {
                        SecretVault.delete(this, JwtStore.vaultName(name))
                    }
                    render()
                    toast("deleted '$name'")
                } catch (e: Exception) {
                    toast("could not delete: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .showDestructive()
    }

    // --- widgets -----------------------------------------------------------

    /**
     * A labelled tap-to-cycle choice: title, "?", then a button showing the current value.
     *
     * The platform `Spinner` needs an adapter and an adapter needs a layout; two options do not
     * justify either. The selected value rides in [View.getTag] so the save path reads it the same way
     * it reads a text field, and there is exactly one source for the answer.
     *
     * The label and help are built HERE rather than at the call site so this cannot drift from
     * [labelledField] — a choice with a different title style beside six fields with the right one
     * would read as a different kind of control.
     */
    private fun labelledChoice(
        container: LinearLayout,
        title: String,
        help: String,
        options: List<String>,
        initial: String,
    ): android.widget.Button {
        var index = options.indexOf(initial).let { if (it < 0) 0 else it }

        // The SAME header a text field draws, so a choice and a field do not look like two different
        // kinds of control on one form.
        container.addView(fieldHeader(title, help))

        return Theme.button(this, options[index], Theme.ButtonStyle.NEUTRAL) { }.apply {
            tag = options[index]
            setOnClickListener {
                index = (index + 1) % options.size
                val value = options[index]
                text = value
                tag = value
                contentDescription = "$title: $value. Tap to change."
            }
            contentDescription = "$title: ${options[index]}. Tap to change."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Theme.dp(this@JwtActivity, 10) }
        }.also { container.addView(it) }
    }

    private fun row(title: String, detail: String, color: Int) = TextView(this).apply {
        text = if (detail.isEmpty()) title else "$title\n$detail"
        textSize = Theme.TEXT_BODY_SP
        setTextColor(Theme.ON_SURFACE)
        Theme.padCard(this@JwtActivity, this)
        background = Theme.card(this@JwtActivity, color)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Theme.dp(this@JwtActivity, 10) }
    }

    private fun small(label: String, onClick: () -> Unit) =
        Theme.button(this, label, Theme.ButtonStyle.NEUTRAL, small = true, onClick = onClick)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Open this screen from elsewhere with a credential preselected, when that becomes useful. */
        const val EXTRA_NAME = "jwt_name"

        fun intent(context: android.content.Context): Intent =
            Intent(context, JwtActivity::class.java)
    }
}
