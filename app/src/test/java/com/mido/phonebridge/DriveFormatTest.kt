package com.mido.phonebridge

import com.mido.phonebridge.plugins.drive.DriveFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The formatting rules behind the approval card.
 *
 * Mido, 2026-10-21: *"i need full file details: full path and name, size(if possible)"*. Two files
 * can share a name in different folders, and a card showing only `report.pdf` cannot tell them
 * apart — so the PATH is the part that makes the modal trustworthy, and it is assembled from an
 * ancestor chain rather than read from any field.
 *
 * The size rule has its own trap: Drive omits `size` for folders and Google-native docs, and the
 * caller passes -1 for that. Rendering -1 as `-1 B` or `0 B` would claim an empty file, which leads
 * a human to the opposite conclusion from "we do not know".
 */
class DriveFormatTest {

    @Test
    fun `a path joins ancestors outermost-first under My Drive`() {
        // The walk produces the immediate parent FIRST, so the caller passes nearest-first and the
        // formatter reverses. That ordering is the whole reason this is tested.
        val path = DriveFormat.fullPath(listOf("2026", "Projects"), "report.pdf")
        assertEquals("My Drive / Projects / 2026 / report.pdf", path)
    }

    @Test
    fun `a file at the root has no folder segments`() {
        assertEquals("My Drive / notes.txt", DriveFormat.fullPath(emptyList(), "notes.txt"))
    }

    @Test
    fun `blank ancestor names are skipped`() {
        // A parent whose name could not be read contributes nothing rather than an empty segment,
        // which would render as "My Drive /  / report.pdf".
        assertEquals("My Drive / B / f.pdf", DriveFormat.fullPath(listOf("B", "", "  "), "f.pdf"))
    }

    @Test
    fun `bytes render in binary units`() {
        assertEquals("512 B", DriveFormat.humanSize(512))
        assertEquals("1 KB", DriveFormat.humanSize(1024))
        assertEquals("2 KB", DriveFormat.humanSize(2048))
    }

    @Test
    fun `a trailing zero decimal is dropped`() {
        // "2 MB" must not read as "2.0 MB" — noise on a card that has to be scanned quickly.
        assertEquals("2 MB", DriveFormat.humanSize(2L * 1024 * 1024))
    }

    @Test
    fun `a fractional size keeps one decimal`() {
        val size = (1536L * 1024) // 1.5 MB
        assertEquals("1.5 MB", DriveFormat.humanSize(size))
    }

    @Test
    fun `the real file from the account renders sanely`() {
        // 20232 bytes, seen on device. Below 1 MB, so it must stay in KB rather than rounding to
        // "0 MB".
        assertEquals("19.8 KB", DriveFormat.humanSize(20232))
    }

    @Test
    fun `an unreported size says unknown, never zero`() {
        // -1 is Drive's "no size for this type" (folders, Google-native docs). "0 B" would claim an
        // empty file; "unknown" tells the truth.
        assertEquals("unknown", DriveFormat.humanSize(-1))
        assertEquals("unknown", DriveFormat.humanSize(-999))
    }

    @Test
    fun `zero bytes is a real size, distinct from unknown`() {
        // The inverse of the above: an actual empty file must not read as "unknown".
        assertEquals("0 B", DriveFormat.humanSize(0))
    }

    @Test
    fun `a folder path drops the trailing separator`() {
        // The card puts the path on its own row, so "My Drive /" reads as a broken path.
        assertEquals(
            "My Drive / Projects",
            DriveFormat.folderOf("My Drive / Projects / file.pdf")
        )
    }

    @Test
    fun `gigabyte sizes step up a unit`() {
        assertEquals("1.5 GB", DriveFormat.humanSize((1536L * 1024 * 1024)))
    }
}
