package com.mido.phonebridge

/** The application class: installs the palette's context so services and overlays can read it. */
class StmpApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        // Skipped under Robolectric: its paused main looper never runs the stall heartbeat, so the
        // watchdog thread would pile up and run the unit-test JVM out of memory.
        if (android.os.Build.FINGERPRINT != "robolectric") {
            Crash.install(this)
            Stall.install(this)
        }
        com.mido.phonebridge.ui.Theme.install(this)
    }
}
