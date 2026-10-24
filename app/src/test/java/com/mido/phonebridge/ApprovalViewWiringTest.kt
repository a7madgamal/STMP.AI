package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The wiring guard for `ApprovalView`.
 *
 * Every branch of `BridgeService.approvalView(...)` returns its own `ApprovalView`, and a
 * field omitted in one branch is not a compile error — it silently takes the constructor
 * default. That is exactly how the modal's exception button shipped absent on calendar and
 * gmail requests while working on notifications: the offer was built, passed to two of the
 * three returns, and dropped by the third. No error, no log, no failing test — just a
 * feature that did not exist on the routes that used the generic path.
 *
 * The same class of bug is already recorded in this project twice: `describe()` computing a
 * verbatim target string that the Activity then discarded, and `auditResponseBody` honoured
 * on one dispatch path and ignored on another. The lesson each time is the same — a value
 * computed in one place and consumed in another must be asserted at the READER, not the
 * writer. This test asserts it at the writer, because that is where the branches are.
 *
 * A source scan in a unit test is unusual, and it is the point: the rule is "every
 * ApprovalView construction carries the offer", and the only place that rule can be broken
 * is this function's source. `user.dir` is the module directory under Gradle, so the path
 * is stable; a missing tree throws rather than reporting a clean result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalViewWiringTest {

    @Test
    fun `every ApprovalView construction passes the exception offer`() {
        val file = File(
            "src/main/java/com/mido/phonebridge/BridgeService.kt"
        )
        assertTrue("BridgeService.kt not found at ${file.absolutePath}", file.isFile)
        val src = file.readText()

        val constructions = Regex("""ApprovalGate\.ApprovalView\(""").findAll(src).count()
        // Guard the guard: a scan that finds nothing has silently stopped testing.
        assertTrue(
            "found no ApprovalView constructions — the scan itself is broken",
            constructions > 0
        )

        // Anchored to end-of-line on purpose: the CALL SITE writes
        // `exceptionOffer = exceptionOfferFor(...)`, which must not be counted as one of the
        // pass-throughs inside the constructor. An unanchored regex counts it and the guard
        // fails on a correct tree — the same "test that matches too much" trap as a loose
        // `strings | grep` scan. Trailing comma or not, it must end the line.
        val offers = Regex("""exceptionOffer\s*=\s*exceptionOffer\s*,?\s*$""", RegexOption.MULTILINE)
            .findAll(src).count()
        assertEquals(
            "every ApprovalView construction must pass `exceptionOffer = exceptionOffer`. " +
                "A branch that omits it takes the null default, so the modal silently " +
                "renders with no exception button on the routes that use that branch — " +
                "no error, no log. Found $constructions construction(s) but $offers " +
                "offer pass-through(s).",
            constructions,
            offers
        )
    }
}
