package com.mido.phonebridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The approval modal has to survive a keyboard, because one of its own controls opens one.
 *
 * **This is a regression guard with a real failure behind it.** The reason sheet focuses an
 * `EditText`, which raises the IME over the pinned block — the field the human was just invited to
 * type into ended up underneath it. The report was one line: *"works, but keyboard is hiding the
 * field when erect"*.
 *
 * Two separate facts make it work and both are easy to delete without noticing: `adjustResize` on
 * the activity (so the window shrinks instead of being covered) and the IME inset in the layout
 * listener (so the content moves out of the space the keyboard took). A screenshot cannot catch
 * either, because Robolectric draws no keyboard — so this is a source scan, in the same style as
 * [GrainCoverageTest]: the rule is enforced where it can actually be broken.
 */
class ImeInsetsTest {

    private fun manifest(): String {
        val f = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `the approval activity resizes for the keyboard`() {
        val m = manifest()
        val start = m.indexOf(".ApprovalActivity")
        assertTrue("ApprovalActivity is not declared in the manifest", start >= 0)
        // The element ends at the next `/>`; the attributes that matter are inside it.
        val element = m.substring(start, m.indexOf("/>", start))
        assertTrue(
            "ApprovalActivity must declare android:windowSoftInputMode=\"adjustResize\", or the " +
                "reason sheet's keyboard covers the field it just focused:\n$element",
            element.contains("windowSoftInputMode=\"adjustResize\""),
        )
    }

    @Test
    fun `the modal pads for the keyboard, not only the system bars`() {
        val src = File("src/main/java/com/mido/phonebridge/ApprovalActivity.kt")
        assertTrue("source not found", src.isFile)
        val text = src.readText()
        assertTrue(
            "the inset listener must read WindowInsetsCompat.Type.ime(); with system bars alone " +
                "the pinned block stays under the keyboard",
            text.contains("WindowInsetsCompat.Type.ime()"),
        )
        // `maxOf`, not a sum: the IME is drawn over the navigation bar, so adding the two would
        // reserve a bar's height twice and push the buttons up out of the sheet.
        assertTrue(
            "the bottom inset must combine bars and IME with maxOf(...)",
            text.contains("maxOf(bars.bottom, ime.bottom)"),
        )
    }
}
