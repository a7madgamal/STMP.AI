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
 * The readiness rule now follows the route's own `aiSummary` flag rather than a plugin
 * name, so the exemption and the thing it exempts cannot drift. Asserted here because it
 * decides whether a request may run at all.
 */
class ModelReadinessServesTest {

    @Test
    fun `a ready model serves every route`() {
        assertTrue(ModelReadiness.serves(ready = true, aiRequired = true))
        assertTrue(ModelReadiness.serves(ready = true, aiRequired = false))
    }

    @Test
    fun `a not-ready model refuses a route that uses the AI`() {
        assertFalse(ModelReadiness.serves(ready = false, aiRequired = true))
    }

    @Test
    fun `a route that does not use the AI is served even when the model is not ready`() {
        // Otherwise a not-ready bridge is undiagnosable from outside — the exact dead end
        // /debug was built to prevent. This is the half that was missing in 0.19.0: the
        // route was SERVED but still ran inference to build its card, so it failed anyway
        // (reported on device: "debug call waited for the model").
        assertTrue(ModelReadiness.serves(ready = false, aiRequired = false))
    }

    @Test
    fun `requiresAi is the route flag, inverted for no one`() {
        assertTrue(ModelReadiness.requiresAi(aiSummary = true))
        assertFalse(ModelReadiness.requiresAi(aiSummary = false))
    }
}
