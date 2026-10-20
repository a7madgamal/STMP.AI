package com.mido.phonebridge.core

/**
 * How many characters of each fact row the summariser gets to see.
 *
 * **The bug this replaces:** a flat `take(200)` per row. It was written as a guard against a
 * runaway prompt (rows are caller-authored — an email body, a notification's text), and it was
 * fine for six short rows. Then a 5.3 KB Todoist payload arrived as a single `BODY` row, became
 * a 200-character prefix, and the model could no longer count what it was being asked to
 * approve. Mido, 2026-10-19: *"I expected the todolist adding one to say Adding 8 todos"*.
 *
 * **Why a flat number cannot work:** the cap has to be per-row, but the constraint is on the
 * TOTAL — the model's context window does not care how the characters are distributed. So the
 * budget is divided by the number of rows: two rows get long excerpts, eight rows get short ones,
 * and the total never exceeds what fits.
 *
 * The numbers come from the real model configuration (`ai/LiteRtLm.kt`):
 *
 *     MAX_CONTEXT_TOKENS = 1024   (KV cache: input + output)
 *     MAX_OUTPUT_TOKENS  =  128
 *     -> input budget    =  896 tok
 *        system prompt   ~  265 tok  (1,061 chars)
 *        scaffolding     ~   30 tok
 *        -> facts        ~  601 tok  ~ 2,400 chars
 *
 * [MAX_TOTAL_CHARS] takes 2,000 of that ~2,400, leaving headroom for the ~4 chars/token estimate
 * being optimistic on some text (code, non-Latin scripts and JSON tokenise worse than prose).
 *
 * Pure and Context-free so the allocation is unit-testable — the same split as `isAttended` and
 * `PayloadShape`.
 */
object PromptBudget {

    /**
     * Characters of fact rows the prompt may contain, total.
     *
     * **Raised 2,000 -> 16,000, because the old number silently cut real payloads in half**
     * (Mido, 2026-10-20: *"can you just pass it the full fucking payload? All of it?"*).
     *
     * The budget has to be derived from the CONTEXT WINDOW, not picked by feel. At
     * `MAX_CONTEXT_TOKENS = 8192` there are ~7,700 tokens for facts; JSON tokenises at roughly
     * 2.5 chars/token, so ~19,000 chars. 16,000 keeps a ~15% margin for a worse-than-average
     * estimate without ever cutting a payload a human is approving.
     *
     * The old 2,000 was chosen against a 1024-token window and then never revisited when the
     * window grew — which is exactly how a limit outlives the constraint that justified it.
     */
    const val MAX_TOTAL_CHARS = 16_000

    /**
     * Nobody gets less than this, even when there are many rows.
     *
     * Below ~120 chars a row is a fragment — a URL with no path, a subject with no noun — and a
     * fragment is worse than an omission because the model treats it as the whole fact.
     */
    const val MIN_PER_ROW = 400

    /**
     * Nobody gets more than this, however few rows there are.
     *
     * Raised 600 -> 12,000 alongside the total. The original cap existed so one huge row could
     * not starve the others — still true, but 600 was cutting a 5 KB JSON body down to its first
     * few fields, which is the very complaint that started this. 12,000 leaves room for the other
     * rows while letting a real payload through whole.
     *
     * A row is still trimmed if it alone would exceed the TOTAL; the renderer drops trailing rows
     * and says so rather than overflowing the window.
     */
    const val MAX_PER_ROW = 12_000

    /**
     * The per-row character allowance for [rowCount] rows.
     *
     * Never returns 0: an empty allowance would render rows as bare labels, which reads as
     * "this field is empty" rather than "this field was clipped".
     */
    fun capFor(rowCount: Int): Int {
        if (rowCount <= 0) return MAX_PER_ROW
        val even = MAX_TOTAL_CHARS / rowCount
        return even.coerceIn(MIN_PER_ROW, MAX_PER_ROW)
    }

    /**
     * Render [rows] for the prompt, clipped to the budget.
     *
     * A clipped value is marked with `…` so the model can tell an abbreviation from a complete
     * value. That is not cosmetic: a silent cut presents half a payload as the whole payload, and
     * the model then asserts confidently about something it did not fully see.
     *
     * **The total is enforced, and rows are dropped rather than letting the floor win.** Past ~16
     * rows the per-row floor multiplied out exceeds [MAX_TOTAL_CHARS] — a request with 30 rows
     * would silently overflow the context window, which is a failed request rather than a
     * degraded summary. So the renderer stops adding rows when the next one would break the
     * budget and says how many it left out. An honest "(+3 more facts not shown)" is worth more
     * than a prompt that quietly exceeds what the model can read.
     */
    fun render(rows: List<Pair<String, String>>): String {
        if (rows.isEmpty()) return "(none)"
        val cap = capFor(rows.size)
        val out = StringBuilder()
        var used = 0
        var shown = 0
        for ((label, value) in rows) {
            val clipped = if (value.length > cap) value.take(cap) + "…" else value
            val line = "- $label: $clipped"
            val cost = line.length + 1 // the newline that will follow
            // Always render the FIRST row even if it alone is over: a prompt with no facts at
            // all would describe nothing, and one oversized row still has to be reported.
            if (shown > 0 && used + cost > MAX_TOTAL_CHARS) break
            out.append(line).append('\n')
            used += cost
            shown++
        }
        val omitted = rows.size - shown
        if (omitted > 0) out.append("- (+$omitted more facts not shown)")
        return out.toString().trimEnd('\n')
    }
}
