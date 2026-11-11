package com.mido.phonebridge.debug

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.TabBar
import com.mido.phonebridge.TopInset

/**
 * Hosts one tab fragment inside the same shell as `MainActivity`, for screenshot tests only.
 *
 * It lives in the debug source set, is not exported, and calls nothing from
 * `MainActivity.appStartup` (no auto-start, no update check, no vault prompt).
 *
 * Extras: `fragment` = fully qualified fragment class name, `tab` = a `Theme.Tab` name.
 */
class ScreenshotHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.palette(this@ScreenshotHostActivity).body)
        }
        val container = FrameLayout(this).apply { id = CONTAINER_ID }
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        TopInset.apply(root)
        setContentView(root)

        val className = requireNotNull(intent.getStringExtra(EXTRA_FRAGMENT)) { "extra '$EXTRA_FRAGMENT' missing" }
        val tab = Theme.Tab.valueOf(intent.getStringExtra(EXTRA_TAB) ?: Theme.Tab.HOME.name)
        val fragment = Class.forName(className).getDeclaredConstructor().newInstance() as Fragment

        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, fragment)
            .setTransition(FragmentTransaction.TRANSIT_NONE)
            .commitNow()
        // Same bar as MainActivity: built once, under the container.
        root.addView(
            TabBar(this, current = tab) { },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    companion object {
        const val EXTRA_FRAGMENT = "fragment"
        const val EXTRA_TAB = "tab"
        private const val CONTAINER_ID = 0x5E1A
    }
}
