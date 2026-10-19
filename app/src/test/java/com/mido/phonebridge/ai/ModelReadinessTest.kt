package com.mido.phonebridge.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readiness is a decision that gates what a caller may REACH — without the model the
 * bridge refuses every route — so it lives in a pure object and is asserted here rather
 * than discovered on a device. This is the same rule the host allowlist and the response
 * classifier were extracted for: a security-relevant decision that cannot be exercised
 * without a device is in the wrong place.
 */
class ModelReadinessTest {

    private val size = 497_516_544L

    @Test
    fun `absent model is not ready and says so`() {
        val s = ModelReadiness.of(exists = false, length = 0L, expectedSize = size)
        assertFalse(s.ready)
        assertTrue(s.detail.contains("not downloaded"))
    }

    @Test
    fun `exact size is ready`() {
        val s = ModelReadiness.of(exists = true, length = size, expectedSize = size)
        assertTrue(s.ready)
    }

    @Test
    fun `a partial download is not ready and reports how far it got`() {
        val s = ModelReadiness.of(exists = true, length = size / 2, expectedSize = size)
        assertFalse(s.ready)
        // The number is the actionable part: it tells the human the fetch stopped rather
        // than that the file is wrong.
        assertTrue(s.detail.contains("incomplete"))
    }

    @Test
    fun `an oversized file is not ready`() {
        val s = ModelReadiness.of(exists = true, length = size + 1, expectedSize = size)
        assertFalse(s.ready)
        assertTrue(s.detail.contains("wrong file"))
    }

    @Test
    fun `a one-byte-short file is not ready`() {
        // The realistic truncation: a download that stopped one byte early must NOT pass,
        // or the runtime fails opaquely at inference time.
        val s = ModelReadiness.of(exists = true, length = size - 1, expectedSize = size)
        assertFalse(s.ready)
    }
}

/**
 * The exemption list is the one way a not-ready bridge still answers, so it is a rule
 * worth asserting: it must cover the diagnosis routes and nothing else.
 */
class ModelReadinessServesTest {

    @Test
    fun `a ready model serves every plugin`() {
        assertTrue(ModelReadiness.serves(ready = true, pluginId = "gmail"))
        assertTrue(ModelReadiness.serves(ready = true, pluginId = "debug"))
        assertTrue(ModelReadiness.serves(ready = true, pluginId = "calendar"))
    }

    @Test
    fun `a not-ready model refuses a normal plugin`() {
        assertFalse(ModelReadiness.serves(ready = false, pluginId = "gmail"))
        assertFalse(ModelReadiness.serves(ready = false, pluginId = "notifications"))
        assertFalse(ModelReadiness.serves(ready = false, pluginId = "calendar"))
    }

    @Test
    fun `the diagnosis routes stay reachable when the model is not ready`() {
        // Otherwise a not-ready bridge is undiagnosable from outside — the exact dead end
        // /debug was built to prevent.
        assertTrue(ModelReadiness.serves(ready = false, pluginId = "debug"))
        assertTrue(ModelReadiness.serves(ready = false, pluginId = "logs"))
    }

    @Test
    fun `a plugin that merely contains the word debug is not exempt`() {
        // Guards against a future `contains` check replacing the set membership.
        assertFalse(ModelReadiness.serves(ready = false, pluginId = "debug-tools"))
        assertFalse(ModelReadiness.serves(ready = false, pluginId = "my-debug"))
    }
}
