package com.mido.phonebridge.ai

import android.content.Context
import com.mido.phonebridge.BuildConfig
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
     * The endpoint, resolved per call.
     *
     * **Policy first, then the build constant.** The policy field is what Options writes, so a
     * human can point this at a box without rebuilding. The build constant remains as a
     * compile-time default for a fixed deployment, and comes second so a runtime choice always
     * wins — the reverse order would make the setting look broken on any build that shipped one.
     */
    private fun baseUrl(ctx: Context): String {
        val stored = try {
            com.mido.phonebridge.policy.PolicyStore.from(ctx).get().aiExternalUrl
        } catch (e: Exception) {
            // An unreadable policy already refuses to start the server; treat it as unset here
            // rather than throwing from inside a request.
            ""
        }
        return stored.ifBlank { BuildConfig.EXTERNAL_AI_URL.trim() }
    }

    private fun configured(ctx: Context): Boolean = baseUrl(ctx).isNotEmpty()

    /** The Ollama chat route. Kept as a constant so the probe and the call cannot disagree. */
    private const val CHAT_PATH = "/api/chat"

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
        baseUrl(ctx).ifEmpty { "no endpoint configured (set it in Options → AI)" }

    override fun isAvailable(ctx: Context): Boolean {
        // An unconfigured build is NOT "probably fine": it cannot reach anything, and the
        // refusal should say that instead of failing at URL parse time.
        if (!configured(ctx)) {
            lastError = "no endpoint configured"
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
            put("model", MODEL)
            put("stream", false)
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
        val conn = (URL(baseUrl(ctx) + CHAT_PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = budgetMs.toInt().coerceAtMost(5_000)
            readTimeout = budgetMs.toInt().coerceAtMost(30_000)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(200)}")
            }
            val obj = JSONObject(text)
            val message = obj.optJSONObject("message") ?: JSONObject()
            val content = message.optString("content")
            if (content.isBlank()) throw IllegalStateException("empty content in reply")
            return content
        } finally {
            conn.disconnect()
        }
    }

    fun status(ctx: Context): String {
        val up = isAvailable(ctx)
        val url = baseUrl(ctx)
        return if (up) {
            "external — $url is reachable (model $MODEL)"
        } else {
            "external — $url is NOT reachable" +
                (if (lastError.isBlank()) "" else " ($lastError)")
        }
    }

    /**
     * The model tag asked for. A constant rather than a setting for now: the point of this
     * backend is "a bigger model than the phone's", and picking one removes a choice that
     * would otherwise need a free-text field to get wrong.
     */
    private const val MODEL = "qwen3:8b"
}
