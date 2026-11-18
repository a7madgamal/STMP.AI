package com.mido.phonebridge

import com.mido.phonebridge.plugins.drive.PhoneSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules for putting a Drive file on the phone.
 *
 * The MediaStore insert itself needs a `ContentResolver` and cannot run on the JVM — but the two
 * decisions that go wrong quietly can, and both are here: "does this file have bytes at all" and
 * "is this name safe to insert".
 *
 * Both were driven by real Drive data seen on the device (2026-10-21): the account had a Google Doc,
 * a Sheet, and a file literally named `" Cover tmp.pdf"` — leading space, uppercase, and a PDF. A
 * name with a `/` is legal in Drive and illegal in MediaStore, which is the case that matters most.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneSaveTest {

    @Test
    fun `a regular file is downloadable`() {
        assertTrue(PhoneSave.isDownloadable("application/pdf"))
        assertTrue(PhoneSave.isDownloadable("image/jpeg"))
        assertTrue(PhoneSave.isDownloadable("application/octet-stream"))
        // No type at all is still a file with bytes.
        assertTrue(PhoneSave.isDownloadable(""))
    }

    @Test
    fun `google-native docs are NOT downloadable`() {
        // A Docs/Sheets/Slides file is a MIME type with no binary payload — `alt=media` answers
        // 403 cannotDownloadFile. Refusing here names the real problem instead of surfacing an
        // opaque google_error after a human already approved.
        assertFalse(PhoneSave.isDownloadable("application/vnd.google-apps.document"))
        assertFalse(PhoneSave.isDownloadable("application/vnd.google-apps.spreadsheet"))
        assertFalse(PhoneSave.isDownloadable("application/vnd.google-apps.presentation"))
    }

    @Test
    fun `a folder is NOT downloadable`() {
        // Out of scope for this pass: a folder is not a file and this release does not walk trees.
        assertFalse(PhoneSave.isDownloadable("application/vnd.google-apps.folder"))
    }

    @Test
    fun `a name with a path separator is sanitised, not rejected`() {
        // "2026/10 report.pdf" is a LEGAL Drive name and an ILLEGAL MediaStore DISPLAY_NAME.
        // Failing the save on a valid Drive file would be the wrong answer.
        val out = PhoneSave.fileName("2026/10 report.pdf", "abc123")
        assertFalse("must not contain a slash: $out", out.contains('/'))
        assertTrue("should stay identifiable: $out", out.contains("2026"))
    }

    @Test
    fun `a backslash is sanitised too`() {
        // Legal on Drive, a separator on some POSIX paths.
        val out = PhoneSave.fileName("a\\b.pdf", "abc123")
        assertFalse("must not contain a backslash: $out", out.contains('\\'))
    }

    @Test
    fun `an ordinary name passes through unchanged`() {
        // Including the leading space and uppercase seen on the real file. No gratuitous rewriting:
        // a name the user recognises is the point.
        assertEquals(" Cover tmp.pdf", PhoneSave.fileName(" Cover tmp.pdf", "abc123"))
        assertEquals("2026-10-20.xml", PhoneSave.fileName("2026-10-20.xml", "abc123"))
    }

    @Test
    fun `a blank name falls back to the drive id`() {
        // An unnamed entry in Downloads is unfindable. The id is not pretty but it is traceable.
        assertEquals("drive-abc123", PhoneSave.fileName("", "abc123"))
        assertEquals("drive-abc123", PhoneSave.fileName("   ", "abc123"))
    }

    @Test
    fun `a NUL is stripped from a name`() {
        // NUL terminates strings in some POSIX paths and has no business in a filename.
        val out = PhoneSave.fileName("evil\u0000.pdf", "abc123")
        assertFalse("must not contain NUL: $out", out.contains('\u0000'))
    }

    @Test
    fun `the target folder is STMP under Downloads`() {
        // Mido, 2026-10-21: its own directory so saved files are one place, easy to find and easy
        // to wipe. Renamed from PhoneBridge on 2026-11-18 — the product is only STMP now, and the
        // download folder was the last place the old name still reached a user.
        //
        // **This pins a user-visible path, so changing it is a decision, not a rename.** Files
        // already in the old folder are deliberately left where they are; a test that demanded they
        // move would be demanding the app delete or relocate the user's data.
        assertEquals("STMP", PhoneSave.RELATIVE_SUBDIR)
    }

    /**
     * The old folder name must survive nowhere in CODE — only in history.
     *
     * A leftover literal is how a rename half-happens: one route keeps writing to the old folder,
     * the user looks in the new one, and the file appears to have vanished.
     */
    @Test
    fun `no source file still writes to the old folder`() {
        val offenders = java.io.File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().contains("\"PhoneBridge\"") }
            .map { it.path }
            .toList()
        assertEquals("these files still name the old folder: $offenders", emptyList<String>(), offenders)
    }
}
