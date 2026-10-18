package com.mido.phonebridge

import com.mido.phonebridge.plugins.secrets.ResponseBody
import com.mido.phonebridge.plugins.secrets.ResponseKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a brokered response is usable.
 *
 * This exists because the rule used to live inside an `HttpURLConnection` call and was
 * therefore untestable — and the untested branch was wrong: a 204 (success, no body)
 * was refused as `unsupported_content_type`, so completing or deleting a Todoist task
 * was unreachable. The fix is not "be careful next time"; it is making the decision a
 * pure function with cases written down.
 */
class ResponseBodyTest {

    private fun kind(status: Int, body: String) = ResponseBody.classify(status, body)

    @Test
    fun `204 with no body is a success`() {
        // The bug. Todoist answers close/delete with 204 and labels it text/html; the
        // label is irrelevant because there is no body to label.
        assertEquals(ResponseKind.EMPTY_SUCCESS, kind(204, ""))
    }

    @Test
    fun `empty 2xx is a success whatever the content type claimed`() {
        // classify does not take a content type at all — that is the design. An empty
        // body cannot be mislabelled.
        assertEquals(ResponseKind.EMPTY_SUCCESS, kind(200, ""))
        assertEquals(ResponseKind.EMPTY_SUCCESS, kind(201, ""))
        assertEquals(ResponseKind.EMPTY_SUCCESS, kind(200, "   \n  "))
    }

    @Test
    fun `a JSON object body is JSON`() {
        assertEquals(ResponseKind.JSON, kind(200, """{"id":"abc","content":"x"}"""))
        assertEquals(ResponseKind.JSON, kind(201, """{"ok":true}"""))
    }

    @Test
    fun `the body decides, not the content type`() {
        // A server that mislabels real JSON as text/plain must still work. The header is
        // a hint; the body is the truth.
        assertEquals(ResponseKind.JSON, kind(200, """{"results":[]}"""))
    }

    @Test
    fun `a non-JSON body is still refused`() {
        // Edge A, preserved: the envelope cannot carry a PDF or an HTML page, and
        // base64-ing one silently would be a lie.
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "<html><body>nope</body></html>"))
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "%PDF-1.7\n...binary..."))
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "not json at all"))
    }

    @Test
    fun `a JSON scalar is not a JSON object`() {
        // "123" and "true" parse as JSON but are not objects, so the envelope could not
        // hold them. They must be refused here rather than failing downstream.
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "123"))
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "true"))
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "\"a string\""))
    }

    @Test
    fun `a JSON array is not a JSON object`() {
        assertEquals(ResponseKind.UNSUPPORTED, kind(200, "[1,2,3]"))
    }

    @Test
    fun `an error response with a JSON body is readable`() {
        // Todoist errors are JSON, and the caller needs to see them.
        assertEquals(
            ResponseKind.JSON,
            kind(400, """{"error_tag":"ARGUMENT_MISSING","http_code":400}""")
        )
    }

    @Test
    fun `an error response with an empty body is not a success`() {
        // The empty-body shortcut is for 2xx only: a 500 with no body is a failure, and
        // must fail loudly rather than be reported as an empty success.
        assertEquals(ResponseKind.UNSUPPORTED, kind(500, ""))
        assertEquals(ResponseKind.UNSUPPORTED, kind(404, ""))
    }
}
