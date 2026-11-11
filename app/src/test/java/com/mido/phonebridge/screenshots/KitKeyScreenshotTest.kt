package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Key component (COMPONENTS), light and dark at both font scales. */
class KitKeyScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_key")
}
