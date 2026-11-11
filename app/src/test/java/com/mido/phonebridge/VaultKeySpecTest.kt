package com.mido.phonebridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.VaultKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guard for how the vault key is specified and generated.
 *
 * `AndroidKeyStore` does not exist under Robolectric, so the generator is a fake that behaves
 * like a device: one without StrongBox throws [StrongBoxUnavailableException] for any spec that
 * asks for StrongBox. The bug this pins: the TEE fallback reused the StrongBox attempt's
 * builder, still asked for StrongBox, and every such device ended in "could not create the
 * vault key".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultKeySpecTest {

    private val alias = "test_vault_key"

    /** A device with no StrongBox: StrongBox specs fail, TEE specs succeed. */
    private fun noStrongBoxDevice(seen: MutableList<KeyGenParameterSpec>): (KeyGenParameterSpec) -> String = { spec ->
        seen.add(spec)
        if (spec.isStrongBoxBacked) throw StrongBoxUnavailableException("no StrongBox")
        "tee-key"
    }

    @Test
    fun `a device without StrongBox gets a TEE key`() {
        val seen = mutableListOf<KeyGenParameterSpec>()

        val key = VaultKeySpec.generate(alias, noStrongBoxDevice(seen))

        assertEquals("tee-key", key)
        assertEquals(2, seen.size)
        assertTrue("first attempt asks for StrongBox", seen[0].isStrongBoxBacked)
        assertFalse("the fallback must not ask for StrongBox", seen[1].isStrongBoxBacked)
    }

    @Test
    fun `a device with StrongBox gets a StrongBox key on the first attempt`() {
        val seen = mutableListOf<KeyGenParameterSpec>()

        val key = VaultKeySpec.generate(alias) { spec -> seen.add(spec); "sb-key" }

        assertEquals("sb-key", key)
        assertEquals(1, seen.size)
        assertTrue(seen[0].isStrongBoxBacked)
    }

    @Test
    fun `the fallback keeps every auth requirement of the StrongBox spec`() {
        val sb = VaultKeySpec.spec(alias, strongBox = true)
        val tee = VaultKeySpec.spec(alias, strongBox = false)

        for (spec in listOf(sb, tee)) {
            assertTrue(spec.isUserAuthenticationRequired)
            assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
            assertTrue(spec.isInvalidatedByBiometricEnrollment)
            assertTrue(spec.isUnlockedDeviceRequired)
            assertEquals(256, spec.keySize)
            assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
        }
    }

    @Test
    fun `when both attempts fail the error names the real causes`() {
        try {
            VaultKeySpec.generate<String>(alias) { spec ->
                if (spec.isStrongBoxBacked) throw StrongBoxUnavailableException("no StrongBox")
                throw IllegalStateException("At least one biometric must be enrolled")
            }
            fail("expected PluginError")
        } catch (e: PluginError) {
            assertEquals("secure_storage_error", e.code)
            val msg = e.message.orEmpty()
            assertTrue(msg, msg.contains("IllegalStateException: At least one biometric must be enrolled"))
            assertTrue(msg, msg.contains("StrongBoxUnavailableException"))
        }
    }

    @Test
    @Config(sdk = [27])
    fun `below API 28 there is a single TEE attempt`() {
        val seen = mutableListOf<KeyGenParameterSpec>()

        val key = VaultKeySpec.generate(alias) { spec -> seen.add(spec); "tee-key" }

        assertEquals("tee-key", key)
        assertEquals(1, seen.size)
    }
}
