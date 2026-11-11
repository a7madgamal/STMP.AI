package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Etched component (COMPONENTS), light and dark at both font scales. */
class KitEtchedScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_etched")
}
