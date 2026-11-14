package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Field component (COMPONENTS), light and dark at both font scales. */
class KitFieldScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_field")
}
