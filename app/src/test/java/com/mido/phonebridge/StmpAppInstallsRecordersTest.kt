package com.mido.phonebridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A later upstream merge must not silently drop crash recording or stall detection again. */
class StmpAppInstallsRecordersTest {
    private val source = File("src/main/java/com/mido/phonebridge/StmpApp.kt").readText()

    @Test
    fun installsCrashRecorder() {
        assertTrue(source.contains("Crash.install(this)"))
    }

    @Test
    fun installsStallDetector() {
        assertTrue(source.contains("Stall.install(this)"))
    }
}
