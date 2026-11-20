package com.mido.phonebridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.GoogleAccounts
import com.mido.phonebridge.core.TokenCipher
import com.mido.phonebridge.core.TokenStore
import com.mido.phonebridge.core.PluginError
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The multi-account rules that must never silently soften.
 *
 * Every assertion here is about a FALLBACK THAT MUST NOT EXIST. The old single-account design had
 * exactly one account and therefore no wrong answer to give; with several, "the one I have" is a
 * plausible-looking reply that ships a send from the wrong mailbox. So these tests exist to make
 * that reply impossible rather than merely discouraged:
 *
 *  - no parameter at two accounts is `account_required`, never "the first one";
 *  - an unknown address is `account_not_found` with the real ones listed, never a near match;
 *  - a local part, a label or a `sub` is not an address and is refused as one;
 *  - a request that resolves an account at one account still works with no parameter.
 *
 * The storage is Robolectric's in-memory prefs and the Keystore is the shadow implementation, so
 * the real encryption path runs — a token that round-trips here is a token that round-trips on a
 * phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoogleAccountsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * **The JVM runner has no `AndroidKeyStore`.** `TokenCipher` exposes an internal key seam for
     * exactly this: the real encrypt/decrypt, IV handling and GCM authentication all run, and
     * only the key's origin differs. Nothing in the app assigns this — it is test-only, which is
     * why it is `internal` and not a production fallback.
     */
    @Before
    fun useTestKey() {
        TokenCipher.keyProvider = {
            val k = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            k
        }
    }

    @After
    fun clear() {
        // Process-wide stores: leaving accounts behind would make the NEXT test's count wrong,
        // and that failure would land in a file that has nothing to do with this one.
        GoogleAccounts.clear(ctx)
        AuthManager.clientId = ""
        AuthManager.serverClientId = ""
        AuthManager.clientSecret = ""
    }

    private fun add(email: String, sub: String, scopes: Set<String> = emptySet()) {
        val account = GoogleAccounts.Account(sub = sub, email = email, grantedScopes = scopes)
        GoogleAccounts.upsert(ctx, account)
        GoogleAccounts.putRefreshToken(ctx, account, "refresh-$sub")
    }

    // --- resolution ---

    @Test
    fun `no account is no_accounts, not an empty success`() {
        try {
            GoogleAccounts.sole(ctx)
            fail("an empty account list must refuse, never answer 'ready'")
        } catch (e: PluginError) {
            assertEquals("no_accounts", e.code)
        }
    }

    @Test
    fun `one account resolves with no parameter and stays addressable by its email`() {
        add("only@example.com", "sub-1")
        assertEquals("only@example.com", GoogleAccounts.sole(ctx).email)
        assertEquals("sub-1", GoogleAccounts.byEmail(ctx, "ONLY@example.com").sub)
    }

    @Test
    fun `two accounts without a parameter is account_required and lists both`() {
        add("a@example.com", "sub-a")
        add("b@example.com", "sub-b")
        try {
            GoogleAccounts.sole(ctx)
            fail("two accounts must never fall back to one")
        } catch (e: PluginError) {
            assertEquals("account_required", e.code)
            // The message has to be ACTIONABLE in one round trip: an error that only says
            // "required" invites a guessing loop, which is how an agent ends up trying a label.
            assertTrue(e.message!!.contains("a@example.com"))
            assertTrue(e.message!!.contains("b@example.com"))
            assertTrue(e.message!!.contains("?account="))
        }
    }

    @Test
    fun `an unknown address is refused with the real ones named, never a near match`() {
        add("eng.a7mad.gamal@gmail.com", "sub-1")
        add("ahmed.masr.ay7aga@gmail.com", "sub-2")
        try {
            GoogleAccounts.byEmail(ctx, "eng")
            fail("a prefix is not an address")
        } catch (e: PluginError) {
            assertEquals("account_not_found", e.code)
            assertTrue(e.message!!.contains("eng.a7mad.gamal@gmail.com"))
        }
    }

    @Test
    fun `an empty parameter at two accounts is account_required, not an unknown address`() {
        add("a@example.com", "sub-a")
        add("b@example.com", "sub-b")
        try {
            GoogleAccounts.byEmail(ctx, "   ")
            fail("an empty value must not be treated as an address")
        } catch (e: PluginError) {
            assertEquals("account_required", e.code)
        }
    }

    @Test
    fun `the single authority decides presence from the count`() {
        assertEquals(AuthManager.AccountRequirement.NONE, AuthManager.accountRequirement(ctx))
        add("a@example.com", "sub-a")
        assertEquals(AuthManager.AccountRequirement.NONE, AuthManager.accountRequirement(ctx))
        add("b@example.com", "sub-b")
        assertEquals(AuthManager.AccountRequirement.REQUIRED, AuthManager.accountRequirement(ctx))
    }

    @Test
    fun `the dispatcher resolver accepts a parameter at one account`() {
        add("solo@example.com", "sub-1")
        // A skill written for multi-account must keep working after an account is removed, so
        // the parameter is ACCEPTED at one account rather than refused as unexpected.
        assertEquals("solo@example.com", AuthManager.resolveAccount(ctx, "solo@example.com").email)
        assertEquals("solo@example.com", AuthManager.resolveAccount(ctx, null).email)
    }

    // --- storage ---

    @Test
    fun `a refresh token round-trips per account and never crosses over`() {
        add("a@example.com", "sub-a")
        add("b@example.com", "sub-b")
        val a = GoogleAccounts.bySub(ctx, "sub-a")
        val b = GoogleAccounts.bySub(ctx, "sub-b")
        assertEquals("refresh-sub-a", GoogleAccounts.refreshToken(ctx, a))
        assertEquals("refresh-sub-b", GoogleAccounts.refreshToken(ctx, b))
    }

    @Test
    fun `removing one account leaves the other working`() {
        add("a@example.com", "sub-a")
        add("b@example.com", "sub-b")
        GoogleAccounts.remove(ctx, "sub-a")
        assertEquals(1, GoogleAccounts.count(ctx))
        assertEquals("b@example.com", GoogleAccounts.sole(ctx).email)
        assertEquals("refresh-sub-b", GoogleAccounts.refreshToken(ctx, GoogleAccounts.sole(ctx)))
    }

    @Test
    fun `a re-authorisation updates the account rather than duplicating it`() {
        add("old@example.com", "sub-1")
        val before = GoogleAccounts.sole(ctx).addedAt
        // Same `sub`, new address: a Workspace rename, not a new account.
        GoogleAccounts.upsert(
            ctx,
            GoogleAccounts.Account(sub = "sub-1", email = "new@example.com"),
        )
        assertEquals(1, GoogleAccounts.count(ctx))
        assertEquals("new@example.com", GoogleAccounts.sole(ctx).email)
        assertEquals(before, GoogleAccounts.sole(ctx).addedAt)
    }

    @Test
    fun `two identities claiming one address is refused rather than shadowed`() {
        add("same@example.com", "sub-1")
        try {
            GoogleAccounts.upsert(
                ctx,
                GoogleAccounts.Account(sub = "sub-2", email = "same@example.com"),
            )
            fail("a second identity on one address would make the address ambiguous")
        } catch (e: PluginError) {
            assertEquals("account_storage_error", e.code)
        }
    }

    @Test
    fun `a stored credential is not left readable as plaintext`() {
        add("a@example.com", "sub-a")
        val raw = ctx.getSharedPreferences("phonebridge_google_accounts", Context.MODE_PRIVATE)
            .getString("tokens", null)
        assertNotNull("the token map must exist", raw)
        assertFalse(
            "the refresh token must never appear in the prefs in the clear",
            raw!!.contains("refresh-sub-a"),
        )
    }

    @Test
    fun `the granted scope set is recorded per account and empty means unknown`() {
        add("a@example.com", "sub-a", setOf("https://www.googleapis.com/auth/gmail.readonly"))
        add("b@example.com", "sub-b")
        assertTrue(
            GoogleAccounts.bySub(ctx, "sub-a").grantedScopes
                .contains("https://www.googleapis.com/auth/gmail.readonly"),
        )
        // B was never told what it holds. Empty must stay empty rather than being read as
        // "granted nothing" — Drive's warning depends on telling those two apart.
        assertTrue(GoogleAccounts.bySub(ctx, "sub-b").grantedScopes.isEmpty())
    }

    // --- labels are never identifiers ---

    @Test
    fun `a label is displayed but is not an address`() {
        GoogleAccounts.upsert(
            ctx,
            GoogleAccounts.Account(sub = "sub-1", email = "work@example.com", label = "Work"),
        )
        assertEquals("work@example.com (Work)", GoogleAccounts.sole(ctx).display)
        try {
            GoogleAccounts.byEmail(ctx, "Work")
            fail("a label is a private mapping and must never resolve as an address")
        } catch (e: PluginError) {
            assertEquals("account_not_found", e.code)
        }
    }

    @Test
    fun `an account cannot be constructed without an identity or an address`() {
        // **The half state is now impossible to build, not merely avoided.** A record used to be
        // allowed with a blank `sub` during migration, and that record could serve requests but
        // could never be re-authorised — it looked healthy until the one action that repairs a
        // credential was the one action it could not perform. The constructor refuses it, so no
        // code path can create it again.
        try {
            GoogleAccounts.Account(sub = "", email = "half@example.com")
            fail("an account with no identity is the half state this design forbids")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("identity"))
        }
        try {
            GoogleAccounts.Account(sub = "sub-1", email = "  ")
            fail("an account with no address cannot be named anywhere a human reads")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("address"))
        }
    }

    @Test
    fun `an install from the single-account build is signed out, never half-migrated`() {
        // Seed the layout that build leaves behind, through the real encryption path.
        TokenStore.save(ctx, "legacy-refresh-token")
        TokenStore.saveEmail(ctx, "legacy@example.com")

        // **No migration and no network**: the very first read clears it. A record carried over
        // from that build could never be re-authorised, and an in-place upgrade produced both a
        // half state and, when forbidden, a crash.
        assertEquals("the upgrade signs out rather than carrying a credential it cannot use", 0, GoogleAccounts.count(ctx))
        val legacy = ctx.getSharedPreferences("phonebridge_secure", Context.MODE_PRIVATE)
        assertNull("the old token must be gone, not left to rot", legacy.getString("refresh_token", null))
        assertNull("and its address with it", legacy.getString("account_email", null))

        // The app is now in the zero-account state, which every screen already explains.
        try {
            GoogleAccounts.sole(ctx)
            fail("zero accounts must refuse, never answer 'ready'")
        } catch (e: PluginError) {
            assertEquals("no_accounts", e.code)
        }
    }

    @Test
    fun `a stored record from the half-state era is discarded, not a crash`() {
        // **This is the upgrade path that crashed the app.** The previous build could WRITE an
        // account with no `sub`; `Account` now refuses to exist without one, so decoding that
        // record threw from the constructor — on a screen, on a request, on any read at all.
        // A crash on upgrade is worse than the half state it came from.
        ctx.getSharedPreferences("phonebridge_google_accounts", Context.MODE_PRIVATE).edit()
            .putString(
                "accounts",
                org.json.JSONObject().put(
                    "list",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("sub", "")
                            .put("email", "half@example.com")
                            .put("addedAt", 1L),
                    ),
                ).toString(),
            )
            .apply()

        val accounts = GoogleAccounts.all(ctx)
        assertEquals("an unaddressable record is dropped", 0, accounts.size)
        // And it is gone from storage, so the warning is not repeated on every read.
        assertEquals("no account", 0, GoogleAccounts.count(ctx))
        val rewritten = ctx.getSharedPreferences("phonebridge_google_accounts", Context.MODE_PRIVATE)
            .getString("accounts", null)
        assertFalse(
            "the bad record must be persisted away, not re-discarded forever",
            rewritten != null && rewritten.contains("half@example.com"),
        )
    }
}
