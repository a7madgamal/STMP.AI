package com.mido.phonebridge

import com.mido.phonebridge.core.ForegroundNotice
import com.mido.phonebridge.policy.GlobalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ForegroundNoticeTest {

    @Test
    fun `the title says starting until there is an address`() {
        assertEquals("STMP · starting", ForegroundNotice.title(null, 8787))
        assertEquals("STMP · starting", ForegroundNotice.title("", 8787))
    }

    @Test
    fun `the title names the address and port`() {
        assertEquals("STMP · open on 100.101.7.22:8787", ForegroundNotice.title("100.101.7.22", 8787))
    }

    @Test
    fun `the text names each gate mode`() {
        assertEquals("Whitelist mode", ForegroundNotice.text("1.2.3.4", GlobalMode.WHITELIST))
        assertEquals("Paranoid mode", ForegroundNotice.text("1.2.3.4", GlobalMode.PARANOID))
        assertEquals("Don't disturb mode", ForegroundNotice.text("1.2.3.4", GlobalMode.DONT_DISTURB))
    }

    @Test
    fun `the text explains a missing address and an unreadable mode`() {
        assertEquals("Looking for a network address", ForegroundNotice.text("", GlobalMode.WHITELIST))
        assertEquals("Looking for a network address", ForegroundNotice.text(null, null))
        assertEquals("Gate mode unknown", ForegroundNotice.text("1.2.3.4", null))
    }

    @Test
    fun `no line says PhoneBridge`() {
        val all = listOf(
            ForegroundNotice.title(null, 1), ForegroundNotice.title("1.2.3.4", 1),
            ForegroundNotice.text(null, null), ForegroundNotice.text("1.2.3.4", null),
            ForegroundNotice.PUBLIC_TITLE,
        ) + GlobalMode.entries.map { ForegroundNotice.text("1.2.3.4", it) }
        all.forEach { assertFalse(it, it.contains("PhoneBridge")) }
    }
}
