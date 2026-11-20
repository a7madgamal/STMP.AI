package com.mido.phonebridge.screenshots

import com.mido.phonebridge.OauthActivity
import org.junit.Test

/** OAuth credential screen. */
class OauthScreenshotTest(variant: Variant) : ShotTest(variant) {

    /** No credentials yet: the empty state plus both add paths. */
    @Test fun oauth_empty() = capture(launch(OauthActivity::class.java), "oauth", "empty")
}
