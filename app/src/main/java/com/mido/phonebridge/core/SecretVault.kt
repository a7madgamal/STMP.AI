package com.mido.phonebridge.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The secret vault: third-party API secrets (Todoist first) at rest, unlocked once
 * per app session with a fingerprint.
 *
 * ## Why envelope encryption and not one Keystore key per secret
 *
 * The plan called for a per-secret Keystore alias, which is right for *revocation*
 * but wrong for *session unlock*, and the platform is what makes it wrong:
 * `setUserAuthenticationRequired(true)` with `setUserAuthenticationParameters(0, …)`
 * demands a fresh finger for **every use of the key — encryption included**. With one
 * auth-per-use key per secret, writing a new secret would cost a prompt, and unlocking
 * would cost one prompt *per secret*. N secrets, N scans. That is not the model we
 * agreed to.
 *
 * So the vault is two keys:
 *
 *  - **VK (vault key)** — a Keystore key, auth-per-use, `BIOMETRIC_STRONG`, StrongBox
 *    where available. It is used for exactly two operations, both requiring a live
 *    finger: wrap DK (first run) and unwrap DK (every later run).
 *  - **DK (data key)** — a random AES-256 key that is NOT in the Keystore. It is stored
 *    only *wrapped by VK*, and it never exists in the clear on disk. All secret
 *    encryption uses DK, so adding or reading a secret needs no biometric at all.
 *
 * Net: **one prompt per session**, and the secrets are unreadable at rest without a
 * live finger — because DK cannot be obtained without unwrapping it with VK.
 *
 * ## The session
 *
 * Unlock decrypts DK into RAM and decrypts the whole vault blob into RAM. That is the
 * session. Process death clears it: there is no on-disk plaintext and no persisted
 * "unlocked" flag, so a restarted process is locked by construction — [isUnlocked] is
 * just "is DK in memory".
 *
 * The accepted cost, named in the plan: while unlocked, DK and the plaintext secrets
 * sit in RAM, so root or a compromised process can read them. That is what "session
 * unlock" means, and it was chosen deliberately over per-call prompts.
 *
 * ## Not a cache
 *
 * Unlike [TokenStore]'s old `cached` field, nothing here is a cache of a value that
 * could be re-derived cheaply. DK is the only way to read the blob; if it is absent,
 * the secrets are absent. [lock] really does zero the session.
 */
object SecretVault {

    private const val TAG = "SecretVault"
    private const val PREFS = "phonebridge_vault"
    private const val VK_ALIAS = "pb_vault_key"
    private const val F_DK_WRAPPED = "vault_dk_wrapped"
    private const val F_BLOB = "vault_blob"
    private const val F_NAMES = "vault_names"   // legacy (0.14.0): bare name array
    private const val F_META = "vault_meta"     // plaintext: name -> {docs, key}

    private const val GCM_TAG_BITS = 128
    private const val DK_BYTES = 32             // AES-256

    /**
     * The session. `null` means locked. Volatile because the HTTP server thread reads
     * it while the UI thread unlocks — the unlock itself is synchronized, but the
     * cheap "are we locked?" check on the request path must not block on a monitor.
     */
    @Volatile
    private var dataKey: SecretKey? = null

    /** Plaintext secrets for the session: name -> value. Empty when locked. */
    @Volatile
    private var session: MutableMap<String, String>? = null

    fun isUnlocked(): Boolean = dataKey != null

