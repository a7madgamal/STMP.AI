package com.mido.phonebridge.core

import android.content.Context

/**
 * Which release track this install follows, and what it has already seen.
 *
 * **Why this is not a field on `Policy`.** `Policy` is the gate's rule table, and every `withX`
 * on it rebuilds the whole object from named arguments. Adding a field there means editing ten
 * copy methods, and the one you forget silently resets — a hazard that file documents at length
 * because it has already shipped that bug. A release channel is a transport preference with no
 * bearing on what may be approved, so it does not belong in that minefield.
 *
 * SharedPreferences rather than a file: three scalars, no schema, nothing worth failing a parse
 * over. A missing or unreadable value falls back to the documented default rather than throwing,
 * because refusing to start the app over a preference would be the worse failure.
 */
object UpdatePrefs {

    private const val FILE = "updates"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_SKIPPED = "skippedVersion"
    private const val KEY_LAST_CHECK = "lastCheckAt"

    /**
     * Stable is the default.
     *
     * A human who has never touched this setting wants the version that was promoted, not the one
     * that was merely pushed to `main` — the beta track is opt-in, and an update that changes
     * behaviour should be a choice rather than a surprise.
     */
    enum class Channel {
        /** Promoted releases only: `prerelease: false`. */
        STABLE,

        /** Every push to `main`: `prerelease: true`. */
        BETA,
    }

    fun channel(ctx: Context): Channel {
        val raw = prefs(ctx).getString(KEY_CHANNEL, null)
        return Channel.entries.firstOrNull { it.name == raw } ?: Channel.STABLE
    }

    fun setChannel(ctx: Context, channel: Channel) {
        prefs(ctx).edit().putString(KEY_CHANNEL, channel.name).apply()
    }

    /**
     * A version the human said "not now" to.
     *
     * Stored so the prompt does not nag on every launch: an update notice that reappears after
     * every dismissal trains people to ignore it. Cleared when a NEWER version appears, which is
     * what makes "later" mean later rather than never.
     */
    fun skippedVersion(ctx: Context): String? =
        prefs(ctx).getString(KEY_SKIPPED, null)?.takeIf { it.isNotBlank() }

    fun setSkippedVersion(ctx: Context, version: String?) {
        prefs(ctx).edit().putString(KEY_SKIPPED, version).apply()
    }

    /** When the last check completed, for the "last checked" line. 0 means never. */
    fun lastCheckAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheckAt(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, at).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
