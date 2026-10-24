package com.mido.phonebridge

import com.mido.phonebridge.core.PayloadShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The payload-shape rule behind a brokered call's summary.
 *
 * This exists because a real summary was useless: writing 8 todos showed the human
 * `todoist: /secrets/todoist/call` — a path. Mido's complaint, and the reason the AI was
 * added at all: *"the payload is the whole reason I added AI, so it's critical to describe
 * it too"*. The model can only name what it is told about, so the deterministic summary has
 * to describe the PAYLOAD: what is being written, and how much of it.
 *
 * Asserts against the REAL [PayloadShape] object. An earlier draft of this test duplicated
 * the logic and asserted against its own copy, so it could never fail — the exact
 * "test that cannot fail" trap. Extracting the rule made this test real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PayloadShapeTest {

    // The shape of the real request that prompted this: a Todoist batch sync adding 8 items.
    private val todoistSync = """
        {"commands":[
          {"type":"item_add","args":{"content":"Google Drive plugin"}},
          {"type":"item_add","args":{"content":"Proton Drive"}},
          {"type":"item_add","args":{"content":"Secrets templates"}},
          {"type":"item_add","args":{"content":"Host control"}},
          {"type":"item_add","args":{"content":"Sudo plugin"}},
          {"type":"item_add","args":{"content":"Tasker plugin"}},
          {"type":"item_add","args":{"content":"Accessibility"}},
          {"type":"item_add","args":{"content":"Push channel"}}
        ]}
    """.trimIndent()

    @Test
    fun `a batch of one kind counts them and names the kind`() {
        // The assertion that would have caught the complaint: the count and the verb both
        // have to be present, or the model cannot say "Adding 8 todos".
        assertEquals("8 commands (item_add ×8)", PayloadShape.of(todoistSync))
    }

    @Test
    fun `mixed kinds are listed separately`() {
        val body = """{"commands":[
            {"type":"item_add"},{"type":"item_close"},{"type":"item_close"}
        ]}"""
        assertEquals("3 commands (item_add ×1, item_close ×2)", PayloadShape.of(body))
    }

    @Test
    fun `an array without types is still counted`() {
        // Gmail's `to` is an array of scalars — the count is the fact, there is no kind.
        assertEquals("2 to (items)", PayloadShape.of("""{"to":["a@x.com","b@y.com"]}"""))
    }

    @Test
    fun `a scalar field quotes its value, so the model can name the thing`() {
        // The report that changed this (Mido, 2026-10-20): *"Says Create task with title and
        // description. This is toooo generic ... I want something like Creating a test todo
        // item"*. Field NAMES alone let the model only restate the field list; the value is
        // what lets it name the object.
        assertEquals(
            """content="test todo item"""",
            PayloadShape.of("""{"content":"test todo item"}""")
        )
    }

    @Test
    fun `a long value is clipped, because the full text is already in BODY`() {
        val long = "x".repeat(200)
        val out = PayloadShape.of("""{"content":"$long"}""")!!
        // The ellipsis lands INSIDE the quotes (`content="xxx…"`), not at the end of the
        // string — asserting endsWith was this test's own bug, not the code's.
        assertTrue("expected a clip marker, got: $out", out.contains("…"))
        assertTrue("clipped value should stay short, got ${out.length} chars", out.length < 90)
    }

    @Test
    fun `a credential-shaped field is named but its value is withheld`() {
        // This string reaches the model prompt AND the on-screen AI line, so echoing a token
        // would print it. The FIELD is still named so the model knows something was set.
        val out = PayloadShape.of(
            """{"access_token":"ya29.secretvalue","content":"ok"}"""
        )!!
        assertFalse("a token value must never appear: $out", out.contains("ya29"))
        assertTrue("the field name should still be reported: $out", out.contains("access_token"))
    }

    @Test
    fun `an empty array contributes nothing`() {
        assertNull(PayloadShape.of("""{"to":[],"bcc":[]}"""))
    }

    @Test
    fun `a non-object body has no shape to describe`() {
        // Null, never a fabricated shape: the caller falls back to a byte count, which is
        // honest about knowing nothing.
        assertNull(PayloadShape.of("""["not","an","object"]"""))
        assertNull(PayloadShape.of("plain text"))
        assertNull(PayloadShape.of(""))
    }
}
