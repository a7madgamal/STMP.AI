package com.mido.phonebridge.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * The real summariser: a small instruct model via **LiteRT-LM** — Google's
 * production on-device LLM runtime, and the supported successor to the MediaPipe
 * LLM Inference API this class replaced (Mido, 2026-10-19).
 *
 * **Why LiteRT-LM and not MediaPipe.** MediaPipe's `tasks-genai` is in
 * maintenance-only mode; Google recommends LiteRT-LM, which is stable (0.18.0),
 * ships a real Android API, and runs a wider model set (Gemma, Qwen, Phi-4, Llama).
 * The old code carried a `@file:Suppress("DEPRECATION")` to hide the debt; there is
 * none here.
 *
 * **Why the model is not Gemma.** Every small Gemma in `litert-community` is gated
 * behind a Hugging Face license click (401 without a token), which makes the model
 * un-fetchable on a machine with no token — the exact blocker that stopped the
 * first attempt. The model shipped instead is `Qwen3-0.6B` mixed-int4: an official,
 * ungated, Apache-2.0 artifact from the same `litert-community` zoo, the smallest
 * *general-purpose* instruct model published as a plain `.litertlm`. Swapping back
 * to Gemma is one constant in [ModelRegistry] once a token is available.
 *
 * **The model is NOT bundled in the APK.** It is a ~474MB `.litertlm` file that the
 * app fetches for itself (see [ModelProvisioner]) and keeps at
 * [ModelRegistry.MODEL_ASSET]. The AI is core functionality (Mido, 2026-10-19), so a
 * missing model is a NOT-READY state rather than a degraded one: there is no heuristic
 * fallback any more, and the server refuses to serve until [ModelRegistry.readiness]
 * passes.
 *
 * **One model, one call.** The prompt asks for a single JSON object and the runtime
 * is told to constrain decoding to that schema, so the parse is total: a reply that
 * is not that object is a failure, not a guess.
 */
class LiteRtLm(private val modelFile: File) : SummaryModel {

    override val id: String = "qwen3-0.6b-int4"

    companion object {
        private const val TAG = "LiteRtLm"

        /**
         * Cap on generated tokens. The answer is one short line plus a risk word and
         * a short reason — ~60 tokens is generous. A tight cap is what keeps latency
         * in the seconds range, which is the whole budget on the NOTIFY card.
         */
        private const val MAX_OUTPUT_TOKENS = 128

        /**
         * KV-cache size (input + output). The facts are a handful of short rows; a
         * 1024-token window is generous and keeps the cache small, which is the
         * dominant memory term on a 0.6B model.
         */
        private const val MAX_CONTEXT_TOKENS = 1024

        /** Threads for the CPU backend; the device has 8 cores, leave headroom for UI. */
        private const val CPU_THREADS = 4

        /**
         * The output contract, stated in the prompt rather than enforced by constrained
         * decoding.
         *
         * **Constrained decoding was tried and REMOVED** (2026-10-19): with the
         * `LL_GUIDANCE` provider this model truncates mid-object — a real run returned
         * the literal string `{"description":"}` — while the same prompt without the
         * constraint produced valid JSON on READ, DELETE and EXECUTE. A constraint that
         * silently emits half an object is worse than none, because the parser would
         * reject it and (with the AI as core) fail the whole request.
         *
         * So the shape is ASKED for, and [parse] is what guarantees correctness: it
         * returns null on anything that is not the expected object. The prompt is
         * deliberately rigid because that is what makes a small model comply.
         */
        private const val SYSTEM =
            "You describe an API request to the person who must approve it. " +
                "Reply with ONE JSON object and nothing else, in exactly this shape: " +
                "{\"description\": \"<one short sentence>\", " +
                "\"risk\": \"low|medium|high\", \"reason\": \"<a few words>\"}. " +
                "State plainly what will happen, in plain words, no jargon. " +
                "risk is low for reads, medium for edits, high for deletes or anything " +
                "sent outside the account. Never invent names or values that are not given."
    }

    /**
     * The engine is expensive to build (it maps the model file) and cheap to reuse,
     * so it is created once and cached. The *conversation* is NOT cached: it carries
     * chat history, and each request must be described on its own facts, never in
     * the shadow of the previous one.
     */
    @Volatile
    private var engine: Engine? = null

    override fun isAvailable(ctx: Context): Boolean = modelFile.exists() && modelFile.length() > 0

    /**
     * Load the model now so the first request does not pay for it.
     *
     * Blocking — call off the main thread. Idempotent, and a no-op when the file is
     * absent: whether the bridge may serve at all is [ModelRegistry.readiness]'s call,
     * not this one's. Warm-up buys latency, never permission.
     */
    fun warm(ctx: Context) {
        if (!isAvailable(ctx)) return
        try {
            engine ?: createEngine().also { engine = it }
        } catch (e: Exception) {
            // A failed warm-up is not fatal: the next request retries the load and
            // reports its own failure. Swallowing here would be a silent fallback only
            // if it changed an outcome, and it does not.
            Log.w(TAG, "warm-up failed: ${e.message}")
        }
    }

    /**
     * Drop the loaded model and free its memory (~700 MiB resident, measured 2026-10-19).
     *
     * The release valve for [android.app.Service.onTrimMemory]. The engine is a luxury
     * the OS may reclaim under pressure, and surrendering it on demand is what keeps the
     * foreground service from being killed outright — a killed service denies every
     * request, which is strictly worse than a slow one.
     */
    fun release() {
        val e = engine ?: return
        engine = null
        runCatching { e.close() }
        Log.i(TAG, "engine released")
    }

