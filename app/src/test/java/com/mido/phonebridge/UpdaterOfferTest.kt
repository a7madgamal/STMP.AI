package com.mido.phonebridge

import com.mido.phonebridge.core.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater offers only a release Android would install, and shows its notes as plain bullets. */
class UpdaterOfferTest {

    @Test
    fun `a higher code with a newer name is offered`() =
        assertTrue(Updater.isOffered("0.52.3", 320, "0.52.2", 317))

    @Test
    fun `an equal code is not offered`() =
        assertFalse(Updater.isOffered("0.52.3", 317, "0.52.2", 317))

    @Test
    fun `a lower code is not offered even when its name looks newer`() =
        assertFalse(Updater.isOffered("0.51.5", 307, "0.49.17", 313))

    @Test
    fun `a higher code under an older name is a downgrade and is not offered`() =
        assertFalse(Updater.isOffered("0.40.0", 999, "0.52.2", 317))

    @Test
    fun `a release with no code falls back to the name comparison`() {
        assertTrue(Updater.isOffered("0.52.3", null, "0.52.2", 317))
        assertFalse(Updater.isOffered("0.52.2", null, "0.52.2", 317))
        assertFalse(Updater.isOffered("0.9.0", null, "0.44.0", 317))
    }

    @Test
    fun `the build code is read from the notes heading`() {
        assertEquals(307, Updater.releaseBuildCode("## 0.51.5 (build 307) — fix\n- x"))
        assertNull(Updater.releaseBuildCode("- just a bullet"))
    }

    @Test
    fun `notes drop the heading and markdown markers`() {
        val raw = "## 0.51.5 (build 307) — fixes\n\n- The `bridge` starts on **Android 12**\n* See [the guide](http://x.y)\n"
        assertEquals(
            "• The bridge starts on Android 12\n• See the guide",
            Updater.plainNotes(raw),
        )
        assertFalse(Updater.shortNotes(raw).contains("#"))
    }

    @Test
    fun `notes of only a heading are empty`() = assertEquals("", Updater.plainNotes("## 1.0.0 (build 5)"))
}
