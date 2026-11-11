package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the HoldKey component (COMPONENTS), light and dark at both font scales. */
class KitHoldKeyScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_hold_key")
}
