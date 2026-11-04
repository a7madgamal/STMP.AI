package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The updater must never install without verification, and must never claim silence is success.
 *
 * **Why a source scan.** Every rule here is one that compiles perfectly while being wrong, and
 * each has a specific failure this project has already paid for in other forms:
 *
 *  - An unverified install is the one mistake that turns an update channel into a remote-code
 *    hole, and it looks like a missing `if`.
 *  - An install that throws away the digest because the release did not publish one is a silent
 *    downgrade of a security property.
 *  - Treating "could not reach the feed" as "up to date" is the "silently does nothing" bug this
 *    codebase has fixed more than once, most recently in the notification card.
 */
class UpdaterSafetyTest {

    private val updater = File("src/main/java/com/mido/phonebridge/core/Updater.kt")
    private val installer = File("src/main/java/com/mido/phonebridge/core/ApkInstaller.kt")
    /**
     * The screen that owns the update UI. It moved from Settings to Home (Mido, 2026-11-04:
     * *"move the update section to the home page"*), so this points at the new owner — the guard
     * failing on the move is the guard working.
     */
    private val screen = File("src/main/java/com/mido/phonebridge/HomeFragment.kt")

    @Test
    fun `a check that cannot reach the source is not reported as up to date`() {
        assertTrue("Updater.kt missing", updater.isFile)
        val source = updater.readText()

        // The unavailable outcome must exist as its own case. Folding it into UpToDate is the
        // defect: the screen would say "up to date" while never having asked.
        assertTrue(
            "Updater must model an Unavailable outcome separately from UpToDate",
            source.contains("data class Unavailable(")
        )
        // A 404 has to be recognised as the private-repository case rather than a generic error.
        assertTrue(
            "a 404 must be recognised as 'the feed is not public'",
            source.contains("code == 404")
        )
    }

    @Test
    fun `the install refuses when the digest does not match`() {
        assertTrue("HomeFragment.kt missing", screen.isFile)
        val source = screen.readText()

        assertTrue(
            "the screen must compare the observed digest against the published one",
            source.contains("digest mismatch")
        )
        assertTrue(
            "a mismatched download must be deleted before returning",
            source.contains("got.file.delete()")
        )
    }

    @Test
    fun `the installer never claims to be silent`() {
        assertTrue("ApkInstaller.kt missing", installer.isFile)
        val source = installer.readText()

        // `canRequestPackageInstalls` is the gate that produces a clear refusal instead of a
        // confusing STATUS_FAILURE_BLOCKED dead end.
        assertTrue(
            "ApkInstaller must check canRequestPackageInstalls before starting a session",
            source.contains("canRequestPackageInstalls()")
        )
        // Uninstalling-and-reinstalling would delete the vault key and every stored secret.
        assertFalse(
            "the installer must never uninstall the app to install over it",
            source.contains("ACTION_DELETE")
        )
    }
}
