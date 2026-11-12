package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** D21: no string literal a user can see says PhoneBridge. The allowlist is the few that must stay. */
class NoPhoneBridgeInUiTest {

    private val allowlist = setOf("Debug.kt", "PhoneSave.kt", "SshExec.kt")

    private fun root(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }

    private fun literals(line: String): List<String> =
        Regex(""""(?:[^"\\]|\\.)*"""").findAll(line).map { it.value }.toList()

    @Test
    fun `no string literal outside the allowlist names PhoneBridge`() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        root().walkTopDown().filter { it.extension == "kt" && it.name !in allowlist }.forEach { f ->
            scanned++
            var inBlock = false
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (inBlock) { if (line.contains("*/")) inBlock = false; return@forEachIndexed }
                if (line.startsWith("/*")) { if (!line.contains("*/")) inBlock = true; return@forEachIndexed }
                if (line.startsWith("//") || line.startsWith("*") ||
                    line.startsWith("package ") || line.startsWith("import ")) return@forEachIndexed
                // A trailing comment is not UI either.
                val code = line.substringBefore(" // ")
                if (literals(code).any { Regex("Phone ?Bridge").containsMatchIn(it) }) {
                    offenders += "${f.name}:${i + 1}: $line"
                }
            }
        }
        assertTrue("scan found no sources", scanned > 50)
        assertTrue("user-visible PhoneBridge:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
