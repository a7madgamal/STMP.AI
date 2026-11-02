package com.mido.phonebridge.ai

import android.content.Context
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * A summariser that asks a model server on the tailnet (Ollama, unsloth).
 *
 * **Why a second backend exists.** The on-device 0.6B is a good *labeller* but a weak writer,
 * and Mido wanted the option to ask a bigger model on the LAN without giving up the phone-local
 * path. [AiBackend] is the switch; this is the EXTERNAL half of it.
 *
 * **The prompt and the parser are NOT duplicated.** Both come from [AiReply], so the two
 * backends ask the same question and accept the same answer shape. Two copies would drift the
 * first time either was tuned, and the drift would be invisible: both would still answer.
 *
 * **Secrets never reach here.** The AI step runs before the broker substitutes anything
 * (`BridgeService`: facts are built inside the approval view, the handler that injects `***`
 * runs after `ApprovalGate.await`), so a brokered request carries the literal placeholder.
 *
 * **Unreachable is a FAILURE, not a degradation** (Mido, 2026-10-31: *"fail"*). A null summary
 * already fails the request because the AI is core; adding a quiet rows-only fallback for this
 * one backend would be exactly the silent fallback the project forbids. [isAvailable] probes
 * reachability so the refusal happens BEFORE a modal is built, with an error that names the
 * backend instead of blaming a model file that this path never uses.
 */
object ExternalModel : SummaryModel {

    override val id: String = "external"

    /**
     * Where the model server lives, or EMPTY when this build was never configured.
     *
     * **There is no fallback endpoint, deliberately.** There used to be one, and it was a
     * specific machine on a specific person's tailnet — so "external AI not set up" silently
     * meant "send this request to a stranger's box". A default that points at someone else's
     * infrastructure is not a default, it is a leak with a friendly name.
     *
     * Empty is a real state and [isAvailable] reports it as unreachable, which under
     * `AiBackend.EXTERNAL` refuses the request and names the cause. The fix is a build with
     * `ai.externalUrl` set in `local.properties`, not a silent guess.
     */
    /**
     * The endpoint, resolved per call — POLICY ONLY.
     *
     * **The build-time constant is gone, deliberately.** It was
     * `stored.ifBlank { BuildConfig.EXTERNAL_AI_URL }`, which meant an empty field silently
     * reached a URL compiled into the APK. For a setting that points at a model server this is
     * the worst kind of fallback: the UI says "no server set", the toggle says EXTERNAL, and
     * requests quietly go somewhere nobody chose. Empty is now empty, and [isAvailable] reports
     * it as a refusal that names the field to fill.
     */
    private fun baseUrl(ctx: Context): String = read(ctx).aiExternalUrl

    /**
     * The model id to ask for, or "" when none has been chosen.
     *
     * Read from the policy on every call, like the URL: picking a different model in Options
     * must take effect on the next request, not the next process.
     */
    private fun model(ctx: Context): String = read(ctx).aiExternalModel

    /**
     * The stored dialect, or null when this server has never been listed.
     *
     * Null is refused rather than guessed. Every guess would be a claim about a server we have
     * not successfully spoken to, and getting it wrong is not a slow path — it is a `405` that
     * produces no summary at all, which is the bug this whole change exists to remove.
     */
    private fun dialect(ctx: Context): com.mido.phonebridge.policy.ExternalDialect? =
        read(ctx).aiExternalDialect

    /**
     * One policy read per call site.
     *
     * An unreadable policy already refuses to start the server, so treating it as "unset"
     * here cannot hide a broken file — and throwing from inside a request would turn a policy
     * problem into a 500 on a route that only wanted a sentence.
     */
    private fun read(ctx: Context): com.mido.phonebridge.policy.Policy = try {
        com.mido.phonebridge.policy.PolicyStore.from(ctx).get()
    } catch (e: Exception) {
        com.mido.phonebridge.policy.Policy(
            com.mido.phonebridge.policy.GlobalMode.AUTOMATIC
        )
    }

    /**
     * The optional bearer token, or "" when none is set.
     *
     * Sent as `Authorization: Bearer <token>` only when non-empty, so a trusted local server
     * that wants no auth keeps getting no header.
     */
    private fun authToken(ctx: Context): String = read(ctx).aiExternalToken

