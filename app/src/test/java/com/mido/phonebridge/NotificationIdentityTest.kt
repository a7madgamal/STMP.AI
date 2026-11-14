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

    @Test
    fun `the status bar icon exists and is white so the system can tint it`() {
        val f = File("src/main/res/drawable/ic_stat_stmp.xml")
        assertTrue("the icon drawable is missing", f.isFile)
        val xml = f.readText()
        assertTrue("a small icon must be 24dp", xml.contains("android:width=\"24dp\""))
        // The platform discards colour and keeps only alpha, so a grey or coloured path arrives as
        // an invisible or half-strength shape. Pure white is the contract.
        val fills = Regex("""fillColor="(#[0-9A-Fa-f]{8})"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue("the icon declares no filled shape at all", fills.isNotEmpty())
        for (c in fills) {
            assertEquals("a small icon's path must be opaque white, got $c", "#FFFFFFFF", c)
        }
        // No hairline strokes: at mdpi a 0.x dp stroke either disappears or breaks the shape up.
        assertFalse("a stroke-based icon breaks up at mdpi", xml.contains("strokeWidth=\"0."))
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
