package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval modal names the Google account it is about to act on.
 *
 * That line is drawn only when the endpoint is a Google route, and the gate passes the endpoint
 * as `"$method $path"` — so the predicate has to see past the method. It did not, and every
 * Gmail/Calendar/Drive modal silently omitted the account: the human approved an action without
 * being told whose mailbox it touched.
 */
class IsGoogleRequestTest {

    private fun isGoogle(endpoint: String): Boolean {
        val path = endpoint.substringAfter(' ', endpoint).substringBefore('?')
        return path.startsWith("/gmail") || path.startsWith("/calendar") || path.startsWith("/drive")
    }

    @Test
    fun `a method-prefixed gmail endpoint is a google request`() {
        assertTrue(isGoogle("GET /gmail/messages"))
        assertTrue(isGoogle("POST /gmail/messages/send"))
        assertTrue(isGoogle("GET /gmail/messages/abc"))
    }

    @Test
    fun `calendar and drive are google requests in either method`() {
        assertTrue(isGoogle("GET /calendar/events"))
        assertTrue(isGoogle("DELETE /drive/files/xyz"))
        assertTrue(isGoogle("PUT /drive/files/xyz/content"))
    }

    @Test
    fun `a bare path with no method still matches`() {
        assertTrue(isGoogle("/gmail/profile"))
    }

    @Test
    fun `other routes are not google requests`() {
        assertFalse(isGoogle("POST /ssh/evox2"))
        assertFalse(isGoogle("GET /notifications"))
        assertFalse(isGoogle("POST /oauth/name/call"))
        assertFalse(isGoogle("GET /help"))
    }

    @Test
    fun `a path that merely CONTAINS gmail is not a google request`() {
        assertFalse(isGoogle("GET /secrets/gmail-key"))
        assertFalse(isGoogle("GET /debug?route=/gmail/messages"))
    }
}
