package com.mido.phonebridge.gate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GateCopyBioSubtitleTest {
    @Test
    fun `a right-to-left override in the endpoint is written out in the subtitle`() {
        val endpoint = "POST /files/" + String(Character.toChars(0x202E)) + "gpj.exe"
        assertEquals("POST /files/\\u{202E}gpj.exe", GateCopy.bioSubtitle(endpoint))
    }

    @Test
    fun `a missing endpoint falls back and a plain one is unchanged`() {
        assertEquals(GateCopy.BIO_SUBTITLE_FALLBACK, GateCopy.bioSubtitle(null))
        assertEquals("GET /status", GateCopy.bioSubtitle("GET /status"))
    }
}
