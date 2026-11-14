package com.mido.phonebridge.gate

import com.mido.phonebridge.ApprovalGate.Row
import com.mido.phonebridge.ApprovalGate.Row.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP03a section 11: the readout headline for every gated route, with rows as the plugins build them. */
class GateReadoutTest {

    private data class Case(val routeKey: String, val line: String, val rows: List<Row>, val label: String, val text: String)

    private val cases = listOf(
        Case(
            "POST /gmail/messages/send", "POST /gmail/messages/send",
            listOf(Row("TO", "a@x.com", Style.WARN), Row("SUBJECT", "Hi", Style.WARN), Row("BODY", "hello", Style.LONG)),
            "TO", "a@x.com",
        ),
        Case(
            "POST /gmail/messages/{id}/trash", "POST /gmail/messages/m1/trash",
            listOf(Row("SUBJECT", "Invoice", Style.NORMAL), Row("FROM", "b@x.com"), Row("MESSAGE ID", "m1", Style.MONO)),
            "SUBJECT", "Invoice",
        ),
        Case(
            "POST /gmail/messages/{id}/trash", "POST /gmail/messages/m1/trash",
            listOf(Row("MESSAGE ID", "m1", Style.MONO)),
            "MESSAGE ID", "m1",
        ),
        Case(
            "POST /gmail/messages/{id}/modify", "POST /gmail/messages/m1/modify",
            listOf(Row("REMOVE LABELS", "INBOX", Style.NORMAL)),
            "REMOVE LABELS", "INBOX",
        ),
        Case("GET /drive/files/{id}/content", "GET /drive/files/f/content", listOf(Row("DOWNLOAD", "a.pdf")), "DOWNLOAD", "a.pdf"),
        Case(
            "POST /drive/files/{id}/trash", "POST /drive/files/f/trash",
            listOf(Row("TRASH (recoverable 30 days)", "a.pdf"), Row("FILE ID", "f", Style.MONO)),
            "TRASH (recoverable 30 days)", "a.pdf",
        ),
        Case("POST /drive/files", "POST /drive/files", listOf(Row("CREATE FOLDER", "New", Style.WARN)), "CREATE FOLDER", "New"),
        Case(
            "POST /drive/files/{id}/rename", "POST /drive/files/f/rename",
            listOf(Row("RENAME", "a.pdf", Style.WARN), Row("TO", "b.pdf", Style.WARN)),
            "TO", "b.pdf",
        ),
        Case(
            "POST /drive/files/{id}/move", "POST /drive/files/f/move",
            listOf(Row("MOVE", "a.pdf", Style.WARN), Row("FROM", "/"), Row("TO", "/Docs")),
            "TO", "/Docs",
        ),
        Case("POST /drive/files/upload", "POST /drive/files/upload", listOf(Row("UPLOAD", "a.pdf", Style.WARN)), "UPLOAD", "a.pdf"),
        Case("POST /calendar/events", "POST /calendar/events", listOf(Row("TITLE", "Dentist"), Row("WHEN", "10:00")), "TITLE", "Dentist"),
        Case(
            "POST /calendar/events/{id}", "POST /calendar/events/e1",
            listOf(Row("EVENT ID", "e1", Style.MONO), Row("TITLE", "Dentist")),
            "TITLE", "Dentist",
        ),
        Case(
            "DELETE /calendar/events/{id}", "DELETE /calendar/events/e1",
            listOf(Row("EVENT ID", "e1", Style.MONO)),
            "EVENT ID", "e1",
        ),
        Case(
            "POST /calendar/events/{id}/move", "POST /calendar/events/e1/move",
            listOf(Row("EVENT ID", "e1", Style.MONO), Row("MOVE TO", "work")),
            "MOVE TO", "work",
        ),
        Case("POST /calendar/quickadd", "POST /calendar/quickadd", listOf(Row("TEXT", "lunch at 1"), Row("TIME ZONE", "UTC")), "TEXT", "lunch at 1"),
        Case("GET /contacts", "GET /contacts", listOf(Row("SEARCH", "sara", Style.MONO)), "SEARCH", "sara"),
        Case(
            "POST /notifications/{id}/action", "POST /notifications/n1/action",
            listOf(Row("ACTION", "Reply", Style.WARN), Row("TITLE", "Sara"), Row("REPLY", "on my way", Style.LONG)),
            "REPLY", "on my way",
        ),
        Case(
            "POST /notifications/{id}/action", "POST /notifications/n1/action",
            listOf(Row("ACTION", "Mark as read", Style.WARN), Row("TITLE", "Sara")),
            "ACTION", "Mark as read",
        ),
        Case(
            "POST /notifications/{id}/dismiss", "POST /notifications/n1/dismiss",
            listOf(Row("DISMISS", "notification from Chat", Style.NORMAL), Row("TITLE", "Sara")),
            "TITLE", "Sara",
        ),
        Case(
            "POST /secrets/todoist/call", "POST /secrets/todoist/call",
            listOf(Row("SECRET", "todoist"), Row("TARGET", "POST https://api.todoist.com/rest/v2/tasks", Style.MONO)),
            "TARGET", "POST https://api.todoist.com/rest/v2/tasks",
        ),
    )

