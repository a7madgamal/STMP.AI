package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The three audit and policy screens keep to the kit: none falls back to the old helpers (WP08). */
class LogSourceScanTest {
    private val names = listOf("LogActivity.kt", "LogDetailActivity.kt", "ExceptionsActivity.kt")
    private val dir = File("src/main/java/com/mido/phonebridge")

    private fun sources() = names.map { it to File(dir, it).readText() }

    private fun assertNone(regex: Regex, what: String) {
        val hits = sources().filter { regex.containsMatchIn(it.second) }.map { it.first }
        assertTrue("$what in $hits", hits.isEmpty())
    }

    @Test fun `no optString with an empty fallback`() = assertNone(Regex("""optString\([^)]*,\s*""\)"""), "optString(.., \"\")")

    @Test fun `no platform toast`() = assertNone(Regex("""Toast\.makeText"""), "Toast.makeText")

    @Test fun `no platform alert dialog builder`() = assertNone(Regex("""AlertDialog\.Builder\("""), "AlertDialog.Builder(")

    @Test fun `no plain Activity base`() = assertNone(Regex(""":\s*Activity\(\)"""), "Activity() base")

    @Test fun `no old theme helpers`() = assertNone(
        Regex("""Theme\.(BG|SURFACE|ON_|ACCENT|SUCCESS|WARNING|DANGER|BORDER|text|button|chip|rippled|sheet|inset|sectionHeader)"""),
        "an old Theme helper",
    )

    @Test fun `no emoji except the explainer's cross`() {
        val bad = sources().filter { (_, text) ->
            text.codePoints().toArray().any { (it in 0x1F300..0x1FAFF || it in 0x2600..0x27BF) && it != 0x2715 }
        }.map { it.first }
        assertTrue("emoji in $bad", bad.isEmpty())
    }
}
