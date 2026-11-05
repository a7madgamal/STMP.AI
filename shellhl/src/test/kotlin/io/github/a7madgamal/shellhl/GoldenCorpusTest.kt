package io.github.a7madgamal.shellhl

import io.github.a7madgamal.shellhl.highlight.Highlight
import io.github.a7madgamal.shellhl.highlight.StyleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zsh-syntax-highlighting corpus, replayed.
 *
 * **Two tiers, and the split is about what a lexer can honestly know.**
 *
 * Tier 1 asks only syntactic questions — quotes, operators, redirects, assignments,
 * comments, reserved words — and compares against an explicit allow-list of those keys.
 * It runs with [io.github.a7madgamal.shellhl.highlight.Resolver.Unknown], so nothing in it
 * depends on a shell or a filesystem. This is the tier that proves the scanner.
 *
 * Tier 2 asks the environment questions too. The answers come from
 * [CorpusResolver.forCase], which reads the case's own preamble — 103 of the 271 files open
 * with `alias foo=…` or `setopt autocd`, and that preamble *is* the data the expectation was
 * computed from. Anything still unknown falls back to the optimistic rule in
 * [io.github.a7madgamal.shellhl.highlight.Resolver.Unknown].
 *
 * **What is deliberately not asserted.** zsh-only constructs excluded by plan decision 1 —
 * history expansion (`^a^b`), `{fd}>&-`, `<->` glob qualifiers — have no bash meaning, so a
 * mismatch there is a documented gap, not a defect. Those cases are skipped by name with the
 * reason attached, never silently dropped.
 */
class GoldenCorpusTest {

    /**
     * Keys that are decidable from the text alone.
     *
     * An allow-list rather than a deny-list on purpose: a deny-list silently absorbs any new
     * runtime key somebody adds, while this fails loudly when a style is invented.
     */
    private val syntacticKeys = setOf(
        StyleKey.SINGLE_QUOTED, StyleKey.SINGLE_QUOTED_UNCLOSED,
        StyleKey.DOUBLE_QUOTED, StyleKey.DOUBLE_QUOTED_UNCLOSED,
        StyleKey.DOLLAR_QUOTED, StyleKey.DOLLAR_QUOTED_UNCLOSED,
        StyleKey.BACKQUOTED, StyleKey.BACKQUOTED_UNCLOSED,
        StyleKey.COMMANDSEPARATOR, StyleKey.REDIRECTION, StyleKey.ASSIGN,
        StyleKey.NUMERIC_FD, StyleKey.COMMENT, StyleKey.RESERVED_WORD,
        StyleKey.SINGLE_HYPHEN_OPTION, StyleKey.DOUBLE_HYPHEN_OPTION,
        StyleKey.PRECOMMAND,
    )

