package com.mido.phonebridge.kit

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.TextView
import com.mido.phonebridge.ui.kit.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FieldTest : KitTest() {

    @Test
    fun `secret keeps the password transformation after inputType`() {
        val a = host()
        val f = Field.create(a, "Secret", "", "paste", Field.Kind.SECRET)
        assertTrue(f.edit.transformationMethod is PasswordTransformationMethod)
    }

    @Test
    fun `setError shows text, goes polite and keeps what was typed`() {
        val a = host()
        val f = Field.create(a, "Name", "abc", "name")
        a.place(f.root)
        f.setError("Name is taken")
        val err = f.root.allViews().filterIsInstance<TextView>().first { it.text.toString() == "Name is taken" }
        assertEquals(View.VISIBLE, err.visibility)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, err.accessibilityLiveRegion)
        assertEquals("abc", f.edit.text.toString())
        f.setError(null)
        assertEquals(View.GONE, err.visibility)
    }

    @Test
    fun `onChange fires per edit and the input is at least 48dp`() {
        val a = host()
        val seen = ArrayList<String>()
        val f = Field.create(a, "Name", "", "name", onChange = { seen.add(it) })
        a.place(f.root)
        f.edit.setText("a")
        f.edit.setText("ab")
        assertEquals(listOf("a", "ab"), seen)
        assertTrue(f.edit.height >= (47.5f * a.resources.displayMetrics.density).toInt())
    }

    @Test
    fun `disabling dims the input and not the label`() {
        val a = host()
        val f = Field.create(a, "Name", "keep", "name")
        a.place(f.root)
        f.setEnabled(false)
        assertEquals(false, f.edit.isEnabled)
        assertEquals(0.38f, f.edit.alpha, 0.001f)
        assertEquals(1f, f.root.allViews().filterIsInstance<TextView>().first { it !is android.widget.EditText }.alpha, 0f)
        assertEquals("keep", f.edit.text.toString())
    }
}
