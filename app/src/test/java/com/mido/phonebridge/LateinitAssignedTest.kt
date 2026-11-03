package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Every `lateinit` field must be assigned somewhere in its own file.**
 *
 * v0.43.90 crashed on open with:
 *
 * ```
 * kotlin.UninitializedPropertyAccessException: lateinit property auditBanner has not been initialized
 *   at MainActivity.render(MainActivity.kt:519)
 *   at MainActivity.onCreate(MainActivity.kt:277)
 * ```
 *
 * When Home was trimmed, the block that CONSTRUCTED `auditBanner` was deleted while `render()`
 * — a separate method, ~200 lines away — went on painting it. Nothing links a `lateinit` field to
 * the code that fills it: the compiler only proves it is non-null where it is READ, and only at
 * the moment of the read. So the file compiled cleanly, all 429 tests passed, lint passed, and the
 * app died on the launch screen.
 *
 * That is the whole defect class in one sentence: **a `lateinit` field is a promise no tool
 * checks.** This test checks it. It is coarse — it proves an assignment EXISTS, not that it runs
 * on every path — but "exists" is exactly what was missing, and a missing assignment is the only
 * form of the bug that produces a crash rather than a stale value.
 *
 * Scoped to Activities because that is where the build/render split makes this possible: one
 * method creates the views, another updates them, and they are far enough apart to lose one.
 */
class LateinitAssignedTest {

    private val sourceDir = File("src/main/java/com/mido/phonebridge")

    @Test
    fun `every lateinit field is assigned in its own file`() {
        assertTrue("source dir not found at ${sourceDir.absolutePath}", sourceDir.isDirectory)

        val offenders = mutableListOf<String>()

        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (name in lateinitFields(text)) {
                    // Counted with the `=` so `auditBanner.visibility` does not count as an
                    // assignment — which is precisely the line that crashed.
                    // Both `name =` and `this.name =` assign. The qualified form is how a
                    // `buildContent(container)` parameter is stored, and missing it made the
                    // guard cry wolf on a field that is perfectly correct.
                    val assigned = Regex("""(?m)^\s*(?:this\.)?$name\s*=""").findAll(text).count()
                    // `by lazy` / `by delegates` are assignments in a different shape.
                    val delegated = Regex("""(?m)^\s*(?:this\.)?$name\s+by\s+""").containsMatchIn(text)
                    if (assigned == 0 && !delegated) {
                        offenders += "${file.name}: $name is declared lateinit but never assigned"
                    }
                }
            }

        assertTrue(
            "these fields are declared `lateinit` and never assigned, so reading one throws " +
                "UninitializedPropertyAccessException at runtime — which is how v0.43.90 crashed " +
                "on launch. Either assign it where the view is built, or make it nullable if it " +
                "really can be absent:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty()
        )
    }

    private fun lateinitFields(text: String): List<String> =
        Regex("""private\s+(?:lateinit\s+)?var\s+(\w+)""")
            .findAll(text)
            .filter { it.value.contains("lateinit") }
            .map { it.groupValues[1] }
            .toList()
            .ifEmpty {
                Regex("""private\s+lateinit\s+var\s+(\w+)""")
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .toList()
            }
}
