package com.mido.phonebridge.plugins.jwt

import android.content.Context
import com.mido.phonebridge.core.PluginError
import org.json.JSONObject

/**
 * A stored JWT credential: the public half of it.
 *
 * **The secret is NOT here.** It lives in [com.mido.phonebridge.core.SecretVault] under
 * `jwt-<name>`, behind the fingerprint. This record is the configuration that has to be readable
 * while the vault is locked — `GET /jwt` lists it, the screen renders it, and a locked vault must
 * not make the list answer "unknown".
 *
 * That split is the same one `SecretVault.SecretMeta` already makes for docs links and host rules,
 * for the same reason: a name and a rule are not credentials.
 */
data class JwtEntry(
    /** The `iss` claim. For Mozilla this is the API key string. */
    val issuer: String,
    /** JOSE algorithm. Resolved through [JwtSigners], so an unknown value refuses rather than guesses. */
    val alg: String = HmacSha256.name,
    /** Token lifetime in seconds. */
    val ttlSeconds: Int = 60,
    val skewSeconds: Int = 30,
    /** `JWT` (Mozilla) or `Bearer`. */
    val scheme: String = "JWT",
    /** Optional key id, empty when the upstream does not ask for one. */
    val kid: String = "",
    /** Extra claims, scalar values only. Never `iss`/`iat`/`exp`/`jti`/`nbf`. */
    val extraClaims: Map<String, Any> = emptyMap(),
    /** Where its API is documented. Plaintext, exactly like a secret's docs link. */
    val docs: String = "",
) {

    /** The spec the minter wants. Kept here so callers never assemble one field-by-field. */
    fun toSpec(): JwtSpec = JwtSpec(
        issuer = issuer,
        alg = alg,
        ttlSeconds = ttlSeconds,
        skewSeconds = skewSeconds,
        scheme = scheme,
        kid = kid,
        extraClaims = extraClaims,
    )

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("issuer", issuer)
            .put("alg", alg)
            .put("ttlSeconds", ttlSeconds)
            .put("skewSeconds", skewSeconds)
            .put("scheme", scheme)
            .put("kid", kid)
            .put("docs", docs)
        if (extraClaims.isNotEmpty()) {
            o.put("extraClaims", JSONObject(extraClaims as Map<*, *>))
        }
        return o
    }

    companion object {
        /**
         * Parse a stored record.
         *
         * Every field that decides what gets signed is REQUIRED from storage and fails loudly when
         * absent. There is no default for [issuer]: a token with no issuer is not a degraded token,
         * it is a credential that cannot work, and defaulting it would produce a signature the
         * upstream rejects for a reason nobody can see.
         */
        fun fromJson(raw: String): JwtEntry {
            val o = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw PluginError("jwt_bad_config", "stored jwt config is not JSON: ${e.message}")
            }
            val issuer = o.optString("issuer", "").trim()
            if (issuer.isEmpty()) {
                throw PluginError("jwt_bad_config", "stored jwt config has no issuer")
            }
            val alg = o.optString("alg", HmacSha256.name).trim().ifEmpty { HmacSha256.name }
            if (!JwtSigners.isSupported(alg)) {
                throw PluginError(
                    "jwt_bad_config",
                    "stored jwt config names alg '$alg', which this build cannot sign with"
                )
            }
            val ttl = o.optInt("ttlSeconds", 60)
            if (ttl !in JwtSpec.MIN_TTL_SECONDS..JwtSpec.MAX_TTL_SECONDS) {
                throw PluginError("jwt_bad_config", "stored jwt ttl $ttl is out of range")
            }
            val skew = o.optInt("skewSeconds", 30)
            if (skew !in 0..JwtSpec.MAX_SKEW_SECONDS) {
                throw PluginError("jwt_bad_config", "stored jwt skew $skew is out of range")
            }
            val storedScheme = o.optString("scheme", "JWT").trim().ifEmpty { "JWT" }
            val scheme = JwtSpec.canonicalScheme(storedScheme)
                ?: throw PluginError(
                    "jwt_bad_config",
                    "stored jwt scheme '$storedScheme' is not supported"
                )
            val extra = mutableMapOf<String, Any>()
            o.optJSONObject("extraClaims")?.let { e ->
                for (k in e.keys()) {
                    when (val v = e.get(k)) {
                        is String, is Number, is Boolean -> extra[k] = v
                        else -> throw PluginError(
                            "jwt_bad_config",
                            "stored jwt claim '$k' is not a plain value"
                        )
                    }
                }
            }
            val conflicts = extra.keys.filter { it in JwtSpec.RESERVED_CLAIMS }
            if (conflicts.isNotEmpty()) {
                throw PluginError(
                    "jwt_bad_config",
                    "stored jwt config redefines ${conflicts.joinToString(", ")}"
                )
            }
            return JwtEntry(
                issuer = issuer,
                alg = alg,
                ttlSeconds = ttl,
                skewSeconds = skew,
                scheme = scheme,
                kid = o.optString("kid", "").trim(),
                extraClaims = extra,
                docs = o.optString("docs", "").trim(),
            )
        }
    }
}

