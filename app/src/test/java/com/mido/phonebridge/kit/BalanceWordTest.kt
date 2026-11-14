package com.mido.phonebridge.kit

import com.mido.phonebridge.ui.kit.balanceWord
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceWordTest {
    @Test fun `a four word status breaks in the middle, not after the third word`() {
        assertEquals("Not in this build", balanceWord("Not in this build"))
    }

    @Test fun `a short word is left alone`() {
        assertEquals("Not available", balanceWord("Not available"))
        assertEquals("On", balanceWord("On"))
    }
}
