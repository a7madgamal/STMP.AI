package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **An entry is attached at most once.**
 *
 * Mido, 2026-11-03: *"touching the hints still freezes them, I'm sick of this bug"*.
 *
 * The device log was unambiguous: `notify[5014] attached` appeared **twice**, 896ms apart, for a
 * single entry. The first attach came from `queue.admit` returning `Now`. The second came from
 * `teardown(5013)` calling `promoteNext()`, which asked the queue for the current item — still
 * 5014 — and presented a card that was already on screen.
 *
 * The damage is not the duplicate draw. The second attach **overwrote `entry.root`**, orphaning
 * the first window. Nothing held a reference to an orphan, so no teardown could ever remove it: it
 * sat on screen, visually plausible, swallowing every touch, while the live card underneath was
 * invisible behind it. Every control on the visible card was dead because the thing being touched
 * was a corpse nothing could reach.
 *
 * This is why the earlier fixes did not help. They made `settle` and `teardown` more forgiving,
 * but the frozen surface was never an entry at all — it was an orphan window, and no amount of
 * per-entry logic can act on a window with no entry.
 *
 * The rule is therefore structural, and pinned here rather than trusted to memory: two places
 * attach a window, and both must refuse when the entry already has one.
 */
class NotifyAttachOnceTest {

    private val overlay = File(
        "src/main/java/com/mido/phonebridge/notify/NotifyOverlay.kt"
    )

    private val source: String by lazy {
        assertTrue("source not found at ${overlay.absolutePath}", overlay.isFile)
        overlay.readText()
    }

    @Test
    fun `present refuses an entry that is already attached`() {
        val body = functionBody("private fun present(entry: Entry)")

        assertTrue(
            "present() must return before building a window when `entry.root` is already set. " +
                "Without this, a second attach overwrites entry.root and ORPHANS the first " +
                "window — which then cannot be torn down by anything, and freezes the card " +
                "underneath it. Found:\n$body",
            body.contains("if (entry.root != null)")
        )

        // The guard has to come FIRST. Placed after `addView`, it would be a comment.
        val guardAt = body.indexOf("if (entry.root != null)")
        val addAt = body.indexOf("wm.addView(card")
        assertTrue("the guard must precede the addView", guardAt in 0..<addAt)
    }

    @Test
    fun `promoteNext does not re-present the card already on screen`() {
        val body = functionBody("private fun promoteNext()")

        assertTrue(
            "promoteNext() runs from every teardown, so a teardown for a non-head entry would " +
                "re-present the card that is already attached. It must skip an entry that has a " +
                "window. Found:\n$body",
            body.contains("if (next.root != null) return") ||
                body.contains("if (next.root != null)") 
        )
    }

    @Test
    fun `root is only cleared, never left pointing at a removed window`() {
        // `entry.root = null` after a successful remove is what makes the guard above safe to
        // keep: a retired entry is collectable, and a re-present of a genuinely removed card is
        // still allowed.
        assertTrue(
            "teardown must null entry.root after removing the window",
            source.contains("entry.root = null")
        )
    }

    /** The body of a function, from its opening brace to the matching one. */
    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("not found: $signature — was it renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val bodyStart = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces after $signature")
    }
}
