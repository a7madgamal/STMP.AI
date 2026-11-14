package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Removing a rule must not throw the reader back to the top of the list.
 *
 * `save()` ends in `render()`, which clears and refills the whole column — and a cleared column
 * has `scrollY == 0`. So changing a rule halfway down a long list moved the screen to the header,
 * and the next rule the reader meant to touch was off-screen. The position has to be read BEFORE
 * the clear and restored AFTER the new rows are measured.
 *
 * Source-scanned because this compiles perfectly while being wrong: the reset is Android's
 * behaviour, not an error in the Kotlin.
 */
class ExceptionsScrollTest {

    private val source = File("src/main/java/com/mido/phonebridge/ExceptionsActivity.kt").readText()

    @Test
    fun `render keeps the scroll view and reads the position before clearing`() {
        assertTrue(
            "ExceptionsActivity must hold the scroll view to restore it",
            source.contains("private var scroll: ScrollView? = null")
        )
        val body = source.substringAfter("private fun render()").substringBefore("\n    /**")
        assertTrue("render must capture the current position", body.contains("val keepY = scroll?.scrollY ?: 0"))
        assertTrue(
            "the position must be read BEFORE the column is cleared, or it is already 0",
            body.indexOf("val keepY") < body.indexOf("container.removeAllViews()")
        )
    }

    @Test
    fun `the restore is posted, because a fresh layout has no height yet`() {
        val body = source.substringAfter("private fun render()").substringBefore("\n    /**")
        assertTrue(
            "the restore must be posted to the next layout pass",
            body.contains("scroll?.post { scroll?.scrollTo(0, keepY) }")
        )
        assertFalse(
            "scrolling synchronously would clamp to the old, now-cleared height",
            body.contains("scroll?.scrollTo(0, keepY)\n    }")
        )
    }
}
