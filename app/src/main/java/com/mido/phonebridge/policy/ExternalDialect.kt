package com.mido.phonebridge.policy

/**
 * Which HTTP shape the configured model server speaks.
 *
 * **Two shapes exist in the wild and they are not interchangeable.** They differ in the route
 * that lists models, the route that answers a chat, the request body, and where the answer text
 * lives in the response. Assuming one was a real bug: EXTERNAL sent `POST /api/chat` to a server
 * that only implements `/v1/chat/completions`, which answers `405 Method Not Allowed` — so every
 * worded route produced no summary at all, and nothing in the app said why.
 *
 * **Lives here, beside [AiBackend], because it is a STORED preference.** [AiBackend] answers
 * whose model does the work; this answers how to ask it. Both are read from `policy.json` and
 * both must be visible to the UI, so putting this next to the backend keeps the two decisions
 * together and avoids `policy` depending on the `ai` package.
 *
 * The dialect is DETECTED once by a model listing and then stored. It is not re-guessed per
 * request: a summarise call that probed first would pay a round trip before it could build a
 * body, and a server that is briefly down would flip the dialect under a request already decided.
 */
enum class ExternalDialect(
    /** Route that lists the models this server can serve. */
    val listPath: String,
    /** Route that answers a chat completion. */
    val chatPath: String,
    /** Said out loud in Options, so the protocol in use is visible rather than implied. */
    val label: String,
) {
    /**
     * The OpenAI-compatible shape: `GET /v1/models`, `POST /v1/chat/completions`.
     *
     * Tried FIRST when detecting, and that is not a preference for a vendor: it is the shape
     * with a published specification that both Ollama and Unsloth implement, so a server that
     * answers it can be driven without anything vendor-specific.
     */
    OPENAI("/v1/models", "/v1/chat/completions", "OpenAI-compatible"),

    /** The native Ollama shape: `GET /api/tags`, `POST /api/chat`. */
    OLLAMA("/api/tags", "/api/chat", "Ollama"),
    ;
}
