package com.mido.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dialog fields carry a LABEL with the app's help bulb — never a placeholder alone.
 *
 * **The rule.** A hint is drawn inside the field and disappears as soon as there is text in it, so the
 * moment a person is deciding what to type is the moment the explanation is invisible. On these
 * screens the explanations are not decorative: a name is a route segment and a policy key, an issuer
 * copied slightly wrong produces a bare 401 from a server that will not say which claim failed, and a
 * host rule that looks like it narrows something may not.
 *
 * **Why a source scan.** The failure this guards against is a NEW field added later by someone who does
 * not know the rule, and no runtime assertion can see a field's missing label — the form builds fine
 * and looks plausible. So the scan refuses the old hint-only helper by name.
 */
class LabelledFieldTest {

    private val dialogs = listOf(
        File("src/main/java/com/mido/phonebridge/SshActivity.kt"),
        File("src/main/java/com/mido/phonebridge/JwtActivity.kt"),
    )

    @Test
    fun `the dialog screens exist where this test expects them`() {
        for (f in dialogs) {
            assertTrue("${f.path} not found — move this test with the screen", f.isFile)
        }
    }

    @Test
    fun `no dialog builds an input with a hint alone`() {
        // `field(` was the old helper: an EditText whose only explanation was its placeholder.
        val offenders = mutableListOf<String>()
        for (f in dialogs) {
            for ((n, line) in f.readLines().withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (Regex("""\bfield\(""").containsMatchIn(line)) {
                    offenders.add("${f.name}:${n + 1}: $trimmed")
                }
            }
        }
        assertFalse(
            "found an input built without a label — use labelledField(...) so the title and its " +
                "help bulb survive typing:\n" + offenders.joinToString("\n"),
            offenders.isNotEmpty(),
        )
    }

    @Test
    fun `every field on the two dialogs is a labelled one`() {
        for (f in dialogs) {
            val uses = Regex("""labelledField\(""").findAll(f.readText()).count()
            val choices = Regex("""labelledChoice\(""").findAll(f.readText()).count()
            assertTrue(
                "${f.name} builds no labelled fields — the dialogs are not using the helper",
                uses + choices > 0,
            )
        }
    }

    @Test
    fun `forms are paper dialogs, not platform alert dialogs`() {
        // Mido, 2026-11-10: *"not in a boring system dialog use a paperlike one"*. The two remaining
        // `AlertDialog.Builder` calls in these screens are the DESTRUCTIVE confirms, which go through
        // `.showDestructive()` — a separate, sanctioned pattern that exists to focus the harmless
        // button. Any other builder is a form that escaped the paper skin.
        for (f in dialogs) {
            val lines = f.readLines()
            for ((n, line) in lines.withIndex()) {
                if (!line.contains("AlertDialog.Builder(")) continue
                // Look ahead for the terminal call of this chain. 30 lines, not 14: the delete confirm
                // sets a title, a multi-line message, two buttons and their handlers, and
                // `.showDestructive()` is past the end of a shorter window — the check then failed on
                // a dialog that was already correct.
                val tail = lines.drop(n).take(30).joinToString("\n")
                assertTrue(
                    "${f.name}:${n + 1}: an AlertDialog that does not end in .showDestructive() — " +
                        "forms must use PaperDialog.show(...)",
                    tail.contains("showDestructive"),
                )
            }
        }
    }

    @Test
    fun `the shared helper draws the title, the help bulb, then the input`() {
        val helper = File("src/main/java/com/mido/phonebridge/ui/LabelledField.kt")
        assertTrue("the shared helper is missing", helper.isFile)
        val text = helper.readText()

        // **Order asserted at RUNTIME, not by file position.** `fieldHeader` is declared below
        // `labelledField` in the file, which says nothing about what is drawn first — the assertion
        // that matters is that `labelledField` adds the header BEFORE it creates and adds the input.
        val fieldBody = text.substringAfter("fun Activity.labelledField(")
        val headerCallAt = fieldBody.indexOf("fieldHeader(")
        val inputAt = fieldBody.indexOf("val input = EditText(this)")
        val headerAddedAt = fieldBody.indexOf("container.addView(")
        val inputAddedAt = fieldBody.indexOf("container.addView(input)")
        assertTrue("the field must add a header", headerCallAt >= 0)
        assertTrue("the header is added before the input is built", headerCallAt < inputAt)
        assertTrue("the header is attached before the input is", headerAddedAt < inputAddedAt)

        // The bulb is the app's own affordance, and it opens the app's own paper sheet.
        val headerBody = text.substringAfter("fun Activity.fieldHeader(")
        assertTrue(
            "the header must use Theme.bulbBadge, like the plugin list does",
            headerBody.contains("Theme.bulbBadge("),
        )
        assertTrue(
            "the bulb must open PaperDialog, not a hand-rolled dialog",
            headerBody.contains("PaperDialog.show("),
        )
    }

    @Test
    fun `the paper dialog draws its title above the scrolling body`() {
        // PaperDialog laid its children out in the order they were added, and the title was added
        // AFTER the scroll — so every paper dialog drew its heading underneath the content it
        // headed, with the scroll claiming all the slack height above it.
        val src = File("src/main/java/com/mido/phonebridge/ui/PaperDialog.kt").readText()
        val titleAt = src.indexOf("sheet.addView(Theme.text(ctx, title")
        val scrollAt = src.indexOf("sheet.addView(scroll")
        assertTrue("the title must be drawn", titleAt > 0)
        assertTrue("the scroll body must be attached", scrollAt > 0)
        assertTrue(
            "the title must be added BEFORE the scrolling body, or it renders below the content",
            titleAt < scrollAt,
        )
    }
}
