package com.mido.phonebridge.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The one place a stored Google credential is encrypted or decrypted.
 *
 * Extracted so there is exactly ONE construction in the app. Before this, `TokenStore` carried
 * its own copy; a second copy in `GoogleAccounts` would have drifted the moment either changed,
 * and crypto that exists in two places is crypto where one copy is eventually wrong.
 *
 * **One device key for every Google account**, under the alias the single-token build already
 * used. That is not laziness: the pre-existing ciphertext was written with that key, so a new
 * alias could not read it and every install would be forced to re-consent. It is also the
 * truthful model — the threat this key defends against is another app reading the file, and
 * having two keys does not reduce that at all.
 *
 * **AES-GCM with a per-encryption random IV, stored as `ivBase64:ciphertextBase64`.** GCM is
 * authenticated, so a flipped byte in the prefs fails the open rather than yielding a subtly
 * wrong token — which matters because a corrupted but "valid-looking" token would be sent to
 * Google and come back `invalid_grant`, pointing debugging at the wrong subsystem.
 *
 * **[keyProvider] is a TEST SEAM and is `internal` on purpose.** The JVM test runner has no
 * `AndroidKeyStore`, so a test that exercises the real storage path has to supply a key. It is
 * not a production fallback: it defaults to the Keystore, nothing in the app sets it, and the
 * only assignments are in `app/src/test`. A plaintext key is never reached by a shipped build
 * because no shipped code assigns it.
 */
object TokenCipher {

    /** The alias the single-token build used, kept so existing ciphertext still opens. */
    const val KEY_ALIAS = "pb_token_key"

    internal var keyProvider: () -> SecretKey = ::hardwareKey

    /** Encrypt one secret. The IV is fresh per call — never reused, which GCM requires. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val ct = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /**
     * Decrypt one secret, or throw.
     *
     * [who] names the account in the failure, because "could not read the credential" without
     * saying whose is the kind of message that sends someone to re-add the wrong account.
     */
    fun decrypt(blob: String, who: String): String {
        val parts = blob.split(":")
        if (parts.size != 2) {
            throw PluginError("auth_required", "the stored credential for $who is malformed")
        }
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct))
        } catch (e: Exception) {
            // NOT "not signed in": the account exists and its credential is unreadable, which a
            // re-authorisation fixes and a silent null would hide.
            throw PluginError(
                "auth_required",
                "the stored credential for $who could not be read " +
                    "(${e.javaClass.simpleName}); authorise that account again",
            )
        }
    }

    private fun hardwareKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Phase 2: `.setUserAuthenticationRequired(true)` would make the token
                // unreadable without a biometric. Not shipped; do not claim it is.
                .build(),
        )
        return gen.generateKey()
    }
}
