package com.mido.phonebridge

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen approval UI. Shows every detail of the pending request and
 * blocks until the human chooses approve/deny.
 */
class ApprovalActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SUMMARY = "summary"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw over other apps / show over the lockscreen, turn the screen on,
        // and keep it on while the human decides. This is what makes the modal
        // appear *immediately* regardless of what is in the foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: "?"
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: "?"
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "?"

        // We only have the summary here; the full detail JSON is pulled from the
        // gate's current pending request to keep the notification small.
        val detail = ApprovalGate.currentDetailJson()

        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        fun label(t: String, size: Float, color: Int = 0xFFFFFFFF.toInt()): TextView =
            TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            }

        root.addView(label("🔐 Approval required", 24f))
        root.addView(label("Source: $remote", 15f, 0xFFFFB74D.toInt()))
        root.addView(label("Endpoint: $endpoint", 15f, 0xFFFFB74D.toInt()))
        root.addView(label("Summary: $summary", 16f))

        val scroll = ScrollView(this)
        val detailView = TextView(this).apply {
            text = detail ?: "(no detail)"
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(detailView)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0
        ).apply { weight = 1f }
        root.addView(scroll, lp)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val deny = Button(this).apply {
            text = "DENY"
            setOnClickListener { finishWith(id, approved = false) }
        }
        val allow = Button(this).apply {
            text = "APPROVE"
            setOnClickListener { finishWith(id, approved = true) }
        }
        btnRow.addView(deny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(allow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        setContentView(root)
    }

    private fun finishWith(id: Long, approved: Boolean) {
        val resolved = ApprovalGate.resolve(id, approved)
        if (!resolved) Log.w("ApprovalActivity", "request $id already resolved/expired")
        finish()
    }

    @Deprecated("Deprecated in the platform; kept for back-compat behavior on older devices.")
    override fun onBackPressed() {
        // Back == deny. Never leave a request dangling open by accident.
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        finishWith(id, approved = false)
    }
}
