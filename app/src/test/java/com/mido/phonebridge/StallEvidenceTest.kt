package com.mido.phonebridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A freeze report is EVIDENCE, and evidence has a shelf life.
 *
 * Both halves of this were broken until 0.51.28, and neither could be seen by reading the file:
 *
 * - `Stall` wrote only `at=`, while `Crash` wrote the full [Evidence.header]. `Evidence.read`
 *   discards a record whose timestamp predates the installed package — the rule that exists
 *   because a stale crash kept showing after its fix (*"works now / but i see this"*). `Stall`
 *   read with a bare `File.readText()`, so it never asked. A freeze from a build the user had
 *   already replaced stayed on Home indefinitely.
 * - Nothing in the app called `Stall.clear`. One freeze left a permanent row above every real
 *   problem, with no way to remove it even after copying the trace.
 *
 * The tests below fail on the old code for the reason each one names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StallEvidenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val file: File get() = File(context.applicationContext.filesDir, "last-stall.txt")

    @Before
    fun clean() {
        file.delete()
    }

    @Test
    fun `a stall record carries the version header a crash carries`() {
        // The header is what makes the freshness rule possible, so its absence was the whole bug.
        // Written through the same builder `Stall.record` uses, so this cannot drift from it.
        val header = Evidence.header(context)
        assertTrue("header must state the moment", header.lineSequence().any { it.startsWith("at=") })
        assertTrue(
            "header must state the build, or a stale record is indistinguishable from a fresh one",
            header.lineSequence().any { it.startsWith("version=") },
        )
    }

    @Test
    fun `a fresh stall record is returned`() {
        file.writeText(Evidence.header(context) + "frozen thread=main\n    at Foo.bar\n")
        assertNotNull("a record from THIS build is the evidence the row exists to show", Stall.last(context))
    }

    @Test
    fun `the staleness rule is exactly a record older than the install`() {
        // **The rule, as a truth table.** Robolectric answers `lastUpdateTime` with 0, so the
        // filesystem can never reach this branch — which is why the branch is a pure function and
        // this test is the one that actually pins it. Every row here is a real situation:
        //
        //  1. left over from a build that was replaced  -> discard, that is the bug this fixed
        //  2. written by the build running now          -> keep, it is current evidence
        //  3. written this millisecond                  -> keep, `lastUpdateTime` is not finer
        //  4. no readable timestamp                     -> keep, unreadable is not stale
        //  5. the device cannot report its install time -> keep, do not throw away the only trace
        assertTrue("older than the install: stale", Evidence.isStale(writtenAt = 1_000L, installedAt = 2_000L))
        assertTrue(
            "written at the exact install moment: keep, the comparison is strict",
            !Evidence.isStale(writtenAt = 2_000L, installedAt = 2_000L),
        )
        assertTrue("newer than the install: current", !Evidence.isStale(writtenAt = 3_000L, installedAt = 2_000L))
        assertTrue("no timestamp: keep", !Evidence.isStale(writtenAt = null, installedAt = 2_000L))
        assertTrue("no install time: keep", !Evidence.isStale(writtenAt = 1_000L, installedAt = 0L))
    }

    @Test
    fun `clearing removes the record so a later freeze is distinguishable`() {
        file.writeText(Evidence.header(context) + "frozen thread=main\n")
        assertNotNull(Stall.last(context))
        Stall.clear(context)
        assertNull("after Dismiss the row must be gone", Stall.last(context))
    }

    @Test
    fun `no record means no row`() {
        assertNull(Stall.last(context))
    }
}
