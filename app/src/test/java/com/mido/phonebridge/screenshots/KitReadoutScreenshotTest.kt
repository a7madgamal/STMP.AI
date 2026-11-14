package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Readout component (COMPONENTS), light and dark at both font scales. */
class KitReadoutScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_readout")
}
