package com.mido.phonebridge.plugins.ssh

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ed25519 key generation and signing, implemented here rather than delegated.
 *
 * **Why this is not `KeyPairGenerator.getInstance("Ed25519")`, and not JSch's own crypto.**
 * JSch ships as a **Multi-Release JAR** whose Java-8 base classes are deliberate stubs:
 *
 * ```
 * com/jcraft/jsch/jce/KeyPairGenEdDSA.class    -> every method throws "requires Java15+"
 * com/jcraft/jsch/jce/SignatureEd25519.class   -> CONSTRUCTOR throws "requires Java15+"
 * META-INF/versions/15/...                     -> the real implementations
 * ```
 *
 * Android's runtime does not read `META-INF/versions/`, so it always loads the stubs. JSch's
 * fallback is the `com.jcraft.jsch.bc.*` family, which needs BouncyCastle — not on this app's
 * classpath. The platform JCA provider is not a dependable substitute either: `Ed25519` arrived in
 * API 33 and this app's `minSdk` is 26, and the vendor build's provider list is not something we
 * can verify remotely.
 *
 * So ed25519 is implemented directly from RFC 8032. The only dependency is `MessageDigest`, which
 * is present on every Android build. Correctness is pinned by the RFC's published test vectors for
 * both key derivation and signatures — a wrong point encoding produces a well-formed key that
 * silently fails to authenticate, which is precisely the failure that burned four releases.
 */
object Ed25519 {

    /**
     * The constant 2, spelled out.
     *
     * `BigInteger.TWO` is a static field added in **API 33**, and this object's properties
     * initialise at class-load — so on Android 8 through 12 the reference threw
     * `NoSuchFieldError` before any runtime version check could help. That meant ed25519 key
     * generation and signing were broken on every device below API 33, which is most of them.
     * Caught by Android Lint's NewApi check in CI (2026-11-03); it had been shipping because
     * the local build never ran `lint`.
     */
    private val TWO: BigInteger = BigInteger.valueOf(2)

    /** 2^255 - 19, the field prime. */
    private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** Curve constant d = -121665/121666 mod p. */
    private val D: BigInteger = run {
        val num = BigInteger.valueOf(-121665).mod(P)
        val den = BigInteger.valueOf(121666).mod(P)
        num.multiply(den.modInverse(P)).mod(P)
    }

    /** 2*d, precomputed: the extended-coordinate addition uses it on every step. */
    private val D2: BigInteger = D.shiftLeft(1).mod(P)

    /** The group order L = 2^252 + 27742317777372353535851937790883648493. */
    internal val L: BigInteger = TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** Base point y = 4/5, and the matching x (the root with the low bit clear). */
    private val BY: BigInteger = BigInteger(
        "46316835694926478169428394003475163141307993866256225615783033603165251855960"
    )
    internal val BX: BigInteger = BigInteger(
        "15112221349535400772501151409588531511454012693041857206046113283949847762202"
    )

    /** A generated keypair. `seed` is what OpenSSH stores as the private key. */
    class Keypair(val seed: ByteArray, val publicKey: ByteArray)

    fun generate(random: SecureRandom = SecureRandom()): Keypair {
        val seed = ByteArray(32)
        random.nextBytes(seed)
        return fromSeed(seed)
    }

    /**
     * Derive the keypair for a known seed — RFC 8032 §5.1.5.
     *
     * Hash the seed, clamp the first half into a scalar, multiply the base point by it. The seed is
     * what gets stored; the scalar is derived on demand so there is only one representation.
     */
    fun fromSeed(seed: ByteArray): Keypair {
        require(seed.size == 32) { "an ed25519 seed must be exactly 32 bytes, got ${seed.size}" }
        val (scalar, _) = expand(seed)
        val a = leToInt(scalar)
        val point = multiply(basePoint(), a)
        return Keypair(seed, encode(point))
    }

    /**
     * Sign [message] — RFC 8032 §5.1.6.
     *
     *   r = SHA-512(prefix ‖ M) mod L,  R = rB
     *   k = SHA-512(R ‖ A ‖ M) mod L
     *   S = (r + k·a) mod L          signature = R ‖ S
     *
     * [publicKey] is passed in because callers already hold it; recomputing it here would create a
     * second source of truth for the same 32 bytes.
     */
    fun sign(seed: ByteArray, publicKey: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes, got ${seed.size}" }
        require(publicKey.size == 32) { "public key must be 32 bytes, got ${publicKey.size}" }

        val (scalar, prefix) = expand(seed)
        val a = leToInt(scalar)

        val r = leToInt(sha512(prefix + message)).mod(L)
        val rEncoded = encode(multiply(basePoint(), r))

        val k = leToInt(sha512(rEncoded + publicKey + message)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)

        return rEncoded + intToLe(s, 32)
    }

