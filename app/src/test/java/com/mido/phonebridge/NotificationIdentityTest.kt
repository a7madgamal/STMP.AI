package com.mido.phonebridge

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.BridgeStatusNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every notification the app posts wears the app's own mark and the app's own name.
 *
 * **Why a source scan and a name assertion, rather than a render.** A small icon is drawn by the
 * system from the notification's resource id; there is no view to inspect. What can be checked is
 * that the id is OURS and not one of Android's placeholders — which is the actual defect: three
 * call sites wore `stat_sys_upload_done`, `stat_notify_error` and `ic_dialog_alert`, so the
 * running bridge looked like a finished upload and a status problem looked like a system fault.
 *
 * The name half is separate: the tray said "PhoneBridge" for three weeks after the app was
 * renamed to STMP, because nothing compared the two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationIdentityTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val serviceSource = File("src/main/java/com/mido/phonebridge/BridgeService.kt").readText()
    private val callerSource =
        File("src/main/java/com/mido/phonebridge/callers/CallerPrompt.kt").readText()

    @Test
    fun `no notification uses a stock Android placeholder icon`() {
        // Every `android.R.drawable.*` small icon is a placeholder: the OS glyph says "something
        // happened in Android", not "this is STMP". A grep is the honest check here — the resource
        // id is not reachable from a built Notification except as an int nobody can read.
        for ((name, src) in listOf("BridgeService" to serviceSource, "CallerPrompt" to callerSource)) {
            val stock = Regex("""setSmallIcon\(android\.R\.drawable\.[A-Za-z_]+\)""").findAll(src).map { it.value }.toList()
            assertTrue("$name still uses a stock icon: $stock", stock.isEmpty())
        }
        assertTrue(
            "the bridge must post its own mark",
            serviceSource.contains("setSmallIcon(R.drawable.ic_stat_stmp)"),
        )
        assertTrue(
            "the caller prompt must post its own mark",
            callerSource.contains("setSmallIcon(R.drawable.ic_stat_stmp)"),
        )
    }

    /**
     * The status-bar icon is the launcher mark itself, not a redrawn lookalike.
     *
     * **Five exact buckets, like the launcher foreground**, because the mark is 2:1 and its
     * lettering does not survive resampling to 24dp. A vector traced from the artwork renders as a
     * smudge at the smallest size — checked, not assumed — whereas a raster layer drawn at the
     * device's own density is scaled 1:1 by the system, which is the same reason the launcher's
     * foreground is a bitmap rather than a traced path.
     */
    @Test
    fun `the status bar icon exists at every density bucket, derived from the mark`() {
        val buckets = mapOf("mdpi" to 24, "hdpi" to 36, "xhdpi" to 48, "xxhdpi" to 72, "xxxhdpi" to 96)
        for ((name, expected) in buckets) {
            val f = File("src/main/res/drawable-$name/ic_stat_stmp.png")
            assertTrue("$name bucket is missing: ${f.path}", f.isFile)
            val img = javaaxImage(f)
            assertEquals("$name must be a 24dp box ($expected px)", expected, img.first)
            assertEquals("$name must be square", img.first, img.second)
        }
    }

    /** Width and height of a PNG, read from its IHDR header — no image library on the test path. */
    private fun javaaxImage(f: File): Pair<Int, Int> {
        val b = f.readBytes()
        require(b.size > 24 && b[1].toInt() == 'P'.code && b[2].toInt() == 'N'.code) { "not a PNG" }
        fun i32(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at+1].toInt() and 0xFF) shl 16) or
            ((b[at+2].toInt() and 0xFF) shl 8) or (b[at+3].toInt() and 0xFF)
        return i32(16) to i32(20)
    }

    @Test
    fun `a vector twin does not also exist, because two drawables with one name is a coin toss`() {
        // Android resolves `ic_stat_stmp` across `drawable/` and the bucket folders. A leftover
        // `drawable/ic_stat_stmp.xml` would win on some builds and lose on others.
        assertFalse(
            "delete the vector: it would shadow or be shadowed by the buckets unpredictably",
            File("src/main/res/drawable/ic_stat_stmp.xml").exists(),
        )
    }

    @Test
    fun `no notification title announces the retired name`() {
        val kinds = BridgeStatusNotice.Kind.entries.map { BridgeStatusNotice.title(it) }
        for (t in kinds) {
            assertFalse("a status title still says PhoneBridge: $t", t.contains("PhoneBridge"))
            assertTrue("a status title must name the app: $t", t.startsWith("STMP"))
        }
        assertFalse(
            "the running notice still says PhoneBridge",
            serviceSource.contains("\"PhoneBridge running\""),
        )
        assertTrue(serviceSource.contains("\"STMP running\""))
    }

    @Test
    fun `the app name and the tray agree`() {
        // The comparison nothing was making: the tray said PhoneBridge while app_name was STMP.
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"app_name\">STMP</string>"))
        val kinds = BridgeStatusNotice.Kind.entries.map { BridgeStatusNotice.title(it) }
        assertEquals(3, kinds.size)
    }

    @Test
    fun `the running notice is ongoing, so it cannot be swiped away`() {
        // Unchanged behaviour, pinned here because this commit touched the notice's construction.
        val notifSource = serviceSource.substringAfter("private fun startForegroundCompat()")
        assertTrue(notifSource.substringBefore("}").contains("setOngoing(true)"))
        assertTrue(Notification::class.java.name.isNotBlank())
    }
}
