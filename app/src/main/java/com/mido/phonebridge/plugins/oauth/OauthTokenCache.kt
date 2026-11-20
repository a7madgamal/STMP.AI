package com.mido.phonebridge.plugins.oauth

/**
 * Access tokens, kept in MEMORY only, for as long as they are nearly valid.
 *
 * **Never persisted.** A token written to prefs would outlive the vault session that produced it,
 * which would make the fingerprint decorative: the point is that the credential is only usable
 * while the vault is open. Losing the cache on process death is the intended behaviour, not a
 * limitation.
 *
 * **A 60s margin.** A token handed out with 2s left produces a 401 that reads like a broken
 * credential. The margin trades one extra mint for never handing out a token that expires in flight.
 */
object OauthTokenCache {

    /** Refreshed early by this much, so a token never expires between cache and socket. */
    private const val MARGIN_SEC = 60L

    private data class Cached(val accessToken: String, val expiresAtSec: Long)

    private val byName = mutableMapOf<String, Cached>()

    /**
     * A usable token for [name], minting only when the cached one is spent.
     *
     * [mint] is a lambda rather than a call to [OauthMinter] so the cache logic is testable on the
     * JVM: nothing here needs Android, and a cache rule that only runs on a device is a cache rule
     * nobody verifies.
     */
    @Synchronized
    fun tokenFor(
        name: String,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        mint: () -> OauthToken,
    ): OauthToken {
        val cached = byName[name]
        if (cached != null && cached.expiresAtSec - MARGIN_SEC > nowSec) {
            return OauthToken(cached.accessToken, cached.expiresAtSec)
        }
        byName.remove(name)
        val fresh = mint()
        byName[name] = Cached(fresh.accessToken, fresh.expiresAtSec)
        return fresh
    }

    /**
     * Drop the cached token for [name].
     *
     * Called when the vault locks, when the config is edited, when the secret is rotated, and after
     * a call that failed with a 401 — each of those means the cached token may no longer be the one
     * the provider will accept.
     */
    fun forget(name: String) {
        byName.remove(name)
    }

    /** Drop everything. Used when the vault locks: no session, no tokens. */
    fun forgetAll() {
        byName.clear()
    }
}
