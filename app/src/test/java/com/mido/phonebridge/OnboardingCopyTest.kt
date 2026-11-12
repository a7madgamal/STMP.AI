package com.mido.phonebridge

import com.mido.phonebridge.ui.OnboardingCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingCopyTest {

    private val base = "http://100.101.7.22:8787"

    @Test fun `steps are numbered consecutively`() {
        OnboardingCopy.steps().forEachIndexed { i, s -> assertTrue(s.startsWith("${i + 1}. ")) }
        assertEquals(4, OnboardingCopy.steps().size)
        OnboardingCopy.hermesSteps().forEachIndexed { i, s -> assertTrue(s.startsWith("${i + 1}. ")) }
        assertEquals(5, OnboardingCopy.hermesSteps().size)
    }

    @Test fun `groups are the five in order without jargon`() {
        assertEquals(listOf("Google", "Phone", "Machine", "Voice", "Diagnostics"), OnboardingCopy.GROUPS.map { it.first })
        OnboardingCopy.GROUPS.forEach {
            assertFalse(it.second.contains("audit", ignoreCase = true))
            assertFalse(it.first == "Plumbing")
        }
    }

    @Test fun `base url falls back to a placeholder`() {
        assertEquals("http://<start STMP first>:8787", OnboardingCopy.baseUrl("", 8787))
        assertFalse(OnboardingCopy.baseUrl("", 8787).contains("server"))
        assertEquals(base, OnboardingCopy.baseUrl("100.101.7.22", 8787))
    }

    @Test fun `prompt keeps its rules and is not hard wrapped`() {
        val p = OnboardingCopy.buildPrompt(base)
        listOf("GET $base/help", "GET $base/probe", "caller_unverified", "caller_denied").forEach { assertTrue(it, p.contains(it)) }
        assertFalse(p.contains("describing it in\n"))
        assertFalse(p.contains("approval,\n"))
    }

    @Test fun `hermes commands are two lines`() {
        val lines = OnboardingCopy.hermesCommands(base).lines()
        assertEquals(2, lines.size)
        assertEquals("hermes plugins install a7madgamal/hermes-stmp --enable", lines[0])
        assertTrue(lines[1].endsWith(base))
    }

    @Test fun `glossary words are absent from the copy and the activity`() {
        val banned = listOf("PhoneBridge", "Phone Bridge", " ? ", "audit", "AUTOMATIC", "OPTIONS", "Settings →")
        val files = listOf(
            "src/main/java/com/mido/phonebridge/ui/OnboardingCopy.kt",
            "src/main/java/com/mido/phonebridge/OnboardingActivity.kt",
        ).map { File(it) }
        for (f in files) {
            val text = f.readLines().map { it.trim() }
                .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
                .joinToString("\n") { it.substringBefore(" // ") }
            banned.forEach { assertFalse("${f.name} contains '$it'", text.contains(it)) }
        }
    }
}
