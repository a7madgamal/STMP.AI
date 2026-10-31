package com.mido.phonebridge.ai

import android.util.Log
import com.mido.phonebridge.core.Verb
import org.json.JSONObject

/**
 * The prompt, the reply parser, and the shape both backends must agree on.
 *
 * **Why this is shared rather than duplicated per backend.** The system prompt is the
 * specification of the answer, and [parse] is the check on it — the file that asks and the
 * file that validates are the same contract. Two copies (one for the on-device model, one for
 * the tailnet box) would drift the first time either was tuned, and the drift would be
 * invisible: both would still answer, and only the summaries would quietly disagree. That is
 * exactly the kind of silent divergence this project treats as a defect.
 *
 * The prompt text below is carried over verbatim from `LiteRtLm`, comment block and all,
 * because every clause in it was bought with a specific failure. Do not "tidy" it.
 */
object AiReply {

    /**
     * The system instruction. See the history inline — each paragraph is a fixed defect.
     *
     * The shape is ASKED for here and GUARANTEED by [parse]: a model that emits half an
     * object produces a null, never a half-answer.
     */
    const val SYSTEM =
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
            // NO WORKED EXAMPLE HERE. A previous version said "say it: 'Adding 8 todos'
            // beats 'updating tasks'" and a 0.6B model PARROTED the example: every request
            // came back "Adding 8 todos", including a READ that added nothing. A concrete
            // example is a template to a small model, not an illustration (Mido,
            // 2026-10-19: *"still says adding 8 todos which is VERY misleading"*).
            // The rule is stated abstractly instead, and [SummaryGuard] enforces it.
            // HARD REQUIREMENT, not a style suggestion (Mido, 2026-10-20: *"I want FUCKING
            // DETAILS FROM THE BODY"*). Saying "be specific" produced "Create task with
            // content and description" — a restatement of STRUCTURE. The description must
            // carry the caller's actual text, quoted from the body.
            "ALWAYS include the literal text from the body that is being acted on — copy it " +
            "character for character, inside quotes. If the body has a content, subject, " +
            "body, text, name, title or query field, that field's value IS the description's " +
            "object: say what is being done TO IT, e.g. Creating \"<that exact value>\". " +
            "Never describe the shape of the request (never say 'with content and " +
            "description'); describe the actual thing. " +
            "Do not state a quantity unless it appears in the body. " +
            "risk is low for reads, medium for edits, high for deletes or anything " +
            "sent outside the account. Never invent names or values that are not given."

    /**
     * The prompt: the request, VERBATIM, plus our system prompt.
     *
     * **No processing of any kind.** Mido, 2026-10-20: *"no fucking processing of any kind, the
     * full json as is + plugin name + url + our system prompt"*.
     *
     * Eight releases went into transforming this payload — truncating it, ordering its rows,
     * quoting field values, deriving a "shape" from arrays — and every transformation was a
     * judgement about what mattered. Each one produced a summary that described our view of the
     * request rather than the request. The model is the thing that should decide what matters,
     * and it can only do that if it sees the actual bytes.
     *
     * The ONLY concession is [com.mido.phonebridge.core.PromptBudget], because the context window
     * is finite and a request that overflows it fails outright (the AI is core). That is a hard
     * platform limit, not a choice about relevance — and when it bites, the truncation is marked
     * so the model can see it happened.
     *
     * Note this carries `***` where a secret belongs, never the secret: the AI runs BEFORE the
     * broker substitutes anything, which is what makes an external backend safe by construction
     * rather than by policy.
     */
    fun buildPrompt(facts: RequestFacts): String {
        val budget = com.mido.phonebridge.core.PromptBudget.MAX_TOTAL_CHARS
        val body = if (facts.rawBody.isBlank()) {
            "(no body — this is a read)"
        } else if (facts.rawBody.length > budget) {
            // Marked, never silent: half a payload presented as whole is the failure this whole
            // feature keeps paying for.
            facts.rawBody.take(budget) + "\n…[TRUNCATED — request body exceeded ${budget} chars]"
        } else {
            facts.rawBody
        }

        val where = if (facts.url.isBlank()) "" else "\nurl: ${facts.url}"
        return """
            plugin: ${facts.plugin}
            verb: ${facts.verb}
            route: ${facts.routeLabel}$where
            request body (verbatim JSON):
            $body
        """.trimIndent()
    }

    /**
     * Parse the model's reply into a [RequestSummary]. Total and strict: a reply that
     * is not the expected JSON object returns null rather than being coerced into a
     * half-answer.
     */
    fun parse(reply: String, factsText: String, verb: Verb): RequestSummary? {
        val json = extractJson(reply) ?: return null
        val obj = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            Log.w("AiReply", "reply not JSON: ${reply.take(120)}")
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
        // LAST LINE OF DEFENCE: a model parrots its prompt and invents quantities, and a
        // misleading approval prompt is worse than none — the human approves what they were
        // told. A rejected summary yields NO AI line rather than a corrected one; the modal
        // still renders every deterministic row (Mido, 2026-10-19).
        if (!com.mido.phonebridge.core.SummaryGuard.allows(description, verb, factsText)) {
            com.mido.phonebridge.Debug.log(
                "ai[REJECTED verb=$verb] said=\"${description.take(140)}\""
            )
            Log.w("AiReply", "summary contradicts the request (verb=$verb): ${description.take(90)}")
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
    fun extractJson(reply: String): String? {
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
