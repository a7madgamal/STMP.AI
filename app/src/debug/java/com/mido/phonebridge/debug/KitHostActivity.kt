package com.mido.phonebridge.debug

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens

/**
 * Hosts one kit component at a time for screenshot and behaviour tests only. A [ScrollView] with
 * a vertical column padded by the screen gutter; tests add views to [column]. Debug source set,
 * not exported.
 */
class KitHostActivity : AppCompatActivity() {

    lateinit var column: LinearLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Theme.palette(this)
        val gutter = Theme.dp(this, Dimens.GUTTER)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.body)
            setPadding(gutter, gutter, gutter, gutter)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.body)
            addView(column)
        })
    }
}
