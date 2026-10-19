package com.mido.phonebridge.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download's verification step is what stops a truncated or tampered model from
 * being mistaken for a provisioned one. It is pure apart from reading a file, so it is
 * asserted here — the alternative is discovering a bad model at inference time on a
 * device, where the failure is opaque.
 */
class ModelProvisionerVerifyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileWith(bytes: ByteArray): File = tmp.newFile("model.litertlm").apply {
        writeBytes(bytes)
    }

    /** sha256("hello") — a value the test can state without hashing anything itself. */
    private val hello = "hello".toByteArray()
    private val helloSha =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    @Test
    fun `a missing file is rejected`() {
        val missing = File(tmp.root, "nope.litertlm")
        assertNotNull(ModelProvisioner.verify(missing, hello.size.toLong(), helloSha))
    }

    @Test
    fun `an exact file passes`() {
        assertNull(ModelProvisioner.verify(fileWith(hello), hello.size.toLong(), helloSha))
    }

    @Test
    fun `a truncated file fails on size before hashing`() {
        val reason = ModelProvisioner.verify(fileWith("hell".toByteArray()), hello.size.toLong(), helloSha)
        assertEquals("size 4 != expected 5", reason)
    }

    @Test
    fun `a same-size file with different bytes fails on the digest`() {
        // The case a size check alone would wave through: right length, wrong content.
        val reason = ModelProvisioner.verify(fileWith("hallo".toByteArray()), hello.size.toLong(), helloSha)
        assertEquals("sha256 mismatch", reason)
    }

    @Test
    fun `an uppercase expected digest still matches`() {
        // The pinned constant is lowercase, but a digest compared case-sensitively is a
        // trap waiting for the next person who pastes one in from a different tool.
        assertNull(ModelProvisioner.verify(fileWith(hello), hello.size.toLong(), helloSha.uppercase()))
    }
}
