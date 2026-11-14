package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the VerbChip component (COMPONENTS), light and dark at both font scales. */
class KitVerbChipScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_verb_chip")
}
