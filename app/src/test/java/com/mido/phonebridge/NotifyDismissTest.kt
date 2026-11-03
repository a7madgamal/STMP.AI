package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The ✕ must always dismiss, and a settled card must always leave.**
 *
 * Mido, 2026-11-03: *"all the notification cards are stuck on summarizing after I touched to
 * accept them! ensure the x IS ALWAYS CLICKABLE and dismisses the card under any fucking
 * circumstances"*.
 *
 * Two defects produced that, and both are shapes in this source rather than anything a unit test
 * could measure:
 *
 * 1. The ✕ shared the misclick guard with tap-to-approve. A guarded APPROVE protects something; a
 *    guarded DISMISS protects nothing and leaves a card the human has told to go away. It also
 *    refuses when `shownAt == 0`, so a card attached in a bad state had a ✕ that could never work.
 *
 * 2. The approve/tap branch of `settle` returns early and leaves removal to the stamp's
 *    `onFinished`. That fires from an animator listener, and `StampOverlay` starts its animation
 *    from `onSizeChanged` — so a stamp whose window was added but never laid out never starts,
 *    never ends, and never calls back. The card sat at "◌ summarising…" forever with every control
 *    inert, because `settled` was already true.
 *
 * Enforced by scanning the source, in the same style as [NotifyCardLayoutTest]: the defect is a
 * missing line, and a missing line is only visible where it should have been.
 */
class NotifyDismissTest {

    private val overlay = File(
        "src/main/java/com/mido/phonebridge/notify/NotifyOverlay.kt"
    )

    private val source: String by lazy {
        assertTrue("source not found at ${overlay.absolutePath}", overlay.isFile)
        overlay.readText()
    }

    @Test
    fun `the dismiss handler never consults the misclick guard`() {
        val body = closeHandlerBody()

        assertFalse(
            "the ✕ must not call ArmGuard.decisionsAllowed or ArmGuard.isArmed: dismissing is not " +
                "a decision about the request, and gating it leaves a card the human explicitly " +
                "asked to remove. Found in:\n$body",
            body.contains("ArmGuard.decisionsAllowed") || body.contains("ArmGuard.isArmed")
        )
        assertFalse(
            "the ✕ must not return early — every exit from it must dismiss. Found in:\n$body",
            body.contains("return@setOnClickListener")
        )
    }

    @Test
    fun `the dismiss handler always settles and tears down`() {
        val body = closeHandlerBody()

        // Both are idempotent, so calling them in any state is safe — and calling BOTH is what
        // removes the window when the entry had already settled on its own.
        assertTrue(
            "the ✕ must call settle(entry, Reason.TAPPED); found:\n$body",
            body.contains("settle(entry, Reason.TAPPED)")
        )
        assertTrue(
            "the ✕ must call teardown(entry) directly, not rely on a fade chain; found:\n$body",
            body.contains("teardown(entry)")
        )
    }

    @Test
    fun `the stamp branch cannot strand the card`() {
        // The branch that returns before the normal fade/backstop must schedule its own removal.
        val stampCall = source.indexOf("stampDecision(entry, approved = reason == Reason.APPROVED)")
        assertTrue("stampDecision call site not found", stampCall > 0)

        val branch = source.substring(
            maxOf(0, source.indexOf("Reason.APPROVED || reason == Reason.TAPPED")),
            stampCall
        )
        assertTrue(
            "the stamp branch must post a teardown before handing off to stampDecision: the " +
                "stamp's onFinished is not a guarantee (it starts from onSizeChanged, which never " +
                "fires for a window that was added but not laid out). Found:\n$branch",
            branch.contains("teardown(entry)")
        )
    }

    /** The `close.setOnClickListener { ... }` body, from its opening brace to the matching one. */
    private fun closeHandlerBody(): String {
        val start = source.indexOf("close.setOnClickListener {")
        assertTrue("the ✕ listener was not found — did it get renamed?", start >= 0)

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
        throw AssertionError("unbalanced braces after close.setOnClickListener")
    }
}
