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
        // The countdown drain is a solid accent bar drawn UNDER the card's grain layer; giving it
        // its own texture would fight the bar's job of being a clean progress fill.
        "NotifyOverlay.kt" to "val drainFill = GradientDrawable()",
        // The card's base fill. It has to stay a `GradientDrawable` because the risk tint
        // recolours it at runtime (`entry.cardBg`), and the grain is already a SEPARATE layer
        // stacked on top of it — see the LayerDrawable in `buildCard`.
        "NotifyOverlay.kt" to "val cardBg = GradientDrawable()",
        // The reject button's countdown fill. It is a PROGRESS FILL, not a surface: it is meant
        // to read as a clean tinted wash shrinking inside the 44x30 touch box, and grain inside
        // a shape that narrow would render as a speck rather than texture. The card behind it
        // still carries grain, and the button sits on top of that.
        // The countdown wash behind the hint card. A fill that DRAINS cannot carry grain: the
        // grain layer sits on top of it (see the LayerDrawable in `buildCard`), and texturing the
        // draining layer instead would make the texture shrink with the bar.
        "NotifyOverlay.kt" to "val entryDrain = ClipDrawable(",
        // The draining layer's own fill. It is the CLIPPED half of a wash: texturing it would
        // make the texture shrink with the bar instead of lying on the card, and the card's own
        // grain layer sits above both.
        "NotifyOverlay.kt" to "GradientDrawable().apply {",
        // The modal's DENY bar. Same reasoning: `denyTrack` is the trough and the fill below it
        // drains, so neither can carry grain — grain on a bar that shrinks would crawl as it
        // drains, which is worse than a flat bar. The modal sheet behind it is textured.
        "ApprovalActivity.kt" to "val denyTrack = GradientDrawable().apply {",
        "ApprovalActivity.kt" to "GradientDrawable().apply {",
        // A 1dp hairline between rows inside the Google card. Grain cannot live in a 1dp strip --
        // there is no surface to texture, and the grain tile is 96px -- so this is deliberately a
        // flat coloured line, the same as `SquiggleRule`'s flat mode.
        "PluginInventoryView.kt" to "setBackgroundColor(Theme.BORDER)",
        // A 10dp status dot: an OVAL, so grain inside it would be clipped to a circle smaller
        // than one grain tile and would render as a flat speck.
        "Theme.kt" to "shape = GradientDrawable.OVAL",
        // The progress track and fill. Both are drawn UNDER the GrainOverlay layer that
        // `progressButton` stacks on top of them.
        "Theme.kt" to "val track = GradientDrawable()",
        "Theme.kt" to "setColor(bg)",
        // `backgroundTintList` is a TINT, not a drawable: it recolours the MaterialButton's own
        // background so the ripple, pressed and disabled states survive. Grain is layered in the
        // button's FOREGROUND instead — see `button()`.
        "HomeFragment.kt" to "serverButton.backgroundTintList",
        "HomeFragment.kt" to "loginButton.backgroundTintList",
        // The gate's fingerprint control, same rule as the two buttons above: a TINT on a
        // MaterialButton, so its ripple and pressed states survive. Grain is layered in the
        // button's foreground by `Theme.button`.
        "HomeFragment.kt" to "gateFingerprint.backgroundTintList",
        "ApprovalActivity.kt" to "allow.backgroundTintList",
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
                        code.contains("ColorDrawable(") ||
                        code.contains("backgroundTintList =")
                    if (raw) hits += file.name to code
                }
            }
        return hits
    }
}
