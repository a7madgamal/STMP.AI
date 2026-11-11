package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the StatusRow component (COMPONENTS), light and dark at both font scales. */
class KitStatusRowScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_status_row")
}
