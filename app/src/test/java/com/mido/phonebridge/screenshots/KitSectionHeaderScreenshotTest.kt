package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the SectionHeader component (COMPONENTS), light and dark at both font scales. */
class KitSectionHeaderScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_section_header")
}
