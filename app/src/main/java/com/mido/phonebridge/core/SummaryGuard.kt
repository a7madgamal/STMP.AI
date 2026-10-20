package com.mido.phonebridge.core

/**
 * Rejects a model summary that contradicts the request it describes.
 *
 * **Why a small model needs this.** A 0.6B model is a confident paraphraser, not a reasoner. It
 * echoed a worked example from its prompt onto every request — a READ that added nothing was
 * summarised as *"Adding 8 todos"* (Mido, 2026-10-19: *"still says adding 8 todos which is VERY
 * misleading"*). The example is gone from the prompt, but the failure mode is general: the model
 * can assert an action or a quantity that the facts do not support, and a misleading approval
 * prompt is worse than no summary at all, because the human approves what they were told.
 *
 * **The rule is deterministic, not clever.** Two checks, both lexical, both cheap:
 *
 *  1. **The verb must match the language.** A `READ` summary must not claim to mutate. This is
 *     the highest-value check because it is the one that misleads about consequence.
 *  2. **A stated quantity must exist in the facts.** If the description says "8", something in
 *     the facts must contain "8". A number the model invented is a number the human will
 *     believe.
 *
 * Rejection means **no AI line**, not a corrected one: the modal still renders every
 * deterministic row, and a blank summary section is honest where an invented one is not. This is
 * the same fail-closed choice as `parse` returning null on malformed JSON.
 *
 * Pure and Context-free so both rules are unit-testable — the split used by `isAttended`,
 * `PayloadShape`, `PromptBudget` and `OutboundVerb`.
 */
object SummaryGuard {

    /**
     * Words that claim the request CHANGES something.
     *
     * Deliberately stems, not whole words: a small model writes "adding", "adds", "added". A
     * prefix match covers all three and costs nothing, since these stems do not appear in the
     * vocabulary of a read ("reading", "listing", "fetching").
     */
    private val MUTATION_STEMS = listOf(
        "add", "creat", "delet", "remov", "updat", "writ", "writ", "modif",
        "send", "post", "edit", "trash", "clos", "moved", "moving", "chang"
    )

    /** Words that are legitimately read-shaped, so they never trip check 1. */
    private val READ_STEMS = listOf(
        "read", "list", "fetch", "get", "load", "show", "view", "search", "find", "summar"
    )

    /**
     * True when [description] is safe to show for a request whose [verb] is known.
     *
     * [facts] is the rendered fact text the model actually saw — the only ground truth it is
     * allowed to restate. An empty description is treated as unsafe, because a blank line where
     * a summary belongs reads as a rendering bug rather than "no summary".
     */
    fun allows(description: String, verb: Verb, facts: String): Boolean {
        val text = description.trim().lowercase()
        if (text.isEmpty()) return false
        if (!verbMatchesVerbLanguage(text, verb)) return false
        if (!statedCountsAreSupported(text, facts)) return false
        // NOTE: there is deliberately NO "must quote body text" rule here. An earlier version
        // had one and it was wrong twice over: it rejected accurate summaries (four tests broke),
        // and rejection means NO line at all — so the human got nothing. Guaranteeing the detail
        // is the job of [com.mido.phonebridge.core.BodyDetail], which supplies it from the body
        // when the model fails to; that way the human always gets detail, and the model stays an
        // optional refinement rather than the only path to a usable description.
        return true
    }

    /**
     * A READ must not describe a mutation.
     *
     * Checks the read vocabulary FIRST: "listing, adding nothing" would otherwise trip on
     * "adding". If any read stem is present the summary is about reading, and the mutation words
     * are describing the absence of one — which is accurate and must not be rejected.
     */
    private fun verbMatchesVerbLanguage(text: String, verb: Verb): Boolean {
        val claimsMutation = MUTATION_STEMS.any { text.contains(it) }
        if (!claimsMutation) return true
        // A mutation claim is always fine for a mutating verb.
        if (verb != Verb.READ) return true
        // For a READ, allow it only when the sentence is also clearly about reading.
        return READ_STEMS.any { text.contains(it) }
    }

    /**
     * Every number in the description must appear in the facts.
     *
     * A stated count is the most believable kind of error: "8 todos" reads as measured. The
     * check is deliberately permissive about WHERE the number appears in the facts — the point is
     * only that the model did not invent it. Single digits are skipped because they are usually
     * ordinals in prose ("one task"), not quantities worth policing.
     */
    private fun statedCountsAreSupported(text: String, facts: String): Boolean {
        val numbers = Regex("""\b(\d{2,})\b""").findAll(text).map { it.groupValues[1] }.toList()
        return numbers.all { facts.contains(it) }
    }
}
