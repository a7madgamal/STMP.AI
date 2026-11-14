package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Punch component (COMPONENTS), light and dark at both font scales. */
class KitPunchScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_punch")
}
