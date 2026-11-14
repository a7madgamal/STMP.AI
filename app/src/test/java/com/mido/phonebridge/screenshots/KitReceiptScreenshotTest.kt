package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Receipt component (COMPONENTS), light and dark at both font scales. */
class KitReceiptScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_receipt")
}
