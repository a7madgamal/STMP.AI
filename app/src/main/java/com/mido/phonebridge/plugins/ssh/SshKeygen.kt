package com.mido.phonebridge.plugins.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.KeyPairGenEdDSA
import com.jcraft.jsch.SignatureEdDSA
import com.mido.phonebridge.core.PluginError
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * JSch's two ed25519 hooks, backed by [Ed25519] instead of any JVM provider.
 *
 * **Why both are needed, and why the library's own are unusable on Android.**
 * `com.github.mwiede:jsch` is a Multi-Release JAR. Its Java-8 base classes are deliberate stubs
 * that throw, and Android never reads `META-INF/versions/`:
 *
 * | config key | JSch's default | on Android |
 * |---|---|---|
 * | `keypairgen.eddsa` | `jce.KeyPairGenEdDSA` | stub: every method throws "requires Java15+" |
 * | `ssh-ed25519` | `jce.SignatureEd25519` | stub: its CONSTRUCTOR throws "requires Java15+" |
 *
 * The `com.jcraft.jsch.bc.*` fallbacks need BouncyCastle, which is not on this classpath. So
 * generation alone was not enough: with generation fixed, a valid key still could not authenticate,
 * because the client could not produce a signature. Both hooks are replaced here.
 *
 * These adapters hold no logic of their own — they translate JSch's call order into [Ed25519],
 * which is where the RFC 8032 maths lives and where the test vectors point.
 */
class JschEd25519Generator : KeyPairGenEdDSA {

    private var seed: ByteArray? = null
    private var pub: ByteArray? = null

    override fun init(name: String, keylen: Int) {
        if (name != "Ed25519") {
            // Generating a different curve under an ed25519 label would produce a key that the
            // label lies about. Refuse instead.
            throw java.security.NoSuchAlgorithmException("unsupported ed25519 curve: $name")
        }
        val kp = Ed25519.generate()
        seed = kp.seed
        pub = kp.publicKey
    }

    /** JSch copies these into its own `prv_array` / `pub_array`. */
    override fun getPrv(): ByteArray = seed ?: throw IllegalStateException("init() was not called")

    override fun getPub(): ByteArray = pub ?: throw IllegalStateException("init() was not called")
}

/**
 * The signing hook, registered under the config key `ssh-ed25519`.
 *
 * JSch calls `init()`, `setPrvKey(prv_array)`, `update(message)`, `sign()`. `prv_array` for an
 * ed25519 key is the 32-byte seed (that is what the OpenSSH private-key format stores), and the
 * public key is derived when it was not supplied — so signing does not depend on `setPubKey` having
 * been called first.
 */
class JschEd25519Signature : SignatureEdDSA {

    private var seed: ByteArray? = null
    private var pub: ByteArray? = null
    private val message = ByteArrayOutputStream()

    override fun init() {
        seed = null
        pub = null
        message.reset()
    }

    override fun setPrvKey(prv: ByteArray) {
        // 32 is the seed. A 64-byte value is the seed followed by the public key (some encoders
        // concatenate them); take the seed half. Anything else cannot be an ed25519 private key.
        seed = when (prv.size) {
            32 -> prv.copyOf()
            64 -> prv.copyOfRange(0, 32)
            else -> throw IllegalArgumentException("ed25519 private key must be 32 or 64 bytes, got ${prv.size}")
        }
        message.reset()
    }

    override fun setPubKey(pubKey: ByteArray) {
        pub = pubKey.copyOf()
        message.reset()
    }

    override fun update(data: ByteArray) {
        message.write(data)
    }

    /**
     * The bare 64-byte signature (R ‖ S). JSch wraps it in the SSH signature blob itself
     * (`string ssh-ed25519` + `string <sig>`), so returning the raw bytes here is correct.
     */
    override fun sign(): ByteArray {
        val s = seed ?: throw IllegalStateException("sign() without a private key")
        // Derive the public half when the caller did not supply it: the signature commits to it, so
        // it cannot be skipped.
        val p = pub ?: Ed25519.fromSeed(s).publicKey
        return Ed25519.sign(s, p, message.toByteArray())
    }

    /**
     * Verify. **This is not optional, and it is not only for our own signatures.**
     *
     * JSch calls this while checking the SERVER's host-key signature during key exchange. When the
     * host key is ed25519 — which is the default on any current OpenSSH — a failure here aborts the
     * handshake before authentication is even attempted. Instrumenting the hook showed exactly
     * that: `setPubKey`, then `verify(83 bytes, msgLen=32) = false`, then `verify: false`, with
     * `sign()` never called at all.
     *
     * **The argument is the SSH signature BLOB, not 64 raw bytes.** For ed25519 that is
     * `string "ssh-ed25519" || string <64-byte sig>` = 4 + 11 + 4 + 64 = 83 bytes. Accepting only
     * the bare form is why every connection failed while the crypto itself was correct — the RFC
     * signature vectors passed the whole time.
     */
    override fun verify(sig: ByteArray): Boolean {
        val p = pub ?: return false
        val raw = unwrapSignature(sig) ?: return false
        return Ed25519.verify(p, message.toByteArray(), raw)
    }

