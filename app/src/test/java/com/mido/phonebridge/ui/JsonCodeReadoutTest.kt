package com.mido.phonebridge.ui

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** JSON on the gate's dark readout uses only readout roles (TOKENS 3.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JsonCodeReadoutTest {

    private val p get() = Palettes.of(RuntimeEnvironment.getApplication())

    @Test
    fun `readoutInk covers every kind`() {
        assertEquals(JsonKind.values().toSet(), JsonCode.readoutInk(p).keys)
    }

    @Test
    fun `a rendered body uses only readout roles`() {
        val roles = setOf(p.readoutText, p.readoutCommand, p.readoutMuted, p.readoutString)
        val raw = """{"url":"https:\/\/api.example.com\/v1","n":3,"ok":true,"tags":["a","b"],"x":null}"""
        val r = JsonCode.render(raw, JsonCode.readoutInk(p)) as Spanned
        val spans = r.getSpans(0, r.length, ForegroundColorSpan::class.java)
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.foregroundColor in roles })
    }
}
