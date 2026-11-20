package com.mido.phonebridge

import android.content.Context
import com.mido.phonebridge.core.SecretVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * **The guard for vault ownership, and the proof that existing vaults keep working.**
 *
 * Mido, 2026-11-19: *"each plugin should handle its own secrets"* — and then, before any of it
 * was built: *"is the cleanup backward compatible with current data?"*
 *
 * That question is the whole point of this file. Ownership (`SecretMeta.owner`) replaces the
 * `startsWith("ssh-") || startsWith("jwt-")` filtering that made one screen know two other
 * plugins' naming conventions. A migration is exactly the kind of change that looks fine on a
 * fresh install and quietly ruins a real vault, so every claim below is asserted against an index
 * written in the OLD shape — the shape sitting on the phone right now.
 *
 * `SecretVault` itself needs `AndroidKeyStore` and cannot be exercised on the JVM (see
 * `VaultStateTest`). But the INDEX is plaintext prefs, and `names` / `meta` / `ownedBy` / `unowned`
 * read nothing else — so the part that migrates is testable, which is the part that matters here.
 */
@RunWith(RobolectricTestRunner::class)
class VaultOwnershipTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        // Each test starts from an empty index: prefs survive between tests in one JVM.
        ctx.getSharedPreferences("phonebridge_vault", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * Write the index the way a build BEFORE this change wrote it: no `owner` key at all.
     *
     * This is the fixture that makes every test below meaningful — a test that seeds the new shape
     * proves nothing about a vault that predates it.
     */
    private fun writeLegacyIndex(vararg entries: Triple<String, String, List<String>>) {
        val o = JSONObject()
        for ((name, kind, hosts) in entries) {
            o.put(
                name,
                JSONObject()
                    .put("docs", "")
                    .put("key", "")
                    .put("hosts", JSONArray(hosts))
                    .put("kind", kind),
            )
        }
        ctx.getSharedPreferences("phonebridge_vault", Context.MODE_PRIVATE)
            .edit().putString("vault_meta", o.toString()).commit()
    }

    @Test
    fun `a legacy ssh entry is owned by ssh, not by its name`() {
        writeLegacyIndex(Triple("ssh-evox2", "ssh", emptyList()))

        assertEquals("ssh", SecretVault.meta(ctx, "ssh-evox2").owner)
        assertEquals(listOf("ssh-evox2"), SecretVault.ownedBy(ctx, "ssh"))
        // And it is gone from the generic screen, which is what the old prefix filter achieved.
        assertTrue(SecretVault.unowned(ctx).isEmpty())
    }

    @Test
    fun `a legacy jwt entry is owned by jwt`() {
        writeLegacyIndex(Triple("jwt-mozillaamothewall", "jwt", listOf("addons.mozilla.org")))

        assertEquals("jwt", SecretVault.meta(ctx, "jwt-mozillaamothewall").owner)
        assertEquals(listOf("jwt-mozillaamothewall"), SecretVault.ownedBy(ctx, "jwt"))
        assertTrue(SecretVault.unowned(ctx).isEmpty())
    }

    @Test
    fun `a legacy plain secret stays unowned even when its name looks like another plugin's`() {
        // **The bug the owner field exists to remove.** `SecretVault.kt` already warns about this:
        // a generic secret can be called anything. Deriving ownership from the name would move this
        // entry to the SSH screen and take its reveal button away; deriving it from `kind` keeps it
        // exactly where it was.
        writeLegacyIndex(Triple("ssh-notes", "secret", listOf("api.example.com")))

        assertNull(SecretVault.meta(ctx, "ssh-notes").owner)
        assertEquals(listOf("ssh-notes"), SecretVault.unowned(ctx))
        assertTrue(SecretVault.ownedBy(ctx, "ssh").isEmpty())
    }

    @Test
    fun `kind still parses, so an older build reading this index behaves the same`() {
        // `kind` is deliberately still written and still parsed. An older APK decides reveal
        // semantics from it, so a vault whose kind column vanished would hand an SSH private key a
        // reveal button.
        writeLegacyIndex(Triple("ssh-evox2", "ssh", emptyList()))

        assertEquals(SecretVault.SecretMeta.Kind.SSH, SecretVault.meta(ctx, "ssh-evox2").kind)
    }

    @Test
    fun `a mixed legacy vault lands each entry on exactly one screen`() {
        // The realistic case: a phone that has all three, written by a build that knew only `kind`.
        writeLegacyIndex(
            Triple("todoist", "secret", listOf("api.todoist.com")),
            Triple("ssh-evox2", "ssh", emptyList()),
            Triple("jwt-mozillaamothewall", "jwt", listOf("addons.mozilla.org")),
        )

        assertEquals(listOf("todoist"), SecretVault.unowned(ctx))
        assertEquals(listOf("ssh-evox2"), SecretVault.ownedBy(ctx, "ssh"))
        assertEquals(listOf("jwt-mozillaamothewall"), SecretVault.ownedBy(ctx, "jwt"))
        // Nothing is listed twice and nothing is lost: three entries, three homes.
        assertEquals(
            3,
            SecretVault.unowned(ctx).size +
                SecretVault.ownedBy(ctx, "ssh").size +
                SecretVault.ownedBy(ctx, "jwt").size,
        )
    }

    @Test
    fun `a plugin that has never stored anything gets an empty list, not a crash`() {
        // A fresh install, or `oauth` before its first credential. The plugin's screen must render
        // an empty list rather than fail.
        writeLegacyIndex(Triple("todoist", "secret", listOf("api.todoist.com")))

        assertTrue(SecretVault.ownedBy(ctx, "oauth").isEmpty())
    }

    @Test
    fun `a corrupt index does not crash the screens that list it`() {
        ctx.getSharedPreferences("phonebridge_vault", Context.MODE_PRIVATE)
            .edit().putString("vault_meta", "{not json").commit()

        // These two render a list, so they return empty instead of throwing: a crash blanks the tab
        // that would explain the problem. `names`/`meta` stay loud for the routes that need to be.
        assertTrue(SecretVault.unowned(ctx).isEmpty())
        assertTrue(SecretVault.ownedBy(ctx, "ssh").isEmpty())
    }
}
