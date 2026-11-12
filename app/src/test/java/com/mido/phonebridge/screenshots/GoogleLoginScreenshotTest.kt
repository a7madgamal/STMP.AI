package com.mido.phonebridge.screenshots

import com.mido.phonebridge.GoogleLoginActivity
import org.junit.Test

/**
 * Google sign-in screen. Only the no-client-id state is captured: the starting, already-signed-in
 * and failed states need `AuthManager` / `PlayAuth` seams that do not exist (WP06 11.2; not
 * captured: no seam for them). The status panel text is covered by `PluginDetailCopyTest`.
 */
class GoogleLoginScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun google_login_no_client_id() = capture(launch(GoogleLoginActivity::class.java), "google_login", "no_client_id")
}
