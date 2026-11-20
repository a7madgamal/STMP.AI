package com.mido.phonebridge.core

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * The Google accounts on this phone, each with its own refresh token.
 *
 * ONE file, one Keystore key, one AES-GCM construction — the same crypto [TokenStore] used
 * for the single token. What changes is the SHAPE: an account is now a record keyed by its
 * Google `sub`, and every token is encrypted on its own so removing one account never touches
 * another's ciphertext.
 *
 * **`sub` is the key, the email is the address.** The `sub` never changes, so a Workspace
 * domain rename leaves the token working; the email is what a caller sends and what a human
 * reads, so it is stored alongside and looked up case-insensitively. Neither substitutes for
 * the other: a token fetched by email is always fetched under the `sub` that email resolved to,
 * and that `sub` is what the cache is keyed by.
 *
 * **Fail hard, in the three places that matter:**
 *
 *  - There is no "the account". [require] throws `account_required` / `account_not_found`; it
 *    never falls back to the first entry, and an empty list is `no_accounts`, not "use nothing".
 *  - A malformed record is a DEFECT and throws `account_storage_error`. Silently skipping it
 *    would make an account disappear and read as "you were signed out".
 *  - A record that will not decrypt throws. A token that cannot be read is not an absent token:
 *    the account exists and its credential is broken, which is a different fix.
 *
 * The one deliberate leniency is the MIGRATION (see [migrateLegacy]) — and it is deliberately
 * ALL-OR-NOTHING: an install that already has the single `refresh_token` either comes across as
 * one complete account, or has that token cleared and is asked to sign in. A record that is
 * usable but un-re-authorisable is not a cheaper migration, it is the failure mode.
 */
object GoogleAccounts {

    private const val TAG = "GoogleAccounts"
    private const val PREFS = "phonebridge_google_accounts"

    /** The whole account list, as JSON. One key, so a partial write cannot half-update it. */
    private const val FIELD_ACCOUNTS = "accounts"

    /** The pre-migration single-token field, read once and never written again. */
    private const val LEGACY_FIELD = "refresh_token"
    private const val LEGACY_EMAIL_FIELD = "account_email"
    private const val LEGACY_PREFS = "phonebridge_secure"

    /** One account. Complete by construction: identity and address are both required. */
    data class Account(
        /**
         * The Google identity. **Required** — a record without one cannot exist.
         *
         * There used to be a "pending migration" record with a blank `sub`, adopted from the
         * old single token and completed later by a userinfo call. That was a half state, and a
         * half state is the worst of both: the account could serve requests but could never be
         * re-authorised (that flow needs a `sub`), so it looked healthy until the one action that
         * fixes a credential was the one action it could not perform. Users get a migration that
         * COMPLETES, or a sign-in — never a record that is neither.
         */
        val sub: String,
        /** As Google reports it. The lookup address, compared case-insensitively. */
        val email: String,
        /** A human label for the picker. Never an identifier — see the plan, §2. */
        val label: String? = null,
        /**
         * What Google actually granted, captured from both the code exchange and every refresh.
         *
         * Empty means UNKNOWN, never "nothing": `PlayAuth` historically passed `null`, and
         * Google omits `scope` on some responses. Drive's old workaround (an empty set must not
         * be read as "missing a scope") is now a fact this field carries rather than a comment.
         */
        val grantedScopes: Set<String> = emptySet(),
        val addedAt: Long = System.currentTimeMillis(),
    ) {
        init {
            // Enforced at construction: an unidentified account cannot be built, so no code path
            // can create the half state this type used to allow.
            require(sub.isNotBlank()) { "a Google account needs its identity (sub)" }
            require(email.isNotBlank()) { "a Google account needs its address" }
        }

        /** How this account is named anywhere a human reads it. */
        val display: String get() = label?.let { "$email ($it)" } ?: email
    }

    // --- reads ---

