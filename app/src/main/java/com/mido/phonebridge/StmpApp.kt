package com.mido.phonebridge

import android.app.Application

/**
 * The process entry point, and the one place early enough to catch a crash before it happens.
 *
 * `MainActivity.onCreate` is too late for the failures that matter most — an exception thrown
 * while the Activity inflates, or in `appStartup()` itself, never reaches a handler installed
 * further down. This runs first, so every later crash is recorded.
 */
class StmpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}
