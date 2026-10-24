package com.mido.phonebridge

/**
 * How many live notifications [NotifStore] retains, and which ones to drop when it is full.
 *
 * **Why this exists at all.** Before this, `NotifStore` was an unbounded `LinkedHashMap`:
 * every notification the listener ever observed stayed in memory until the app process died,
 * and nothing evicted an entry. Each value is a whole `StatusBarNotification`, which drags a
 * `Bundle` of extras plus any bitmaps the posting app attached. On a phone that receives a
 * normal volume of messages that is slow, silent, unbounded growth — and because the store
 * lives in a `specialUse` foreground service, the process is one the OS is *obliged* to try to
 * keep alive. Mido, 2026-10-20: *"my phone was DYING until I uninstalled the shit out of the
 * app"*. Uninstalling fixed it because it was this map, not the network.
 *
 * **The cap is a RULE, so it lives in a pure object a test can reach.** `NotifStore` itself
 * cannot be unit-tested — `StatusBarNotification` is an Android type that does not exist on the
 * JVM — which is exactly how an unbounded map shipped for dozens of releases without a single
 * test objecting. The Android-free half is here: given the retained keys in recency order and a
 * maximum, which keys must go. `NotifCapacityTest` pins the boundaries, including the case this
 * is most likely to get wrong — evicting the NEWEST instead of the OLDEST, which would make the
 * API's list quietly lose the notification a human just received.
 */
object NotifCapacity {

    /**
     * The hard ceiling on retained notifications.
     *
     * Sized against a real notification shade rather than a memory figure: 200 is far more
     * than a phone displays at once, so a caller asking for "everything" still receives
     * everything a human could actually see, while weeks of accumulated noise cannot pile up.
     * Eviction is oldest-first, so what survives is the most recent — the same end of the list
     * every caller reads.
     */
    const val MAX_ITEMS = 200

    /**
     * Which keys to drop so that at most [max] remain, given [keysOldestFirst].
     *
     * The order is the contract: [keysOldestFirst] must be oldest-first, and the returned
     * keys are the leading slice — the oldest entries. Reversing either one silently keeps the
     * wrong end of the list, which is why the test asserts on the returned VALUES and not just
     * on the count.
     *
     * Returns an empty list when already within bounds, so a caller can apply it
     * unconditionally. A negative [max] is refused rather than clamped: it means the constant
     * was misconfigured, and quietly treating it as 0 would turn a config bug into "the bridge
     * reports no notifications" — a failure that looks like an empty phone.
     */
    fun keysToEvict(keysOldestFirst: List<String>, max: Int = MAX_ITEMS): List<String> {
        require(max >= 0) { "capacity must not be negative, got $max" }
        val excess = keysOldestFirst.size - max
        return if (excess <= 0) emptyList() else keysOldestFirst.subList(0, excess).toList()
    }
}
