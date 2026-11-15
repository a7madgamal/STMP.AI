package com.mido.phonebridge

import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.ai.RiskFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The modal must PAINT from the floor, not only compute it.
 *
 * `RiskFloorTest` proves the arithmetic. This proves the wiring — the failure being fixed was
 * not a wrong answer, it was a correct answer nobody called. Source-scanned because the
 * alternative is driving a live biometric modal in Robolectric to read a button's tint, which
 * would test the harness more than the app.
 */
class ApprovalFloorWiringTest {

    private val source = File("src/main/java/com/mido/phonebridge/ApprovalActivity.kt").readText()

    @Test
    fun `the painted risk is combined with the floor, never the model alone`() {
        assertTrue(
            "the button colour must come from the combined risk",
            source.contains("RiskFloor.combine(risk, floor)"),
        )
        assertFalse(
            "painting the model's answer directly is the bug that shipped a green APPROVE",
            Regex("""val effective = risk\s*($|//)""").containsMatchIn(source),
        )
    }

    @Test
    fun `the floor is computed from the command rows the modal rendered`() {
        assertTrue(
            "command rows must be collected as they render",
            source.contains("commandsSeen += r.value"),
        )
        assertTrue(
            "the floor must read those commands",
            source.contains("RiskFloor.of(it)"),
        )
        // Several commands can be on one card; the worst one decides.
        assertTrue(
            "the floor is the MAXIMUM over every command, not the first",
            source.contains("maxByOrNull { it.ordinal }"),
        )
    }

    @Test
    fun `the collector is a field, not a local, so both scopes can see it`() {
        // The first attempt put it inside the AI-state block, and the row renderer — which is
        // in a different scope — could not compile against it.
        assertTrue(
            "commandsSeen must be an Activity field",
            source.contains("private val commandsSeen = mutableListOf<String>()"),
        )
    }

    @Test
    fun `the reported command would paint red end to end`() {
        // The arithmetic and the wiring, stated together: this is what the phone will do.
        val reported = "P=/home/x; chown -R user:user \$P; stat -c \"%U\" \$P/config"
        val floor = RiskFloor.of(reported)
        assertEquals(Risk.HIGH, RiskFloor.combine(null, floor))
        assertEquals(Risk.HIGH, RiskFloor.combine(Risk.LOW, floor))
    }
}
