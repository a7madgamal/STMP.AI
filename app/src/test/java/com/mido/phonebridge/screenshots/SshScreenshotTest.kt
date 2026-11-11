package com.mido.phonebridge.screenshots

import com.mido.phonebridge.SshActivity
import org.junit.Test

/** SSH screen. */
class SshScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun ssh_locked() = capture(launch(SshActivity::class.java), "ssh", "locked")
}
