package com.mido.phonebridge

import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.oauth.OauthClientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The guard on importing a downloaded Google OAuth client file.
 *
 * **Why this is tested and not just exercised on a phone.** The parser is the only new code that
 * touches untrusted input, and every rule in it is a decision someone could reasonably get wrong
 * later: refuse a missing mandatory member rather than defaulting it, re-check https on a URL that
 * came from Google, name the field but never echo a value. A rule that only runs on a device is a
 * rule nobody verifies.
 *
 * `org.json` needs the Android runtime, hence Robolectric — the same reason `AndroidJsonProofTest`
 * exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OauthClientJsonTest {

    /** The exact shape Google's client-library docs publish for a web application. */
    private val webFile = """
        {
          "web": {
            "client_id": "503841343554-j8d5darc70pivko0e0ud6tf8tus811a7.apps.googleusercontent.com",
            "client_secret": "GOCSPX-example-secret",
            "redirect_uris": ["https://developers.google.com/oauthplayground"],
            "auth_uri": "https://accounts.google.com/o/oauth2/auth",
            "token_uri": "https://oauth2.googleapis.com/token"
          }
        }
    """.trimIndent()

    /** And for an installed (desktop) application, which uses different redirect URIs. */
    private val installedFile = """
        {
          "installed": {
            "client_id": "837647042410-75ifg.apps.googleusercontent.com",
            "client_secret": "asdlkfjaskd",
            "redirect_uris": ["http://localhost", "urn:ietf:wg:oauth:2.0:oob"],
            "auth_uri": "https://accounts.google.com/o/oauth2/auth",
            "token_uri": "https://oauth2.googleapis.com/token"
          }
        }
    """.trimIndent()

    @Test
    fun `a web client file yields all three values`() {
        val found = OauthClientJson.parse(webFile)
        assertEquals(
            "503841343554-j8d5darc70pivko0e0ud6tf8tus811a7.apps.googleusercontent.com",
            found.clientId,
        )
        assertEquals("GOCSPX-example-secret", found.clientSecret)
        assertEquals("https://oauth2.googleapis.com/token", found.tokenUri)
    }

    @Test
    fun `an installed client file parses too`() {
        val found = OauthClientJson.parse(installedFile)
        assertEquals("837647042410-75ifg.apps.googleusercontent.com", found.clientId)
        assertEquals("asdlkfjaskd", found.clientSecret)
    }

    @Test
    fun `the derived name is a legal credential name and is not the project number`() {
        val found = OauthClientJson.parse(webFile)
        // Must satisfy SecretRules: [a-z0-9_-], 1-64 chars, starting alphanumeric.
        assertTrue("name must be non-empty", found.name.isNotEmpty())
        assertTrue(
            "derived name '${found.name}' must be a legal credential name",
            found.name.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")),
        )
        assertFalse(
            "the project number is the least distinctive part of the client id",
            found.name.startsWith("503841343554"),
        )
    }

    @Test
    fun `the registered redirect URIs are still parsed, though nothing redirects any more`() {
        // The URIs are no longer used to mint a token — CONNECT runs in-app through Identity
        // Services and never leaves the app on a redirect. They are still parsed because a client
        // file carries them and dropping the field would lose information the human can see.
        assertTrue(
            "a web client file lists its redirect URIs",
            OauthClientJson.parse(webFile).redirectUris.isNotEmpty(),
        )
    }

    @Test
    fun `a missing mandatory member is refused, and the message names it`() {
        val noSecret = """
            { "web": {
                "client_id": "x.apps.googleusercontent.com",
                "redirect_uris": ["https://developers.google.com/oauthplayground"],
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token"
            } }
        """.trimIndent()
        try {
            OauthClientJson.parse(noSecret)
            fail("must refuse a file without client_secret")
        } catch (e: PluginError) {
            assertEquals("oauth_import_failed", e.code)
            assertTrue(
                "the message must name the missing member: ${e.message}",
                e.message!!.contains("client_secret"),
            )
        }
    }

    @Test
    fun `every missing member is reported at once, not one per attempt`() {
        try {
            OauthClientJson.parse("""{ "web": { "client_id": "x" } }""")
            fail("must refuse")
        } catch (e: PluginError) {
            // One round trip per missing field would be four identical dialogues.
            val msg = e.message!!
            assertTrue(msg.contains("client_secret"))
            assertTrue(msg.contains("token_uri"))
            assertTrue(msg.contains("redirect_uris"))
        }
    }

    @Test
    fun `a refusal never echoes a value from the file`() {
        // A parse error must not become a copy of the credential in a toast, because toasts are
        // screenshotted. The value here is distinctive so an accidental echo is unmistakable.
        val secret = "GOCSPX-DO-NOT-ECHO-THIS-VALUE"
        val badFile = """
            { "web": {
                "client_id": "x.apps.googleusercontent.com",
                "client_secret": "$secret",
                "redirect_uris": ["https://developers.google.com/oauthplayground"],
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "http://insecure.example/token"
            } }
        """.trimIndent()
        try {
            OauthClientJson.parse(badFile)
            fail("must refuse a non-https token_uri")
        } catch (e: PluginError) {
            assertFalse(
                "the refusal must not quote the client secret: ${e.message}",
                e.message!!.contains(secret),
            )
        }
    }

    @Test
    fun `a non-https token_uri is refused even though the format says https`() {
        val insecure = """
            { "web": {
                "client_id": "x.apps.googleusercontent.com",
                "client_secret": "s",
                "redirect_uris": ["https://developers.google.com/oauthplayground"],
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "http://accounts.google.com/o/oauth2/token"
            } }
        """.trimIndent()
        try {
            OauthClientJson.parse(insecure)
            fail("must refuse")
        } catch (e: PluginError) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `a service account key is refused with the actual explanation`() {
        // The realistic wrong download: the same console page offers service-account keys, and the
        // failure "no web or installed object" does not tell anyone what to do instead.
        val serviceAccount = """{ "type": "service_account", "project_id": "x" }"""
        try {
            OauthClientJson.parse(serviceAccount)
            fail("must refuse")
        } catch (e: PluginError) {
            assertTrue(
                "the message must name what was actually downloaded: ${e.message}",
                e.message!!.contains("service-account"),
            )
        }
    }

    @Test
    fun `an unknown wrapper key reports what was found`() {
        try {
            OauthClientJson.parse("""{ "something_else": { "client_id": "x" } }""")
            fail("must refuse")
        } catch (e: PluginError) {
            assertTrue(e.message!!.contains("something_else"))
        }
    }

    @Test
    fun `not json is refused as not json, not as a crash`() {
        try {
            OauthClientJson.parse("this is not json at all")
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_import_failed", e.code)
            assertTrue(e.message!!.contains("not JSON"))
        }
    }

    @Test
    fun `an empty file is refused`() {
        try {
            OauthClientJson.parse("   ")
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_import_failed", e.code)
        }
    }

    @Test
    fun `a huge file is refused before parsing`() {
        val huge = "{" + " ".repeat(OauthClientJson.MAX_BYTES + 10) + "}"
        try {
            OauthClientJson.parse(huge)
            fail("must refuse")
        } catch (e: PluginError) {
            assertTrue(
                "the message must say the file is too large: ${e.message}",
                e.message!!.contains("KiB"),
            )
        }
    }

    @Test
    fun `the json picker never takes a persistable permission`() {
        // Mido's instruction for this feature was "don't store the file anywhere, just read it".
        // A persistable grant is precisely the mechanism that would let the app re-read it later,
        // so its absence is the property being asserted — not a style preference.
        val source = File("src/main/java/com/mido/phonebridge/OauthActivity.kt").readText()
        val importBlock = source.substringAfter("private fun importClientJson()", "")
            .substringBefore("private fun readAndParse")
        // **Comments are stripped before the check.** The doc comment above `readAndParse` names the
        // API in prose to explain WHY it is not used, and matching the bare word would fail on that
        // explanation rather than on a call — a test that punishes documentation is a test that
        // gets the documentation deleted.
        val code = importBlock.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")

        assertFalse(
            "the client-json picker must not CALL takePersistableUriPermission: ${code.take(200)}",
            code.contains("takePersistableUriPermission"),
        )
        assertTrue(
            "it should be a single-document picker, not the tree picker the upload grant uses",
            code.contains("Intent.ACTION_OPEN_DOCUMENT"),
        )
        assertFalse(
            "ACTION_OPEN_DOCUMENT_TREE would grant a whole folder for one file",
            code.contains("ACTION_OPEN_DOCUMENT_TREE"),
        )
    }
}
