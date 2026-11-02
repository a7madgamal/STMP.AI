package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `NOTIFY` card may have exactly ONE child, and the risk tint may not be a view.
 *
 * **Why a source scan, and why this rule specifically.** The card going full-screen was
 * shipped twice (0.6.2 and 0.25.1) from the same cause: a second child in a `WRAP_CONTENT`
 * `FrameLayout`. A `MATCH_PARENT`-height child there makes the parent match ITS parent — the
 * whole window — so a decoration decides the card's size. Both times the code looked right
 * and the phone showed a full-height card; both times the bug was invisible until the
 * decoration became visible, which is why it was reported as *"looked good until summarize
 * finished"*.
 *
 * Unit tests cannot measure a view, but they CAN read the source, and this defect is a shape in
 * the source: a `card.addView` that is not the content column. So the rule is enforced where it
 * can actually be broken, in the same style as [GrainCoverageTest] and `ErrorCodesTest`.
 *
 * The second assertion covers the fix's other half: the risk tint must be a `Drawable` layer
 * ([com.mido.phonebridge.ui.EdgeBand]), because a layer cannot participate in layout at all.
 * If it ever returns as a `View`, it can once again change the card's measured height.
 */
class NotifyCardLayoutTest {

    private val overlay = File(
        "src/main/java/com/mido/phonebridge/notify/NotifyOverlay.kt"
    )

    @Test
    fun `the notify card has exactly one layout child`() {
        assertTrue("source not found at ${overlay.absolutePath}", overlay.isFile)

        val cardChildren = overlay.readLines()
            .map { it.substringBefore("//").trim() }
            .filter { it.startsWith("card.addView(") }

        assertEquals(
            "the NOTIFY card is WRAP_CONTENT, so a second child can decide its size — a " +
                "MATCH_PARENT child makes it the full window (the 0.6.2 and 0.25.1 bug, " +
                "twice). Every decoration (countdown drain, risk band, grain) belongs in the " +
                "background LayerDrawable, which cannot participate in layout. Found:\n  " +
                cardChildren.joinToString("\n  "),
            1,
            cardChildren.size
        )
    }

    @Test
    fun `the risk tint is a layer, not a view`() {
        val source = overlay.readText()

        assertTrue(
            "the risk tint must be a Drawable layer (ui/EdgeBand): a View with a height makes " +
                "the card's size depend on whether the AI summary arrived, which is the defect " +
                "that shipped twice. If the tint needs to return as a View, it needs a " +
                "different fix, not a reintroduction.",
            source.contains("var riskBand: com.mido.phonebridge.ui.EdgeBand?")
        )
        assertTrue(
            "no child view named riskStrip may exist on the card",
            !source.contains("var riskStrip")
        )
        assertTrue(
            "EdgeBand must be layered into the card background",
            source.contains("riskBand,")
        )
    }
}
