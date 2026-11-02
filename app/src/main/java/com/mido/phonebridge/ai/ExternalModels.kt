package com.mido.phonebridge.ai

import com.mido.phonebridge.policy.ExternalDialect
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which HTTP shape a model server speaks.
 *
 * **Two shapes exist in the wild and they are not interchangeable.** They differ in the route
 * that lists models, the route that answers a chat, the request body, and where the answer text
 * lives in the response. Guessing one shape and hoping is what made EXTERNAL send `POST
 * /api/chat` to a server that only implements `/v1/chat/completions` — which answers `405 Method
 * Not Allowed`, so every worded route silently produced no summary at all.
 *
 * The dialect is DETECTED by [ExternalModels.fetch] and then STORED in the policy. It is not
 * re-guessed per request: a summarise call that has to probe first would pay a round trip before
 * it could even build a body, and a server that is briefly down would flip the dialect under a
 * request that was already decided.
 */
/**
 * What a server said it can serve.
 *
 * [models] is the list a human picks from — never a default, because a model name this build
 * invented is exactly the hidden fallback this project forbids. Empty is impossible: [fetch]
 * treats "answers 200 but lists nothing" as a failure, since a server with no models cannot
 * answer anything.
 */
data class ModelCatalogue(
    val dialect: ExternalDialect,
    val models: List<String>,
)

/**
 * Discovers which models a configured server offers, and which dialect it speaks.
 *
 * **A plain object with pure parsers, not an Android thing.** The parsing is where this can be
 * wrong (a server renames a field and the list silently comes back empty), so it is separated
 * from the transport and covered by JVM tests. `android.util.Log` never runs in the test JVM,
 * which is why nothing here logs.
 */
object ExternalModels {

    /** How long one listing call may take. Listing is a UI action, not a request path. */
    private const val LIST_TIMEOUT_MS = 8_000

    /**
     * Ask [url] which models it has.
     *
     * Both dialects are attempted and the winner is reported with the list, so the caller can
     * STORE the dialect. Throws when NEITHER answers, and the message names every attempt and
     * its actual error — "not reachable" alone sends a human to check a network that is fine,
     * which is the mistake this project has already made three times.
     *
     * A dialect that answers `200` with no models counts as a failure for that dialect, not as
     * an empty list: the user cannot pick nothing, and an empty selector would look like a
     * broken server rather than a wrong one.
     */
    fun fetch(url: String, token: String, budgetMs: Long = LIST_TIMEOUT_MS.toLong()): ModelCatalogue {
        val timeout = budgetMs.toInt()
        val failures = ArrayList<String>(2)
        for (dialect in ExternalDialect.values()) {
            try {
                val text = get(url + dialect.listPath, token, timeout)
                val models = parse(dialect, text)
                if (models.isEmpty()) {
                    failures.add("${dialect.label}: answered but listed no models")
                    continue
                }
                return ModelCatalogue(dialect, models)
            } catch (e: Exception) {
                failures.add("${dialect.label}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        throw IllegalStateException(
            "no usable model list from $url — " + failures.joinToString("; ")
        )
    }

    /**
     * Read the model ids out of a listing response.
     *
     * Throws on a shape it does not recognise rather than returning an empty list, because those
     * two outcomes need different words to a human: an empty list means "this server has
     * nothing", and a bad shape means "this server is not the thing you pointed at".
     */
    fun parse(dialect: ExternalDialect, text: String): List<String> = try {
        when (dialect) {
            ExternalDialect.OPENAI -> parseOpenAi(text)
            ExternalDialect.OLLAMA -> parseOllama(text)
        }
    } catch (e: Exception) {
        throw IllegalStateException(
            "${dialect.label} listing was not readable (${e.message ?: e.javaClass.simpleName})",
            e
        )
    }

    /** `{"data":[{"id":"..."}]}` */
    private fun parseOpenAi(text: String): List<String> {
        val data = JSONObject(text).optJSONArray("data")
            ?: throw IllegalStateException("no 'data' array")
        return ids(data, "id")
    }

    /** `{"models":[{"name":"..."}]}` — `model` is the older key of the same field. */
    private fun parseOllama(text: String): List<String> {
        val models = JSONObject(text).optJSONArray("models")
            ?: throw IllegalStateException("no 'models' array")
        val out = ArrayList<String>(models.length())
        for (i in 0 until models.length()) {
            val o = models.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("model") }.trim()
            if (name.isNotEmpty()) out.add(name)
        }
        return out.distinct().sorted()
    }

    private fun ids(arr: JSONArray, key: String): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString(key).trim()
            if (id.isNotEmpty()) out.add(id)
        }
        return out.distinct().sorted()
    }

    /**
     * GET [path] with the same auth a chat call would send.
     *
     * The token goes on the LISTING too: a server that requires a Bearer key answers `401` to an
     * unauthenticated listing, and reporting "cannot list models" for a server that is working
     * fine would send someone to debug the wrong thing.
     */
    private fun get(url: String, token: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            token.takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
