package com.mido.phonebridge.screenshots

import com.mido.phonebridge.SecretsActivity
import org.junit.Test

/** Secrets vault screen. */
class SecretsScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun secrets_locked() = capture(launch(SecretsActivity::class.java), "secrets", "locked")
}
