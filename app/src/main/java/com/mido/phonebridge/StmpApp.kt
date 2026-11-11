package com.mido.phonebridge

/** The application class: installs the palette's context so services and overlays can read it. */
class StmpApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        com.mido.phonebridge.ui.Theme.install(this)
    }
}