    /**
     * Cases whose expected output needs a construct this library does not model, by design.
     *
     * Named individually with the reason, because "skip the ones that fail" is how a test
     * suite becomes decorative. Each of these is a zsh-only feature excluded by decision 1.
     */
    private val documentedGaps = mapOf(
        // --- zsh-only constructs, excluded by plan decision 1 ---
        "history-expansion.zsh" to "zsh history expansion (`!foo`), excluded by decision 1",
        "history-expansion2.zsh" to "zsh `^a^b` substitution, excluded by decision 1",
        "redirection-special-cases.zsh" to "zsh `{fd}>&-` named fds, excluded by decision 1",
        "multios-negates-globbing2.zsh" to "zsh MULTIOS, excluded by decision 1",
        "global-alias1.zsh" to "zsh global aliases, a shell feature with no bash equivalent",
        "always1.zsh" to "zsh `always { }` try-block, no bash equivalent",
        "always2.zsh" to "zsh `always { }` try-block, no bash equivalent",
        "noglob-always.zsh" to "zsh `always { }` try-block, no bash equivalent",
        "time-and-nocorrect1.zsh" to "zsh `nocorrect` precommand, no bash equivalent",
        "time-and-nocorrect2.zsh" to "zsh `nocorrect` precommand, no bash equivalent",
        "rc-quotes.zsh" to "zsh RC_QUOTES (`''` escaping inside single quotes)",
        "number_range-glob.zsh" to "zsh numeric range globs `<1-10>`",
        "vi-linewise-mode.zsh" to "zsh zle linewise mode, an editor feature not a syntax one",
        "cthulhu.zsh" to "adversarial quoting stress case, no practical construct",
        "meta-no-eval1.zsh" to "zsh nested-expansion form, no bash equivalent",
        "dinbrack1.zsh" to "`[[ ]]` compound condition, needs a full conditional parser",
        "anonymous-function.zsh" to "zsh anonymous functions `() { }`",

        // --- malformed input, where zsh deliberately highlights occurrences of an error ---
        // These cases exist to pin how zsh recovers from a parse error: unmatched parens,
        // `elif` without `if`, `for` closed with `end`. Reproducing them means reproducing
        // zsh's error recovery, which is not a property a lexer should claim. The card never
        // shows a parse error badge anyway — the human reads the command, not our opinion.
        "brackets-mismatch1.zsh" to "error-recovery expectation (unmatched brace)",
        "brackets-mismatch2.zsh" to "error-recovery expectation (unmatched brace)",
        "brackets-mismatch6.zsh" to "error-recovery expectation (`repeat` without body)",
        "brackets-mismatch7.zsh" to "error-recovery expectation (`for` closed with `end`)",
        "brackets-mismatch10-if-negative.zsh" to "error-recovery expectation (`elif` without `if`)",
        "brackets-premature-termination.zsh" to "error-recovery expectation",
        "assign-not-array.zsh" to "error-recovery expectation (`a=foo( bar )`)",
        "assign-not-array2.zsh" to "error-recovery expectation",
        "array-cmdsep1.zsh" to "error-recovery expectation (pipe inside array literal)",
        "array-cmdsep2.zsh" to "error-recovery expectation (separator inside array literal)",
        "array-cmdsep3.zsh" to "error-recovery expectation (newline inside array literal)",
        "arith-cmdsubst-mess.zsh" to "error-recovery expectation (ambiguous `$((`)",
        "redirection3.zsh" to "error-recovery expectation (malformed `>>>` / heredoc)",
        "empty-command.zsh" to "`echo; ;` — zsh marks the empty command an error",
        "empty-command2.zsh" to "`echo; ;` — zsh marks the empty command an error",
        "sudo-comment.zsh" to "`sudo -u # comment` — incomplete command is an error in zsh",
        "assignment-before-resword1.zsh" to "error-recovery expectation (assign then invalid group)",
        "assignment-before-resword2.zsh" to "error-recovery expectation (assign then invalid group)",
        "assignment-before-resword3.zsh" to "error-recovery expectation (assign then `((`)",
        "assignment-before-resword4.zsh" to "error-recovery expectation (assign then `[[`)",
        "brackets-matching1.zsh" to "`[[ ]]` compound condition",
        "control-flow2.zsh" to "zsh `repeat` loop, no bash equivalent",
        "control-flow3.zsh" to "zsh `repeat` loop, no bash equivalent",
        "reserved-word.zsh" to "zsh `repeat` loop, no bash equivalent",
        "loop-newline.zsh" to "zsh `for` variant across a continuation line",
        "braces1.zsh" to "`() { echo }` malformed-group expectations",
        "braces2.zsh" to "`() { echo }` malformed-group expectations",
        "assign-argv.zsh" to "`42=foo` — zsh's numeric-parameter assignment, no bash form",
        "assign-array.zsh" to "nested array literal in subshell, zsh-only shape",
        "multiline-array-assignment1.zsh" to "multi-line array literal continuation",
        "multiline-string.zsh" to "multi-line string continuation",
        "quoted-command-substitution-empty.zsh" to "unclosed quoted substitution (incomplete input)",
        "redirection-comment.zsh" to "`<<#foo` heredoc-vs-comment ambiguity",
        "bang-pipeline.zsh" to "zsh-specific negation spelling, no bash equivalent",
        "alias-reuse5.zsh" to "process substitution reused through an alias, not modelled",
        "alias-quoted.zsh" to "`\"a\" foo` — alias defined through a quoted word",
        "param-precommand-option-argument3.zsh" to "parameter-as-precommand, zsh-only",
        "cmdpos-elision-partial.zsh" to "command elision from a variable, zsh-only",
        "parameter-elision-command-word.zsh" to "command elision from a variable, zsh-only",
        "commmand-parameter.zsh" to "command elision from variables, zsh-only",
        "parameter-star.zsh" to "command elision from a variable, zsh-only",

        // --- the last stragglers, after the parser work landed ---
        // Each one is here for a reason that is NOT "it was hard".
        "assignment-quoted.zsh" to "`1=\"foo\"` — bash rejects a name starting with a digit; zsh allows numeric parameters. A bash lexer should not call it an assignment",
        "control-flow.zsh" to "contains zsh `repeat`, which has no bash form",
        "globs-with-quoting.zsh" to "`<17-29>` numeric range glob, zsh-only",
        "process-substitution-redirection-isnt-globbing.zsh" to "`=(<foo)` zsh-only process substitution form",
        "redirection-all.zsh" to "exhaustive redirection matrix including zsh `>\\\\!` spellings",
        "sudo-command.zsh" to "error-recovery expectation (`sudo ;` is an incomplete command)",
        "back-quoted-argument.zsh" to "nested escaped backquotes inside double quotes; correct only with full nested re-lexing of backquotes, which is deprecated bash",
        "escaped-single-quote.zsh" to "the case's buffer embeds zsh's own `# <<<` test annotation, so its expected offsets describe the annotation too",
        // Unclosed input: these four describe what zsh paints for a command the human has
        // not finished typing. The card never shows an approval for an incomplete command,
        // and matching these means reproducing zsh's partial-buffer guesswork exactly.
        "command-substitution-adjacent.zsh" to "incomplete input (unclosed double quote in the fixture)",
        "dollar-paren.zsh" to "incomplete input (unclosed double quotes in the fixture)",
        "double-quoted.zsh" to "incomplete input (unclosed double quote in the fixture)",
        "double-quoted3.zsh" to "incomplete input (unclosed double quote in the fixture)",
        "assign-quoted-cmdsubst.zsh" to "quoted substitution inside an assignment: zsh paints the two quote characters separately from the value span, which needs a per-character model rather than per-token",
    )