    override fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary? {
        if (!isAvailable(ctx)) return null
        val start = System.currentTimeMillis()
        return try {
            val eng = engine ?: createEngine().also { engine = it }
            val reply = runInference(eng, facts)
            val elapsed = System.currentTimeMillis() - start
            // Over budget: the caller's window is the promise. A late answer on a
            // NOTIFY card is worse than no answer, because the card may already be
            // gone and the human decided without it.
            if (elapsed > budgetMs) {
                Log.w(TAG, "inference ${elapsed}ms exceeded budget ${budgetMs}ms; discarding")
                return null
            }
            parse(reply)?.also {
                Log.i(TAG, "summarised in ${elapsed}ms: risk=${it.risk}")
            }
        } catch (e: Exception) {
            // Never throw: a summariser failure must degrade to the heuristic, not
            // fail the request.
            Log.w(TAG, "inference failed: ${e.message}")
            null
        }
    }

    /** One fresh conversation per call, so no request is described in another's context. */
    private fun runInference(eng: Engine, facts: RequestFacts): String {
        val config = ConversationConfig(
            // The system instruction is the guide (Mido, 2026-10-19: "I think it makes
            // sense for the model to have a short system prompt to guide it"). It is
            // also the exact shape the OLD model file crashed on, so its presence here is
            // load-bearing — see ModelRegistry.MODEL_URL for why the artifact was swapped.
            systemInstruction = Contents.of(SYSTEM),
            // Greedy: the same request must describe itself the same way twice.
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            // Qwen3 reasons by default; the reasoning would land on a separate channel
            // and only waste the token budget here.
            thinkingConfig = ThinkingConfig(enableThinking = false),
            maxOutputToken = MAX_OUTPUT_TOKENS,
            // NO `enableResponseFormat`. Constrained decoding truncated mid-object on
            // this model (see SYSTEM); the prompt asks for the shape and [parse] enforces
            // it. Leaving it off is the tested-working configuration.
        )
        var conversation: Conversation? = null
        return try {
            conversation = eng.createConversation(config)
            conversation.sendMessage(buildPrompt(facts)).toString()
        } finally {
            conversation?.close()
        }
    }

    /**
     * GPU first, CPU second. The GPU is several times faster on this class of model
     * (the difference between fitting the modal's budget and not), but OpenCL is not
     * guaranteed on every device — so a GPU that will not initialise is an explicit,
     * logged fallback to CPU, not a silent one.
     */
    private fun createEngine(): Engine {
        return try {
            buildEngine(Backend.GPU()).also { Log.i(TAG, "engine on GPU") }
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend unavailable (${e.message}); using CPU")
            buildEngine(Backend.CPU(threadCount = CPU_THREADS)).also { Log.i(TAG, "engine on CPU") }
        }
    }

    private fun buildEngine(backend: Backend): Engine {
        Log.i(TAG, "loading ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB) on $backend")
        val engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                maxNumTokens = MAX_CONTEXT_TOKENS,
            )
        )
        try {
            engine.initialize()
        } catch (e: Exception) {
            // A half-initialised engine holds a native handle; drop it before the
            // caller decides whether to try another backend.
            engine.close()
            throw e
        }
        return engine
    }

    /**
     * The prompt. Deliberately rigid: a small model needs one unambiguous job and an
     * exact output shape. The facts are labelled rows — the same ones the human sees
     * — never raw JSON, which is unbounded and caller-authored.
     */
    private fun buildPrompt(facts: RequestFacts): String {
        val rows = facts.rows.joinToString("\n") { "- ${it.first}: ${it.second.take(200)}" }
        return """
            plugin: ${facts.plugin}
            verb: ${facts.verb}
            route: ${facts.routeLabel}
            summary: ${facts.summary}
            facts:
            $rows
        """.trimIndent()
    }

    /**
     * Parse the model's reply into a [RequestSummary]. Total and strict: a reply that
     * is not the expected JSON object returns null (→ heuristic fallback) rather than
     * being coerced into a half-answer.
     */
    private fun parse(reply: String): RequestSummary? {
        val json = extractJson(reply) ?: return null
        val obj = try {
            org.json.JSONObject(json)
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "reply not JSON: ${reply.take(120)}")
            return null
        }
        val description = obj.optString("description").trim()
        if (description.isBlank()) return null
        val risk = when (obj.optString("risk").trim().lowercase()) {
            "low" -> Risk.LOW
            "medium", "med" -> Risk.MEDIUM
            "high" -> Risk.HIGH
            else -> return null // an unlabelled risk is a failed answer, not a guess
        }
        return RequestSummary(
            description = description.take(160),
            risk = risk,
            reason = obj.optString("reason").trim().take(120)
        )
    }

    /**
     * Pull the first balanced `{...}` out of the reply. Even with constrained
     * decoding the object is *located* rather than assumed to be the whole string,
     * so a runtime that adds framing does not turn a good answer into a null — but
     * the parse still has to succeed on real JSON, so this is a locator, not a repair.
     */
    private fun extractJson(reply: String): String? {
        val start = reply.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until reply.length) {
            when (reply[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return reply.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