    /**
     * Pull the 64-byte signature out of whatever JSch handed us.
     *
     * Handles the two shapes that occur in practice: the bare signature (some call sites pass it
     * after parsing) and the SSH blob (`string name || string sig`). Returns null for anything
     * else rather than guessing — a verifier that guesses is worse than one that refuses.
     */
    private fun unwrapSignature(blob: ByteArray): ByteArray? {
        if (blob.size == 64) return blob
        if (blob.size < 8) return null

        fun u32(at: Int): Int? {
            if (at + 4 > blob.size) return null
            return ((blob[at].toInt() and 0xff) shl 24) or
                ((blob[at + 1].toInt() and 0xff) shl 16) or
                ((blob[at + 2].toInt() and 0xff) shl 8) or
                (blob[at + 3].toInt() and 0xff)
        }

        var i = 0
        // Some callers prefix the whole blob with its own length; skip it only when it is exactly
        // consistent with the array we were given, so a real name-length is never mistaken for one.
        val outer = u32(0)
        if (outer != null && outer == blob.size - 4) i = 4

        val nameLen = u32(i) ?: return null
        i += 4
        if (nameLen <= 0 || i + nameLen > blob.size) return null
        i += nameLen

        val sigLen = u32(i) ?: return null
        i += 4
        if (sigLen != 64 || i + sigLen > blob.size) return null

        return blob.copyOfRange(i, i + sigLen)
    }
}

/**
 * Installs JSch's two ed25519 hooks, exactly once.
 *
 * **Why this is a separate object with an idempotent installer.** JSch resolves both hooks from
 * static config at the moment of use, and its defaults are the Android stubs (see [Ed25519]).
 * Registration therefore has to happen before ANY connection — not only before key generation. An
 * `init` block on [SshKeygen] alone was not enough: a run that imports a stored key never touches
 * the key-generation object, and the earlier same-process test only passed because it had just
 * generated a key in the same JVM. After a real app restart the hooks were unregistered and JSch
 * fell back to BouncyCastle classes that do not exist on the device.
 *
 * Both [SshKeygen] and [SshExec] call this, so whichever loads first is enough.
 */
internal object JschHooks {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            JSch.setConfig("keypairgen.eddsa", JschEd25519Generator::class.java.name)
            JSch.setConfig("ssh-ed25519", JschEd25519Signature::class.java.name)
            installed = true
        }
    }
}

/**
 * ed25519 keypairs, generated on the phone.
 *
 * **Why ed25519 rather than RSA.** Shorter, faster, and the type every current OpenSSH accepts
 * (`ssh-ed25519` has been the default since OpenSSH 9).
 *
 * **No passphrase.** The vault is the boundary: a key that needs a second unlock per call buys
 * nothing, because the private key is in memory during the call either way. The PEM written here
 * has an empty passphrase by design, not by omission.
 */
object SshKeygen {

    init {
        // Idempotent, and shared with SshExec — see JschHooks for why registration cannot live here
        // alone.
        JschHooks.install()
    }

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
            // `writePrivateKey` is built on `KeyPairEdDSA.getPrivateKey`, which the ed25519
            // subclass does not implement — it throws `UnsupportedOperationException`. The
            // OpenSSH-v1 writer encodes from `prv_array` directly, so it works for every key type,
            // and emits the `OPENSSH PRIVATE KEY` block that `KeyPair.load` reads back.
            //
            // The null passphrase is the documented no-passphrase case, not an oversight.
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

    /**
     * The OpenSSH `SHA256:` fingerprint of a PUBLIC key.
     *
     * **Why this is computed from the public half, not the private one.** A fingerprint is what
     * `ssh-keygen -lf` prints and what a server's `known_hosts` or `authorized_keys` comment
     * quotes, so it has to be derived the same way the tool derives it: SHA-256 over the decoded
     * key BLOB (the middle field), base64'd without padding. Hashing the PEM text would produce a
     * value that matches nothing anywhere.
     *
     * Returns null when the line cannot be parsed, rather than a placeholder: a wrong fingerprint
     * is worse than none, because it is the string a human compares.
     */
    fun fingerprintOf(publicKeyLine: String): String? = try {
        val fields = publicKeyLine.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size < 2) {
            null
        } else {
            val blob = android.util.Base64.decode(fields[1], android.util.Base64.DEFAULT)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            "SHA256:" + android.util.Base64.encodeToString(
                digest,
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
        }
    } catch (e: Exception) {
        null
    }
}
