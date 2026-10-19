package com.mido.phonebridge.ai

import com.mido.phonebridge.core.Verb

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

    /**
     * Reported in `model warm:` and `/debug`. It said `qwen3-0.6b-int4` while actually loading
     * the 1.7B file after the swap — a log line that lies about which model is resident, which
     * is precisely the kind of thing that makes a later diagnosis wrong.
     */
    override val id: String = "qwen3-1.7b-int4"

    companion object {
        private const val TAG = "LiteRtLm"

        /**
         * Why the engine is not usable, or "" — surfaced in `/debug` and in the modal.
         *
         * **Companion-level, not per-instance**, because [ModelRegistry] caches exactly one
         * summariser: there is one engine in the process, so there is one failure reason.
         * Keeping it per-instance would have made it unreadable from the gate, which is
         * where it is needed — the modal has to say WHY the summary failed, and a bare
         * "failed" is the dead end this change exists to remove.
         */
        @Volatile
        var lastEngineError: String = ""
            private set

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
                // A SUGGESTION, not a cap (Mido, 2026-10-19: *"remove the hard cab 8 words,
                // or make it a suggestion instead"*). A hard ceiling makes a small model either
                // contort the sentence or drop the very noun that matters — and a truncated
                // summary is worse than a slightly long one, because the human reads it as the
                // whole truth. The length is guided here and bounded by the RENDERER instead
                // (3 lines, ellipsized), which degrades visibly rather than silently.
                "{\"description\": \"<brief, ideally under ~8 words>\", " +
                "\"risk\": \"low|medium|high\", \"reason\": \"<a few words>\"}. " +
                // Brevity is demanded explicitly (Mido, 2026-10-19: *"make the ui summary
                // more concise"*). Left to itself this model produces wordy sentences like
                // "The user requested to read an email from bob@example.com using the Google
                // plugin" — accurate, and far too long for a 216dp card or a glance. The
                // modal renders it LARGE, so every wasted word costs real screen space.
                "Be terse: a headline, not a sentence. No filler like 'the user requested' " +
                "or 'using the Google plugin' — state the act and its object. If the exact " +
                "target needs more words, use them rather than dropping it. " +
                // THE PAYLOAD IS THE POINT (Mido, 2026-10-19: *"the payload is the whole
                // reason I added AI, so it's critical to describe it too"*). A path-like
                // summary such as "todoist: /secrets/todoist/call" says nothing about what
                // is about to happen; the human is approving the CONTENTS. The prompt asks
                // for the count and the object, and the deterministic `summary` line now
                // carries the payload shape, so the model has something concrete to name.
                // NO WORKED EXAMPLE HERE. The previous version said "say it: 'Adding 8 todos'
                // beats 'updating tasks'" and a 0.6B model PARROTED the example: every request
                // came back "Adding 8 todos", including a READ that added nothing. A concrete
                // example is a template to a small model, not an illustration (Mido,
                // 2026-10-19: *"still says adding 8 todos which is VERY misleading"*).
                // The rule is stated abstractly instead, and [SummaryGuard] enforces it.
                "Describe WHAT THE PAYLOAD DOES, not the endpoint. Name the object being " +
                "changed. Do not state a quantity unless the summary or facts contain it. " +
                "risk is low for reads, medium for edits, high for deletes or anything " +
                "sent outside the account. Never invent names or values that are not given."
    }

    /**
     * The loaded engine. GPU only — there is no CPU variant, by decision (Mido,
     * 2026-10-19): *"fail hard ... only the best most efficient performance for my phone
     * is accepted"*. A CPU fallback is a silent degradation that hides a broken GPU behind
     * acceptable-looking behaviour, which is the exact pattern this project forbids.
     *
     * Expensive to build (it maps the model file) and cheap to reuse, so it is created once
     * and cached. The *conversation* is NOT cached: it carries chat history, and each request
     * must be described on its own facts, never in the shadow of the previous one.
     */
    @Volatile
    private var engine: Engine? = null

    override fun isAvailable(ctx: Context): Boolean = modelFile.exists() && modelFile.length() > 0

    /**
     * Load the model on the GPU and PROVE it can infer.
     *
     * Blocking — call off the main thread. Idempotent, and a no-op when the file is absent:
     * whether the bridge may serve at all is [ModelRegistry.readiness]'s call.
     *
     * **The probe is the point.** A GPU engine can initialise and still fail every
     * inference — measured on device 2026-10-19, where the engine reported `engine on GPU`
     * and then died with `Can not find OpenCL library on this device`, because LiteRT-LM's
     * GPU sampler needs OpenCL and the app had not declared it. Loading is not proof;
     * running is. So warm-up runs one throwaway inference, and a failure is recorded loudly
     * here rather than discovered on a request the human is waiting on.
     */
    fun warm(ctx: Context) {
        if (!isAvailable(ctx)) return
        try {
            engine ?: createEngine().also { engine = it }
        } catch (e: Exception) {
            lastEngineError = "engine load failed: ${e.message}"
            Log.e(TAG, lastEngineError)
            return
        }
        try {
            runInference(engine!!, PROBE_FACTS)
            lastEngineError = ""
            Log.i(TAG, "warm-up: GPU proven, inference OK")
        } catch (e: Exception) {
            // Fail hard: no demotion to CPU, no retry on another backend. The GPU is the
            // only accepted path, so a GPU that cannot run is a hard failure that says why.
            lastEngineError = "GPU inference failed: ${e.message}"
            Log.e(TAG, "$lastEngineError — no CPU fallback (fail-hard)")
            release()
        }
    }

    /**
     * A throwaway request used only to prove the backend can infer.
     *
     * Deliberately tiny and static: its RESULT is discarded, so it must not depend on
     * anything, and it must not look like real traffic in the log.
     */
    private val PROBE_FACTS = RequestFacts(
        plugin = "probe",
        verb = "READ",
        routeLabel = "WARM UP",
        summary = "warm up",
        rows = emptyList()
    )

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
        return try {
            // ENGINE LOAD IS NOT INFERENCE TIME. The first request after a cold start pays the
            // whole model-map + OpenCL-init cost (~15s on the 1.7B, measured 2026-10-19), and
            // that was being charged to the caller's budget — so a perfectly good inference was
            // discarded as "too slow" purely because it was the first one. The budget exists to
            // bound the HUMAN's wait for a summary, and a cold engine is a one-off setup cost
            // that `warm()` is supposed to have already paid on service start.
            val eng = engine ?: createEngine().also { engine = it }
            val start = System.currentTimeMillis()
            val reply = runInference(eng, facts)
            val elapsed = System.currentTimeMillis() - start
            // Over budget: the caller's window is the promise. A late answer on a
            // NOTIFY card is worse than no answer, because the card may already be
            // gone and the human decided without it.
            if (elapsed > budgetMs) {
                Log.w(TAG, "inference ${elapsed}ms exceeded budget ${budgetMs}ms; discarding")
                return null
            }
            parse(reply, com.mido.phonebridge.core.PromptBudget.render(facts.rows), parseVerb(facts))?.also {
                // LOG THE TEXT, not just the risk. Every round of tuning this feature has been
                // blocked on "what did the modal actually say?" — the log recorded the risk and
                // the timing and nothing else, so the one fact that mattered had to be read off
                // the phone by a human (Mido, 2026-10-19: *"let's add the AI text to logs"*).
                //
                // Written through [Debug.log] rather than [Log.i] so it lands in BOTH the
                // on-screen ring (which `GET /debug` returns as `logTail`) and logcat (which
                // `GET /logs` returns). Redaction runs inside it, so a summariser that echoes
                // something token-shaped cannot leak it here.
                com.mido.phonebridge.Debug.log(
                    "ai[${facts.plugin}/${facts.verb}] ${elapsed}ms risk=${it.risk} " +
                        "why=\"${it.reason}\" said=\"${it.description}\""
                )
                Log.i(TAG, "summarised in ${elapsed}ms: risk=${it.risk}")
            }
        } catch (e: Exception) {
            // Fail hard, loudly, with the reason kept for /debug. No CPU fallback and no
            // retry: the GPU is the only accepted path, so a failure here is reported by
            // the caller as `ai_unavailable` rather than papered over with a slow answer.
            lastEngineError = "inference failed: ${e.message}"
            Log.e(TAG, lastEngineError)
            null
        }
    }

    /**
     * The [Verb] from the facts, which arrive as a NAME string.
     *
     * `RequestFacts.verb` is a string because the facts cross the seam without depending on the
     * policy package. An unknown name falls back to EXECUTE, which the guard treats as
     * mutating — the fail-closed direction, since an unrecognised verb must not licence a
     * "reading" claim.
     */
    private fun parseVerb(facts: RequestFacts): Verb =
        Verb.values().firstOrNull { it.name == facts.verb } ?: Verb.EXECUTE

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
            val prompt = buildPrompt(facts)
            // LOG THE PROMPT. Repeatedly I asserted "the body is passed" by reading the code,
            // and could not prove it when challenged — the exact failure mode this project
            // keeps paying for. One line makes the question answerable from `/logs` instead
            // of from confidence. Truncated to keep the ring readable; the STRUCTURE (which
            // rows exist and what leads) survives in that window.
            com.mido.phonebridge.Debug.log(
                "ai-prompt[${facts.plugin}/${facts.verb}] " +
                    prompt.replace("\n", " ⏎ ").take(600)
            )
            conversation.sendMessage(prompt).toString()
        } finally {
            conversation?.close()
        }
    }

    /**
     * Build the GPU engine. There is no other backend.
     *
     * GPU is not a preference here, it is the requirement (Mido, 2026-10-19: *"only the
     * best most efficient performance for my phone is accepted"*), so this does not catch
     * and it does not retry elsewhere — a GPU that will not initialise is a hard failure
     * whose message reaches [lastEngineError] and `/debug`.
     *
     * The one platform prerequisite is declared in the manifest, not here: LiteRT-LM's
     * GPU sampler loads the VENDOR OpenCL driver, and an app cannot dlopen `libOpenCL.so`
     * without a `<uses-native-library>` entry. Without it the engine still reports
     * `engine on GPU` and then fails every inference with `Can not find OpenCL library on
     * this device` — which is exactly what happened on device before the declaration was
     * added (LiteRT-LM issue #3867 documents the same case).
     */
    private fun createEngine(): Engine {
        Log.i(TAG, "loading ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB) on GPU")
        return buildEngine(Backend.GPU())
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
        // The cap comes from PromptBudget, NOT a flat number. A flat take(200) was applied
        // per row, so a 5.3 KB payload arrived as a 200-char prefix and the model could no
        // longer count what it was approving (Mido, 2026-10-19: *"the payload is the whole
        // reason I added AI"*). The budget divides the ONE real constraint (context window)
        // across however many rows exist, so two rows get long excerpts and eight get short
        // ones — and a clipped value is marked '…' rather than silently presented as whole.
        //
        // **CONTENT FIRST, and this is a real fix, not a tidy-up.** A 0.6B model reads the
        // first fact and largely describes THAT. With `TARGET: POST https://api.todoist.com/
        // api/v1/tasks` first, every write summarised as the URL — the body was present and
        // simply never reached (Mido, 2026-10-19: *"ai desc says POST https://api.todoist.com
        // /api/v1/tasks this is BS"*). The human needs the URL first in the MODAL; the model
        // does not. So the prompt reorders: the rows that say WHAT is changing lead, and the
        // address trails. Two audiences, two orders, one source of facts.
        val ordered = facts.rows.sortedBy { (label, _) ->
            when (label.uppercase()) {
                "PAYLOAD", "BODY", "QUERY", "SUBJECT", "BODY TEXT" -> 0
                "TARGET", "HOST", "SECRET" -> 2
                else -> 1
            }
        }
        val rows = com.mido.phonebridge.core.PromptBudget.render(ordered)
        // NO `summary:` LINE. It was a verbatim echo target: the 1.7B answered
        // `said="POST tasks"`, which is exactly this field minus the plugin prefix (measured
        // 2026-10-19). A distinctive string near the top of a prompt is the thing a small
        // model copies, so the field is gone.
        //
        // It was also REDUNDANT: the deterministic summary already lives in the audit record
        // (`todoist · POST tasks · query: …`) and `PAYLOAD` carries the same information as a
        // fact row. Asking the model to improve on a line it can only repeat was never going
        // to produce anything but a repeat.
        return """
            plugin: ${facts.plugin}
            verb: ${facts.verb}
            route: ${facts.routeLabel}
            facts:
            $rows
        """.trimIndent()
    }

    /**
     * Parse the model's reply into a [RequestSummary]. Total and strict: a reply that
     * is not the expected JSON object returns null (→ heuristic fallback) rather than
     * being coerced into a half-answer.
     */
    private fun parse(reply: String, factsText: String, verb: Verb): RequestSummary? {
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
        // LAST LINE OF DEFENCE: a small model parrots its prompt and invents quantities, and a
        // misleading approval prompt is worse than none — the human approves what they were
        // told. A rejected summary yields NO AI line rather than a corrected one; the modal
        // still renders every deterministic row (Mido, 2026-10-19: *"still says adding 8 todos
        // which is VERY misleading"*).
        if (!com.mido.phonebridge.core.SummaryGuard.allows(description, verb, factsText)) {
            // Logged through Debug too: a rejected summary is the most diagnostic event in this
            // feature — it is the model being wrong — and it previously left one logcat line
            // that `GET /logs` could not reach on this build.
            com.mido.phonebridge.Debug.log(
                "ai[REJECTED verb=$verb] said=\"${description.take(140)}\""
            )
            Log.w(TAG, "summary contradicts the request (verb=$verb): ${description.take(90)}")
            return null
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
