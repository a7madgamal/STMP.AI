package com.mido.phonebridge.kit

import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.Hints
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
    /** The original, uncut hint, in the BODY face at the field's own text size. */
    private fun fullWidth(edit: android.widget.EditText, hint: String): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.get(edit.context, TypeRole.BODY.face)
            textSize = edit.paint.textSize
        }
        return paint.measureText(hint)
    }

    private fun shown(view: android.widget.EditText): String = (view as com.mido.phonebridge.ui.kit.FieldEditText).drawnHint.toString()

    private fun hintWidth(view: android.widget.EditText): Float = fullWidth(view, shown(view))

    private fun laidOut(hint: String, widthDp: Int): android.widget.EditText {
        val ctx = RuntimeEnvironment.getApplication()
        val edit = Field.create(ctx, "X", "", hint, Field.Kind.URL).edit
        val w = (widthDp * ctx.resources.displayMetrics.density).toInt()
        edit.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        edit.layout(0, 0, w, edit.measuredHeight)
        return edit
    }

    @Test fun `every hint in the app fits its field uncut at font scale 1_35`() {
        RuntimeEnvironment.setFontScale(1.35f)
        assertTrue(Hints.all.isNotEmpty())
        // Narrowest field: a dialog on a 360dp phone, less dialog gutters and body padding.
        val cut = Hints.all.mapNotNull { hint ->
            val edit = laidOut(hint, 236)
            val inner = edit.width - edit.compoundPaddingLeft - edit.compoundPaddingRight
            val needed = fullWidth(edit, hint)
            "'$hint' needs $needed of $inner".takeIf { needed > inner }
        }
        assertTrue("hints that would be cut: $cut", cut.isEmpty())
    }

    @Test fun `every Field in the app takes its hint from Hints`() {
        val src = java.io.File("src/main/java/com/mido/phonebridge")
        val files = src.walkTopDown().filter { it.extension == "kt" && it.name != "Field.kt" }.toList()
        val creates = files.sumOf { Regex("""Field\.create\(""").findAll(it.readText()).count() }
        val used = files.sumOf { Regex("""Hints\.[A-Z_]+""").findAll(it.readText()).count() }
        assertEquals("every Field.create passes a Hints constant", creates, used)
    }

    @Test fun `TalkBack and events get the whole hint when the visible one is cut`() {
        RuntimeEnvironment.setFontScale(1.35f)
        val long = "Where to get the key from, with the full address (https://example.com/keys)"
        val edit = laidOut(long, 200)
        assertTrue(shown(edit), shown(edit).endsWith("…"))
        assertEquals(long, edit.hint.toString())
        val info = android.view.accessibility.AccessibilityNodeInfo()
        edit.onInitializeAccessibilityNodeInfo(info)
        assertEquals(long, info.hintText.toString())
        // An empty field also reports its hint as info.text (showingHintText): that is the whole hint too.
        assertEquals(long, info.text.toString())
        val event = android.view.accessibility.AccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        edit.onInitializeAccessibilityEvent(event)
        edit.onPopulateAccessibilityEvent(event)
        assertTrue(event.text.toString(), event.text.any { it.toString() == long })
    }

    @Test fun `a hint that is too long ends in an ellipsis and a short one is untouched`() {
        RuntimeEnvironment.setFontScale(1.35f)
        val long = laidOut("Where to get the key from, with the full address (https://example.com/keys)", 200)
        assertTrue(shown(long), shown(long).endsWith("…"))
        val inner = long.width - long.compoundPaddingLeft - long.compoundPaddingRight
        assertTrue(hintWidth(long) <= inner)
        assertEquals("Secret value", shown(laidOut("Secret value", 300)))
    }
}