    /** True when a vault has been created (a wrapped DK exists on disk). */
    fun exists(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(F_DK_WRAPPED, null) != null

    // --- names and links are not secret ---------------------------------------
    //
    // The secret NAME is a policy unit (it is the plugin id — see D7), so it has to be
    // readable while locked: the rules screen lists `todoist × EXECUTE` with the vault
    // shut, and the broker has to answer "unknown secret" without decrypting anything.
    // Only the VALUE is secret. Keeping names in the clear is therefore not a leak, it
    // is the design: a name is a label, not a credential.
    //
    // The two LINKS follow the same rule for a stronger reason. They point at where the
    // API is documented and where a key is issued — pointers, not credentials. They sit
    // in the plaintext index precisely so they are readable while the vault is shut,
    // which is exactly when you need them: a key just failed and you want the page that
    // reissues it, without a fingerprint to read a URL you could type from memory.
    //
    // The ALLOWED HOSTS live here too, and that is the important one. They used to be a
    // single global list, which meant the `todoist` secret could be sent to any host
    // some *other* secret had approved — the domains were shared, so one careless rule
    // widened every secret's blast radius. A host is a property of the secret it
    // belongs to, so it is stored with it, in the clear, for the same reason as the
    // names: the broker must answer "this host is not allowed" while locked.

    /**
     * Everything about a secret that is NOT the value: the two links, and the hosts
     * that secret is allowed to reach. Plaintext by design (see above).
     */
    data class SecretMeta(
        val docs: String = "",
        val key: String = "",
        val hosts: List<String> = emptyList()
    )

    /** Secret names, sorted. Readable while locked — a name is a label, not a credential. */
    fun names(ctx: Context): List<String> = readMeta(ctx).keys.sorted()

    /** Whether [name] is a registered secret, without needing the vault unlocked. */
    fun has(ctx: Context, name: String): Boolean = readMeta(ctx).containsKey(name)

    /** The meta for [name]. Plaintext, so this works while the vault is locked. */
    fun meta(ctx: Context, name: String): SecretMeta =
        readMeta(ctx)[name] ?: throw PluginError("unknown_secret", "no secret named '$name'")

    /**
     * Read the plaintext index. Handles both older shapes: 0.14.0 wrote a bare name
     * array (`F_NAMES`), 0.14.1 wrote name -> {docs,key} (`F_META`). A vault created
     * before hosts existed must not lose its names or its links.
     */
    private fun readMeta(ctx: Context): Map<String, SecretMeta> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(F_META, null)?.let { raw ->
            return try {
                val o = JSONObject(raw)
                val m = mutableMapOf<String, SecretMeta>()
                for (k in o.keys()) {
                    val v = o.optJSONObject(k)
                    val hostsArr = v?.optJSONArray("hosts")
                    val hosts = if (hostsArr == null) emptyList()
                    else (0 until hostsArr.length()).map { hostsArr.getString(it) }
                    m[k] = SecretMeta(
                        docs = v?.optString("docs") ?: "",
                        key = v?.optString("key") ?: "",
                        hosts = hosts
                    )
                }
                m
            } catch (e: Exception) {
                throw PluginError("vault_corrupt", "secret index unreadable: ${e.message}")
            }
        }
        val legacy = prefs.getString(F_NAMES, null) ?: return emptyMap()
        return try {
            val arr = org.json.JSONArray(legacy)
            (0 until arr.length()).associate { arr.getString(it) to SecretMeta() }
        } catch (e: Exception) {
            // A corrupt index is a real fault: it would silently hide secrets that
            // still exist in the blob. Fail loud rather than report an empty vault.
            throw PluginError("vault_corrupt", "secret name index unreadable: ${e.message}")
        }
    }

    private fun writeMeta(ctx: Context, meta: Map<String, SecretMeta>) {
        val o = JSONObject()
        for ((k, v) in meta) {
            o.put(
                k,
                JSONObject()
                    .put("docs", v.docs)
                    .put("key", v.key)
                    .put("hosts", org.json.JSONArray(v.hosts))
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(F_META, o.toString())
            .remove(F_NAMES)   // superseded; leaving it would let the two disagree
            .apply()
    }

    // --- unlock / init --------------------------------------------------------

    /**
     * The cipher the biometric prompt must carry to UNLOCK. Auth-per-use means this
     * cipher cannot be used until a finger has authorised it, so it is handed to
     * `BiometricPrompt.authenticate(info, CryptoObject(cipher))` and only usable from
     * the success callback.
     */
    fun prepareUnlockCipher(ctx: Context): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, vaultKey(ctx))
        return cipher
    }