    /**
     * Every account, in the order they were added.
     *
     * Throws rather than returning a shortened list: a stored record that will not parse is a
     * defect, and hiding it would silently drop an account.
     */
    @Synchronized
    fun all(ctx: Context): List<Account> {
        // Cheap and local: the old single credential is DROPPED rather than migrated, so this is
        // a preference removal, not a network call, and it is safe on a paint path.
        clearLegacyCredential(ctx)
        val raw = prefs(ctx).getString(FIELD_ACCOUNTS, null) ?: return emptyList()
        val arr = try {
            JSONObject(raw).getJSONArray("list")
        } catch (e: Exception) {
            throw PluginError(
                "account_storage_error",
                "the stored account list is unreadable (${e.javaClass.simpleName}); " +
                    "this needs fixing on the phone rather than a re-login",
            )
        }
        val out = ArrayList<Account>(arr.length())
        var discarded = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw PluginError(
                "account_storage_error",
                "entry $i in the stored account list is not an object",
            )
            val account = decode(o)
            if (account == null) discarded++ else out.add(account)
        }
        if (discarded > 0) {
            // Persist the removal: a record that is discarded on every read would log the same
            // warning forever and keep the store in a shape the type forbids.
            writeAccounts(ctx, out)
            Log.w(TAG, "removed $discarded unaddressable account record(s)")
        }
        return out
    }

    fun count(ctx: Context): Int = all(ctx).size

    /** Every account's email, for an error that has to list what DOES exist. */
    fun emails(ctx: Context): List<String> = all(ctx).map { it.email }

    fun isEmpty(ctx: Context): Boolean = all(ctx).isEmpty()

    /**
     * Resolve an address to exactly one account, or throw.
     *
     * Case-insensitive on the whole address, because Google treats the local part that way and
     * a caller should not have to reproduce the server's casing. There is no partial match and
     * no prefix match: `eng` is not `eng.a7mad.gamal`.
     */
    fun byEmail(ctx: Context, email: String): Account {
        val accounts = all(ctx)
        if (accounts.isEmpty()) {
            throw PluginError("no_accounts", "no Google account is connected on this phone")
        }
        val wanted = email.trim().lowercase()
        if (wanted.isEmpty()) throw PluginError("account_required", requirementsFor(accounts))
        accounts.firstOrNull { it.email.lowercase() == wanted }?.let { return it }
        // A `sub` or a label is not an address. Refuse and name the accepted form rather than
        // accepting a second spelling — `/help` can only document one.
        throw PluginError(
            "account_not_found",
            "no account \"${email.trim()}\" — accounts: ${emails(ctx).joinToString(", ")}. " +
                "Send the full email address, exactly as listed.",
        )
    }

    /** Resolve a `sub`. Used by the token path, never by a caller. */
    fun bySub(ctx: Context, sub: String): Account {
        val found = all(ctx).firstOrNull { it.sub == sub }
            ?: throw PluginError(
                "account_not_found",
                "no account with that identity — accounts: ${emails(ctx).joinToString(", ")}",
            )
        return found
    }

    /**
     * The one account, for the single-account interface.
     *
     * This is the ONLY place the absence of an `account` parameter is allowed to mean anything,
     * and only while exactly one account exists. At two or more it throws `account_required`, so
     * a caller that forgot the parameter fails loudly instead of acting as whichever account
     * happened to be first.
     */
    fun sole(ctx: Context): Account {
        val accounts = all(ctx)
        return when (accounts.size) {
            0 -> throw PluginError("no_accounts", "no Google account is connected on this phone")
            1 -> accounts[0]
            else -> throw PluginError("account_required", requirementsFor(accounts))
        }
    }

    /** What `account_required` has to say: the accounts, and the form to send. */
    private fun requirementsFor(accounts: List<Account>): String =
        "this request must name an account — ${accounts.size} connected: " +
            accounts.joinToString(", ") { it.email } +
            ". Add ?account=<full email> (see GET /help)."

    // --- token storage ---

    /**
     * The refresh token for one account, decrypted.
     *
     * Keyed by `sub`: a record adopted from the legacy token has no `sub` yet, and its token is
     * stored under the legacy field until [adoptIdentity] gives it one. Reading by email in that
     * window is deliberate — it is the only account that can exist in it.
     */
    @Synchronized
    fun refreshToken(ctx: Context, account: Account): String {
        val blob = tokens(ctx).optString(account.sub, "")
        if (blob.isEmpty()) {
            throw PluginError(
                "auth_required",
                "no stored credential for ${account.email}; sign in again",
            )
        }
        return TokenCipher.decrypt(blob, account.email)
    }

    /**
     * Store one account's credential.
     *
     * A blank [refreshToken] is refused: Google issues one on the code exchange and omits it on
     * later refreshes, so an absent value must leave the existing token alone rather than erase
     * it. Callers pass only what they received.
     */
    @Synchronized
    fun putRefreshToken(ctx: Context, account: Account, refreshToken: String) {
        if (refreshToken.isBlank()) {
            throw PluginError("auth_error", "refusing to store an empty refresh token")
        }
        val o = tokens(ctx)
        o.put(account.sub, TokenCipher.encrypt(refreshToken))
        writeTokens(ctx, o)
    }

    /**
     * Add an account, or update the one that already has this `sub`.
     *
     * Matching on `sub` is what makes re-authorising an existing account an UPDATE rather than a
     * duplicate — the email may have changed with a Workspace rename, and the identity did not.
     */
    @Synchronized
    fun upsert(ctx: Context, account: Account) {
        val current = all(ctx).toMutableList()
        val at = current.indexOfFirst { it.sub == account.sub }
        if (at >= 0) {
            // Keep the earlier `addedAt`: the ORDER of the list is the order accounts were added,
            // and a re-authorisation is not a new account.
            current[at] = account.copy(addedAt = current[at].addedAt)
        } else {
            // Two records with one address would make the address ambiguous, and `byEmail`
            // resolves by address — so refuse rather than store a second one that shadows.
            if (current.any { it.email.lowercase() == account.email.lowercase() }) {
                throw PluginError(
                    "account_storage_error",
                    "an account with the address ${account.email} already exists under a " +
                        "different identity; remove it first",
                )
            }
            current.add(account)
        }
        writeAccounts(ctx, current)
    }

    /** Record what Google granted. Empty is kept as empty: unknown, never "nothing". */
    @Synchronized
    fun setGrantedScopes(ctx: Context, sub: String, scopes: Set<String>) {
        val current = all(ctx).toMutableList()
        val at = current.indexOfFirst { it.sub == sub }
        if (at < 0) {
            throw PluginError(
                "account_not_found",
                "no account with that identity — accounts: ${emails(ctx).joinToString(", ")}",
            )
        }
        current[at] = current[at].copy(grantedScopes = scopes)
        writeAccounts(ctx, current)
    }

    /**
     * Remove one account and its credential.
     *
     * The token is deleted BEFORE the record, so a kill in between leaves an account with no
     * credential (which reports `auth_required` and is fixed by re-adding) rather than a token
     * with no account (which is unreachable and would live on the phone forever).
     */
    @Synchronized
    fun remove(ctx: Context, sub: String) {
        val current = all(ctx).toMutableList()
        val at = current.indexOfFirst { it.sub == sub }
        if (at < 0) {
            throw PluginError(
                "account_not_found",
                "no account with that identity — accounts: ${emails(ctx).joinToString(", ")}",
            )
        }
        current.removeAt(at)
        val o = tokens(ctx)
        o.remove(sub)
        writeTokens(ctx, o)
        writeAccounts(ctx, current)
    }

    /** Remove everything. Used by a full sign-out, never by per-account removal. */
    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(FIELD_ACCOUNTS).remove(FIELD_TOKENS).apply()
        prefs(ctx, LEGACY_PREFS).edit().remove(LEGACY_FIELD).apply()
    }

    // --- migration ---

    private const val FIELD_MIGRATED = "legacy_migrated"

    /**
     * Clear the pre-multi-account credential. **No migration, no network.**
     *
     * An install that predates multi-account has a single `refresh_token` and an address but
     * never a `sub`, and a record without a `sub` cannot be re-authorised. Attempting to upgrade
     * it in place produced two bad outcomes in a row: first a half state that could serve but
     * never repair itself, then — once that state was forbidden — a crash on upgrade.
     *
     * Mido's decision: *"no migration just logout user"*. So the upgrade simply signs everyone
     * out. That is strictly better than either attempt: it is one step, it cannot half-succeed,
     * it needs no network at startup, and it lands the app in the ZERO-account state that every
     * screen already renders and every error already explains. A sign-in afterwards creates a
     * complete record with its identity known from the start.
     *
     * Runs on read and is idempotent: once the field is gone there is nothing to do.
     */
    @Synchronized
    private fun clearLegacyCredential(ctx: Context) {
        if (prefs(ctx).getBoolean(FIELD_MIGRATED, false)) return
        val had = prefs(ctx, LEGACY_PREFS).contains(LEGACY_FIELD) ||
            prefs(ctx, LEGACY_PREFS).contains(LEGACY_EMAIL_FIELD)
        if (had) {
            Log.w(TAG, "pre-multi-account credential cleared; a Google sign-in is required")
        }
        prefs(ctx, LEGACY_PREFS).edit()
            .remove(LEGACY_FIELD)
            .remove(LEGACY_EMAIL_FIELD)
            .apply()
        prefs(ctx).edit().putBoolean(FIELD_MIGRATED, true).apply()
    }

    // --- serialisation ---

    private fun decode(o: JSONObject): Account? {
        val email = o.optString("email", "")
        if (email.isBlank()) {
            throw PluginError("account_storage_error", "a stored account has no address")
        }
        val scopes = LinkedHashSet<String>()
        o.optJSONArray("scopes")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let(scopes::add)
            }
        }
        val sub = o.optString("sub", "")
        if (sub.isBlank()) {
            // **A record from the half-state era.** It was written by the version that allowed an
            // account with no identity, so it is already on disk on any install that ran it, and
            // constructing `Account` now throws — which, called from a screen or a request, is a
            // crash on an upgrade rather than a message. So it is treated as what it is: a
            // credential nothing can address, which is discarded and reported, leaving the app in
            // the zero-account state every screen already explains. Silent deletion would be
            // wrong; a crash is worse.
            Log.w(TAG, "discarding an account with no identity (pre-0.52.4 half state)")
            return null
        }
        return Account(
            sub = sub,
            email = email,
            label = o.optString("label", "").takeIf { it.isNotBlank() },
            grantedScopes = scopes,
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
        )
    }

    private fun encode(a: Account): JSONObject = JSONObject().apply {
        put("sub", a.sub)
        put("email", a.email)
        a.label?.let { put("label", it) }
        put("scopes", org.json.JSONArray(a.grantedScopes.toList()))
        put("addedAt", a.addedAt)
    }

    private fun writeAccounts(ctx: Context, accounts: List<Account>) {
        val arr = org.json.JSONArray()
        for (a in accounts) arr.put(encode(a))
        prefs(ctx).edit().putString(FIELD_ACCOUNTS, JSONObject().put("list", arr).toString()).apply()
    }

    // --- tokens, per sub ---

    private const val FIELD_TOKENS = "tokens"

    private fun tokens(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(FIELD_TOKENS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw PluginError(
                "account_storage_error",
                "the stored credential map is unreadable (${e.javaClass.simpleName})",
            )
        }
    }

    private fun writeTokens(ctx: Context, tokens: JSONObject) {
        prefs(ctx).edit().putString(FIELD_TOKENS, tokens.toString()).apply()
    }

    private fun prefs(ctx: Context, name: String = PREFS) =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
}
