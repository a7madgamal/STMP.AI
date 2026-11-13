package com.mido.phonebridge.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a newline or tab in the endpoint is shown as a token so the subtitle stays one line`() {
        val subtitle = GateCopy.bioSubtitle("POST /a\nAPPROVED: yes\tok")
        assertEquals("POST /a\\u{000A}APPROVED: yes\\u{0009}ok", subtitle)
        assertFalse(subtitle.contains('\n') || subtitle.contains('\t'))
    }

    @Test
    fun `an over-long endpoint is capped with a count and never cuts a token or a surrogate pair`() {
        val long = "GET /" + "a".repeat(200)
        val subtitle = GateCopy.bioSubtitle(long)
        assertEquals("GET /" + "a".repeat(115) + "…(+85 chars)", subtitle)

        // A token that would straddle the cap is dropped whole.
        val straddle = "a".repeat(119) + "‮" + "bb"
        assertEquals("a".repeat(119) + "…(+10 chars)", GateCopy.bioSubtitle(straddle))

        // A surrogate pair at the cap is kept or dropped whole, never split.
        val pair = "a".repeat(120) + "😀"
        val cut = GateCopy.bioSubtitle(pair)
        assertEquals("a".repeat(120) + "…(+1 chars)", cut)
        assertTrue(cut.none { it.isHighSurrogate() })
    }

    @Test
    fun `a lone surrogate at the end of the endpoint is a token`() {
        assertEquals("GET /\\u{D800}", GateCopy.bioSubtitle("GET /\uD800"))
        assertEquals("GET /\\u{DFFF}", GateCopy.bioSubtitle("GET /\uDFFF"))
    }
}
