package com.mido.phonebridge.kit

import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.tokens.Fonts
import com.mido.phonebridge.ui.tokens.TypeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An EditText has a key listener, so TextView.ellipsize never applies: the kit cuts the hint itself. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class HintFitTest {
    /** Every hint string passed to Field.create in the app. */
    private val appHints = listOf(
        "API docs (https://…)", "Where to get the key (https://…)", "Name (lowercase, e.g. todoist)",
        "Secret value", "API docs URL (optional)", "Where to get it (optional)",
        "api.todoist.com, comma-separated", "api.todoist.com or *.todoist.com", "e.g. Laptop",
        "http://host:port", "Empty if the server trusts this phone", "name (lowercase, e.g. prod)",
        "host (e.g. box.example.com)", "port (default 22)", "user (e.g. deploy)",
    )

    private fun hintWidth(view: android.widget.EditText): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.get(view.context, TypeRole.BODY.face)
            textSize = view.paint.textSize
        }
        return paint.measureText(view.hint.toString())
    }

    private fun laidOut(hint: String, widthDp: Int): android.widget.EditText {
        val ctx = RuntimeEnvironment.getApplication()
        val edit = Field.create(ctx, "X", "", hint, Field.Kind.URL).edit
        val w = (widthDp * ctx.resources.displayMetrics.density).toInt()
        edit.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        edit.layout(0, 0, w, edit.measuredHeight)
        return edit
    }

    @Test fun `every hint in the app fits its field at font scale 1_35`() {
        RuntimeEnvironment.setFontScale(1.35f)
        // Narrowest field: a dialog on a 360dp phone, less dialog gutters and body padding.
        for (hint in appHints) {
            val edit = laidOut(hint, 236)
            val inner = edit.width - edit.compoundPaddingLeft - edit.compoundPaddingRight
            assertTrue("'$hint' is ${hintWidth(edit)} wide in $inner", hintWidth(edit) <= inner)
        }
    }

    @Test fun `a hint that is too long ends in an ellipsis and a short one is untouched`() {
        RuntimeEnvironment.setFontScale(1.35f)
        val long = laidOut("Where to get the key from, with the full address (https://example.com/keys)", 200)
        assertTrue(long.hint.toString(), long.hint.toString().endsWith("…"))
        val inner = long.width - long.compoundPaddingLeft - long.compoundPaddingRight
        assertTrue(hintWidth(long) <= inner)
        assertEquals("Secret value", laidOut("Secret value", 300).hint.toString())
    }
}
