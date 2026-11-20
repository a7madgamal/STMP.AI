package com.mido.phonebridge.core

import android.content.Context
import android.util.Log

/**
 * Stores the Google refresh token, encrypted with a hardware-backed Keystore key.
 *
 * Phase 1: key is not yet biometric-gated (fingerprint lands in Phase 2, where the
 * same key gains setUserAuthenticationRequired(true)).
 * Phase 2 note: because the ciphertext lives in prefs and the key is in the Keystore,
 * gating the key is enough to make the token unreadable without a biometric.
 */
object TokenStore {
    private const val TAG = "TokenStore"
    private const val PREFS = "phonebridge_secure"
    private const val KEY_ALIAS = "pb_token_key"
    private const val FIELD = "refresh_token"

    @Volatile private var cached: String? = null

    fun save(ctx: Context, token: String) {
        try {
            val blob = TokenCipher.encrypt(token)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(FIELD, blob).apply()
            cached = token
            Log.i(TAG, "refresh token stored (encrypted)")
        } catch (e: Exception) {
            throw PluginError("secure_storage_error", "could not store token: ${e.message}")
        }
    }

    fun refreshToken(ctx: Context): String? {
        cached?.let { return it }
        val blob = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FIELD, null)
            ?: return null
        return try {
            val token = TokenCipher.decrypt(blob, "the pre-multi-account account")
            cached = token
            token
        } catch (e: Exception) {
            Log.w(TAG, "could not decrypt stored token: ${e.message}")
            null
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(FIELD).apply()
        cached = null
    }

    // --- plain (non-secret) persisted values: survive updates, no re-config ---

    private const val F_EMAIL = "account_email"
    private const val F_CLIENT_ID = "oauth_client_id"
    private const val F_SERVER_CLIENT_ID = "oauth_server_client_id"
    private const val F_CLIENT_SECRET = "oauth_client_secret"

    private fun put(ctx: Context, key: String, v: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, v).apply()
    }
    private fun get(ctx: Context, key: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)

    fun saveEmail(ctx: Context, email: String) = put(ctx, F_EMAIL, email)
    fun email(ctx: Context): String? = get(ctx, F_EMAIL)

    /** Wipe persisted identity (email) on a real logout. Config is kept. */
    fun clearIdentity(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(F_EMAIL).apply()
    }

    fun saveConfig(ctx: Context, clientId: String, serverClientId: String, clientSecret: String) {
        put(ctx, F_CLIENT_ID, clientId)
        put(ctx, F_SERVER_CLIENT_ID, serverClientId)
        put(ctx, F_CLIENT_SECRET, clientSecret)
    }
    fun config(ctx: Context): Triple<String, String, String>? {
        val a = get(ctx, F_CLIENT_ID) ?: return null
        val b = get(ctx, F_SERVER_CLIENT_ID) ?: return null
        val c = get(ctx, F_CLIENT_SECRET) ?: return null
        return Triple(a, b, c)
    }

    fun load(ctx: Context) { /* no-op; decrypt happens lazily on read */ }

}
