package com.mido.phonebridge.screenshots

import com.mido.phonebridge.GoogleLoginActivity
import org.junit.Test

/** Google login screen. */
class GoogleLoginScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun google_login_no_client_id() = capture(launch(GoogleLoginActivity::class.java), "google_login", "no_client_id")
}
