package com.mido.phonebridge

import android.app.Activity
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
import com.mido.phonebridge.core.StmpFolder
import com.mido.phonebridge.plugins.files.FilesStore
import com.mido.phonebridge.plugins.oauth.OauthEntry
import com.mido.phonebridge.plugins.oauth.OauthSecret
import com.mido.phonebridge.plugins.oauth.OauthStore
import com.mido.phonebridge.plugins.oauth.OauthTokenCache
import com.mido.phonebridge.plugins.secrets.HostAllowlist
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.PaperDialog
import com.mido.phonebridge.ui.fieldHeader
import com.mido.phonebridge.ui.labelledField
import com.mido.phonebridge.ui.showDestructive
import com.mido.phonebridge.ui.showSecure

/**
 * OAuth2 credentials: one row per credential, plus the folder uploads are read from.
 *
 * **Its own screen, like JWT's and SSH's.** An OAuth credential is not a name and a value: it is a
 * client id, a token endpoint, an auth style, a display-only scope and a host allowlist, and the
 * operation you want against it — rotate the refresh token — is nothing like "send this API a
 * request".
 *
 * **Both secrets are never rendered here.** They go from the dialog straight into the vault under
 * `oauth-<name>` as one JSON object, owned by this plugin, and neither this screen nor
 * `SecretsActivity` offers a reveal for an owned entry. Rotation is a separate action that takes a
 * new value, exactly like the JWT screen's.
 *
 * **The folder grant is this plugin's own.** [FilesStore] is constructed with [GRANT_PREFS], so the
 * permission taken here is independent of the `files` plugin's. Both point at
 * [StmpFolder.PATH] — a shared constant — but turning `files` off cannot break uploads from here.
 */
class OauthActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var folderContainer: LinearLayout
    private lateinit var header: TextView

    /** This plugin's own grant. Never the files plugin's. */
    private lateinit var store: FilesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A refresh token is typed on this screen, so no screenshots and no recents thumbnail — the
        // same reason `SecretsActivity` and `JwtActivity` set this.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        store = FilesStore(this, grantPrefs = GRANT_PREFS)

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

        content.addView(Theme.sectionHeader(this, "OAuth credentials"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        val addBtn = Theme.button(this, "ADD CREDENTIAL", Theme.ButtonStyle.SUCCESS) { addDialog() }
        addBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(this@OauthActivity, 12) }
        content.addView(addBtn)

        content.addView(Theme.sectionHeader(this, "Upload folder"))
        folderContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(folderContainer)

        content.addView(
            Theme.text(
                this,
                "The refresh token and client secret stay in the vault. A caller sends a request to " +
                    "/oauth/<name>/call and writes *** where the token belongs; the phone exchanges " +
                    "the refresh token for an access token, makes the call, and drops the token.",
                Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, Theme.dp(this@OauthActivity, 14), 0, 0) },
        )

        root.addView(scroll)
        setContentView(Theme.inset(root))
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // --- rendering ------------------------------------------------------------

    private fun render() {
        val unlocked = SecretVault.isUnlocked()
        header.text = if (unlocked) {
            "Vault unlocked for this session."
        } else {
            "Vault locked — open the app and scan your fingerprint to add or rotate a credential. " +
                "Existing credentials are listed below."
        }

        listContainer.removeAllViews()
        val names = try {
            OauthStore.names(this)
        } catch (e: Exception) {
            listContainer.addView(row("config unreadable", e.message ?: "", Theme.DANGER_SURFACE))
            emptyList()
        }
        if (names.isEmpty()) {
            listContainer.addView(row("no credentials yet", "tap ADD CREDENTIAL", Theme.SURFACE))
        }

        for (name in names) {
            val entry = try {
                OauthStore.get(this, name)
            } catch (e: Exception) {
                listContainer.addView(row(name, "unreadable: ${e.message}", Theme.DANGER_SURFACE))
                continue
            }
            listContainer.addView(credentialRow(name, entry))
        }

        renderFolder()
    }

    private fun credentialRow(name: String, entry: OauthEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OauthActivity, 14), Theme.dp(this@OauthActivity, 12),
                Theme.dp(this@OauthActivity, 14), Theme.dp(this@OauthActivity, 12),
            )
            background = Theme.outlined(this@OauthActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Theme.dp(this@OauthActivity, 10) }

        card.addView(Theme.text(this, name, Theme.TEXT_BODY_SP))
        card.addView(
            Theme.text(
                this,
                // The client id, not the secret: this is the public half, and it is what a caller
                // needs to match a credential against a Google Cloud console entry.
                "client ${entry.clientId.ifEmpty { "(unset)" }}",
                Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
            ).apply { typeface = Theme.monoFont(this@OauthActivity) }
        )
        card.addView(
            Theme.text(
                this,
                "mints at ${entry.tokenUrl} · ${entry.authStyle} auth",
                Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
            )
        )

        // The hosts are the security boundary, per credential. Shown on the card because "what can
        // this reach" is the question the card exists to answer.
        val hosts = runCatching { SecretVault.meta(this, OauthStore.vaultName(name)).hosts }
            .getOrDefault(emptyList())
        card.addView(
            Theme.text(
                this,
                if (hosts.isEmpty()) "no hosts allowed — every call will be refused"
                else "allowed: ${hosts.joinToString(", ")}",
                Theme.TEXT_CAPTION_SP,
                if (hosts.isEmpty()) Theme.WARNING else Theme.ON_SURFACE_VARIANT,
            )
        )

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(small("rotate") { rotateSecretDialog(name) })
        buttons.addView(small("edit") { addDialog(name) })
        buttons.addView(small("hosts") { hostsDialog(name) })
        card.addView(buttons)

        // Delete on its own line, away from the harmless controls: it destroys the credential and
        // means re-running the Playground to get another refresh token.
        val destructive = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        destructive.addView(
            Theme.button(this, "delete", Theme.ButtonStyle.GHOST, small = true) { confirmDelete(name) }
        )
        card.addView(destructive)

        return card
    }

    /**
     * The upload folder, and its grant state.
     *
     * **Named as this plugin's own.** A human who grants the folder here and then wonders why the
     * Files plugin still says "no folder" needs the sentence to say these are separate — otherwise
     * the two screens look like they disagree about the same thing.
     */
    private fun renderFolder() {
        folderContainer.removeAllViews()
        // **The REAL folder, asked of the provider — not the expected one.**
        //
        // This used to print `StmpFolder.PATH` whenever a grant existed, which meant it claimed
        // "granted: Downloads/STMP" after the human had picked some other tree. Reported by Mido,
        // 2026-11-20: the indicator was wrong. A label that echoes the constant tells you what the
        // app WANTS, not what it HAS, and the difference is exactly what breaks an upload.
        val grantedLabel = try {
            store.root()
            store.rootLabel()
        } catch (e: FilesStore.NotGranted) {
            null
        } catch (e: Exception) {
            null
        }
        val granted = grantedLabel != null || runCatching { store.root() }.isSuccess

        if (!granted) {
            folderContainer.addView(
                Theme.text(
                    this,
                    "no folder granted — uploads will refuse until one is chosen",
                    Theme.TEXT_DETAIL_SP, Theme.WARNING,
                )
            )
        } else {
            // The actual name, in mono because it is a path a human may compare with a file manager.
            folderContainer.addView(
                Theme.text(
                    this,
                    "granted: ${grantedLabel ?: "(name unavailable)"}",
                    Theme.TEXT_DETAIL_SP, Theme.ON_SURFACE_VARIANT,
                ).apply { typeface = Theme.monoFont(this@OauthActivity) }
            )
            // Uploads look for files by NAME inside whatever tree was granted, so a folder other
            // than the expected one is not automatically broken — but it is worth saying, because
            // a package in the wrong place reads later as `file_not_found`.
            if (grantedLabel != null && !grantedLabel.equals(StmpFolder.LAST_SEGMENT, ignoreCase = true)) {
                folderContainer.addView(
                    Theme.text(
                        this,
                        "uploads are expected from ${StmpFolder.PATH} — this is a different folder",
                        Theme.TEXT_CAPTION_SP, Theme.WARNING,
                    ).apply { setPadding(0, Theme.dp(this@OauthActivity, 4), 0, 0) }
                )
            }
        }

        folderContainer.addView(
            Theme.text(
                this,
                "Uploads read a package from this folder. This grant belongs to the OAuth screen only: " +
                    "the Files plugin has its own, so turning one off does not break the other.",
                Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, Theme.dp(this@OauthActivity, 4), 0, Theme.dp(this@OauthActivity, 8)) }
        )

        val btn = Theme.button(
            this,
            if (granted) "CHANGE FOLDER" else "GRANT FOLDER",
            if (granted) Theme.ButtonStyle.NEUTRAL else Theme.ButtonStyle.SUCCESS,
        ) { pickFolder() }
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        folderContainer.addView(btn)
    }

    // --- folder grant ---------------------------------------------------------

    /**
     * Open the system picker for a FOLDER.
     *
     * `ACTION_OPEN_DOCUMENT_TREE`, not `ACTION_OPEN_DOCUMENT`: only a tree grant supports CREATING
     * or, here, reading back a file an upload wrote. A single-document grant cannot express "the
     * folder the packages land in".
     */
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_PICK_FOLDER)
    }

    @Deprecated("startActivityForResult is the pattern the rest of this app uses")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            store.accept(uri)
            // **Ask the provider what was granted, instead of guessing from the URI.**
            //
            // The old check was `last.contains("STMP")` on `uri.lastPathSegment`, which is
            // `primary:Downloads/STMP` for some providers and a bare document id for others — so it
            // was right by luck on one provider and wrong on the next. `rootLabel()` asks the same
            // question the indicator does, so the toast and the panel can never disagree.
            val grantedLabel = store.rootLabel()
            val expected = grantedLabel != null &&
                grantedLabel.equals(StmpFolder.LAST_SEGMENT, ignoreCase = true)
            toast(
                if (expected) "folder granted: $grantedLabel"
                else "granted '${grantedLabel ?: "(name unavailable)"}' — uploads are expected " +
                    "from ${StmpFolder.PATH}"
            )
        } catch (e: Exception) {
            toast("could not take the grant: ${e.message}")
        }
        render()
    }

    // --- add / edit -----------------------------------------------------------

    /**
     * Add a credential, or edit its public half.
     *
     * **On edit, the two secret fields are absent, not blank.** A blank password field that saves
     * nothing is ambiguous — did it keep the old value or store an empty one? Rotation is a separate,
     * named action, so the answer is never a guess.
     */
    private fun addDialog(existingName: String? = null) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
        val creating = existingName == null
        val existing = existingName?.let { runCatching { OauthStore.get(this, it) }.getOrNull() }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val nameField = labelledField(
            box, "NAME",
            "What this credential is called. It becomes the route segment — " +
                "/oauth/<name>/call — and the vault entry is oauth-<name>. Lowercase letters, " +
                "digits, underscore and dash only.",
            example = "chromewebstore",
            required = true,
        ).apply {
            setText(existingName ?: "")
            isEnabled = creating
            typeface = Theme.monoFont(this@OauthActivity)
        }

        val clientIdField = labelledField(
            box, "CLIENT ID",
            "The OAuth client id from the provider's console. This is NOT a secret — it ships inside " +
                "every OAuth client — so it is stored in the clear and shown on the card.",
            example = "503841343554-…apps.googleusercontent.com",
            required = true,
        ).apply {
            setText(existing?.clientId ?: "")
            typeface = Theme.monoFont(this@OauthActivity)
        }

        // --- the two secrets: create only ---
        val clientSecretField: EditText?
        val refreshField: EditText?
        if (creating) {
            clientSecretField = labelledField(
                box, "CLIENT SECRET",
                "The client secret from the same console page. It goes into the vault and is never " +
                    "shown again. Together with the refresh token it is the whole credential: one " +
                    "without the other cannot mint a token.",
                example = "GOCSPX-…",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                required = true,
            )
            refreshField = labelledField(
                box, "REFRESH TOKEN",
                "The long-lived token from an authorisation round trip — for Google, the OAuth " +
                    "Playground with your own client id and secret. It is the key to the account " +
                    "until revoked, which is why it lives in the vault and never leaves the phone.",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                required = true,
            )
        } else {
            clientSecretField = null
            refreshField = null
        }

        // --- defaults that are usually right ---
        val tokenUrlField = labelledField(
            box, "TOKEN URL",
            "Where the phone exchanges the refresh token for an access token. https only — a client " +
                "secret over http is a secret in the clear. This is NOT the API you are calling; it " +
                "is the provider's token endpoint.",
            example = "https://oauth2.googleapis.com/token",
            required = true,
        ).apply {
            setText(existing?.tokenUrl ?: "")
            typeface = Theme.monoFont(this@OauthActivity)
        }

        val scopeField = labelledField(
            box, "SCOPE (OPTIONAL, DISPLAY ONLY)",
            "The scope the refresh token was granted, recorded so you can see what this credential " +
                "can do. It is NEVER sent on a refresh: RFC 6749 §6 allows it only as a subset of " +
                "what was granted, and a provider that disagrees answers invalid_scope.",
            example = "https://www.googleapis.com/auth/chromewebstore",
        ).apply {
            setText(existing?.scope ?: "")
            typeface = Theme.monoFont(this@OauthActivity)
        }

        val styleField = labelledField(
            box, "AUTH STYLE (basic OR body)",
            "How the client authenticates at the token endpoint. Basic is the default because " +
                "RFC 6749 §2.3.1 says a server MUST support it for clients issued a password, while " +
                "credentials in the form body are only a MAY. Getting this backwards yields " +
                "invalid_client, which looks exactly like a wrong secret.",
            example = OauthEntry.AUTH_BASIC,
        ).apply {
            setText(existing?.authStyle ?: OauthEntry.AUTH_BASIC)
            typeface = Theme.monoFont(this@OauthActivity)
        }

        val hostsField = labelledField(
            box, "ALLOWED HOSTS",
            "Comma-separated hosts this credential may send its access token to. This is the security " +
                "boundary and it is per credential. The token endpoint is deliberately NOT listed " +
                "here: this list says where a token may GO, not where it is minted.",
            example = "chromewebstore.googleapis.com",
        ).apply {
            val hosts = existingName?.let {
                runCatching { SecretVault.meta(this@OauthActivity, OauthStore.vaultName(it)).hosts }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            setText(hosts.joinToString(", "))
            typeface = Theme.monoFont(this@OauthActivity)
        }

        val docsField = labelledField(
            box, "DOCS LINK (OPTIONAL)",
            "Where this provider's OAuth documentation lives. Opened from this screen when a " +
                "credential fails and you need the page that explains it.",
            example = "https://developers.google.com/…",
        ).apply { setText(existing?.docs ?: "") }

        val refuse: (String) -> Boolean = { message ->
            val field = when {
                message.startsWith("NAME") -> nameField
                message.startsWith("CLIENT ID") -> clientIdField
                message.startsWith("CLIENT SECRET") -> clientSecretField
                message.startsWith("REFRESH") -> refreshField
                message.startsWith("TOKEN URL") -> tokenUrlField
                message.startsWith("AUTH STYLE") -> styleField
                message.startsWith("ALLOWED HOSTS") -> hostsField
                else -> nameField
            }
            val text = message.substringAfter(')').trim().ifEmpty { message }
            Debug.log("oauth: save refused: $message")
            toast(text)
            field?.requestFocus()
            true
        }

        PaperDialog.show(
            this,
            if (creating) "Add OAuth credential" else "Edit $existingName",
            box,
            actions = listOf(
                "cancel" to {},
                "save" to {
                    save(
                        creating = creating,
                        existingName = existingName,
                        name = nameField.text.toString(),
                        clientId = clientIdField.text.toString(),
                        clientSecret = clientSecretField?.text?.toString(),
                        refreshToken = refreshField?.text?.toString(),
                        tokenUrl = tokenUrlField.text.toString(),
                        scope = scopeField.text.toString(),
                        authStyle = styleField.text.toString(),
                        hosts = hostsField.text.toString(),
                        docs = docsField.text.toString(),
                        refuse = { msg -> refuse(msg) },
                    )
                },
            ),
            style = { index -> if (index == 1) Theme.ButtonStyle.SUCCESS else Theme.ButtonStyle.NEUTRAL },
            refuse = refuse,
        )
    }

    /**
     * Validate and save.
     *
     * Every failure is prefixed with the FIELD name so the refusal can put the cursor back in the
     * right box — a sheet that keeps what was typed but not where the problem is still costs the
     * human a hunt across eight fields.
     */
    private fun save(
        creating: Boolean,
        existingName: String?,
        name: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String?,
        tokenUrl: String,
        scope: String,
        authStyle: String,
        hosts: String,
        docs: String,
        /** Reports a field-specific refusal. The sheet stays open; the return value is unused here. */
        refuse: (String) -> Unit,
    ) {
        val cleanName = name.trim().lowercase()
        if (cleanName.isEmpty()) return refuse("(name) NAME is required")
        if (!com.mido.phonebridge.core.SecretRules.isValidName(cleanName)) {
            return refuse("(name) NAME must be lowercase [a-z0-9_-], 1-64 chars")
        }
        if (creating && runCatching { OauthStore.get(this, cleanName) }.isSuccess) {
            return refuse("(name) NAME '$cleanName' is already used — edit it instead")
        }

        if (clientId.isBlank()) return refuse("(client) CLIENT ID is required")
        if (tokenUrl.isBlank()) return refuse("(token) TOKEN URL is required")

        val parsedUrl = runCatching { java.net.URL(tokenUrl.trim()) }.getOrNull()
            ?: return refuse("(token) TOKEN URL is not a valid URL")
        if (parsedUrl.protocol != "https") {
            return refuse("(token) TOKEN URL must be https — a client secret over http is in the clear")
        }

        val style = authStyle.trim().lowercase()
        if (style !in OauthEntry.AUTH_STYLES) {
            return refuse("(auth) AUTH STYLE must be '${OauthEntry.AUTH_BASIC}' or '${OauthEntry.AUTH_BODY}'")
        }

        if (creating) {
            if (clientSecret?.isBlank() != false) return refuse("(secret) CLIENT SECRET is required")
            if (refreshToken?.isBlank() != false) return refuse("(refresh) REFRESH TOKEN is required")
        }

        // Hosts are validated as a whole before anything is written, so a typo in the last one cannot
        // leave the credential half-saved.
        val hostRules = try {
            hosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { HostAllowlist.requireRule(it) }.distinct()
        } catch (e: PluginError) {
            return refuse("(hosts) ALLOWED HOSTS: ${e.message}")
        }

        try {
            if (creating) {
                // One vault entry, two fields: RFC 6749 §6 needs both in the same request, and the
                // broker substitutes exactly one value.
                SecretVault.put(
                    this,
                    OauthStore.vaultName(cleanName),
                    OauthSecret(refreshToken!!.trim(), clientSecret!!.trim()).toJson(),
                    hosts = hostRules,
                    docs = docs.trim(),
                    // OWNED BY THIS PLUGIN. The Secrets screen lists only unowned entries, so this is
                    // what keeps a refresh token off that screen — no prefix, no Kind check there.
                    owner = "oauth",
                )
            } else {
                // Editing the public half: the hosts and docs travel, the secrets do not.
                SecretVault.put(
                    this,
                    OauthStore.vaultName(cleanName),
                    SecretVault.get(this, OauthStore.vaultName(cleanName)),
                    hosts = hostRules,
                    docs = docs.trim(),
                    owner = "oauth",
                )
            }

            OauthStore.put(
                this,
                cleanName,
                OauthEntry(
                    clientId = clientId.trim(),
                    tokenUrl = tokenUrl.trim(),
                    scope = scope.trim(),
                    authStyle = style,
                    docs = docs.trim(),
                ),
            )
            // A cached token was minted for the OLD client; keeping it would attribute the next call
            // to the previous config.
            OauthTokenCache.forget(cleanName)
            BridgeService.registerOauthPlugin(this, cleanName)
            toast(if (creating) "credential '$cleanName' saved" else "updated '$cleanName'")
            render()
        } catch (e: PluginError) {
            refuse("(fail) ${e.message}")
        } catch (e: Exception) {
            refuse("(fail) Could not save: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Replace the two secrets without touching the public half.
     *
     * Its own action, and its own dialog, because it is a different intention: an edit fixes a URL,
     * a rotation replaces the credential. Both fields are required here — half a rotated credential
     * is a credential that cannot mint.
     */
    private fun rotateSecretDialog(name: String) {
        if (!SecretVault.isUnlocked()) {
            toast("vault locked — unlock the app first")
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val secretField = labelledField(
            box, "NEW CLIENT SECRET",
            "The replacement client secret. Both fields must be replaced together: the token endpoint " +
                "needs the secret and the refresh token in one request, so half a rotation is a " +
                "credential that cannot mint.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            required = true,
        )
        val refreshField = labelledField(
            box, "NEW REFRESH TOKEN",
            "The replacement refresh token. Get a new one from the same authorisation round trip " +
                "you used the first time — the old one stops working the moment it is replaced.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            required = true,
        )

        val refuse: (String) -> Boolean = { message ->
            val isRefresh = message.startsWith("REFRESH")
            Debug.log("oauth: rotate refused: $message")
            toast(message.substringAfter(')').trim().ifEmpty { message })
            (if (isRefresh) refreshField else secretField).requestFocus()
            true
        }
        // Statement form for the action lambda, which returns Unit.
        val fail: (String) -> Unit = { msg -> refuse(msg) }

        PaperDialog.show(
            this,
            "Replace secrets for $name",
            box,
            actions = listOf(
                "cancel" to {},
                "replace" to {
                    val s = secretField.text.toString()
                    val r = refreshField.text.toString()
                    if (s.isBlank()) return@to fail("(secret) CLIENT SECRET is required")
                    if (r.isBlank()) return@to fail("(refresh) REFRESH TOKEN is required")
                    try {
                        val hosts = runCatching {
                            SecretVault.meta(this, OauthStore.vaultName(name)).hosts
                        }.getOrDefault(emptyList())
                        SecretVault.put(
                            this,
                            OauthStore.vaultName(name),
                            OauthSecret(r.trim(), s.trim()).toJson(),
                            hosts = hosts,
                            owner = "oauth",
                        )
                        // The old token was minted from the old credential and is now worthless.
                        OauthTokenCache.forget(name)
                        toast("secrets replaced for '$name'")
                        render()
                    } catch (e: Exception) {
                        fail("(fail) ${e.message}")
                    }
                },
            ),
            style = { index -> if (index == 1) Theme.ButtonStyle.SUCCESS else Theme.ButtonStyle.NEUTRAL },
            refuse = refuse,
        )
    }

    private fun hostsDialog(name: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val current = runCatching {
            SecretVault.meta(this, OauthStore.vaultName(name)).hosts
        }.getOrDefault(emptyList())

        val field = labelledField(
            box, "ALLOWED HOSTS",
            "Comma-separated hosts this credential may send its access token to. Empty means every " +
                "call is refused, which is the right default for a credential nobody has decided " +
                "about. Wildcards follow the same rules as a secret's hosts.",
            example = "chromewebstore.googleapis.com",
        ).apply {
            setText(current.joinToString(", "))
            typeface = Theme.monoFont(this@OauthActivity)
        }

        PaperDialog.show(
            this,
            "Hosts for $name",
            box,
            actions = listOf(
                "cancel" to {},
                "save" to {
                    try {
                        val rules = field.text.toString().split(',').map { it.trim() }
                            .filter { it.isNotEmpty() }.map { HostAllowlist.requireRule(it) }.distinct()
                        SecretVault.put(
                            this,
                            OauthStore.vaultName(name),
                            SecretVault.get(this, OauthStore.vaultName(name)),
                            hosts = rules,
                            owner = "oauth",
                        )
                        toast("hosts saved for '$name'")
                        render()
                    } catch (e: Exception) {
                        toast(e.message ?: "could not save")
                    }
                },
            ),
            style = { index -> if (index == 1) Theme.ButtonStyle.SUCCESS else Theme.ButtonStyle.NEUTRAL },
        )
    }

    private fun confirmDelete(name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete '$name'?")
            .setMessage(
                "This destroys the refresh token and client secret. Getting them back means " +
                    "re-running the provider's authorisation round trip."
            )
            .setPositiveButton("delete") { _, _ ->
                try {
                    SecretVault.delete(this, OauthStore.vaultName(name))
                    OauthStore.remove(this, name)
                    OauthTokenCache.forget(name)
                    toast("deleted '$name'")
                    render()
                } catch (e: Exception) {
                    toast("could not delete: ${e.message}")
                }
            }
            .setNegativeButton("cancel", null)
            .showDestructive()
    }

    // --- small UI helpers -----------------------------------------------------

    private fun row(title: String, detail: String, bg: Int): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OauthActivity, 14), Theme.dp(this@OauthActivity, 12),
                Theme.dp(this@OauthActivity, 14), Theme.dp(this@OauthActivity, 12),
            )
            background = Theme.outlined(this@OauthActivity, bg, Theme.RADIUS_CARD)
        }
        v.addView(Theme.text(this, title, Theme.TEXT_BODY_SP))
        if (detail.isNotEmpty()) {
            v.addView(Theme.text(this, detail, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT))
        }
        return v
    }

    private fun small(label: String, action: () -> Unit): View =
        Theme.button(this, label, Theme.ButtonStyle.GHOST, small = true, onClick = action)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /**
         * THIS SCREEN'S OWN grant preferences.
         *
         * Not `files_grant`. The folder name is shared ([StmpFolder.PATH]); the permission is not, so
         * turning the `files` plugin off cannot break uploads from here.
         */
        const val GRANT_PREFS = "oauth_grant"
        const val REQ_PICK_FOLDER = 9101
    }
}
