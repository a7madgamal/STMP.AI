package com.mido.phonebridge

import com.mido.phonebridge.plugins.help.HelpPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guard on [HelpPlugin.CONTRACT_VERSION].
 *
 * **Why a number needs a test.** `contractVersion` only means something if it moves exactly when
 * the agent-facing surface moves. A number that someone must remember to bump is a number that is
 * wrong within two releases — and stale is worse than absent, because an agent that trusts it will
 * keep using an understanding that no longer matches the API.
 *
 * So the route set is PINNED here. Adding, removing or renaming a route without bumping the
 * contract fails this test, and the failure message says which of the two edits is missing.
 *
 * A source scan rather than an instance check, for the same reason [ErrorCodesTest] scans: most
 * plugins need an Android `Context` to construct, the JVM suite has none, and a guard that can
 * only run on a device is a guard that runs too late.
 */
class HelpContractTest {

    @Test
    fun `the route set matches the pinned contract`() {
        val found = declaredRoutes()
        // Guard the guard: an empty scan is a broken regex, not a codebase with no routes.
        assertTrue("scan found no routes — the scan itself is broken", found.isNotEmpty())

        val missing = PINNED_ROUTES - found
        val added = found - PINNED_ROUTES

        assertTrue(
            "these routes were REMOVED but contractVersion is still $CONTRACT_VERSION: $missing. " +
                "If callers relied on them, bump CONTRACT_VERSION and update PINNED_ROUTES.",
            missing.isEmpty(),
        )
        assertTrue(
            "these routes were ADDED but contractVersion is still $CONTRACT_VERSION: $added. " +
                "A caller's cached understanding is now incomplete — bump CONTRACT_VERSION and " +
                "update PINNED_ROUTES.",
            added.isEmpty(),
        )
    }

    @Test
    fun `the pinned contract version matches the constant`() {
        // Two places, on purpose: the pin above describes the SURFACE, this describes the
        // NUMBER. Changing one without the other is exactly the drift the guard exists to catch.
        assertEquals(HelpPlugin.CONTRACT_VERSION, CONTRACT_VERSION)
    }

    @Test
    fun `only the two sound routes skip the gate`() {
        // `/help` must NOT be exempt: it is a normal plugin whose DEFAULT RULE downgrades it to a
        // NOTIFY card. If someone "helpfully" marks it gateExempt, the seeded rule becomes dead
        // config and the call stops being recorded as a decision.
        //
        // An exact set, so a THIRD exemption cannot be added quietly — it has to argue here.
        //
        // Both current entries are one decision (Mido, 2026-11-02: *"exempt as long as known
        // ip"*): a route whose job is to reach someone who is NOT looking cannot itself require
        // them to look. The bound is not the route but the CALLER — the caller check runs before
        // routing, so an unnamed address is refused before a route is even resolved, and neither
        // of these can be fired by a stranger. Neither can do anything but make a sound: one has
        // a closed set of three cues, the other speaks the string it was given and nothing else.
        assertEquals(
            setOf("POST /audio/{cue}", "POST /tts"),
            declaredExemptRoutes()
        )
    }

    /** Every `Method "path"` pair in the main source. */
    private fun declaredRoutes(): Set<String> {
        val root = sourceRoot()
        // `$secretName` is a Kotlin interpolation, not a URL template. Normalised to `{name}`
        // here so the scan matches what a caller actually sees on the wire.
        val pattern = Regex(""""(GET|POST|PUT|PATCH|DELETE)",\s*"(/[^"]*)"""")
        val found = mutableSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                for (m in pattern.findAll(file.readText())) {
                    val path = m.groupValues[2].replace("\$secretName", "{name}")
                    found.add("${m.groupValues[1]} $path")
                }
            }
        return found
    }

    /** Every route that declares `gateExempt = true`, keyed the same way. */
    private fun declaredExemptRoutes(): Set<String> {
        val root = sourceRoot()
        val routePattern = Regex(""""(GET|POST|PUT|PATCH|DELETE)",\s*"(/[^"]*)"""")
        val exempt = mutableSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                // A declaration block: from `Route(` to the next `) {` or `)` + newline. Simple
                // and adequate — a gateExempt flag is always inside the same constructor call.
                val blocks = text.split("Route(")
                for (block in blocks.drop(1)) {
                    if (!block.contains("gateExempt = true")) continue
                    val head = block.take(200)
                    val m = routePattern.find(head) ?: continue
                    val path = m.groupValues[2].replace("\$secretName", "{name}")
                    exempt.add("${m.groupValues[1]} $path")
                }
            }
        return exempt
    }

    private fun sourceRoot(): File {
        val root = File("src/main/java")
        assertTrue("main source tree not found at ${root.absolutePath}", root.isDirectory)
        return root
    }

    private companion object {
        /**
         * The agent-facing surface as of [CONTRACT_VERSION]. Update BOTH when the surface changes.
         *
         * Not generated: writing the pin by hand is what makes a bump a deliberate act rather
         * than something a script does while you are not looking.
         */
        const val CONTRACT_VERSION = 4

        val PINNED_ROUTES: Set<String> = setOf(
            "DELETE /audit",
            "DELETE /calendar/events/{id}",
            "GET /calendar/events",
            "GET /contacts",
            "GET /debug",
            "GET /drive/files",
            "GET /drive/files/{id}",
            "GET /drive/files/{id}/content",
            "GET /gmail/messages",
            "GET /gmail/messages/{id}",
            "GET /gmail/profile",
            "GET /help",
            "GET /probe",
            "GET /logs",
            "GET /notifications",
            "GET /secrets",
            "GET /secrets/{name}",
            "GET /ssh/job/{id}",
            "POST /audio/{cue}",
            "POST /calendar/events",
            "POST /calendar/events/{id}",
            "POST /calendar/events/{id}/move",
            "POST /calendar/quickadd",
            "POST /drive/files",
            "POST /drive/files/upload",
            "POST /drive/files/{id}/move",
            "POST /drive/files/{id}/rename",
            "POST /drive/files/{id}/trash",
            "POST /gmail/messages/send",
            "POST /gmail/messages/{id}/modify",
            "POST /gmail/messages/{id}/trash",
            "POST /notifications/{id}/action",
            "POST /notifications/{id}/dismiss",
            "POST /secrets/{name}/call",
            "POST /ssh/{name}",
            "POST /ssh/{name}/detach",
            "POST /tts",
        )
    }
}
