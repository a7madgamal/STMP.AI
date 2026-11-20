package com.mido.phonebridge.ui

import android.app.Activity
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

/**
 * One labelled input: a title with the app's own help bulb beside it, then the field.
 *
 * **Why a label and not a hint.** A hint is drawn inside the field and disappears the moment there is
 * any text in it — so the moment a person is deciding what to type is exactly when the explanation is
 * invisible. On these screens the explanations are not decoration either: a name becomes a route
 * segment and a policy key, an issuer copied slightly wrong produces a bare 401 from a server that
 * will not say which claim failed, and a host rule that looks narrow may not be.
 *
 * **The bulb is [Theme.bulbBadge], the same affordance the plugin list uses**, and the explanation
 * opens in [PaperDialog], the same sheet every other help surface in the app uses. Inventing a second
 * help control would mean two things on one screen that say "tap for an explanation" and look
 * different.
 *
 * The [example] stays as the field's hint: it is a FORMAT rather than an explanation, it is short, and
 * losing it once typing starts costs nothing now that the real answer is one tap away.
 *
 * @return the input, so a caller can still set a default value or a numeric keyboard.
 */
fun Activity.labelledField(
    container: LinearLayout,
    title: String,
    help: String,
    example: String = "",
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    required: Boolean = false,
): EditText {
    container.addView(
        this@labelledField.fieldHeader(title, help, example, required = required)
    )
    val input = EditText(this).apply {
        // Explicit colours: the platform default is white-on-cream here, and with a DayNight parent it
        // went the other way on a dark-mode phone.
        setTextColor(Theme.ON_SURFACE)
        setHintTextColor(Theme.ON_SURFACE_VARIANT)
        highlightColor = Theme.ACCENT
        // A drawn background, so the field is a paper block rather than a Material underline that
        // carries its own theme colours.
        background = Theme.outlined(this@labelledField, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL)
        setPadding(
            Theme.dp(this@labelledField, 12), Theme.dp(this@labelledField, 10),
            Theme.dp(this@labelledField, 12), Theme.dp(this@labelledField, 10)
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(this@labelledField, 10) }
        hint = example
        this.inputType = inputType
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    container.addView(input)
    return input
}

/**
 * The title row a labelled field draws: the name, a "required" marker when it applies, and the bulb.
 *
 * Exposed because a CHOICE (a tap-to-cycle button) needs the identical header, and a second
 * hand-rolled copy is how the two would come to disagree about weight, colour or the bulb's size.
 *
 * @param required draws a small "REQUIRED" marker. Only for fields with no working default — marking
 *   everything required is the same as marking nothing.
 */
fun Activity.fieldHeader(
    title: String,
    help: String,
    example: String = "",
    required: Boolean = false,
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, Theme.dp(this@fieldHeader, 6), 0, 0)
    }
    row.addView(
        Theme.text(this, title, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE).apply {
            typeface = Theme.bodyFont(this@fieldHeader, bold = true)
        }
    )
    if (required) {
        row.addView(
            // 13sp is the floor (`Theme.TEXT_CAPTION_SP`); this was 10f, which is the size the
            // project's own typography test exists to reject.
            Theme.text(this, "REQUIRED", Theme.TEXT_CAPTION_SP, Theme.ACCENT).apply {
                typeface = Theme.bodyFont(this@fieldHeader, bold = true)
                setPadding(Theme.dp(this@fieldHeader, 6), 0, 0, 0)
            }
        )
    }
    // The bulb sits last and takes the click, so the row itself stays inert — the same rule the
    // plugin list row follows.
    row.addView(
        Theme.bulbBadge(this, "What is $title?") {
            PaperDialog.show(
                ctx = this,
                title = title,
                body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(PaperDialog.caption(this@fieldHeader, help))
                    if (example.isNotBlank()) {
                        addView(PaperDialog.heading(this@fieldHeader, "Example"))
                        addView(PaperDialog.line(this@fieldHeader, "", example))
                    }
                },
                actions = listOf("got it" to {}),
                style = { Theme.ButtonStyle.PRIMARY },
            )
        },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = Theme.dp(this@fieldHeader, 4) }
    )
    return row
}
