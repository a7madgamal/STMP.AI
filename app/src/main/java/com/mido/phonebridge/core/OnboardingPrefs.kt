package com.mido.phonebridge.core

import android.content.Context

/**
 * Whether this install has been shown the onboarding flow.
 *
 * **Why a flag is needed at all.** The flow explains what the app IS. Shown once, that is
 * orientation; shown on every launch it is an obstacle between the human and the server switch
 * they came for. So it opens automatically exactly once and lives behind a button after that.
 *
 * **Written on ENTRY, not on finish.** A human who opens the flow, reads step one and swipes the
 * app away has already seen it; re-opening it every launch because they never reached the last
 * page would make the flag mean "completed a tour" instead of "has been introduced". The latter
 * is the fact worth storing, and it is the one that cannot nag.
 *
 * SharedPreferences for the same reason `UpdatePrefs` uses it: one boolean, no schema, nothing
 * worth failing a parse over. An unreadable value falls back to "seen" rather than "unseen",
 * because re-showing orientation is a worse failure than skipping it.
 */
object OnboardingPrefs {

    private const val FILE = "onboarding"
    private const val KEY_SEEN = "seen"

    fun seen(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SEEN, false)

    fun markSeen(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** Only for a debug reset. Nothing in the UI calls this. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SEEN).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