    /**
     * Can this backend be called at all?
     *
     * FOUR things, not one. Address, model and dialect are each independently required, and an
     * earlier version tested only the address — which is how a request went out asking for a
     * model that did not exist, down a route the server did not implement. Checking them here
     * means [isAvailable] refuses with a reason before a modal is built, instead of failing
     * deep inside a POST with an HTTP code that describes the wrong thing.
     */
    private fun configured(ctx: Context): Boolean =
        baseUrl(ctx).isNotEmpty() &&
            model(ctx).isNotEmpty() &&
            dialect(ctx) != null

    /**
     * The stored dialect, refusing loudly when there is none.
     *
     * Reached only after [configured] said yes, so a null here means the policy changed under a
     * request in flight. Throwing is right: guessing would send a request down the wrong route,
     * and the failure would arrive as an HTTP code describing the protocol instead of the
     * missing fact.
     */
    private fun requireDialect(ctx: Context): com.mido.phonebridge.policy.ExternalDialect =
        dialect(ctx) ?: throw IllegalStateException(
            "no model server protocol selected; press refresh in Options → AI"
        )

    /**
     * Pull the assistant text out of a chat reply.
     *
     * **The two dialects disagree about where it lives**, which is the second half of why one
     * request body was not enough:
     *
     *  - OpenAI: `choices[0].message.content`
     *  - Ollama: `message.content`
     *
     * Blank is an error rather than an empty summary. A reasoning model that spends its whole
     * budget thinking returns exactly that: `200`, a valid body, and no text — and "no text" must
     * not become "a summary with no words" further up.
     */
    private fun extractContent(
        dialect: com.mido.phonebridge.policy.ExternalDialect,
        text: String,
    ): String {
        val obj = JSONObject(text)
        val content = when (dialect) {
            com.mido.phonebridge.policy.ExternalDialect.OPENAI ->
                obj.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()

            com.mido.phonebridge.policy.ExternalDialect.OLLAMA ->
                obj.optJSONObject("message")?.optString("content").orEmpty()
        }
        if (content.isBlank()) {
            throw IllegalStateException("the reply had no text (finish_reason=" +
                (obj.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
                    ?: "unknown") + ")")
        }
        return content
    }

    /**
     * How long a reachability probe may take.
     *
     * Short on purpose: this runs on the request path, before a modal is posted, and the whole
     * promise of the neediness check is that a dead box is discovered quickly. A generous
     * timeout here would make every request wait for a machine that is simply off.
     */
    private const val PROBE_TIMEOUT_MS = 1_500

    /**
     * How long a reachability answer is trusted.
     *
     * The gate asks [isAvailable] at least twice per request (the `serves` check and the
     * summarise path). Without caching, one request would pay two probes and a burst of traffic
     * would hammer the box with health checks it never asked for. A few seconds is long enough
     * to cover one request and short enough that starting the server takes effect immediately
     * from a human's point of view.
     */
    private const val PROBE_TTL_MS = 3_000L

    /** When the cached probe result was taken, and what it said. */
    private val probedAtMs = AtomicLong(0L)

    @Volatile
    private var lastProbeOk = false

    /** The last transport/model error, for the refusal detail and `/debug`. */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * What the UI and the refusal detail show.
     *
     * An unconfigured build says so in words rather than rendering an empty string, because
     * "the external backend () is not reachable" tells a human nothing actionable.
     */
    fun endpointLabel(ctx: Context): String =
        baseUrl(ctx).ifEmpty { "no model server address set" }

    /** The reason this backend cannot answer right now, or null when it could. */
    fun problem(ctx: Context): String? = configureProblem(ctx)

    /**
     * What is missing before this backend could answer, or null when nothing is.
     *
     * **Each missing piece is named separately.** "Not configured" covering three different
     * faults is the same class of bug as the gate's single `Boolean`: a human reads one generic
     * sentence and cannot act. The three are genuinely different actions — type an address,
     * press refresh, or pick a model — so each gets its own words.
     *
     * Order is the order they must be fixed in: there is no point listing models before there is
     * a server to ask.
     */
    private fun configureProblem(ctx: Context): String? = when {
        baseUrl(ctx).isEmpty() -> "no model server address; set it in Options → AI"
        dialect(ctx) == null -> "the server has never been listed; press refresh in Options → AI"
        model(ctx).isEmpty() -> "no model selected; press refresh and pick one in Options → AI"
        else -> null
    }

    override fun isAvailable(ctx: Context): Boolean {
        // An unconfigured build is NOT "probably fine": it cannot reach anything, and the
        // refusal should say that instead of failing at URL parse time.
        if (!configured(ctx)) {
            // The SPECIFIC missing piece, not a generic "not configured": the refusal a caller
            // receives is this string, and it has to tell a human which control to touch.
            lastError = configureProblem(ctx) ?: "not configured"
            lastProbeOk = false
            return false
        }
        val now = System.currentTimeMillis()
        val at = probedAtMs.get()
        if (at != 0L && now - at < PROBE_TTL_MS) return lastProbeOk
        val ok = probe(ctx)
        lastProbeOk = ok
        probedAtMs.set(now)
        return ok
    }

