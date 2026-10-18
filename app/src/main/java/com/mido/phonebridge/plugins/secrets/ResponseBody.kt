package com.mido.phonebridge.plugins.secrets

import org.json.JSONObject

/**
 * What an upstream response body actually is.
 *
 *  - [EMPTY_SUCCESS] — a 2xx with no body. A success with nothing to say.
 *  - [JSON] — a JSON object, the envelope's normal case.
 *  - [UNSUPPORTED] — a non-empty body that is not a JSON object, so the envelope
 *    cannot carry it.
 */
enum class ResponseKind { EMPTY_SUCCESS, JSON, UNSUPPORTED }

/**
 * The rule that decides what a caller is allowed to see, as a pure function.
 *
 * It lives here, apart from [SecretBroker], for the same reason [HostAllowlist] does:
 * the decision is the security-relevant part, and a decision buried inside an
 * `HttpURLConnection` call cannot be tested. The bug this fixes — a successful `close`
 * rejected as if it were a binary payload — was invisible to every test precisely
 * because the rule was entangled with the network.
 *
 * ## The rule
 *
 *  - **A 2xx with an empty body is a success.** Content-Type is IGNORED, and that is the
 *    whole point: a 204 has no body, so whatever the server puts in Content-Type
 *    describes nothing. Todoist labels its 204s `text/html`, and trusting that label is
 *    exactly what turned "task closed" into `unsupported_content_type`.
 *  - **Otherwise the BODY decides, not the header.** A body that parses as a JSON object
 *    is JSON; one that does not is refused. This keeps edge A's intent — a PDF, image or
 *    zip is still refused, because none of them parses as a JSON object — while not
 *    breaking on a server that mislabels real JSON as `text/plain`. A header can lie; a
 *    body cannot.
 *
 * Note what this deliberately does NOT do: it never base64s a binary body into the
 * envelope. Refusing is still the honest answer for a payload the envelope cannot carry.
 */
object ResponseBody {

    fun classify(status: Int, body: String): ResponseKind {
        // Empty 2xx first, before any content-type reasoning: there is nothing to judge.
        if (status in 200..299 && body.isBlank()) return ResponseKind.EMPTY_SUCCESS
        return if (parsesAsJsonObject(body)) ResponseKind.JSON else ResponseKind.UNSUPPORTED
    }

    private fun parsesAsJsonObject(body: String): Boolean = try {
        // JSONObject, not JSONArray or a scalar: the envelope carries an object, and a
        // bare `123` or `true` would otherwise count as "JSON" and then fail downstream.
        JSONObject(body)
        true
    } catch (e: Exception) {
        false
    }
}
