package com.mido.phonebridge

import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec 3.4.1 / 3.4.2: the name field has the first focus, never ALLOW, NOT NOW or BLOCK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class CallerFocusTest {

    @Test
    fun `the name field has the initial focus and no key does`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CallerActivity::class.java)
            .putExtra(CallerActivity.EXTRA_IP, "100.64.0.9")
        val activity = Robolectric.buildActivity(CallerActivity::class.java, intent).setup().visible().get()

        val focused = activity.window.decorView.findFocus()
        assertNotNull("something must have focus", focused)
        assertTrue("the focused view is the name field, was ${focused.javaClass.simpleName}", focused is EditText)
    }
}
