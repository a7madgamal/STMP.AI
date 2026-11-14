package com.mido.phonebridge

import android.app.Application

/**
 * The process entry point, and the one place early enough to catch a crash before it happens.
 *
 * `MainActivity.onCreate` is too late for the failures that matter most — an exception thrown
 * while the Activity inflates, or in `appStartup()` itself, never reaches a handler installed
 * further down. This runs first, so every later crash is recorded.
 *
 * It also installs the palette's context. The design tokens resolve colours from a Context, and
 * the gate's own surfaces — the notification card, the stamp overlay, the fingerprint prompt —
 * run in services and overlay windows where there is no Activity to borrow one from. Installing
 * here is what lets them read the same palette the screens do.
 */
class StmpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Stall.install(this)
        com.mido.phonebridge.ui.Theme.install(this)
    }
}
