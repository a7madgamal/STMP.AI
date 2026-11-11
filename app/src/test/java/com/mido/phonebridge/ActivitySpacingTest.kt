package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Activity filter strip must have real gaps.
 *
 * Reported 2026-11-11: *"the filter buttons in the middle screen are touching everything"*.
 * The chips were added bare to a horizontal container — zero margins — so the whole strip
 * rendered as one long bar against the summary line above it.
 *
 * The rule: every chip goes through `addChip`, which attaches a trailing `ROW_GAP_DP` margin,
 * and the strip itself carries top/bottom margin against its neighbours.
 */
class ActivitySpacingTest {

    private val fragment = File("src/main/java/com/mido/phonebridge/ActivityFragment.kt")

    @Test
    fun `filter chips carry their own gaps and the strip breathes`() {
        assertTrue("ActivityFragment.kt missing", fragment.isFile)
        val source = fragment.readText()

        assertTrue(
            "chips must be added through the gapped addChip helper",
            source.contains("fun addChip(") &&
                source.contains("marginEnd = Theme.dp(ctx, Theme.ROW_GAP_DP)")
        )
        assertFalse(
            "no bare chips.addView(chip(...)) may remain",
            Regex("chips\\.addView\\(chip\\(").containsMatchIn(source)
        )
        assertTrue(
            "the summary line and the strip keep margins against their neighbours",
            source.contains("topMargin = Theme.dp(ctx, Theme.ROW_GAP_DP)") &&
                source.contains("bottomMargin = Theme.dp(ctx, Theme.ROW_GAP_DP)")
        )
    }
}
