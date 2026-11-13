package com.mido.phonebridge.kit

import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.balanceWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class R4PolishTest {
    @Test fun `a long status word breaks in the middle without an orphan`() {
        assertEquals("NOT IN THIS BUILD", balanceWord("NOT IN THIS BUILD"))
        assertEquals("OFF", balanceWord("OFF"))
        assertEquals("NOT SET UP", balanceWord("NOT SET UP"))
    }

    @Test fun `a field hint keeps its text and is cut with an ellipsis`() {
        for (scale in listOf(1f, 1.35f)) {
            RuntimeEnvironment.setFontScale(scale)
            val ctx = RuntimeEnvironment.getApplication()
            val h = Field.create(ctx, "URL", "", "Where to get it (optional)", Field.Kind.URL)
            assertEquals("Where to get it (optional)", h.edit.hint.toString())
            assertTrue(h.edit.ellipsize == android.text.TextUtils.TruncateAt.END)
        }
    }
}
