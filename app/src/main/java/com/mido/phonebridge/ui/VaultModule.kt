package com.mido.phonebridge.ui

import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import android.widget.ScrollView
import com.mido.phonebridge.BottomInset
import com.mido.phonebridge.ui.kit.TopBar
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.core.VaultState
import com.mido.phonebridge.ui.kit.DISABLED_ALPHA_F
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The vault card shared by the Secrets and SSH screens (WP09 3.1). It states the vault's real
 * condition and offers the one key that fixes it. [model] is pure so the table can be tested
 * without a device.
 */
internal object VaultModule {
    enum class Action { UNLOCK, CREATE, RESET }

    data class Model(
        val led: Led.State,
        val tone: StatusRow.Tone,
        val value: String,
        val detail: String,
        val action: Action?,
        val actionLabel: String?,
    )

    private const val CREATE_DETAIL = "Create the vault with your fingerprint before adding anything."
    private const val UNRECOVERABLE_DETAIL =
        "An earlier build saved this vault without the detail it needs to reopen it, so its secrets can't be recovered."
    private const val ORPHANED_DETAIL =
        "The vault's key was removed when the app was reinstalled, so its secrets are gone."

    /** [state] is `SecretVault.state(ctx)`, [unlocked] is `SecretVault.isUnlocked()`. Unlocked wins. */
    fun model(state: VaultState.State, unlocked: Boolean, forSsh: Boolean): Model {
        if (unlocked) {
            return Model(
                Led.State.OK, StatusRow.Tone.OK, "UNLOCKED",
                if (forSsh) "Open for this session." else "Open for this session. Values are revealed one at a time.",
                null, null,
            )
        }
        return when (state) {
            VaultState.State.UNLOCKABLE -> Model(
                Led.State.CAUTION, StatusRow.Tone.CAUTION, "LOCKED",
                if (forSsh) "Unlock to add a connection."
                else "Names, links and allowed hosts stay readable. Unlock to add, replace or reveal.",
                Action.UNLOCK, "UNLOCK",
            )
            VaultState.State.ABSENT -> Model(
                Led.State.OFF, StatusRow.Tone.NEUTRAL, "NOT CREATED", CREATE_DETAIL,
                Action.CREATE, "CREATE VAULT",
            )
            VaultState.State.UNRECOVERABLE -> Model(
                Led.State.CAUTION, StatusRow.Tone.CAUTION, "NEEDS RESET", UNRECOVERABLE_DETAIL,
                Action.RESET, "RESET VAULT",
            )
            VaultState.State.ORPHANED -> Model(
                Led.State.CAUTION, StatusRow.Tone.CAUTION, "NEEDS RESET", ORPHANED_DETAIL,
                Action.RESET, "RESET VAULT",
            )
        }
    }

    /** One plain panel: the status row, the last failure if any, and the fixing key if any. */
    fun create(
        activity: FragmentActivity,
        model: Model,
        lastError: String?,
        onAction: (Action) -> Unit,
    ): View {
        val p = Theme.palette(activity)
        val panel = Panel.create(activity, Panel.Tone.PLAIN)
        panel.addView(
            StatusRow.create(
                activity, label = "Secret vault", value = model.value, tone = model.tone,
                detail = model.detail, led = model.led,
            )
        )
        if (lastError != null) {
            panel.addView(
                Type.text(activity, "Not unlocked: $lastError", TypeRole.DETAIL, p.cautionText).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Theme.dp(activity, Dimens.SPACE_S) },
            )
        }
        val action = model.action
        val label = model.actionLabel
        if (action != null && label != null) {
            panel.addView(
                KeyButton.create(
                    activity, label, KeyButton.Kind.PANEL, KeyButton.Size.REGULAR,
                    describe = "$label the secret vault",
                ) { onAction(action) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Theme.dp(activity, Dimens.SPACE_M) },
            )
        }
        return panel
    }

    /** Greys a key out (alpha and `isEnabled`), so it is plainly unavailable while the vault is locked. */
    fun setEnabled(key: View, enabled: Boolean) {
        key.isEnabled = enabled
        key.alpha = if (enabled) 1f else DISABLED_ALPHA_F
    }

    /** Enables or disables the key inside [root] whose content description is [describe]. */
    fun setKeyEnabled(root: View, describe: String, enabled: Boolean) {
        if (root is MaterialButton && root.contentDescription?.toString() == describe) {
            setEnabled(root, enabled)
            return
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) setKeyEnabled(root.getChildAt(i), describe, enabled)
    }

    /** The pieces of a vault screen: the root to show and the column its slots are added to. */
    class Frame(val root: LinearLayout, val content: LinearLayout)

    /**
     * The frame Secrets and SSH share (WP09 3.0): a top bar with a back key, then a scroll area
     * with 20dp gutters. The top bar pads itself by the status-bar inset, the content pads its
     * bottom by the gesture bar, and the root pads nothing (never both, TOKENS 12).
     */
    fun frame(activity: FragmentActivity, title: String): Frame {
        val p = Theme.palette(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.body)
        }
        root.addView(TopBar.create(activity, title))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(activity, Dimens.GUTTER), Theme.dp(activity, Dimens.SPACE_L),
                Theme.dp(activity, Dimens.GUTTER), Theme.dp(activity, Dimens.SPACE_XL),
            )
        }
        BottomInset.apply(content)
        val scroll = ScrollView(activity).apply {
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        activity.setContentView(root)
        return Frame(root, content)
    }

    /**
     * A dialog body with the 24dp side inset, in a scroll view so five fields still reach the keys
     * at large text. `setView` adds no inset of its own (the kit's `StmpDialog.show` pads the same way).
     */
    fun dialogBody(activity: FragmentActivity, body: View): View =
        androidx.core.widget.NestedScrollView(activity).apply {
            setPadding(Theme.dp(activity, Dimens.SPACE_XL), 0, Theme.dp(activity, Dimens.SPACE_XL), 0)
            addView(body)
        }
}

/**
 * Dialog windows do not inherit the activity's FLAG_SECURE, so a dialog that holds or shows a
 * secret must set it on its own window before it is shown.
 */
fun AlertDialog.showSecure() {
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
    )
    show()
}

fun AlertDialog.Builder.showSecure(): AlertDialog = create().also { it.showSecure() }
