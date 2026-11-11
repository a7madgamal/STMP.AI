package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No colour literal, raw `textSize` or inline `Typeface` outside the token files.
 *
 * Violations that existed when a rule was introduced are listed in `token-baseline.tsv` as
 * `RULE<TAB>path<TAB>count`. The count may only go down: a higher count is a new violation, a
 * lower one means the baseline must be lowered. Regenerate with `STMP_WRITE_TOKEN_BASELINE=1`
 * (and `--no-daemon`).
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

    private fun format(v: Map<Pair<String, String>, Int>) =
        v.entries.joinToString("\n") { "${it.key.first}\t${it.key.second}\t${it.value}" } + "\n"

    @Test
    fun `violations never exceed the baseline`() {
        val current = violations()
        val baselineFile = File("src/test/resources/token-baseline.tsv")
        if (System.getenv("STMP_WRITE_TOKEN_BASELINE") == "1") {
            baselineFile.writeText(format(current))
            return
        }
        val baseline = baselineFile.readLines().filter { it.isNotBlank() }.associate {
            val (r, f, n) = it.split("\t")
            (r to f) to n.toInt()
        }
        val problems = mutableListOf<String>()
        for ((key, n) in current) {
            val allowed = baseline[key]
            when {
                allowed == null -> problems += "new violation: ${key.first} ${key.second} x$n"
                n > allowed -> problems += "new violation: ${key.first} ${key.second} $allowed -> $n"
                n < allowed -> problems += "you fixed something, lower the baseline: ${key.first} ${key.second} $allowed -> $n"
            }
        }
        for ((key, _) in baseline) {
            if (key !in current && scanned().none { it.first == key.second }) {
                problems += "baseline names a missing file: ${key.first} ${key.second}"
            } else if (key !in current) {
                problems += "you fixed something, lower the baseline: ${key.first} ${key.second} -> 0"
            }
        }
        assertTrue(
            problems.joinToString("\n") + "\n\nCurrent table:\n" + format(current),
            problems.isEmpty(),
        )
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
