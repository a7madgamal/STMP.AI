package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No colour literal, raw `textSize`, inline `Typeface`, direct dialog or direct toast outside the
 * token and kit files. Zero tolerance: there is no baseline file (WP12 deleted it).
 */
class TokenCoverageTest {

    private class Rule(val id: String, val regex: Regex, val exempt: (String) -> Boolean)

    private val colorMasks = setOf("0x00FFFFFF", "0xFFFFFFFF")

    private val rules = listOf(
        Rule(
            "COLOR_LITERAL",
            Regex("""\b0x[0-9A-Fa-f]{8}\b|Color\.(argb|rgb|parseColor|valueOf)\(|Color\.(BLACK|WHITE|RED|GREEN|BLUE|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\b"""),
        ) { it.startsWith("ui/tokens/") || it == "ui/ShellTheme.kt" },
        Rule(
            "TEXT_SIZE",
            Regex("""textSize\s*=|setTextSize\(|Theme\.text\(.*\b\d+(\.\d+)?f\b"""),
        ) { it.startsWith("ui/tokens/") },
        Rule(
            "TYPEFACE",
            Regex("""Typeface\.(MONOSPACE|DEFAULT|DEFAULT_BOLD|SANS_SERIF|SERIF|create|createFromAsset|createFromFile|BOLD|ITALIC|BOLD_ITALIC|NORMAL)\b|ResourcesCompat\.getFont|R\.font\."""),
        ) { it.startsWith("ui/tokens/") },
        // Every dialog goes through StmpDialog (WP02).
        Rule(
            "DIALOG",
            Regex("""android\.app\.AlertDialog|AlertDialog\.Builder\("""),
        ) { it == "ui/kit/StmpDialog.kt" },
        // Every toast goes through Toasts.show (WP02).
        Rule(
            "TOAST",
            Regex("""Toast\.makeText"""),
        ) { it == "ui/kit/Toasts.kt" },
    )

    private fun root(name: String): File? =
        listOf("src/$name/java/com/mido/phonebridge").map { File(it) }.firstOrNull { it.isDirectory }

    private fun scanned(): List<Pair<String, File>> =
        listOfNotNull(root("main"), root("debug")).flatMap { base ->
            base.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(base).path to it }.toList()
        }

    private fun relevant(line: String): String? {
        val t = line.trim()
        if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) return null
        val code = line.substringBefore("//")
        return code.takeIf { it.isNotBlank() }
    }

    private fun violations(): Map<Pair<String, String>, Int> {
        val out = sortedMapOf<Pair<String, String>, Int>(compareBy({ it.first }, { it.second }))
        for ((path, file) in scanned()) {
            val lines = file.readLines().mapNotNull(::relevant)
            for (rule in rules) {
                if (rule.exempt(path)) continue
                val n = lines.sumOf { line ->
                    rule.regex.findAll(line).count { m -> m.value !in colorMasks }
                }
                if (n > 0) out[rule.id to path] = n
            }
        }
        return out
    }

    @Test
    fun `no rule has a single violation outside its exempt files`() {
        val current = violations()
        val table = current.entries.joinToString("\n") { "${it.key.first}\t${it.key.second}\t${it.value}" }
        assertTrue("token rule violations (zero tolerance, no baseline):\n$table", current.isEmpty())
    }

    @Test
    fun `the theme file holds no colour literal`() {
        val theme = scanned().first { it.first == "ui/Theme.kt" }.second
        val hits = theme.readLines().mapNotNull(::relevant).flatMap { l ->
            rules[0].regex.findAll(l).map { it.value }.filter { it !in colorMasks }.toList()
        }
        assertTrue("Theme.kt colour literals: $hits", hits.isEmpty())
    }
}
