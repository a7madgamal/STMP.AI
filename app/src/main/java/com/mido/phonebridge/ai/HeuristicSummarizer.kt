package com.mido.phonebridge.ai

import android.content.Context

/**
 * The always-available summariser: no model, no download, no latency.
 *
 * It exists for three reasons, and none of them is "a placeholder":
 *
 *  1. **The seam must have a working implementation.** The app must never depend on
 *     a 550 MB model being present to describe a request. This is what runs when
 *     the Gemma asset is missing, still downloading, or crashed — which is the
 *     normal state on a fresh install.
 *  2. **It is the honest baseline.** A small LLM's value is measured against
 *     something; this is that something, and on many routes (a plain read) it is
 *     already the best possible answer because there is nothing to infer.
 *  3. **It is pure, so it is tested.** Risk classification is a rule; a rule that
 *     lives only inside a model call can never be asserted. See `HeuristicSummarizerTest`.
 *
 * Deliberately NOT trying to be clever prose: it states the act and its object in
 * one line. Where the model adds value is fusing rows into a sentence; where this
 * wins is never being wrong about the verb.
 */
object HeuristicSummarizer : SummaryModel {

    override val id: String = "heuristic"

    override fun isAvailable(ctx: Context): Boolean = true

    override fun summarize(ctx: Context, facts: RequestFacts, budgetMs: Long): RequestSummary =
        RequestSummary(
            description = describe(facts),
            risk = risk(facts),
            reason = reason(facts)
        )

    /**
     * One line, verb-first, naming the object when a row provides one.
     *
     * The object is picked by label preference rather than by row order, because
     * row order is a plugin's choice and "the first row" is not "the subject".
     */
    private fun describe(facts: RequestFacts): String {
        val subject = subject(facts)
        val act = act(facts)
        return if (subject == null) act else "$act: $subject"
    }

    /** The verb as a human word, never the enum name. */
    private fun act(facts: RequestFacts): String {
        val label = facts.routeLabel.ifBlank { facts.summary }.trim()
        // A route label is already a sentence ("SEND EMAIL"); do not double-wrap it.
        return label.ifBlank { "${facts.plugin} ${facts.verb.lowercase()}" }
    }

    /** The most "subject-like" row value, or null when there is nothing to name. */
    private fun subject(facts: RequestFacts): String? {
        // Preference order: what the act targets, then what it acts on.
        val preferred = listOf(
            "TO", "SUBJECT", "TITLE", "SUMMARY", "NAME", "QUERY", "PACKAGE", "URL", "HOST"
        )
        for (key in preferred) {
            val v = facts.rows.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
            if (!v.isNullOrBlank()) return v.take(120)
        }
        // Fall back to the first non-blank row so a route with unusual labels still
        // gets its most relevant fact named rather than a bare verb.
        return facts.rows.firstOrNull { it.second.isNotBlank() }?.second?.take(120)
    }

    /**
     * Risk by consequence of the VERB, then narrowed or widened by what the rows say.
     *
     * The verb is the primary signal because it is the one fact the route author
     * classified deliberately (it is a required field on `Route`), so it cannot be
     * forgotten or inferred. The row pass exists for the cases where the verb alone
     * understates: a send to an external address, a delete naming a whole thread.
     */
    private fun risk(facts: RequestFacts): Risk {
        val base = when (facts.verb.uppercase()) {
            "READ" -> Risk.LOW
            "EDIT" -> Risk.MEDIUM
            "WRITE" -> Risk.MEDIUM
            "DELETE" -> Risk.HIGH
            "EXECUTE" -> Risk.HIGH
            else -> Risk.MEDIUM // an unknown verb is not a licence to say LOW
        }
        // Widen, never narrow: a heuristic that lowers risk is a heuristic that can
        // hide something, and this is a display-only signal that must not understate.
        val external = facts.rows.any { (label, value) ->
            (label.equals("TO", true) || label.equals("CC", true) || label.equals("BCC", true)) &&
                value.contains("@")
        }
        if (external && base == Risk.MEDIUM) return Risk.HIGH
        return base
    }

    private fun reason(facts: RequestFacts): String = when (risk(facts)) {
        Risk.LOW -> "read-only: nothing on the phone or in your account changes"
        Risk.MEDIUM -> "modifies existing data"
        Risk.HIGH -> "destructive or reaches outside your account"
    }
}