    // --- internals (shared with the JSch adapters) ---

    /** SHA-512 of the seed, split into the clamped scalar half and the nonce prefix. */
    /**
     * Verify a 64-byte signature over [message].
     *
     * Not used by the app's outbound connections (they authenticate with their own key), but the
     * adapters expose it and it is the natural way to test signing end to end.
     *
     *   check S < L, compute k = SHA-512(R ‖ A ‖ M) mod L, and test [S]B = R + [k]A
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false

        val rEncoded = signature.copyOfRange(0, 32)
        val s = leToInt(signature.copyOfRange(32, 64))
        // A non-canonical S is the classic ed25519 malleability bug; reject rather than normalise.
        if (s >= L) return false

        val a = decode(publicKey) ?: return false
        val r = decode(rEncoded) ?: return false

        val k = leToInt(sha512(rEncoded + publicKey + message)).mod(L)

        // [S]B must equal R + [k]A.
        //
        // This is the whole verification equation, and it is easy to get wrong in a way that still
        // compiles and still produces a point: `[S]B - [k]A = R` is the same statement, but
        // implementing "minus" needs the curve inverse, and getting THAT wrong (or negating the
        // wrong coordinate) silently breaks verification while leaving signing correct. Signing
        // matched the RFC vectors while this line was wrong, which is exactly how it surfaced.
        // So: no negation, just add [k]A to R.
        val right = add(r, multiply(a, k))
        val left = multiply(basePoint(), s)
        // `contentEquals`, not `==`: array equality in Kotlin is reference equality, so `==` would
        // compare two distinct ByteArray objects and be false for every valid signature. The
        // compiler's warning caught this; a test that only asserted "rejects bad signatures" would
        // have passed with a verifier that rejected everything.
        return encode(left).contentEquals(encode(right))
    }

    /**
     * Decompress a 32-byte public key back to a point, or null when it is not on the curve.
     *
     * Needed only by [verify]; generation and signing work in the forward direction.
     */
    private fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val yBytes = bytes.copyOf()
        val xOdd = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = leToInt(yBytes)
        if (y >= P) return null

        // x² = (y² - 1) / (d·y² + 1)
        val y2 = y.multiply(y).mod(P)
        val num = y2.subtract(BigInteger.ONE).mod(P)
        val den = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (den.signum() == 0) return null
        var x = num.multiply(den.modInverse(P)).mod(P)

        // sqrt via the 2^255-19 shortcut: x = x^((p+3)/8), then a correction by sqrt(-1).
        val exp = P.add(BigInteger.valueOf(3)).shiftRight(3)
        x = x.modPow(exp, P)
        val check = x.multiply(x).mod(P)
        if (check != num.multiply(den.modInverse(P)).mod(P)) {
            val sqrtMinus1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
            x = x.multiply(sqrtMinus1).mod(P)
        }
        if (x.multiply(x).mod(P) != num.multiply(den.modInverse(P)).mod(P)) return null

        if (x.testBit(0) != xOdd) x = P.subtract(x).mod(P)
        if (x.signum() == 0 && xOdd) return null

        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun expand(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val h = sha512(seed)
        val scalar = h.copyOfRange(0, 32)
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = (scalar[31].toInt() and 127).toByte()
        scalar[31] = (scalar[31].toInt() or 64).toByte()
        return scalar to h.copyOfRange(32, 64)
    }

    private fun sha512(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(bytes)

    internal fun basePoint() = Point(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))

    /** Extended twisted-Edwards coordinates: x = X/Z, y = Y/Z, T = XY/Z. */
    internal class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    /** The standard "add-2008-hwcd-3" formulas for a = -1, in extended coordinates. */
    internal fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    /** Double-and-add. Not constant-time; this signs a key the human explicitly asked to use. */
    internal fun multiply(base: Point, scalar: BigInteger): Point {
        var result = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var addend = base
        for (i in 0 until scalar.bitLength()) {
            if (scalar.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    /**
     * Compressed encoding: y little-endian in 32 bytes, with the top bit of the last byte carrying
     * the low bit of x. Getting this backwards yields a valid-looking key that cannot authenticate.
     */
    internal fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = intToLe(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    internal fun leToInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    internal fun intToLe(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray() // big-endian, possibly with a leading sign byte
        val out = ByteArray(size)
        for (i in 0 until size) {
            val src = big.size - 1 - i
            out[i] = if (src >= 0) big[src] else 0
        }
        return out
    }
}
