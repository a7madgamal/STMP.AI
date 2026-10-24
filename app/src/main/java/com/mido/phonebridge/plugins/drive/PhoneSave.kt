package com.mido.phonebridge.plugins.drive

/**
 * The rules for putting a Drive file on the phone's Downloads folder.
 *
 * **Pure and Android-free, because the interesting part is a set of decisions, not I/O.** The
 * MediaStore insert itself needs a `ContentResolver` and cannot run on the JVM — but "can this file
 * be downloaded at all" and "what name should it get" are exactly the rules that go wrong quietly,
 * so they live here where `PhoneSaveTest` can fail on them.
 */
object PhoneSave {

    /**
     * Google's native document types have no bytes to download.
     *
     * A Docs/Sheets/Slides file is a `application/vnd.google-apps.*` MIME with no binary payload —
     * `alt=media` answers `403 cannotDownloadFile`. Passing one through would surface as an opaque
     * `google_error`, so it is refused here with a message that says what to do instead (export).
     *
     * Folders are included for the same reason: a folder is not a file, and this release
     * deliberately does not walk trees.
     */
    private val NOT_DOWNLOADABLE_PREFIX = "application/vnd.google-apps."

    /** True when Drive can hand back actual bytes for this MIME type. */
    fun isDownloadable(mimeType: String): Boolean =
        !mimeType.startsWith(NOT_DOWNLOADABLE_PREFIX)

    /**
     * A filename safe for MediaStore.
     *
     * MediaStore rejects a `DISPLAY_NAME` containing a path separator, and a Drive name is
     * arbitrary user input — "2026/10 report.pdf" is a legal Drive name and an illegal insert.
     * Rather than fail the save on a valid Drive file, the separators become `-` so the file is
     * still identifiable, and an empty result falls back to the Drive id (the caller passes it as
     * a last resort) so a nameless file cannot become an unnamed entry.
     *
     * NUL is stripped too: it terminates strings in some POSIX paths and has no business in a
     * filename.
     */
    fun fileName(driveName: String, fallbackId: String): String {
        // ONLY characters that are genuinely illegal are replaced. No `.trim()`: a leading space
        // is legal in MediaStore, and the real account had a file literally named
        // " Cover tmp.pdf" — silently renaming it would make the saved copy unfindable by the
        // name the user sees in Drive. `ifBlank` still catches a whitespace-only name, because
        // that one is unusable rather than merely odd.
        val cleaned = driveName
            .replace('/', '-')
            .replace('\\', '-')
            .replace("\u0000", "")
        return cleaned.ifBlank { "drive-$fallbackId" }
    }

    /**
     * The folder under Downloads. Its own directory on purpose (Mido, 2026-10-21: *"/PhoneBridge"*)
     * so saved files are one place, easy to find and easy to wipe.
     */
    const val RELATIVE_SUBDIR = "PhoneBridge"
}