    /**
     * A HEAD-ish reachability check against the configured host.
     *
     * Any HTTP answer counts as reachable — a 404 from a wrong path still proves something is
     * listening, and treating a non-200 as "down" would report a working box as dead. Only a
     * refused connection or a timeout is a "no".
     */
    private fun probe(ctx: Context): Boolean = try {
        val conn = (URL(baseUrl(ctx)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            // The probe must carry the SAME auth as a real call, or a healthy server that
            // requires a token answers 401 and gets reported as unreachable — the wrong
            // verdict, and one that would send a human hunting for a network fault.
            authToken(ctx).takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
            // Ollama answers 200 on /; anything else still proves a listener.
        }
        try {
            conn.responseCode
            true
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        Debug.log("ai-external: probe failed — $lastError")
        false
    }

    override fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary? {
        if (!configured(ctx)) {
            lastError = "no endpoint configured"
            return null
        }
        val body = JSONObject().apply {
            // FROM THE POLICY, not a constant. A fixed tag is how this sent "qwen3:8b" to a
            // server that never served it: Unsloth answers an unknown tag with its loaded
            // default instead of failing, so the bug was invisible until the model changed.
            put("model", model(ctx))
            put("stream", false)
            // THINKING OFF, and it is not a preference. The loaded model is a reasoning model:
            // left on, it spends the whole completion budget on reasoning_content and returns an
            // EMPTY content string with finish_reason=length. Cost measured on the real server:
            // ~10s to produce nothing, versus ~1.9s to produce the summary with thinking off.
            // A summariser has no use for chain-of-thought — it gets a handful of facts and must
            // answer in one sentence.
            //
            // Sent as a chat-template kwarg because that is what the server reads; a model that
            // does not recognise it ignores an unknown kwarg rather than failing the request.
            put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            // A hard ceiling regardless: an unbounded completion is a latency bug waiting for the
            // right input. The summaries are one to three short sentences.
            put("max_tokens", MAX_TOKENS)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().put("role", "system").put("content", AiReply.SYSTEM)
                    )
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", AiReply.buildPrompt(facts))
                    )
                }
            )
        }

        val reply = try {
            post(ctx, body, budgetMs)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Debug.log("ai-external: request failed — $lastError")
            return null
        }

        val verb = Verb.valueOf(facts.verb)
        val parsed = AiReply.parse(reply, facts.rawBody, verb) ?: run {
            lastError = "the reply was not a usable summary"
            return null
        }
        Debug.log(
            "ai-external[${facts.plugin}/${facts.verb}] risk=${parsed.risk} " +
                "said=\"${parsed.description}\""
        )
        return parsed
    }

    /**
     * POST the chat request and return the assistant's message text.
     *
     * `stream:false` so the answer is one JSON object rather than a stream of partial ones —
     * the parser expects a complete reply, and reassembling a stream would be a second protocol
     * to get wrong.
     */
    private fun post(ctx: Context, body: JSONObject, budgetMs: Long): String {
        val path = requireDialect(ctx).chatPath
        val conn = (URL(baseUrl(ctx) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = budgetMs.toInt().coerceAtMost(5_000)
            readTimeout = budgetMs.toInt().coerceAtMost(30_000)
            setRequestProperty("Content-Type", "application/json")
            // Only when a token is configured: a local server that trusts the host must not
            // receive an empty Authorization header, which some servers reject outright.
            authToken(ctx).takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(200)}")
            }
            return extractContent(requireDialect(ctx), text)
        } finally {
            conn.disconnect()
        }
    }

    fun status(ctx: Context): String {
        val up = isAvailable(ctx)
        val url = baseUrl(ctx)
        return if (up) {
            val d = dialect(ctx)
            "external — $url is reachable (model ${model(ctx)}, ${d?.label ?: "?"})"
        } else {
            "external — $url is NOT reachable" +
                (if (lastError.isBlank()) "" else " ($lastError)")
        }
    }

    /**
     * Completion ceiling for a summary.
     *
     * Generous for the job — one to three short sentences — but bounded, because the failure this
     * guards against is not truncation: it is a model that thinks forever and returns nothing.
     */
    private const val MAX_TOKENS = 400
}
