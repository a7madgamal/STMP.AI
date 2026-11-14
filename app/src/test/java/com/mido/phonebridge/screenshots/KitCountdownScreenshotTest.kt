package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Countdown component (COMPONENTS), light and dark at both font scales. */
class KitCountdownScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_countdown")
}
