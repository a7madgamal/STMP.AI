package com.mido.phonebridge.plugins.drive

/**
 * Formatting helpers for the approval card, kept pure so a test can fail on them.
 *
 * **Why these are not inline string templates.** Mido asked for full file details in the modal
 * (2026-10-21): *"i need full file details: full path and name, size(if possible)"*. The path and
 * the size are the two things a human needs to be sure they are approving the right file, so a
 * wrong render is not cosmetic — it is the difference between approving `report.pdf` and
 * `report-final-FINAL.pdf` in a folder that happens to share a name.
 *
 * A byte count also goes wrong in a specific way that is worth testing: `-1` means "Drive did not
 * report a size" (folders, some Google-native types), and rendering that as `-1 B` or `0 B` would
 * claim an empty file. It must read as unknown.
 */
object DriveFormat {

    /**
     * A byte count a human can read at a glance.
     *
     * Binary units (1024), one decimal place above bytes. `-1` — Drive's "no size reported" —
     * becomes `"unknown"` rather than a number, because an empty file and an unreported size lead
     * a human to different conclusions and must not look the same on an approval card.
     */
    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "unknown"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        // Step up while the value would still be >= 1024 in the next unit.
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        // One decimal, but drop a trailing ".0" so "2 MB" does not read as "2.0 MB".
        val rounded = Math.round(value * 10.0) / 10.0
        val text = if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
        return "$text ${units[unit]}"
    }

    /**
     * The folder part of a path, or `/` for a file at the top of My Drive.
     *
     * The card shows the path and the name on separate rows, so this strips the trailing separator
     * rather than returning `"My Drive/"`.
     */
    fun folderOf(path: String): String {
        // Split on the SAME separator `fullPath` joins with, then drop the last segment (the file's
        // own name). Trimming trailing slashes — the first version — left the filename attached, so
        // a rename card said the file lived in "My Drive / Projects / report.pdf" instead of
        // "My Drive / Projects".
        val parts = path.split(SEPARATOR)
        if (parts.size <= 1) return path
        return parts.dropLast(1).joinToString(SEPARATOR).ifEmpty { "/" }
    }

    /** The path separator, shared by [fullPath] and [folderOf] so they cannot disagree. */
    private const val SEPARATOR = " / "

    /**
     * The full path a human can recognise: `My Drive / Folder / Subfolder / name.pdf`.
     *
     * **Built from the ancestor chain, not from the file name alone.** Two files can share a name in
     * different folders, and "report.pdf" tells nobody which one they are about to download. Drive
     * has no path string in its API — you walk `parents` — so the caller supplies the chain
     * outermost-first and this joins it.
     *
     * `My Drive` is prepended because the card should read like a location, not a bare list. An
     * empty chain yields `My Drive / name`, which is exactly right for a file at the root.
     */
    fun fullPath(ancestorNames: List<String>, fileName: String): String {
        val parts = ArrayList<String>(ancestorNames.size + 2)
        parts.add("My Drive")
        // Reversed: callers walk from the file UP to the root (that is the direction the API gives),
        // so the outermost ancestor arrives last. Sorting that out here means no caller has to
        // remember, and the ordering is tested.
        for (name in ancestorNames.asReversed()) {
            if (name.isNotBlank()) parts.add(name)
        }
        if (fileName.isNotBlank()) parts.add(fileName)
        return parts.joinToString(SEPARATOR)
    }
}
