package com.mido.phonebridge

import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The id_token is the authority for "which account did this consent just create", because it
 * arrives in the same TLS response as the refresh token. These tests pin the two things that
 * must never slip: the issuer check and the presence of both identifiers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IdTokenIdentityTest {

    private fun token(payload: String) = "eyJhbGciOiJSUzI1NiJ9.$payload.c2ln"

    @Test
    fun `reads email and sub from a Google id_token`() {
        val id = AuthManager.identityFromIdToken(token("eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMTgwMTM5OTAwNDg3MjEwNzU3NzUiLCJlbWFpbCI6ImVuZy5hN21hZC5nYW1hbEBnbWFpbC5jb20iLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZX0"))
        assertEquals("eng.a7mad.gamal@gmail.com", id.email)
        assertEquals("118013990048721075775", id.sub)
    }

    @Test
    fun `refuses a token that is not issued by Google`() {
        try {
            AuthManager.identityFromIdToken(token("eyJpc3MiOiJodHRwczovL2V2aWwuZXhhbXBsZS5jb20iLCJzdWIiOiIxIiwiZW1haWwiOiJhQGIuYyJ9"))
            fail("a non-Google issuer must not be accepted")
        } catch (e: PluginError) {
            assertEquals("identity_lookup_failed", e.code)
            assertTrue(e.message!!.contains("evil.example.com"))
        }
    }

    @Test
    fun `refuses a token with no email`() {
        try {
            AuthManager.identityFromIdToken(token("eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxIn0"))
            fail("an account with no address cannot be named")
        } catch (e: PluginError) {
            assertEquals("identity_lookup_failed", e.code)
            assertTrue(e.message!!.contains("email"))
        }
    }

    @Test
    fun `refuses a token with no sub`() {
        try {
            AuthManager.identityFromIdToken(token("eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJlbWFpbCI6ImFAYi5jIn0"))
            fail("an account with no sub is unaddressable in the vault")
        } catch (e: PluginError) {
            assertEquals("identity_lookup_failed", e.code)
            assertTrue(e.message!!.contains("sub"))
        }
    }

    @Test
    fun `refuses something that is not a three-part JWT`() {
        try {
            AuthManager.identityFromIdToken("not-a-jwt")
            fail("a non-JWT must be refused, not parsed")
        } catch (e: PluginError) {
            assertEquals("identity_lookup_failed", e.code)
        }
    }
}
