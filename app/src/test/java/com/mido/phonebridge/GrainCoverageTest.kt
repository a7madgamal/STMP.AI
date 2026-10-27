package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every background must go through `Theme.sheet(...)`, so nothing can quietly ship flat.
 *
 * **Why this is a source scan and not a convention.** The paper grain shipped twice without
 * reaching the screen: first it went into `surface()` while the homepage cards use `tornCard()`,
 * then the root was still a flat `setBackgroundColor`. Both times the code looked right and the
 * device showed beige. A rule that lives in a comment is a rule that gets missed on the next
 * screen someone adds.
 *
 * So the rule is enforced where it can actually be broken — the source tree — in the same style as
 * [ErrorCodesTest]: find every raw background assignment and fail the build on one that is not
 * explicitly accounted for below.
 *
 * **The allowlist is deliberately tiny and each entry says why.** An empty allowlist would be
 * stricter but would force grain onto things it cannot help (a 3dp risk strip), and a rule that is
 * wrong gets worked around instead of followed.
 */
class GrainCoverageTest {

    /**
     * Sites that may construct a background directly, as `file` to `code prefix`, each with the
     * reason it is not paper.
     *
     * Matching is on FILE + the start of the code, not the whole `file:line:code` string: a line
     * number in the key would make every allowlist entry break the moment anything above it
     * shifts, and an allowlist that silently stops matching is worse than none — it fails open.
     */
    private val allowed = listOf(
        // A 3dp colour strip: there is no surface for grain to sit on, and tinting it is the
        // whole point of the view.
        "NotifyOverlay.kt" to "entry.riskStrip?.setBackgroundColor",
        // The countdown drain is a solid accent bar drawn UNDER the card's grain layer; giving it
        // its own texture would fight the bar's job of being a clean progress fill.
        "NotifyOverlay.kt" to "val drainFill = GradientDrawable()",
        // The card's base fill. It has to stay a `GradientDrawable` because the risk tint
        // recolours it at runtime (`entry.cardBg`), and the grain is already a SEPARATE layer
        // stacked on top of it — see the LayerDrawable in `buildCard`.
        "NotifyOverlay.kt" to "val cardBg = GradientDrawable()",
    )

    @Test
    fun `no screen paints a flat background`() {
        val offenders = rawBackgroundSites()
        // Guard the guard: an empty scan means the scanner broke, not that the app is clean.
        assertTrue(
            "scan found no background assignments at all — the scan itself is broken",
            offenders.isNotEmpty()
        )

        val unexplained = offenders.filter { (file, code) ->
            allowed.none { (okFile, okCode) -> file == okFile && code.startsWith(okCode) }
        }

        assertEquals(
            "these assign a background without Theme.sheet(...), so they ship flat and the " +
                "surface reads as beige UI instead of paper. Use Theme.sheet(color) / " +
                "Theme.sheetDp(ctx, color, radius), or add an entry to GrainCoverageTest.allowed " +
                "with the reason it cannot carry grain:\n  " +
                unexplained.joinToString("\n  ") { (f, c) -> "$f: $c" },
            emptyList<Pair<String, String>>(),
            unexplained
        )
    }

    /**
     * Every `setBackgroundColor(...)` / `GradientDrawable()` outside the `ui` package, as
     * `file:line-content`.
     *
     * The `ui` package is excluded because that is where the sanctioned constructors live —
     * including the ones that legitimately build a `GradientDrawable` and then layer grain over
     * it.
     */
    private fun rawBackgroundSites(): List<Pair<String, String>> {
        val root = File("src/main/java/com/mido/phonebridge")
        assertTrue("source root not found at ${root.absolutePath}", root.isDirectory)

        val hits = mutableListOf<Pair<String, String>>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.parentFile?.name != "ui" }
            .forEach { file ->
                file.readLines().forEach { line ->
                    val code = line.substringBefore("//").trim()
                    if (code.isEmpty()) return@forEach
                    val raw = code.contains("setBackgroundColor(") ||
                        code.contains("GradientDrawable()") ||
                        code.contains("ColorDrawable(")
                    if (raw) hits += file.name to code
                }
            }
        return hits
    }
}
