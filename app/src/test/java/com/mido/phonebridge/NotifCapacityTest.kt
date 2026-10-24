package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capacity guard for [NotifCapacity].
 *
 * This is the test that could not exist before the rule was extracted: `NotifStore` holds
 * `StatusBarNotification`, which does not exist on the JVM, so the unbounded map shipped for
 * dozens of releases while the whole suite stayed green. Mido, 2026-10-20: *"my phone was
 * DYING until I uninstalled the shit out of the app"*.
 *
 * **The assertions are on VALUES, not counts.** A count-only test passes when the code evicts
 * the wrong end of the list — and evicting the newest would quietly drop the notification a
 * human just received, which is a worse bug than the memory leak it was fixing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotifCapacityTest {

    @Test
    fun `within capacity nothing is evicted`() {
        val keys = listOf("a", "b", "c")
        assertTrue(NotifCapacity.keysToEvict(keys, max = 3).isEmpty())
        assertTrue(NotifCapacity.keysToEvict(keys, max = 10).isEmpty())
    }

    @Test
    fun `an empty store evicts nothing`() {
        assertTrue(NotifCapacity.keysToEvict(emptyList()).isEmpty())
    }

    @Test
    fun `over capacity drops the OLDEST entries and keeps the newest`() {
        // Oldest-first, as the store's LinkedHashMap iteration guarantees.
        val keys = listOf("oldest", "middle", "newest")
        val doomed = NotifCapacity.keysToEvict(keys, max = 2)
        assertEquals(listOf("oldest"), doomed)
        // The survivors are the most recent — the end every caller reads.
        assertEquals(listOf("middle", "newest"), keys - doomed.toSet())
    }

    @Test
    fun `one over capacity drops exactly one, even at the real bound`() {
        val keys = (1..NotifCapacity.MAX_ITEMS + 1).map { "k$it" }
        val doomed = NotifCapacity.keysToEvict(keys)
        assertEquals(listOf("k1"), doomed)
    }

    @Test
    fun `a bulk overrun drops exactly the excess`() {
        val keys = (1..50).map { "k$it" }
        assertEquals(30, NotifCapacity.keysToEvict(keys, max = 20).size)
    }

    @Test
    fun `max of zero evicts everything`() {
        val keys = listOf("a", "b")
        assertEquals(keys, NotifCapacity.keysToEvict(keys, max = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative max fails hard rather than clamping`() {
        // Clamping would turn a misconfigured constant into "the bridge reports no
        // notifications", which reads as an empty phone rather than a bug.
        NotifCapacity.keysToEvict(listOf("a"), max = -1)
    }

    @Test
    fun `the default bound is the documented one`() {
        assertEquals(200, NotifCapacity.MAX_ITEMS)
    }
}
