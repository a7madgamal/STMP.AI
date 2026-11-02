package com.mido.phonebridge

import com.mido.phonebridge.ai.ExternalModels
import com.mido.phonebridge.policy.ExternalDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The model listing is where this feature can fail QUIETLY.
 *
 * A server that renames a field, or a body that is JSON but the wrong JSON, parses to an empty
 * list — and an empty list is an empty picker, which looks like a broken server rather than a
 * parser that does not understand it. These tests pin the exact shapes and, just as importantly,
 * pin the DIFFERENCE between "listed nothing" and "not readable" so the two can be worded
 * differently to a human.
 */
// Robolectric because `org.json` is an Android class: on a plain JVM its methods are stubs that
// return null, so every parser here would "fail" for a reason that has nothing to do with parsing.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalModelsTest {

    @Test
    fun `openai listing yields sorted distinct ids`() {
        val body = """
            {"object":"list","data":[
              {"id":"unsloth/Qwen3.8-27B-GGUF","object":"model"},
              {"id":"llama3:8b","object":"model"},
              {"id":"llama3:8b","object":"model"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("llama3:8b", "unsloth/Qwen3.8-27B-GGUF"),
            ExternalModels.parse(ExternalDialect.OPENAI, body)
        )
    }

    @Test
    fun `ollama listing reads name and tolerates the older model key`() {
        val body = """
            {"models":[
              {"name":"qwen3:8b","size":123},
              {"model":"llama3:8b","size":456}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("llama3:8b", "qwen3:8b"),
            ExternalModels.parse(ExternalDialect.OLLAMA, body)
        )
    }

    @Test
    fun `a 200 with no entries is an empty list, not an error`() {
        // These two outcomes get DIFFERENT words on screen: "this server has nothing" versus
        // "this is not the server you think it is". Collapsing them hides a wrong address.
        assertEquals(
            emptyList<String>(),
            ExternalModels.parse(ExternalDialect.OPENAI, """{"data":[]}""")
        )
    }

    @Test
    fun `a body in the wrong shape is an error, never an empty list`() {
        // An Ollama body handed to the OpenAI parser must NOT read as "no models": that would
        // report a working server as empty and send someone hunting for a missing download.
        val ollamaBody = """{"models":[{"name":"qwen3:8b"}]}"""
        val failure = runCatching {
            ExternalModels.parse(ExternalDialect.OPENAI, ollamaBody)
        }.exceptionOrNull()
        assertTrue(
            "expected a readable-shape failure, got $failure",
            failure is IllegalStateException
        )
        assertTrue(
            "the message must name the dialect so the report is actionable: ${failure?.message}",
            failure?.message!!.contains("OpenAI-compatible")
        )
    }

    @Test
    fun `non-json is an error`() {
        val failure = runCatching {
            ExternalModels.parse(ExternalDialect.OLLAMA, "<html>nope</html>")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `each dialect points at a different listing and chat route`() {
        // The whole bug this change removes: one hardcoded chat path for both shapes. If these
        // two ever converge, that bug is back.
        assertEquals("/v1/models", ExternalDialect.OPENAI.listPath)
        assertEquals("/v1/chat/completions", ExternalDialect.OPENAI.chatPath)
        assertEquals("/api/tags", ExternalDialect.OLLAMA.listPath)
        assertEquals("/api/chat", ExternalDialect.OLLAMA.chatPath)
        assertTrue(ExternalDialect.OPENAI.chatPath != ExternalDialect.OLLAMA.chatPath)
    }
}
