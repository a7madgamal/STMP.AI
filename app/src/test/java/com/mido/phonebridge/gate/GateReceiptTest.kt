package com.mido.phonebridge.gate

import com.mido.phonebridge.core.Verb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** D16: the receipt's five lines, exactly. */
class GateReceiptTest {

    private var zone: TimeZone? = null

    @Before fun utc() { zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(zone) }

    private val at = 1_794_390_000_123L // 2026-11-11 09:40:00.123 UTC

    @Test
    fun `approved receipt`() {
        assertEquals(
            listOf(
                "STMP RECEIPT ········ 1043",
                "Work laptop  EXECUTE  /ssh/kvm-box",
                "stamped 09:40:00.123",
                "status approved · running now",
                "by this phone · hold",
            ),
            GateReceipt.lines(1043, "Work laptop", Verb.EXECUTE, "/ssh/kvm-box", at, approved = true, by = "hold"),
        )
    }

    @Test
    fun `denied receipt`() {
        assertEquals(
            listOf(
                "STMP RECEIPT ········ 7",
                "Work laptop  DELETE  /audit",
                "stamped 09:40:00.123",
                "status denied · caller told 403",
                "by this phone · back",
            ),
            GateReceipt.lines(7, "Work laptop", Verb.DELETE, "/audit", at, approved = false, by = "back"),
        )
    }

    @Test
    fun `path of a request line`() {
        assertEquals("/probe", GateReceipt.pathOf("GET /probe"))
        assertEquals("?", GateReceipt.pathOf("?"))
    }

    @Test
    fun `punch meta`() {
        assertEquals("№ 1043 · 09:40:00", GateCopy.punchMeta(1043, at))
    }
}