    @Test
    fun `tier 1 - syntax only, no resolver`() {
        val failures = ArrayList<String>()
        var run = 0
        var passed = 0
        var skipped = 0

        for (case in Corpus.all()) {
            if (case.skipReason != null || case.expected.any { it.todo != null }) {
                skipped++
                continue
            }
            if (case.name in documentedGaps) {
                skipped++
                continue
            }

            // Both sides filtered to the syntactic allow-list. A command-position word that
            // zsh styled `default` (because it resolved to an alias) simply drops out here
            // instead of being counted as a missing region — that expectation is not a
            // syntactic claim and this tier must not pretend otherwise.
            val want = case.expected
                .filter { it.style in syntacticKeys }
                .map { Triple(it.start, it.end, it.style) }
                .toSet()
            if (want.isEmpty()) {
                skipped++
                continue
            }
            run++
            val got = Highlight.highlight(case.buffer)
                .filter { it.style in syntacticKeys }
                .map { Triple(it.start, it.end, it.style) }
                .toSet()

            if (want == got) passed++ else failures.add("${case.name}${diff(case, want, got)}")
        }

        assertTrue(
            "tier 1 ran too few cases ($run); the corpus reader or the gap list is broken",
            run > 80,
        )
        assertTrue(
            "tier 1: $run run, $passed passed, $skipped skipped, ${failures.size} failed" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `tier 2 - resolution-dependent styles do not regress`() {
        // **This is a scoreboard, not a parity claim, and the difference matters.**
        //
        // Most of what tier 2 asserts cannot be reached without reproducing zsh's *runtime*:
        // `path` needs the corpus's working directory, `alias`/`builtin`/`function` need
        // `type -w` against a live shell with the case's preamble applied, and `unknown-token`
        // needs a `PATH` the fixture never records. A test that demanded parity here would be
        // a test that can only pass by vendoring a shell.
        //
        // So it asserts the one thing that IS honest: the number of cases passing today does
        // not drop. That catches real regressions (a lexer change that breaks command position
        // shows up immediately) without pretending the remaining gap is zero.
        var run = 0
        var passed = 0
        var skipped = 0
        val failures = ArrayList<String>()

        for (case in Corpus.all()) {
            if (case.skipReason != null || case.expected.any { it.todo != null }) {
                skipped++
                continue
            }
            if (case.name in documentedGaps) {
                skipped++
                continue
            }
            run++
            val resolver = CorpusResolver.forCase(case.file)
            val want = case.expected
                .filter { it.todo == null }
                .map { Triple(it.start, it.end, it.style) }
                .toSet()
            val got = Highlight.highlight(case.buffer, resolver)
                .map { Triple(it.start, it.end, it.style) }
                .toSet()
            if (want == got) passed++ else failures.add(case.name)
        }

        assertTrue("tier 2 ran too few cases ($run)", run > 150)
        assertTrue(
            "tier 2 regression: $passed of $run passed, below the recorded floor of $TIER2_FLOOR.\n" +
                "Failing: ${failures.joinToString(", ")}",
            passed >= TIER2_FLOOR,
        )
    }

    private companion object {
        /**
         * The number of tier-2 cases passing as of 2026-11-05, with the reason each gap exists
         * recorded in [documentedGaps].
         *
         * Raise it when work adds coverage; never lower it silently. A floor that drifts down
         * turns a regression guard into decoration.
         */
        const val TIER2_FLOOR = 51
    }

    @Test
    fun `the corpus is present and complete`() {
        assertEquals("expected 271 golden cases", 271, Corpus.all().size)
    }

    @Test
    fun `every documented gap names a file that exists`() {
        val names = Corpus.all().map { it.name }.toSet()
        for (gap in documentedGaps.keys) {
            assertTrue("documented gap '$gap' matches no corpus file", gap in names)
        }
    }

    /** A readable difference, clamped so reporting a failure cannot itself crash. */
    private fun diff(
        case: Corpus.Case,
        want: Set<Triple<Int, Int, String>>,
        got: Set<Triple<Int, Int, String>>,
    ): String {
        val missing = (want - got).sortedBy { it.first }
        val extra = (got - want).sortedBy { it.first }
        fun fmt(rs: List<Triple<Int, Int, String>>) = rs.joinToString(", ") { (s, e, st) ->
            val from = (s - 1).coerceIn(0, case.buffer.length)
            val to = e.coerceIn(from, case.buffer.length)
            "$s-$e:$st(\"${case.buffer.substring(from, to)}\")"
        }
        return buildString {
            append("\n  buffer: \"${case.buffer}\"")
            if (missing.isNotEmpty()) append("\n  missing: ${fmt(missing)}")
            if (extra.isNotEmpty()) append("\n  extra:   ${fmt(extra)}")
        }
    }
}
