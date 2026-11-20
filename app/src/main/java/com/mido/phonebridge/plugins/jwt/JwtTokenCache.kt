package com.mido.phonebridge.plugins.jwt

import android.content.Context

/**
 * The token cache.
 *
 * **Why a cache exists at all.** Every brokered call would otherwise mint a token. Mozilla's tokens
 * are valid for minutes and explicitly reusable ("no JWT claims are made about the actual API
 * request"), so signing on each call would be a pure waste of a signature — and, on a phone, thirty
 * signatures in a burst look like nothing so much as a broken client to a rate limiter.
 *
 * **Why it is keyed by a fingerprint rather than by name.** Rotation and reconfiguration must
 * invalidate the entry without a separate "forget" call at every call site. The fingerprint covers
 * the secret AND every field that changes what gets signed, so a new secret, a new ttl or a new
 * claim is a different cache key — the old token can never be handed out under a new config.
 *
 * **What this deliberately does not do:** it does not persist anything, and it does not survive a
 * lock. A token in memory is not a credential at rest; a token on disk would be. Losing the cache on
 * lock costs one signature on the next call and removes a class of "why is there a token in my
 * backup" questions.
 */
object JwtTokenCache {

    private data class Slot(
        val fingerprint: String,
        val token: MintedToken,
    )

    private val slots = HashMap<String, Slot>()

    /**
     * Get a usable token for [name], minting one when there is none or the stored one is spent.
     *
     * Called with the vault already open, so [keyMaterial] is available. The mint happens inline —
     * an HS256 signature is microseconds, and a background thread would add a state machine for no
     * measurable gain.
     */
    @Synchronized
    fun tokenFor(
        ctx: Context,
        name: String,
        entry: JwtEntry,
        keyMaterial: String,
        nowMs: Long,
    ): MintedToken {
        val fingerprint = fingerprintOf(entry, keyMaterial)
        val cached = slots[name]
        if (cached != null && cached.fingerprint == fingerprint && cached.token.usableAt(nowMs)) {
            return cached.token
        }
        val minted = JwtMinter.mint(entry.toSpec(), keyMaterial, nowMs)
        slots[name] = Slot(fingerprint, minted)
        return minted
    }

    /** Drop one credential's cached token. Called on rotation, edit and delete. */
    @Synchronized
    fun forget(name: String) {
        slots.remove(name)
    }

    /** Drop everything. Called when the vault locks: the keys that produced them are gone. */
    @Synchronized
    fun clear() {
        slots.clear()
    }

    /** Test seam: how many credentials currently hold a cached token. */
    @Synchronized
    fun size(): Int = slots.size

    /**
     * Everything that changes what a token would look like, hashed.
     *
     * `hashCode` is deliberately NOT used: two distinct configs colliding there is unlikely but
     * free to avoid, and a collision here means serving a token signed under a different secret.
     */
    private fun fingerprintOf(entry: JwtEntry, keyMaterial: String): String {
        val canonical = buildString {
            append(entry.issuer).append('\u0000')
            append(entry.alg).append('\u0000')
            append(entry.ttlSeconds).append('\u0000')
            append(entry.skewSeconds).append('\u0000')
            append(entry.scheme).append('\u0000')
            append(entry.kid).append('\u0000')
            // Sorted so two equal maps in a different insertion order share a token.
            for (k in entry.extraClaims.keys.sorted()) {
                append(k).append('=').append(entry.extraClaims[k]).append('\u0000')
            }
            append('\u0000').append(keyMaterial)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
