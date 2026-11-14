package com.mido.phonebridge.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** D12: exactly one route opts out of the hold, and it is the notification dismiss. */
class HoldExemptScanTest {

    @Test
    fun `holdExempt is set once, on the dismiss route`() {
        val root = File("src/main/java/com/mido/phonebridge/plugins")
        assertTrue("plugins tree not found at ${root.absolutePath}", root.isDirectory)
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (Regex("""holdExempt\s*=\s*true""").containsMatchIn(line)) Triple(f, i + 1, line) else null
            }
        }.toList()
        assertEquals("holdExempt = true must occur exactly once; saw $hits", 1, hits.size)
        val (file, _, line) = hits.single()
        assertEquals("NotificationsPlugin.kt", file.name)
        assertEquals("notifications", file.parentFile?.name)
        assertTrue(line, line.contains("\"/notifications/{id}/dismiss\""))
    }
}
