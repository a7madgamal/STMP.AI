package com.mido.phonebridge

import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.ui.FingerprintSetting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FingerprintSettingTest {

    @Before fun before() = Fixtures.dropSingletons()

    @After fun after() = Fixtures.reset()

    @Test
    fun `forceOff turns a stored on into off`() {
        val store = PolicyStore.from(Fixtures.app)
        assertEquals(true, store.get().fingerprintForModals)
        FingerprintSetting.forceOff(Fixtures.app, "no sensor", "settings")
        assertEquals(false, store.get().fingerprintForModals)
    }

    @Test
    fun `forceOff writes nothing when the value is already off`() {
        val store = PolicyStore.from(Fixtures.app)
        store.set(store.get().withFingerprintForModals(false))
        val before = store.get()
        FingerprintSetting.forceOff(Fixtures.app, "no sensor", "settings")
        assertEquals(before.fingerprintForModals, store.get().fingerprintForModals)
    }
}