    @Test
    fun `every routed headline picks its row`() {
        for (c in cases) {
            val h = GateReadout.headline(c.routeKey, c.line, c.rows)
            assertEquals(c.routeKey, "${c.label} · verbatim", h.label)
            assertEquals(c.routeKey, c.text, h.text)
            assertEquals(c.routeKey, c.line, h.prompt)
            assertEquals(c.routeKey, c.rows.indexOfFirst { it.label == c.label }, h.rowIndex)
            assertFalse(c.routeKey, h.isCommand)
        }
    }

    @Test
    fun `an ssh command is the headline with a user-at-host prompt`() {
        val rows = listOf(
            Row("CONNECTION", "kvm-box", Style.NORMAL),
            Row("HOST", "mido@kvm-box:22", Style.MONO),
            Row("COMMAND", "ls -la", Style.CODE),
        )
        for (key in listOf("POST /ssh/{name}", "POST /ssh/{name}/detach")) {
            val h = GateReadout.headline(key, "POST /ssh/kvm-box", rows)
            assertEquals("COMMAND · verbatim", h.label)
            assertEquals("ls -la", h.text)
            assertTrue(h.isCommand)
            assertEquals("POST /ssh/kvm-box\nmido@kvm-box \$", h.prompt)
            assertEquals(2, h.rowIndex)
        }
    }

    @Test
    fun `a route with no rows shows the request line`() {
        for (line in listOf("GET /probe", "DELETE /audit", "GET /ssh/job/7", "GET /secrets", "GET /notifications")) {
            val h = GateReadout.headline(line, line, emptyList())
            assertEquals(GateCopy.READOUT_REQUEST, h.label)
            assertEquals(line, h.text)
            assertNull(h.prompt)
            assertNull(h.rowIndex)
            assertFalse(h.isCommand)
        }
    }

    @Test
    fun `an unknown route with a CODE row picks it`() {
        val rows = listOf(Row("WHERE", "box"), Row("SCRIPT", "make all", Style.CODE))
        val h = GateReadout.headline("POST /future/{x}", "POST /future/1", rows)
        assertEquals("SCRIPT · verbatim", h.label)
        assertTrue(h.isCommand)
        assertEquals(1, h.rowIndex)
        assertEquals("POST /future/1", h.prompt)
    }

    @Test
    fun `secrets keys match by regex only`() {
        val rows = listOf(Row("TARGET", "GET https://x", Style.MONO))
        assertEquals("TARGET · verbatim", GateReadout.headline("POST /secrets/a-b_c/call", "POST /secrets/a-b_c/call", rows).label)
        assertEquals(GateCopy.READOUT_REQUEST, GateReadout.headline("POST /secrets/a/b/call", "x", rows).label)
    }
}
