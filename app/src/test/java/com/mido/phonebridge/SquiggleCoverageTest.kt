package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every card and button must carry the drawn edge, so a new screen cannot ship a straight border.
 *
 * **Why a source scan.** Grain shipped twice without reaching the screen — once into the wrong
 * helper, once under a flat root — and both times the code read correctly. The border has the same
 * failure shape: a screen that builds its own `MaterialButton` or its own stroked
 * `GradientDrawable` looks right in review and is the only flat thing on the device.
 *
 * The scan covers the WHOLE source tree, `ui` included, and allowlists the few sanctioned sites.
 * Scanning only outside `ui` was the first attempt and it was wrong: `Theme` is the only place
 * either call appears, so the scan found nothing and its own empty-result guard fired. An
 * allowlist makes the sanctioned sites explicit and keeps the guard meaningful.
 */
class SquiggleCoverageTest {

    /**
     * Sites allowed to build a control or a stroke directly, as `file` to code prefix.
     *
     * Matched on file + code prefix, never a line number: a key containing a line number stops
     * matching the moment anything above it shifts, which makes the allowlist fail open.
     */
    private val allowed = listOf(
        // The one button factory. Every screen goes through it.
        "Theme.kt" to "return MaterialButton(ctx).apply",
        "Theme.kt" to "return MaterialButton(ctx).apply {",
        // The progress button: its own background is a two-tone track+fill, so the drawn edge is
        // layered on afterwards rather than replacing it.
        "Theme.kt" to "return MaterialButton(ctx).apply",
        // GHOST buttons keep their hairline stroke under the drawn edge; the wobble sits on top
        // of it so the edge still reads as ink at 1dp.
        "Theme.kt" to "strokeWidth = dp(ctx, 1)",
    )

    @Test
    fun `no screen builds its own button or stroked outline`() {
        val sites = controlSites()
        // Guard the guard: an empty scan means the scanner broke, not that the app is clean.
        assertTrue(
            "scan found no controls or strokes at all — the scan itself is broken",
            sites.isNotEmpty()
        )

        val unexplained = sites.filter { (file, code) ->
            allowed.none { (okFile, okCode) -> file == okFile && code.startsWith(okCode) }
        }

        assertEquals(
            "these build a control or a stroked outline outside the sanctioned Theme factories, " +
                "so they ship with a straight edge while everything else is drawn by hand. Use " +
                "Theme.button / Theme.chip / Theme.outlined:\n  " +
                unexplained.joinToString("\n  ") { (f, c) -> "$f: $c" },
            emptyList<Pair<String, String>>(),
            unexplained
        )
    }

    /** `MaterialButton(` and `setStroke(` anywhere under the source root. */
    private fun controlSites(): List<Pair<String, String>> {
        val root = File("src/main/java/com/mido/phonebridge")
        assertTrue("source root not found at ${root.absolutePath}", root.isDirectory)

        val hits = mutableListOf<Pair<String, String>>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEach { line ->
                    val code = line.substringBefore("//").trim()
                    if (code.isEmpty()) return@forEach
                    if (code.contains("MaterialButton(") || code.contains("setStroke(")) {
                        hits += file.name to code
                    }
                }
            }
        return hits
    }
}
