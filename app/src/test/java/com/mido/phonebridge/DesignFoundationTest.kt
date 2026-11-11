package com.mido.phonebridge

import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import androidx.core.content.res.ResourcesCompat
import com.mido.phonebridge.ui.RuggedToggle
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DesignFoundationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(android.graphics.Color.red(color)) +
            0.7152 * channel(android.graphics.Color.green(color)) +
            0.0722 * channel(android.graphics.Color.blue(color))
    }

    private fun contrast(fg: Int, bg: Int): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** Read PostScript names from the real TTFs; Robolectric may alias Typeface instances. */
    private fun postScriptNames(resourceName: String): Set<String> =
        postScriptNames(java.io.File("src/main/res/font/$resourceName.ttf").readBytes())

    private fun postScriptNames(bytes: ByteArray): Set<String> {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        val tableCount = buffer.getShort(4).toInt() and 0xffff
        var nameTable = -1
        for (index in 0 until tableCount) {
            val directory = 12 + index * 16
            if (bytes.copyOfRange(directory, directory + 4).decodeToString() == "name") {
                nameTable = buffer.getInt(directory + 8)
            }
        }
        assertTrue("name table missing", nameTable >= 0)
        val count = buffer.getShort(nameTable + 2).toInt() and 0xffff
        val strings = nameTable + (buffer.getShort(nameTable + 4).toInt() and 0xffff)
        val names = mutableSetOf<String>()
        for (index in 0 until count) {
            val record = nameTable + 6 + index * 12
            val platform = buffer.getShort(record).toInt() and 0xffff
            val nameId = buffer.getShort(record + 6).toInt() and 0xffff
            if (nameId != 6) continue
            val length = buffer.getShort(record + 8).toInt() and 0xffff
            val offset = strings + (buffer.getShort(record + 10).toInt() and 0xffff)
            require(offset >= 0 && offset + length <= bytes.size) { "invalid TTF name bounds" }
            val raw = bytes.copyOfRange(offset, offset + length)
            names += String(raw, if (platform == 0 || platform == 3) Charsets.UTF_16BE else Charsets.ISO_8859_1)
        }
        assertTrue("PostScript name missing", names.isNotEmpty())
        return names
    }

    @Test
    fun `semantic text and filled control pairs meet ordinary text contrast`() {
        val pairs = listOf(
            "secondary on paper" to (Theme.ON_SURFACE_VARIANT to Theme.BG),
            "secondary on a card" to (Theme.ON_SURFACE_VARIANT to Theme.SURFACE),
            "secondary on an inset" to (Theme.ON_SURFACE_VARIANT to Theme.SURFACE_VARIANT),
            "accent on a card" to (Theme.ACCENT to Theme.SURFACE),
            "success on paper" to (Theme.SUCCESS to Theme.BG),
            "warning on a card" to (Theme.WARNING to Theme.SURFACE),
            "danger on a card" to (Theme.DANGER to Theme.SURFACE),
            "primary button" to (Theme.ON_COLOR to Theme.ACCENT),
            "success button" to (Theme.ON_COLOR to Theme.SUCCESS),
            "danger button" to (Theme.ON_COLOR to Theme.DANGER),
        )
        val failures = pairs.filter { (_, pair) -> contrast(pair.first, pair.second) < 4.5 }
        assertTrue(
            failures.joinToString("\n") { (label, pair) ->
                "$label: ${contrast(pair.first, pair.second)}:1"
            },
            failures.isEmpty(),
        )
        assertNotEquals("brand ink must not double as destructive ink", Theme.ACCENT, Theme.DANGER)
        assertTrue("control boundaries need non-text contrast", contrast(Theme.BORDER, Theme.SURFACE) >= 3.0)
    }

    @Test
    fun `bundled fonts expose distinct real weights and roles`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_STMP)
        val context = activity
        val body = Theme.bodyFont(context)
        val bold = Theme.bodyFont(context, bold = true)
        val mono = Theme.monoFont(context)
        Theme.monoFont(context, bold = true)
        val fontData = java.io.File("src/main/res/font")
        assertTrue("font resources must exist on disk", fontData.isDirectory)
        for (name in listOf("atkinson_regular", "atkinson_bold", "jetbrains_mono_nl_regular", "jetbrains_mono_nl_bold")) {
            val bytes = java.io.File(fontData, "$name.ttf").readBytes()
            var weight = -1
            val tableCount = ((bytes[4].toInt() and 0xff) shl 8) or (bytes[5].toInt() and 0xff)
            for (index in 0 until tableCount) {
                val offset = 12 + index * 16
                if (bytes.copyOfRange(offset, offset + 4).decodeToString() == "OS/2") {
                    val table = ((bytes[offset + 8].toInt() and 0xff) shl 24) or
                        ((bytes[offset + 9].toInt() and 0xff) shl 16) or
                        ((bytes[offset + 10].toInt() and 0xff) shl 8) or
                        (bytes[offset + 11].toInt() and 0xff)
                    weight = ((bytes[table + 4].toInt() and 0xff) shl 8) or
                        (bytes[table + 5].toInt() and 0xff)
                }
            }
            assertTrue("OS/2 table missing from $name", weight >= 0)
            val expected = if (name.endsWith("_bold")) 700 else 400
            assertEquals("OS/2 weight in $name", expected, weight)
            val suffix = if (name.endsWith("_bold")) "Bold" else "Regular"
            assertTrue(
                "wrong PostScript face in $name: ${postScriptNames(bytes)}",
                postScriptNames(bytes).any { it.endsWith("-$suffix") },
            )
        }
        assertNotEquals("Atkinson must not collapse to the Handlee family", postScriptNames("atkinson_regular"), postScriptNames("handlee"))
        assertNotEquals("Atkinson and JetBrains Mono must be different families", postScriptNames("atkinson_regular"), postScriptNames("jetbrains_mono_nl_regular"))
        assertEquals(body, Theme.text(context, "body").typeface)
        assertEquals(mono, Theme.text(context, "code", mono = true).typeface)
        assertEquals(bold, Theme.button(context, "Approve") {}.typeface)
        assertEquals(Theme.handFont(context), Theme.sectionHeader(context, "section").typeface)
    }

    @Test
    fun `all four bundled weights are present as Android font resources`() {
        for (resource in listOf(
            R.font.atkinson_regular, R.font.atkinson_bold,
            R.font.jetbrains_mono_nl_regular, R.font.jetbrains_mono_nl_bold,
        )) {
            val typeface = ResourcesCompat.getFont(context, resource)
            assertTrue("font resource $resource is absent", typeface != null)
        }
    }

    @Test
    fun `paper switch exposes role state label and keyboard activation`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var request: Boolean? = null
        val toggle = Theme.toggle(activity, on = false) { request = it }.apply {
            setAccessibilityLabel("Bridge server")
        }
        activity.setContentView(toggle)

        val info = AccessibilityNodeInfo()
        toggle.onInitializeAccessibilityNodeInfo(info)
        assertEquals(Switch::class.java.name, info.className.toString())
        assertTrue(info.isCheckable)
        assertFalse(info.isChecked)
        assertTrue(info.contentDescription.toString().contains("Bridge server"))
        assertTrue(toggle.contentDescription.toString().contains("off"))
        assertTrue(toggle.isFocusable)
        assertTrue(toggle.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertEquals("a tap requests a change; it must not pretend it succeeded", true, request)
        assertFalse(toggle.checked)

        toggle.setCheckedImmediately(true)
        toggle.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.isChecked)
        assertTrue(toggle.contentDescription.toString().contains("on"))
        toggle.pending = true
        assertTrue(toggle.contentDescription.toString().contains("changing"))
        val bitmap = android.graphics.Bitmap.createBitmap(128, 96, android.graphics.Bitmap.Config.ARGB_8888)
        toggle.layout(0, 0, 128, 96)
        toggle.draw(android.graphics.Canvas(bitmap))
        bitmap.recycle()
    }

    @Test
    fun `Material controls use the app theme and retain their targets`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_STMP)
        val chip = Theme.chip(activity, "All") {}
        assertTrue(chip.minHeight >= Theme.dp(activity, Theme.MIN_TARGET_DP))
    }
}
