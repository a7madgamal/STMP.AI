package com.mido.phonebridge.notify

/**
 * Who is on screen and who waits. Pure: no Android, no views, no clocks.
 *
 * A **queue**, not a stack. The first version drew every in-flight prompt at one
 * coordinate, so three simultaneous requests produced ONE visible card with ONE
 * countdown — three windows expiring behind a single bar. Mido caught that on a real
 * screen ("3 at once I saw only one timer"). A device run exists to find exactly this.
 *
 * Two properties this type must never lose:
 *
 *  - **No eviction.** The stacking version dropped the oldest item when it ran out of
 *    room, denying a request the human was never shown. An item that has been accepted
 *    into the line is either answered or refused — never silently discarded. When
 *    there is no room, the NEWCOMER is refused, immediately and visibly, because it is
 *    the one we cannot serve in time.
 *  - **One clock at a time.** A waiting item has no window and no countdown. Its clock
 *    starts when it reaches the screen, so "silence means approve" always refers to a
 *    full window the human could actually see.
 *
 * Ordering and capacity live here rather than in the overlay so they can be tested
 * without a device. The overlay owns pixels; this owns the rules.
 */
class NotifyQueue<T>(private val keyOf: (T) -> Long, private val maxWaiting: Int) {

    /** What happened when an item asked for a place in line. */
    sealed class Admission<out T> {
        /** Nothing was on screen. Show this now; its clock starts now. */
        class Now<T>(val item: T) : Admission<T>()

        /** Someone else is on screen. Wait, invisible, behind [ahead] items. */
        class Waiting<T>(val item: T, val ahead: Int) : Admission<T>()

        /** The line is full. Refuse this request rather than show it late. */
        class Refused<T>(val item: T, val ahead: Int) : Admission<T>()
    }

    private val lock = Any()
    private var current: T? = null
    private val waiting = ArrayDeque<T>()

    /**
     * Offer [item] a place. Returns which of the three fates it got.
     *
     * [Admission.Waiting.ahead] counts everything in front of it, current included:
     * `1` means "one prompt is on screen and you are next". That number is what lets a
     * caller bound its own wait honestly instead of guessing.
     */
    fun admit(item: T): Admission<T> = synchronized(lock) {
        if (current == null) {
            current = item
            Admission.Now(item)
        } else {
            val ahead = waiting.size + 1
            if (waiting.size >= maxWaiting) {
                Admission.Refused(item, ahead)
            } else {
                waiting.addLast(item)
                Admission.Waiting(item, ahead)
            }
        }
    }

    /**
     * Remove the item with [id]. Returns the item promoted onto the screen, or null if
     * nothing was promoted.
     *
     * Called for every ending: a tap, an expiry, an abandonment, a refusal. Retiring a
     * *waiting* item removes only that one and promotes nobody — a departure from the
     * middle of the line must not jump the queue.
     */
    fun retire(id: Long): T? = synchronized(lock) {
        val head = current
        if (head != null && keyOf(head) == id) {
            current = waiting.removeFirstOrNull()
            return current
        }
        waiting.removeAll { keyOf(it) == id }
        null
    }

    /** The item on screen, if any. */
    fun currentItem(): T? = synchronized(lock) { current }

    /** How many items are invisible, waiting their turn. */
    fun waitingBehind(): Int = synchronized(lock) { waiting.size }

    /** Everything in the system: the one on screen plus the line. */
    fun depth(): Int = synchronized(lock) { (if (current == null) 0 else 1) + waiting.size }

    /**
     * Empty the queue, returning everything that was in it, current first. Used when
     * the service stops: each returned item must still be resolved by the caller, so
     * no request is left waiting on a prompt that will never appear.
     */
    fun clear(): List<T> = synchronized(lock) {
        val all = ArrayList<T>(waiting.size + 1)
        current?.let { all.add(it) }
        all.addAll(waiting)
        current = null
        waiting.clear()
        all
    }
}
