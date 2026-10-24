package com.mido.phonebridge.plugins.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.mido.phonebridge.core.PluginError
import java.io.ByteArrayOutputStream

/**
 * ed25519 keypairs, generated on the phone.
 *
 * Mido, 2026-10-21: *"generate, or use one if already generated"* and *"ed25519 keys"*.
 *
 * **Why ed25519 rather than RSA.** Shorter, faster, and the type every current OpenSSH accepts
 * (`ssh-ed25519` has been the default since OpenSSH 9). JSch 2.28.8 carries a pure-Java
 * `KeyPairEd25519`, so no BouncyCastle dependency is needed for generation — verified by inspecting
 * the jar, though it is still proven only when a real key is generated on the device.
 *
 * **No passphrase.** The vault is the boundary: a key that needs a second unlock per call buys
 * nothing, because the private key is in memory during the call either way. The PEM written here
 * has an empty passphrase by design, not by omission.
 */
object SshKeygen {

    /** A fresh keypair. The private half goes straight to the vault and is never displayed. */
    data class Generated(val privateKey: String, val publicKey: String)

    /**
     * Generate an ed25519 keypair.
     *
     * The private key is PEM (`OPENSSH PRIVATE KEY`), which is what JSch reads back. The public key
     * is one OpenSSH line — `ssh-ed25519 AAAA... comment` — ready to paste into `authorized_keys`.
     */
    fun generate(comment: String = "phonebridge"): Generated {
        val jsch = JSch()
        val kpg = try {
            KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        } catch (e: Throwable) {
            // A generation failure is a real fault, not a fallback case: silently producing an RSA
            // key instead would give the user a different key type than the one they asked for.
            throw PluginError(
                "ssh_keygen_failed",
                "could not generate an ed25519 key on this device: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        try {
            // `writeOpenSSHv1PrivateKey`, NOT `writePrivateKey`.
            //
            // On JSch 2.28.8 an ed25519 keypair is a `KeyPairEdDSA`, and that class does not
            // implement `getPrivateKey()` — the method `writePrivateKey` is built on. Calling it
            // throws `UnsupportedOperationException` on the first ed25519 key, which is every key
            // this app creates. Verified on the JVM against the same jar:
            //   KeyPairEdDSA.getPrivateKey(KeyPairEdDSA.java:75)
            //   KeyPair.writePrivateKey(KeyPair.java:163)
            // The OpenSSH-v1 writer encodes the key from `prv_array` directly, so it works for
            // ed25519 (and RSA/ECDSA too), and emits the `OPENSSH PRIVATE KEY` block that
            // `KeyPair.load` reads back — proven by a round-trip and by `JSch.addIdentity`.
            //
            // The null passphrase is the documented no-passphrase case, not an oversight: the
            // vault is the boundary (see the class comment).
            val priv = ByteArrayOutputStream()
                .also { kpg.writeOpenSSHv1PrivateKey(it, null) }
                .toString(Charsets.UTF_8.name())
            val pub = ByteArrayOutputStream().also { kpg.writePublicKey(it, comment) }
                .toString(Charsets.UTF_8.name())
                .trim()
            if (!priv.contains("PRIVATE KEY")) {
                throw PluginError("ssh_keygen_failed", "generated key was not PEM-encoded")
            }
            return Generated(priv, pub)
        } catch (e: PluginError) {
            throw e
        } catch (e: Exception) {
            throw PluginError("ssh_keygen_failed", "key generation failed: ${e.message}")
        } finally {
            // Zeroes the key material JSch holds. A keypair left in memory after the call is
            // exactly the kind of copy this design is trying to avoid.
            try {
                kpg.dispose()
            } catch (ignored: Exception) {
                // Nothing useful to do; the object is unreachable after this anyway.
            }
        }
    }

    /**
     * The public half of an existing private key, for display.
     *
     * Needed because a stored connection keeps only the private key — the public line is derived
     * on demand, so there is one source of truth and no chance of the two disagreeing.
     *
     * Returns null when the stored key cannot be parsed. That is not fatal for display: the SSH
     * screen shows "public key unavailable" rather than hiding the whole connection.
     */
    fun publicKeyOf(privateKey: String, comment: String = "phonebridge"): String? = try {
        val jsch = JSch()
        val kpg = KeyPair.load(jsch, privateKey.toByteArray(Charsets.UTF_8), null)
        try {
            ByteArrayOutputStream().also { kpg.writePublicKey(it, comment) }
                .toString(Charsets.UTF_8.name())
                .trim()
        } finally {
            runCatching { kpg.dispose() }
        }
    } catch (e: Exception) {
        null
    }

    /** The key type, e.g. `ssh-ed25519`, for the connection card. Null when unreadable. */
    fun keyTypeOf(privateKey: String): String? = try {
        val jsch = JSch()
        val kpg = KeyPair.load(jsch, privateKey.toByteArray(Charsets.UTF_8), null)
        try {
            // KeyPair's constants are the COARSE types (one ECDSA value for all three curves) —
            // the per-curve constants live on HostKey. Reporting "ecdsa" for any curve is a
            // slightly less precise label rather than a wrong one, and this is display only.
            when (kpg.keyType) {
                KeyPair.ED25519 -> "ssh-ed25519"
                KeyPair.ED448 -> "ssh-ed448"
                KeyPair.RSA -> "ssh-rsa"
                KeyPair.DSA -> "ssh-dss"
                KeyPair.ECDSA -> "ecdsa"
                else -> "unknown"
            }
        } finally {
            runCatching { kpg.dispose() }
        }
    } catch (e: Exception) {
        null
    }
}
