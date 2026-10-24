package com.mido.phonebridge

import com.mido.phonebridge.plugins.ssh.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ed25519 key generation, checked against the RFC's own numbers.
 *
 * **Why the vectors matter more than any assertion about shape.** A wrong point encoding, a
 * reversed byte order, or a bad clamp all produce a 32-byte "public key" that looks completely
 * valid and simply never authenticates. Every earlier failure in this feature was found only after
 * it broke on the phone, so "it generated something" is not evidence. RFC 8032 §7.1 publishes a
 * seed and the exact public key it must produce; matching those proves the maths.
 *
 * The three vectors below are TEST 1, TEST 2 and TEST 3 from that section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Ed25519Test {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun toHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    @Test
    fun `RFC 8032 test vector 1`() {
        assertVector(
            seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            expectedPublic = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        )
    }

    @Test
    fun `RFC 8032 test vector 2`() {
        assertVector(
            seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            expectedPublic = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        )
    }

    @Test
    fun `RFC 8032 test vector 3`() {
        assertVector(
            seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            expectedPublic = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
        )
    }

    private fun assertVector(seed: String, expectedPublic: String) {
        val kp = Ed25519.fromSeed(hex(seed))
        assertEquals("the seed must be stored verbatim", seed, toHex(kp.seed))
        assertEquals(
            "derived public key does not match RFC 8032",
            expectedPublic,
            toHex(kp.publicKey)
        )
    }

    @Test
    fun `the seed is always 32 bytes and the public key is always 32 bytes`() {
        repeat(20) {
            val kp = Ed25519.generate()
            assertEquals(32, kp.seed.size)
            assertEquals(32, kp.publicKey.size)
        }
    }

    @Test
    fun `generated keys differ`() {
        // A generator returning a constant would still pass every shape check above.
        val seen = HashSet<String>()
        repeat(20) { seen.add(toHex(Ed25519.generate().publicKey)) }
        assertEquals("20 generated public keys must be distinct", 20, seen.size)
    }

    @Test
    fun `a wrong seed length is refused`() {
        val threw = try {
            Ed25519.fromSeed(ByteArray(31))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("expected IllegalArgumentException for a 31-byte seed", threw)
    }

    @Test
    fun `the clamping bits are applied as the RFC specifies`() {
        // Deterministic from a known seed: the public key must be stable across runs, and the
        // clamp must produce a scalar that still lands on a valid point (checked by the vectors).
        val a = Ed25519.fromSeed(ByteArray(32) { 7 })
        val b = Ed25519.fromSeed(ByteArray(32) { 7 })
        assertEquals(toHex(a.publicKey), toHex(b.publicKey))
        assertFalse("the derived key must not be all zeroes", a.publicKey.all { it == 0.toByte() })
    }

    // --- signatures ---

    @Test
    fun `RFC 8032 signature test vector 1`() {
        // The empty message. This is the vector that catches a wrong R, a wrong k, or a
        // signature assembled in the wrong order — all of which produce 64 valid-looking bytes.
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val kp = Ed25519.fromSeed(seed)
        assertEquals(pub, toHex(kp.publicKey))

        val sig = Ed25519.sign(kp.seed, kp.publicKey, ByteArray(0))
        assertEquals(
            "signature does not match RFC 8032",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            toHex(sig)
        )
        assertTrue("our own verifier must accept it", Ed25519.verify(kp.publicKey, ByteArray(0), sig))
    }

    @Test
    fun `RFC 8032 signature test vector 2 - one byte message`() {
        val seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val kp = Ed25519.fromSeed(seed)
        val message = hex("72")

        val sig = Ed25519.sign(kp.seed, kp.publicKey, message)
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            toHex(sig)
        )
        assertTrue(Ed25519.verify(kp.publicKey, message, sig))
    }

    @Test
    fun `a tampered message or signature fails verification`() {
        // Without this, a verifier that accepts everything would pass the vector tests above only
        // by coincidence of never being asked to reject.
        val kp = Ed25519.generate()
        val message = "hello phonebridge".toByteArray()
        val sig = Ed25519.sign(kp.seed, kp.publicKey, message)
        assertTrue(Ed25519.verify(kp.publicKey, message, sig))

        assertFalse("a changed message must not verify", Ed25519.verify(kp.publicKey, "hello phonebridgE".toByteArray(), sig))

        val tampered = sig.copyOf()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        assertFalse("a changed signature must not verify", Ed25519.verify(kp.publicKey, message, tampered))

        val other = Ed25519.generate()
        assertFalse("another key must not verify it", Ed25519.verify(other.publicKey, message, sig))
    }
}
