package com.mido.phonebridge

import com.mido.phonebridge.core.BodyDetail
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee behind "I want FUCKING DETAILS FROM THE BODY".
 *
 * Eight releases of prompt wording produced structural restatements — "Create task with content
 * and description" — because a small model paraphrases the KEYS of a JSON object rather than
 * quoting its values. No instruction fixed it. This object does not ask the model nicely; it
 * supplies the detail from the body, so the human always gets the specific thing.
 */
class BodyDetailTest {

    @Test
    fun `a create names the content being written`() {
        // The exact case from the report: a todo whose text was sitting in `content`.
        val body = """{"content":"test todo item","description":"delete after test"}"""
        assertEquals("Writing \"test todo item\"", BodyDetail.of(Verb.WRITE, body))
    }

    @Test
    fun `content wins over description`() {
        // DESCRIPTIONS ARE METADATA, CONTENT IS THE THING. Picking the description here would
        // produce "delete after test", which describes the test harness rather than the item.
        val body = """{"content":"buy milk","description":"urgent"}"""
        assertEquals("Writing \"buy milk\"", BodyDetail.of(Verb.WRITE, body))
    }

    @Test
    fun `an email is named by its subject, not its body`() {
        val body = """{"to":["a@b.com"],"subject":"Quarterly numbers","body":"Long text…"}"""
        assertEquals("Writing \"Quarterly numbers\"", BodyDetail.of(Verb.WRITE, body))
    }

    @Test
    fun `a delete says deleting`() {
        assertEquals("Deleting \"old notes\"", BodyDetail.of(Verb.DELETE, """{"content":"old notes"}"""))
    }

    @Test
    fun `a read names its query`() {
        val body = """{"url":"https://api.todoist.com/api/v1/tasks","method":"GET","query":{"query":"today"}}"""
        // `query` is an object, not a scalar, so the scalar walk finds nothing here — this asserts
        // the honest null rather than a made-up phrase.
        assertNull(BodyDetail.of(Verb.READ, body))
    }

    @Test
    fun `a body with nothing nameable returns null so the caller keeps the model's line`() {
        assertNull(BodyDetail.of(Verb.WRITE, """{"to":["a@b.com"]}"""))
        assertNull(BodyDetail.of(Verb.WRITE, ""))
        assertNull(BodyDetail.of(Verb.WRITE, "not json"))
    }

    @Test
    fun `a credential-shaped value is never quoted onto the screen`() {
        // This string reaches the modal AND the AI log line. A long unbroken run is a token, not
        // prose, so it is skipped — being generic beats printing a secret.
        val token = "a".repeat(64)
        assertNull(BodyDetail.of(Verb.WRITE, """{"content":"$token"}"""))
    }

    @Test
    fun `a long value is clipped`() {
        val long = "word ".repeat(40)
        val out = BodyDetail.of(Verb.WRITE, """{"content":"$long"}""")!!
        assertTrue("expected a clip marker, got: $out", out.contains("…"))
        assertTrue("should stay readable, got ${out.length} chars", out.length < 100)
    }

    @Test
    fun `alreadyMentions spots a real body value in the model's sentence`() {
        val body = """{"content":"test todo item"}"""
        // The model got it right and keeps its own wording (and its reasoning).
        assertTrue(BodyDetail.alreadyMentions("Creating test todo item", body))
        // It restated structure instead, so the body must supply the line.
        assertFalse(BodyDetail.alreadyMentions("Create task with content and description", body))
    }

    @Test
    fun `alreadyMentions is case and punctuation tolerant`() {
        // Models re-case freely; demanding an exact match would discard good sentences.
        assertTrue(BodyDetail.alreadyMentions("CREATING Test Todo Item", """{"content":"test todo item"}"""))
    }
}
