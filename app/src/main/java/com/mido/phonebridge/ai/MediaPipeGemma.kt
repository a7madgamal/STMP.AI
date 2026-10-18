@file:Suppress("DEPRECATION")

package com.mido.phonebridge.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Gemma 3 via MediaPipe LLM Inference — the real summariser.
 *
 * **The model is NOT bundled in the APK.** It is a ~550MB `.task` file that must be
 * placed on the device (see [ModelRegistry.MODEL_ASSET] for the path). This class is
 * therefore written to be *absent-safe*: every entry point returns null rather than
 * throwing when the file is missing, so a phone without the model simply falls back
 * to [HeuristicSummarizer]. That is the whole contract of [SummaryModel], and it is
 * what keeps a clarity feature from ever blocking an approval.
 *
 * Why MediaPipe and not AICore/Gemini Nano: Nano is a Pixel 8+/S24+ system feature.
 * The target device is an S22 Ultra, where it does not exist, so the system path is
 * a dead end. MediaPipe ships its own runtime and runs a real Gemma 3 on any device.
 *
 * One model, one call. The prompt asks for a single JSON object so the parse is
 * total: a reply that is not that object is a failure, not a guess. A second model
 * for risk would double RAM and latency for no information gain — the same call
 * already sees the request.
 */
class MediaPipeGemma(private val modelFile: File) : SummaryModel {

    override val id: String = "gemma3-1b-int4"

    companion object {
        private const val TAG = "MediaPipeGemma"

        /**
         * Cap on generated tokens. The answer is one short line plus a risk word and a
         * short reason — ~60 tokens is generous. A tight cap is what keeps latency in
         * the seconds range, which is the whole budget on the NOTIFY card.
         */
        private const val MAX_TOKENS = 96

        /**
         * Hard cap on prompt tokens. The facts are a handful of short rows; anything
         * larger means something upstream is wrong and should fail rather than queue
         * behind a slow prefill.
         */
        private const val MAX_PROMPT_TOKENS = 512
    }

    @Volatile
    private var engine: LlmInference? = null

    override fun isAvailable(ctx: Context): Boolean = modelFile.exists() && modelFile.length() > 0

    override fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary? {
        if (!isAvailable(ctx)) return null
        val start = System.currentTimeMillis()
        return try {
            val llm = engine ?: create(ctx).also { engine = it }
            val prompt = buildPrompt(facts)
            val reply = llm.generateResponse(prompt)
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

    private fun create(ctx: Context): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setMaxTopK(1)
            .build()
        Log.i(TAG, "loading model ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")
        return LlmInference.createFromOptions(ctx, options)
    }

    /**
     * The prompt. Deliberately rigid: a small model needs one unambiguous job and an
     * exact output shape, or it produces prose that cannot be parsed. The facts are
     * labelled rows — the same ones the human sees — never raw JSON, which is
     * unbounded and caller-authored.
     */
    private fun buildPrompt(facts: RequestFacts): String {
        val rows = facts.rows.joinToString("\n") { "- ${it.first}: ${it.second.take(200)}" }
        return """
            You describe an API request to the person who must approve it.
            Reply with ONE JSON object and nothing else:
            {"description": "<one short sentence, max 12 words>", "risk": "low|medium|high", "reason": "<max 10 words>"}

            Rules:
            - description states what will happen, in plain words, no jargon.
            - risk is low for reads, medium for edits, high for deletes or anything sent outside the account.
            - Never invent names or values that are not below.

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
     * being coerced into a half-answer. A model that rambles must not produce a
     * description the human reads as fact.
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
     * Pull the first balanced `{...}` out of the reply. A small model often wraps its
     * JSON in prose or a code fence even when told not to, so the object is located
     * rather than assumed to be the whole string — but the parse still has to succeed
     * on real JSON, so this is a locator, not a repair.
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
