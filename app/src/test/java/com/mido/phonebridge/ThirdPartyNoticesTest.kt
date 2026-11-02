package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The totality guard for `THIRD-PARTY-NOTICES.md`.
 *
 * A notices file is a legal claim, and it goes stale the first time someone adds a
 * dependency — silently, because nothing breaks. That is the same failure shape as an
 * unclassified error code: the artefact still works, and the claim it makes is no
 * longer true.
 *
 * `ErrorCodesTest` already solves this by scanning the source tree instead of trusting
 * a future author to remember. This does the same for `app/build.gradle.kts`: every
 * `implementation("group:artifact:version")` must have a row in the notices file that
 * names its `group:artifact`.
 *
 * **Why match on `group:artifact` and not the whole coordinate.** A version bump is not
 * a licence change, and a test that failed on every dependency update would be deleted
 * within a month. What must not happen quietly is a NEW component arriving with no
 * notice at all.
 *
 * `testImplementation` is deliberately out of scope: those never reach the APK and so
 * create no attribution obligation for anyone receiving the app. The notices file says
 * the same thing in its last section.
 */
class ThirdPartyNoticesTest {

    private val buildFile = File("build.gradle.kts")
    private val notices = File("../THIRD-PARTY-NOTICES.md")

    @Test
    fun `every shipped dependency is named in the notices file`() {
        val declared = shippedCoordinates()
        // Guard the guard: a scan that finds nothing has stopped testing, and would pass
        // for the worst possible reason.
        assertTrue(
            "scan found no implementation() lines — the scan itself is broken",
            declared.isNotEmpty()
        )

        val text = noticesText()
        val missing = declared.filterNot { text.contains(it) }.sorted()
        assertEquals(
            "these dependencies ship in the APK but have no entry in " +
                "THIRD-PARTY-NOTICES.md, so the attribution is incomplete: $missing",
            emptyList<String>(),
            missing
        )
    }

    @Test
    fun `the notices file exists and is not a stub`() {
        // A one-line placeholder would satisfy the check above while telling a recipient
        // nothing. The licence summary table alone is well past this.
        assertTrue("THIRD-PARTY-NOTICES.md is missing", notices.isFile)
        val text = noticesText()
        assertTrue(
            "THIRD-PARTY-NOTICES.md is suspiciously short (${text.length} chars) — " +
                "it looks like a stub rather than a real notices file",
            text.length > 1_000
        )
    }

    @Test
    fun `every non-Apache licence family is spelled out`() {
        // The three that actually require something of a redistributor. Apache-2.0 is
        // already in LICENSE, so restating it is optional; these are not.
        val text = noticesText()
        for (required in listOf("BSD 3-Clause", "ISC", "SIL Open Font License")) {
            assertTrue(
                "the notices file no longer mentions '$required', which one of the " +
                    "bundled components is licensed under",
                text.contains(required)
            )
        }
    }

    /** `group:artifact` for each `implementation("...")` line in the build file. */
    private fun shippedCoordinates(): List<String> {
        assertTrue("build.gradle.kts not found from ${File(".").absolutePath}", buildFile.isFile)
        val pattern = Regex("""implementation\("([^":]+):([^":]+):[^"]+"\)""")
        return buildFile.readText()
            .lines()
            .filter { it.trimStart().startsWith("implementation(") }
            .mapNotNull { line ->
                val m = pattern.find(line) ?: return@mapNotNull null
                "${m.groupValues[1]}:${m.groupValues[2]}"
            }
            .distinct()
    }

    private fun noticesText(): String {
        assertTrue("THIRD-PARTY-NOTICES.md is missing", notices.isFile)
        return notices.readText()
    }
}
