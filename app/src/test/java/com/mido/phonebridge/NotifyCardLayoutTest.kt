package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the risk tint is a background colour, never a stripe`() {
        val source = overlay.readText()

        // The tint must live on the card's existing background layer. `cardBg` is already
        // inside the card's LayerDrawable, so recolouring it repaints without touching
        // geometry — the property that makes any tint safe on a WRAP_CONTENT card.
        assertTrue(
            "the risk must tint the card background (entry.cardBg), so it can never change " +
                "the card's measured height",
            source.contains("entry.cardBg?.setColor(")
        )

        // Mido, 2026-11-03: *"remove left green border"* / *"the hint bg to match the level"*.
        // An edge band draws a stripe down one side, which is what was asked to go away.
        assertFalse(
            "no EdgeBand may be layered into the NOTIFY card — the risk is the background " +
                "colour now, not a stripe down the edge",
            source.contains("EdgeBand(")
        )
        assertFalse(
            "no riskBand field may exist; the tint is carried by cardBg",
            source.contains("var riskBand")
        )
    }
}
