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
         * KV-cache size (input + output). **This is the dominant memory term** — see below.
         *
         * **1024 -> 8192 -> 2048, and the middle step is the one that cost real money.**
         *
         * 0.31.0 raised it to 8192 to stop truncating a ~4.9 KB Todoist body (the old window
         * cut the facts to a fraction of the payload while the code and UI both claimed the
         * body was being sent). That was correct at the time — but it was chosen while the
         * **1.7B** model was live, and 0.32.1's revert to 0.6B changed MODEL_REGISTRY only.
         * `LiteRtLm.kt` was never touched, so the small model shipped with the big window.
         *
         * The KV cache is `2 * layers * kvHeads * headDim * 2 bytes` per token. For
         * Qwen3-0.6B (28 layers, 8 KV heads, 128 head dim, fp16) that is **112 KiB/token**:
         *
         *     1024 tok -> ~112 MiB
         *     8192 tok -> ~896 MiB   <- what 0.32.1 actually shipped
         *     2048 tok -> ~224 MiB   <- this
         *
         * So the "revert to the small model" shrank the weights by ~650 MB and left the cache
         * ~800 MB larger than the 0.6B had ever run with. On a 24/7 foreground service that
         * pressure is what got Tailscale killed by the OS, which then left the bridge with no
         * tailnet address to bind. Mido, 2026-10-20: *"something was fucked up badly it's like
         * the server eats ALL memory"* — he was right, and the revert was the cause.
         *
         * **2048 holds ~1,800 tokens of facts (~4,500 chars of JSON) after the system prompt
         * and output reserve.** That covers the ordinary single-item writes this app is asked
         * to describe; a large batch body is truncated, and [PromptBudget] marks the cut so the
         * model can see it happened. Correctness of the label on a huge batch is worth less than
         * a service that stays alive.
         */
        private const val MAX_CONTEXT_TOKENS = 2048

        /**
         * The context window, readable from outside this class for `/debug`.
         *
         * A separate public name rather than making [MAX_CONTEXT_TOKENS] public: this one is
         * explicitly a REPORTING surface, so the next person can see that `/debug` depends on
         * it and that changing it changes what the device reports.
         */
        const val CONTEXT_TOKENS = MAX_CONTEXT_TOKENS

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
        // **Synchronized, because `engine ?: createEngine()` is check-then-act.** Two callers
        // arriving together (the service's warm thread plus a request's lazy load) could both
        // see null and each build a full engine — two ~700 MiB mappings for one model, which is
        // exactly the kind of duplicated cost that makes a phone feel like it is dying. The
        // lock is on `this` and `release()` below takes the same one, so a release cannot race
        // a load and leave a leaked engine behind it.
        synchronized(this) {
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
        // Same monitor as `warm()`: a release that lands mid-load would otherwise null a field
        // the loader is about to assign, leaving an engine whose reference nobody holds — a
        // leak that no later release can reach.
        synchronized(this) {
            val e = engine ?: return
            engine = null
            runCatching { e.close() }
            Log.i(TAG, "engine released")
        }
    }

    /**
     * Is the engine resident right now?
     *
     * Reported by `/debug`, where it answers a question the source cannot: whether the window a
     * build was compiled with is actually being paid for at this moment. A loaded engine holds
     * its KV cache for the life of the process; an unloaded one holds nothing.
     */
    fun isLoaded(): Boolean = engine != null

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
            // The guard checks the summary against what the model ACTUALLY saw: the raw body
            // now, not a rendered row list. A number the model states must appear in the request.
            val verb = parseVerb(facts)
            val parsed = AiReply.parse(reply, facts.rawBody, verb)
            // GUARANTEE THE DETAIL. The model paraphrases structure ("Create task with content
            // and description") no matter how the prompt is worded, and eight releases proved
            // that. So when its sentence carries none of the body's actual text, the body
            // supplies the line instead — the human always gets the specific thing, and the
            // model stays a refinement rather than the only path to a usable description.
            //
            // Mido, 2026-10-20: *"I want FUCKING DETAILS FROM THE BODY"*.
            val detailed = parsed?.let { sum ->
                val fromBody = com.mido.phonebridge.core.BodyDetail.of(verb, facts.rawBody)
                if (fromBody != null &&
                    !com.mido.phonebridge.core.BodyDetail.alreadyMentions(sum.description, facts.rawBody)
                ) {
                    // Keep the model's risk (which it is genuinely good at) and take the
                    // description from the body, which is where the detail actually lives.
                    sum.copy(description = fromBody)
                } else {
                    sum
                }
            }
            detailed?.also {
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
            systemInstruction = Contents.of(AiReply.SYSTEM),
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
            val prompt = AiReply.buildPrompt(facts)
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
}
