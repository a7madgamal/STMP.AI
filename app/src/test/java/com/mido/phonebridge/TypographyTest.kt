package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Nothing in this app may render text below the documented floor.
 *
 * **Why a scan.** Sizes drifted to ten different values across nine files with no rule, and the
 * smallest — 9, 10 and 11sp — were hard to read on a phone at arm's length. Mido, 2026-11-04:
 * *"review all font sizes, in general I feel some can be hard to read so increase the smallest
 * font size"*. That is not a bug you can see in one file; it is a property of the whole tree, so
 * it is checked against the whole tree.
 *
 * The floor lives in [com.mido.phonebridge.ui.Theme.TEXT_FLOOR_SP] and the scale beside it, so
 * there is one place to change a size and one place that defines "too small".
 */
class TypographyTest {

    private val root = File("src/main/java/com/mido/phonebridge")

    /**
     * Below this, text is unreadable at normal phone distance. 12sp is the scale's floor; this
     * test enforces it rather than trusting that the previous sweep stayed swept.
     */
    private val floorSp = 12.0

    @Test
    fun `no text renders below the floor`() {
        assertTrue("source root not found at ${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val line = raw.substringBefore("//")

                    // SHAPE 1: `textSize = 11f` — unambiguous.
                    Regex("textSize\\s*=\\s*(\\d+(?:\\.\\d+)?)f").findAll(line).forEach { m ->
                        val size = m.groupValues[1].toDoubleOrNull() ?: return@forEach
                        if (size < floorSp) {
                            offenders += "${file.name}:${index + 1}  ${line.trim()}"
                        }
                    }

                    // SHAPE 2: `Theme.text(ctx, "…", 11f, …)` — a size argument in a text call.
                    //
                    // Matched ONLY on the Theme.text / Theme.text(call shape. A general
                    // "number followed by f" match is useless here: it also catches animation
                    // fractions (`ofFloat(this, "alpha", 0f, 1f)`) and layout weights
                    // (`LayoutParams(0, dp(ctx, 52), 1f)`), and a guard that cries wolf gets
                    // disabled rather than fixed.
                    if (line.contains("Theme.text(")) {
                        Regex(",\\s*(\\d+(?:\\.\\d+)?)f\\s*,").findAll(line).forEach { m ->
                            val size = m.groupValues[1].toDoubleOrNull() ?: return@forEach
                            if (size < floorSp && size > 0.0 && size < 40.0) {
                                // A dp/cornerRadius argument can also sit between commas; only
                                // flag values that plausibly ARE type sizes.
                                if (size >= 6.0) {
                                    offenders += "${file.name}:${index + 1}  ${line.trim()}"
                                }
                            }
                        }
                    }
                }
            }

        assertTrue(
            "these render text smaller than ${floorSp}sp, which is unreadable on a phone at " +
                "arm's length. Use Theme's scale (TEXT_FLOOR_SP is the floor):\n  " +
                offenders.joinToString("\n  "),
            offenders.isEmpty()
        )
    }
}
