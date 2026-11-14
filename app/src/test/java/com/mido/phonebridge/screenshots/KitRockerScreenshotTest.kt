package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Rocker component (COMPONENTS), light and dark at both font scales. */
class KitRockerScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_rocker")
}
