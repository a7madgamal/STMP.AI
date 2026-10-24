package com.mido.phonebridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the test runtime IS Android's, which is the whole point of switching to Robolectric.
 *
 * Two releases shipped green tests for code that could not run on the phone, both because the test
 * JVM was not the device's runtime. This test makes the runtime itself an assertion: if the suite
 * ever drifts back to the reference `org.json`, this fails immediately with a clear message instead
 * of the difference surfacing as a phantom bug on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidJsonProofTest {

    @Test
    fun `optString on a JSON null returns the Android string, not the reference empty string`() {
        val o = JSONObject().put("fp", JSONObject.NULL)

        // THE assertion of this file. Android returns "null"; the reference jar returns "".
        // `SshConnection.fromJson` depends on knowing which, because a pin of "null" can never
        // match a fingerprint and turned every first contact into a phantom key mismatch.
        assertEquals("null", o.optString("fp"))
        assertTrue("isNull must be true on both implementations", o.isNull("fp"))
    }

    @Test
    fun `the runtime is Android, not a bare JVM`() {
        // android.os.Build is absent on a plain JVM; a value here means Robolectric's environment
        // is in play. This is what makes every other test in this module trustworthy.
        assertTrue("expected an Android SDK level", android.os.Build.VERSION.SDK_INT > 0)
    }

    @Test
    fun `an unpinned connection survives the round trip on the Android runtime`() {
        // The end-to-end consequence, on the real implementation.
        val conn = com.mido.phonebridge.plugins.ssh.SshConnection("h", 22, "u", "key", null)
        val back = com.mido.phonebridge.plugins.ssh.SshConnection.fromJson(conn.toJson().toString())
        assertNull("an unpinned connection must stay unpinned", back.hostKeyFingerprint)
    }
}
