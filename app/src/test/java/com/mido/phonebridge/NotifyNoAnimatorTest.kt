package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Don't-disturb card stays clock-driven (D18): no animator, no grain layer, no colour literal.
 *
 * Comment lines are skipped, the same rule `TokenCoverageTest` uses, because the file header
 * explains in prose why animators were removed and that history must stay readable.
 */
class NotifyNoAnimatorTest {

    private val overlay = File("src/main/java/com/mido/phonebridge/notify/NotifyOverlay.kt")

    private val code: List<String> by lazy {
        assertTrue("source not found at ${overlay.absolutePath}", overlay.isFile)
        overlay.readLines()
            .map { it.substringBefore("//").trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("import ") }
    }

    private val banned = listOf(
        Regex("""\bValueAnimator\b"""),
        Regex("""\bObjectAnimator\b"""),
        Regex("""\.animate\(\)"""),
        Regex("""\bGrainOverlay\b"""),
        Regex("""\b0x[0-9A-Fa-f]{8}\b"""),
    )

    @Test
    fun `the notify card uses no animator, no grain and no colour literal`() {
        val hits = code.filter { line -> banned.any { it.containsMatchIn(line) } }
        assertTrue(
            "NotifyOverlay is driven by one clock tick (file header); found:\n  " + hits.joinToString("\n  "),
            hits.isEmpty(),
        )
    }

    @Test
    fun `the notify card never uses the hand font`() {
        val hits = code.filter { it.contains("handFont") }
        assertTrue("D5: no handFont in NotifyOverlay; found:\n  " + hits.joinToString("\n  "), hits.isEmpty())
    }
}
