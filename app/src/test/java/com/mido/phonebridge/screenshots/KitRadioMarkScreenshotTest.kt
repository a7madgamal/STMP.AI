package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the RadioMark component (COMPONENTS), light and dark at both font scales. */
class KitRadioMarkScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_radio_mark")
}
