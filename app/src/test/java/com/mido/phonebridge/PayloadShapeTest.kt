package com.mido.phonebridge

import com.mido.phonebridge.core.PayloadShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun `a scalar field names the field, never its value`() {
        // The value is already a modal row and may be long or sensitive; the summary says
        // that something is being SET, not what it is set to.
        assertEquals("subject", PayloadShape.of("""{"subject":"very long subject line"}"""))
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