    /**
     * The cipher the biometric prompt must carry to CREATE the vault on first run.
     * Encrypt-mode against VK, for wrapping the freshly generated DK.
     */
    fun prepareInitCipher(ctx: Context): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vaultKey(ctx))
        return cipher
    }

    /**
     * Complete a first-run init with the cipher the biometric prompt authorised:
     * generate DK, wrap it with VK, store an empty vault.
     *
     * Throws if a vault already exists — re-initialising would orphan every stored
     * secret behind a DK nobody can recover.
     */
    fun initialize(ctx: Context, authorised: Cipher) {
        if (exists(ctx)) {
            throw PluginError("vault_exists", "a vault already exists; unlock it instead")
        }
        val dk = SecretKeySpec(randomBytes(DK_BYTES), "AES")
        val wrapped = authorised.doFinal(dk.encoded)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(F_DK_WRAPPED, b64(wrapped))
            .putString(F_BLOB, null)
            .putString(F_META, "{}")
            .apply()
        synchronized(this) {
            dataKey = dk
            session = mutableMapOf()
        }
        Log.i(TAG, "vault initialised (DK wrapped, session open)")
    }

    /**
     * Complete an unlock with the cipher the biometric prompt authorised. Unwraps DK,
     * decrypts the blob, opens the session.
     *
     * A failure here is loud: an authorised cipher that cannot unwrap means the vault
     * is corrupt or the key was invalidated (e.g. a new fingerprint was enrolled —
     * `setInvalidatedByBiometricEnrollment` destroys VK, which is the intended
     * behaviour and must not be papered over with an empty vault).
     */
    fun unlock(ctx: Context, authorised: Cipher) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(F_DK_WRAPPED, null)
            ?: throw PluginError("vault_missing", "no vault to unlock")
        val dk = try {
            SecretKeySpec(authorised.doFinal(b64d(wrapped)), "AES")
        } catch (e: Exception) {
            throw PluginError(
                "vault_unwrap_failed",
                "could not unwrap the data key (key invalidated or vault corrupt): ${e.message}"
            )
        }
        val blob = prefs.getString(F_BLOB, null)
        val map: MutableMap<String, String> = if (blob == null) mutableMapOf() else {
            try {
                val o = JSONObject(String(decrypt(dk, blob)))
                val m = mutableMapOf<String, String>()
                for (k in o.keys()) m[k] = o.getString(k)
                m
            } catch (e: Exception) {
                throw PluginError("vault_corrupt", "vault blob unreadable: ${e.message}")
            }
        }
        synchronized(this) {
            dataKey = dk
            session = map
        }
        Log.i(TAG, "vault unlocked; ${map.size} secret(s) in session")
    }

    /** Drop the session. Called when the app goes away, and on explicit lock. */
    @Synchronized
    fun lock() {
        session = null
        dataKey = null
        Log.i(TAG, "vault locked")
    }

    // --- reads/writes (require an open session) -------------------------------

    private fun openSession(): MutableMap<String, String> =
        session ?: throw PluginError("secret_locked", "the vault is locked; unlock the app first")

    /** The secret value. Throws `secret_locked` when the vault is shut. */
    fun get(ctx: Context, name: String): String {
        val s = openSession()
        return s[name] ?: throw PluginError("unknown_secret", "no secret named '$name'")
    }

    /** All secrets for the CRUD screen. Requires an open session. */
    fun all(): Map<String, String> = openSession().toMap()

    /**
     * Create or replace a secret, and persist. Requires an open session, so the
     * human's finger has already been on VK this session.
     */
    @Synchronized
    fun put(ctx: Context, name: String, value: String, docs: String = "", key: String = "") {
        requireName(name)
        if (value.isEmpty()) throw PluginError("bad_request", "secret value must not be empty")
        requireUrlOrEmpty("docs link", docs)
        requireUrlOrEmpty("key link", key)
        val s = openSession()
        val next = s.toMutableMap().apply { this[name] = value }
        // Read the index BEFORE writing anything: a corrupt index must fail before the
        // blob is touched, not after, or the two would disagree.
        val existing = readMeta(ctx)
        val hosts = existing[name]?.hosts ?: emptyList()
        val meta = existing.toMutableMap().apply { this[name] = SecretMeta(docs, key, hosts) }
        persist(ctx, next)
        writeMeta(ctx, meta)
        session = next
    }

    /**
     * Edit the links. Deliberately does NOT require an unlocked vault: the links are
     * plaintext, so demanding a fingerprint to fix a URL would be theatre.
     */
    @Synchronized
    fun setLinks(ctx: Context, name: String, docs: String, key: String) {
        requireUrlOrEmpty("docs link", docs)
        requireUrlOrEmpty("key link", key)
        val meta = readMeta(ctx)
        val cur = meta[name] ?: throw PluginError("unknown_secret", "no secret named '$name'")
        writeMeta(ctx, meta.toMutableMap().apply { this[name] = cur.copy(docs = docs, key = key) })
    }

    /**
     * Add an allowed host to one secret. Plaintext, so no fingerprint is needed — and
     * that is correct: a host rule is not a credential, and you often want to fix it
     * right after a call was refused.
     */
    @Synchronized
    fun allowHost(ctx: Context, name: String, host: String) {
        val rule = com.mido.phonebridge.plugins.secrets.HostAllowlist.requireRule(host)
        val meta = readMeta(ctx)
        val cur = meta[name] ?: throw PluginError("unknown_secret", "no secret named '$name'")
        if (cur.hosts.any { it.equals(rule, ignoreCase = true) }) return
        writeMeta(ctx, meta.toMutableMap().apply { this[name] = cur.copy(hosts = cur.hosts + rule) })
    }

    @Synchronized
    fun revokeHost(ctx: Context, name: String, host: String) {
        val meta = readMeta(ctx)
        val cur = meta[name] ?: throw PluginError("unknown_secret", "no secret named '$name'")
        writeMeta(
            ctx,
            meta.toMutableMap().apply {
                this[name] = cur.copy(hosts = cur.hosts.filterNot { it.equals(host, ignoreCase = true) })
            }
        )
    }

    /** Delete a secret. Its ciphertext goes with the next blob write — clean revocation. */
    @Synchronized
    fun delete(ctx: Context, name: String) {
        val s = openSession()
        if (!s.containsKey(name)) throw PluginError("unknown_secret", "no secret named '$name'")
        val next = s.toMutableMap().apply { remove(name) }
        val meta = readMeta(ctx).toMutableMap().apply { remove(name) }
        persist(ctx, next)
        writeMeta(ctx, meta)
        session = next
    }

    private fun persist(ctx: Context, map: Map<String, String>) {
        val dk = dataKey ?: throw PluginError("secret_locked", "the vault is locked")
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        val blob = encrypt(dk, o.toString().toByteArray())
        // The name index lives in F_META now (see writeMeta); this writes only the blob.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(F_BLOB, blob)
            .apply()
    }

    /** Wipe everything, including VK. A reset, not a lock. */
    @Synchronized
    fun destroy(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(VK_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "could not delete vault key: ${e.message}")
        }
        lock()
    }

    /** Reject names that cannot be a policy unit or a route segment. */
    private fun requireName(name: String) {
        if (!name.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) {
            throw PluginError(
                "bad_request",
                "secret name must be lowercase [a-z0-9_-], 1-64 chars, starting alphanumeric"
            )
        }
    }

    /**
     * A link is either absent or an https URL. https-only because these get opened in a
     * browser from the CRUD screen: allowing http would let a stored link be a cleartext
     * trip, and the two links exist to be trustworthy.
     */
    private fun requireUrlOrEmpty(field: String, url: String) {
        if (url.isEmpty()) return
        val parsed = try {
            java.net.URL(url)
        } catch (e: Exception) {
            throw PluginError("bad_request", "$field is not a valid URL: ${e.message}")
        }
        if (parsed.protocol != "https") {
            throw PluginError("bad_request", "$field must be https, got '${parsed.protocol}'")
        }
    }

    // --- crypto ---------------------------------------------------------------

    private fun encrypt(key: SecretKey, plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val ct = c.doFinal(plain)
        return b64(iv) + ":" + b64(ct)
    }

    private fun decrypt(key: SecretKey, blob: String): ByteArray {
        val parts = blob.split(":")
        if (parts.size != 2) throw IllegalStateException("malformed vault blob")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, b64d(parts[0])))
        return c.doFinal(b64d(parts[1]))
    }

    /**
     * VK, created on first use. Auth-per-use (timeout 0) so the key is only usable
     * behind a live finger; StrongBox where the device has it, TEE otherwise.
     */
    private fun vaultKey(ctx: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(VK_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val builder = KeyGenParameterSpec.Builder(
            VK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // The whole point: no cipher init without a fresh finger.
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        // setUnlockedDeviceRequired is API 28+; minSdk is 26, so it is added only where
        // the platform has it. Below 28 the auth-per-use requirement above is the
        // guarantee — this is belt, not the only brace.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // timeout 0 => auth-per-use (every single use needs authentication).
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        return generate(ks, builder, strongBox = true)
            ?: generate(ks, builder, strongBox = false)
            ?: throw PluginError("secure_storage_error", "could not create the vault key")
    }

    /**
     * Generate VK, optionally StrongBox-backed. Returns null when StrongBox is
     * requested and unavailable — the caller retries without it, which is a real
     * capability fallback (the key still lives in the TEE), not a silent downgrade.
     */
    private fun generate(
        ks: KeyStore,
        builder: KeyGenParameterSpec.Builder,
        strongBox: Boolean
    ): SecretKey? {
        return try {
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(builder.build())
            val k = gen.generateKey()
            Log.i(TAG, "vault key created (strongBox=$strongBox)")
            k
        } catch (e: StrongBoxUnavailableException) {
            Log.i(TAG, "StrongBox unavailable; falling back to TEE")
            null
        } catch (e: Exception) {
            if (strongBox) {
                Log.i(TAG, "StrongBox key failed (${e.javaClass.simpleName}); falling back to TEE")
                null
            } else {
                throw PluginError("secure_storage_error", "vault key generation failed: ${e.message}")
            }
        }
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