/**
 * The plaintext sidecar: one [JwtEntry] per credential name.
 *
 * **A separate store, not fields on `SecretVault.SecretMeta`.** Adding `alg`/`ttl`/`kid` to the vault
 * index would mean every future JWT knob touches a structure that also holds SSH keys and API
 * secrets, and a change there is a change to a file the user cannot repair by hand. This is additive:
 * an older build simply does not read it.
 *
 * Writes are atomic (`commit` on one key) so a kill mid-write cannot leave a half record that parses
 * as a token with the wrong TTL.
 */
object JwtStore {

    private const val PREFS = "phonebridge_jwt"
    private const val KEY_ENTRIES = "entries"

    /** The vault name for a credential. Names are `[a-z0-9_-]`, hence the prefix. */
    fun vaultName(name: String): String = "jwt-$name"

    /** The credential name for a vault entry name, or null when it is not one. */
    fun nameOf(vaultEntryName: String): String? =
        vaultEntryName.removePrefix("jwt-").takeIf { it != vaultEntryName && it.isNotEmpty() }

    /** Every configured name, sorted. Readable while the vault is locked. */
    fun names(ctx: Context): List<String> = readAll(ctx).keys.sorted()

    fun has(ctx: Context, name: String): Boolean = readAll(ctx).containsKey(name)

    /** The config for [name]. Throws `unknown_jwt` when there is none. */
    fun get(ctx: Context, name: String): JwtEntry =
        readAll(ctx)[name] ?: throw PluginError("unknown_jwt", "no jwt credential named '$name'")

    /** Insert or replace one entry. The caller is responsible for the secret half. */
    @Synchronized
    fun put(ctx: Context, name: String, entry: JwtEntry) {
        val next = readAll(ctx).toMutableMap()
        next[name] = entry
        writeAll(ctx, next)
    }

    /** Remove one entry. Harmless when absent, so a deletion is idempotent. */
    @Synchronized
    fun remove(ctx: Context, name: String) {
        val all = readAll(ctx)
        if (!all.containsKey(name)) return
        writeAll(ctx, all - name)
    }

    /**
     * Every stored record.
     *
     * A single unreadable record does not fail the whole list — that would make one typo hide every
     * other credential. It is skipped here and reported through the plugin's own status, the same
     * way `PluginInventoryView` treats an unreadable SSH connection.
     */
    private fun readAll(ctx: Context): Map<String, JwtEntry> {
        val raw = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyMap()
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            // The whole store being unparseable IS a fault worth refusing over: it would silently
            // report "no credentials" while the vault still holds secrets.
            throw PluginError("jwt_bad_config", "jwt config store is unreadable: ${e.message}")
        }
        val out = mutableMapOf<String, JwtEntry>()
        for (name in o.keys()) {
            val entryRaw = o.optJSONObject(name)?.toString() ?: continue
            // Per-record failure: skip and keep the rest, so one bad record cannot hide the others.
            runCatching { JwtEntry.fromJson(entryRaw) }.getOrNull()?.let { out[name] = it }
        }
        return out
    }

    private fun writeAll(ctx: Context, all: Map<String, JwtEntry>) {
        val o = JSONObject()
        for ((name, entry) in all) o.put(name, entry.toJson())
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, o.toString())
            .commit()
    }
}
