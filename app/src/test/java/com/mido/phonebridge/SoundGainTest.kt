package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's cues play at half their old level, and the level lives in ONE place per file.
 *
 * **Why a source scan.** There is no audio output in a JVM test and no `SoundPool` on Robolectric,
 * so "is the crease quieter" cannot be asserted by playing it. What CAN be asserted is the thing
 * that goes wrong next: a literal creeping back in beside the constant, so the next "turn it down"
 * changes one call site and misses three. That is how these four numbers drifted apart in the first
 * place — they were 0.7, 0.85 and 35 with no relationship to each other.
 *
 * The scan also pins the parts of the rule that are decisions rather than taste: the tone's unit is
 * different, the pitch must stay 1.0, and haptics are explicitly out of scope.
 */
class SoundGainTest {

    private fun source(rel: String): String {
        val f = File("src/main/java/com/mido/phonebridge/alert/$rel")
        assertTrue("source not found at ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** Every `play(...)` in these two files must take its volume from the named gain. */
    @Test
    fun `no sound call passes a literal volume`() {
        for (file in listOf("SoundAlerts.kt", "AudioCues.kt")) {
            val lines = source(file).lines()
                .map { it.substringBefore("//") }
                .filter { it.contains(".play(") }
            assertTrue("$file should contain a play() call", lines.isNotEmpty())
            for (line in lines) {
                assertTrue(
                    "$file passes a literal gain, so a future change will miss it:\n$line",
                    line.contains("GAIN"),
                )
            }
        }
    }

    /**
     * Pitch stays 1.0 everywhere: loudness and pitch are different properties, and a "quieter"
     * change that also slowed a sample down would be a different sound, not the same one at a lower
     * volume.
     */
    @Test
    fun `pitch is untouched`() {
        for (file in listOf("SoundAlerts.kt", "AudioCues.kt")) {
            source(file).lines()
                .filter { it.contains(".play(") && !it.trimStart().startsWith("*") }
                .forEach { line ->
                    assertTrue("pitch must stay 1.0f, not follow the gain:\n$line", line.contains("1.0f"))
                }
        }
    }

    /** Both channels take the same value, or a cue plays in one ear. */
    @Test
    fun `both channels are equal`() {
        for (file in listOf("SoundAlerts.kt", "AudioCues.kt")) {
            source(file).lines()
                .filter { it.contains(".play(") && !it.trimStart().startsWith("*") }
                .forEach { line ->
                    val args = line.substringAfter(".play(").substringBefore(")")
                    val parts = args.split(",").map { it.trim() }
                    assertTrue("a play() call should have at least 3 args:\n$line", parts.size >= 3)
                    assertTrue(
                        "left and right must be the same expression:\n$line",
                        parts[1].contains("GAIN") && parts[1] == parts[2],
                    )
                }
        }
    }

    /** The tone is its own unit, so it is its own constant — and it halved. */
    @Test
    fun `the notify tone is a named constant at half volume`() {
        val src = source("SoundAlerts.kt")
        assertTrue("the tick must use the named constant", src.contains("tone(ctx, volume = NOTIFY_TONE_VOLUME)"))
        assertTrue("the constant must be 18 (half of 35)", src.contains("NOTIFY_TONE_VOLUME = 18"))
    }

    /** Haptics are explicitly out of scope: this change is audio only. */
    @Test
    fun `vibration is untouched by the audio change`() {
        val src = source("SoundAlerts.kt")
        assertTrue(
            "the stamp's haptic must keep its original amplitude",
            src.contains("intArrayOf(0, 160)"),
        )
    }
}
