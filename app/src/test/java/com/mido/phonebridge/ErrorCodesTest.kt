package com.mido.phonebridge

import com.mido.phonebridge.core.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The totality guard for [ErrorCodes].
 *
 * A `when` with `else -> 500` is a silent fallback, and it already cost us: `bad_lines`
 * shipped unclassified, so a caller's bad `?lines=` answered 500 and landed in the audit
 * log as `HANDLER_FAILED` — a non-noise server fault. The fix is not "be careful next
 * time", it is this test: it scans the main source for every `PluginError("...")` code
 * and fails if any of them is missing from the map.
 *
 * A source scan in a unit test is unusual, and it is the point. The rule is "every code
 * must be classified", and the only place that rule can be broken is the source tree, so
 * that is where the test looks. A code cannot be added quietly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ErrorCodesTest {

    @Test
    fun `every error code thrown in main source is classified`() {
        val codes = thrownCodes()
        // Guard the guard: if the scan stops finding anything, the test has silently
        // stopped testing. An empty result is a broken scan, not a clean codebase.
        assertTrue("scan found no PluginError codes — the scan itself is broken", codes.isNotEmpty())

        val unclassified = codes - ErrorCodes.classified()
        assertEquals(
            "these error codes have no entry in ErrorCodes.MAP, so they would answer 500 " +
                "and be recorded as a server fault: $unclassified",
            emptySet<String>(),
            unclassified
        )
    }

    @Test
    fun `every classified code carries a meaning`() {
        // `MAP` alone cannot be used: knowing `denied` is 403 does not say whether a human
        // refused or the phone was in a pocket, and those demand opposite responses. A
        // hand-maintained meanings map that nothing checks is wrong within two releases, so
        // this is the same guard as the status map — add a code without a meaning and the
        // build goes red.
        val withoutMeaning = ErrorCodes.classified() - ErrorCodes.meanings()
        assertEquals(
            "these codes would answer a status but tell a caller nothing: $withoutMeaning",
            emptySet<String>(),
            withoutMeaning,
        )

        val orphaned = ErrorCodes.meanings() - ErrorCodes.classified()
        assertEquals(
            "these meanings have no matching status entry: $orphaned",
            emptySet<String>(),
            orphaned,
        )

        // A meaning that is blank is worse than none: it looks answered.
        for (code in ErrorCodes.meanings()) {
            val meaning = ErrorCodes.meaningOf(code)
            assertTrue("$code has no meaning", meaning != null && meaning.isNotBlank())
        }
    }

    @Test
    fun `caller-fault codes are 4xx and never 5xx`() {
        // The line that matters. A malformed request must be noise in the log; if one of
        // these ever drifts to 5xx it becomes an incident-shaped record for a typo.
        for (code in listOf(
            "bad_request", "bad_count", "bad_package", "bad_time_min", "bad_time_max",
            "bad_time_range", "bad_max", "bad_lines", "unsupported_action",
            "missing_reply_text", "decode_error", "not_found", "notification_gone"
        )) {
            val status = ErrorCodes.statusFor(code)
            assertTrue("$code must be 4xx, got $status", status in 400..499)
        }
    }

    @Test
    fun `our-fault codes are 5xx`() {
        for (code in listOf(
            "auth_error", "secure_storage_error", "email_lookup_failed", "no_scopes",
            "internal_error", "policy_unavailable", "listener_unavailable",
            "auth_not_configured"
        )) {
            val status = ErrorCodes.statusFor(code)
            assertTrue("$code must be 5xx, got $status", status in 500..599)
        }
    }

    @Test
    fun `an unknown code is a 500, never a 4xx`() {
        // The safe direction: something we cannot classify must not be recorded as noise.
        assertEquals(500, ErrorCodes.statusFor("definitely_not_a_real_code"))
    }

    @Test
    fun `an unknown code is reported through the hook`() {
        // The alarm must actually fire — a hook nobody wired is the same as no hook.
        val seen = mutableListOf<String>()
        val previous = ErrorCodes.unclassified
        try {
            ErrorCodes.unclassified = { seen.add(it) }
            ErrorCodes.statusFor("definitely_not_a_real_code")
        } finally {
            ErrorCodes.unclassified = previous
        }
        assertEquals(listOf("definitely_not_a_real_code"), seen)
    }

    /**
     * Every `PluginError("code"` literal in `src/main/java`, found by reading the tree.
     *
     * `user.dir` is the module directory for a Gradle unit test, so the path is stable.
     * A missing directory throws rather than returning empty — a scan that cannot run
     * must fail loudly, not report a clean result.
     */
    private fun thrownCodes(): Set<String> {
        val root = File("src/main/java")
        assertTrue("main source tree not found at ${root.absolutePath}", root.isDirectory)

        val pattern = Regex("""PluginError\(\s*"([a-z_]+)"""")
        val found = mutableSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                for (match in pattern.findAll(file.readText())) {
                    found.add(match.groupValues[1])
                }
            }
        return found
    }
}
