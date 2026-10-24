package com.mido.phonebridge

import com.mido.phonebridge.notify.NotifyQueue
import com.mido.phonebridge.notify.NotifyQueue.Admission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The prompt queue, tested without a device.
 *
 * These exist because the stacked-overlay defect ("3 at once I saw only one timer")
 * was an ORDERING bug, and ordering is exactly the part that does not need pixels to
 * verify. Items are plain [Long]s; `keyOf` is the identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotifyQueueTest {

    private fun queue(maxWaiting: Int = 3) = NotifyQueue<Long>(keyOf = { it }, maxWaiting = maxWaiting)

    @Test
    fun `first item is shown immediately`() {
        val admission = queue().admit(1L)
        assertTrue(admission is Admission.Now)
    }

    @Test
    fun `second item waits behind exactly one`() {
        val q = queue()
        q.admit(1L)
        val admission = q.admit(2L)
        assertTrue(admission is Admission.Waiting)
        assertEquals(1, (admission as Admission.Waiting).ahead)
    }

    @Test
    fun `waiting items wait in arrival order`() {
        val q = queue()
        q.admit(1L)
        val second = q.admit(2L) as Admission.Waiting
        val third = q.admit(3L) as Admission.Waiting
        assertEquals(1, second.ahead)
        assertEquals(2, third.ahead)
        assertEquals(2, q.waitingBehind())
        assertEquals(3, q.depth())
    }

    @Test
    fun `only the item on screen counts as current`() {
        val q = queue()
        q.admit(1L)
        assertEquals(1L, q.currentItem())
        q.admit(2L)
        // The waiting item must NOT become current: two clocks at once is the bug.
        assertEquals(1L, q.currentItem())
    }

    @Test
    fun `retiring the head promotes the first waiter in order`() {
        val q = queue()
        q.admit(1L)
        q.admit(2L)
        q.admit(3L)

        assertEquals(2L, q.retire(1L))
        assertEquals(2L, q.currentItem())
        assertEquals(1, q.waitingBehind())

        assertEquals(3L, q.retire(2L))
        assertEquals(3L, q.currentItem())
        assertEquals(0, q.waitingBehind())
    }

    @Test
    fun `retiring the last item promotes nobody`() {
        val q = queue()
        q.admit(1L)
        assertNull(q.retire(1L))
        assertNull(q.currentItem())
        assertEquals(0, q.depth())
    }

    @Test
    fun `retiring a waiter removes only that one and jumps nobody`() {
        val q = queue()
        q.admit(1L)
        q.admit(2L)
        q.admit(3L)

        // 2 gives up while 1 is still on screen. Nothing new may appear.
        assertNull(q.retire(2L))
        assertEquals(1L, q.currentItem())
        assertEquals(1, q.waitingBehind())

        // ...and 3 is still next in line, not 2's ghost.
        assertEquals(3L, q.retire(1L))
    }

    @Test
    fun `retiring an unknown id changes nothing`() {
        val q = queue()
        q.admit(1L)
        q.admit(2L)
        assertNull(q.retire(99L))
        assertEquals(1L, q.currentItem())
        assertEquals(1, q.waitingBehind())
    }

    @Test
    fun `a full line refuses the newcomer and leaves the line intact`() {
        val q = queue(maxWaiting = 2)
        q.admit(1L)                      // screen
        q.admit(2L)                      // waiting 1
        q.admit(3L)                      // waiting 2
        val refusal = q.admit(4L)

        assertTrue(refusal is Admission.Refused)
        assertEquals(3, (refusal as Admission.Refused).ahead)
        // The refusal must not have displaced anyone: no eviction, ever.
        assertEquals(1L, q.currentItem())
        assertEquals(2, q.waitingBehind())
        assertEquals(3, q.depth())
    }

    @Test
    fun `a refused item is never shown later`() {
        val q = queue(maxWaiting = 1)
        q.admit(1L)
        q.admit(2L)
        q.admit(3L)                      // refused

        assertEquals(2L, q.retire(1L))   // 2 comes on screen
        assertNull(q.retire(2L))         // then nothing: 3 was refused, not queued
        assertNull(q.currentItem())
        assertEquals(0, q.depth())
    }

    @Test
    fun `waitingBehind counts only the line, never the screen`() {
        val q = queue()
        assertEquals(0, q.waitingBehind())
        q.admit(1L)
        assertEquals(0, q.waitingBehind())
        assertEquals(1, q.depth())
        q.admit(2L)
        assertEquals(1, q.waitingBehind())
        assertEquals(2, q.depth())
    }

    @Test
    fun `clear returns everything in order, current first, and empties`() {
        val q = queue()
        q.admit(1L)
        q.admit(2L)
        q.admit(3L)

        assertEquals(listOf(1L, 2L, 3L), q.clear())
        assertNull(q.currentItem())
        assertEquals(0, q.depth())
        // A refill after clear starts cleanly at the top.
        assertTrue(q.admit(4L) is Admission.Now)
    }
}
