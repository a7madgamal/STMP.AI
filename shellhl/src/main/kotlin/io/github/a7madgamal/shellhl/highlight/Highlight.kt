package io.github.a7madgamal.shellhl.highlight

import io.github.a7madgamal.shellhl.lexer.Lexer
import io.github.a7madgamal.shellhl.lexer.Token
import io.github.a7madgamal.shellhl.lexer.TokenKind

/**
 * Tokens → colored regions, in zsh-syntax-highlighting's vocabulary and format.
 *
 * **The model, in one sentence:** a token is not a region. zsh paints *overlapping*
 * spans — a composite word gets `default` across the whole thing, and each quoted run
 * or glob character inside it gets its own span on top. A one-token-one-region mapping
 * cannot express that, which is why [regionsOf] returns a flat list built from several
 * passes rather than a transform of the token list.
 *
 * This is also the only place offsets change convention: tokens are half-open 0-indexed,
 * regions are inclusive 1-indexed. Doing it anywhere else would guarantee an off-by-one,
 * and an off-by-one here is silent — every span shifts and the output still looks fine.
 */
object Highlight {

    /**
     * Highlight [command].
     *
     * @param resolver supplies the facts a lexer cannot know (is this a builtin? does this
     *   path exist?). Defaults to [Resolver.Unknown], which reports nothing as missing —
     *   see that object for why optimism is the right default on a phone.
     */
    fun highlight(
        command: String,
        resolver: Resolver = Resolver.Unknown,
    ): List<Region> = regionsOf(Lexer.tokenize(command), resolver)

    /** The same mapping over an already-tokenized command, so callers can reuse a pass. */
    fun regionsOf(tokens: List<Token>, resolver: Resolver = Resolver.Unknown): List<Region> {
        val out = ArrayList<Region>(tokens.size)
        var i = 0
        // Set after a REDIRECT token: the next word is a *target*, not an option. `> -x`
        // makes `-x` ordinary text (`redirection-is-not-option.zsh`), and painting it as a
        // flag would claim the command takes a `-x` it was never given.
        var afterRedirect = false

        while (i < tokens.size) {
            val t = tokens[i]

            // Whitespace between a redirect and its target must not clear the pending
            // flag: `> -x` has a space, and `-x` is still the target (`default`), not an
            // option. Clearing on the whitespace token was why that case still failed.
            if (t.kind == TokenKind.WHITESPACE) {
                i++
                continue
            }

            // **An assignment whose value is a substitution is ONE span.** `s=$(p foo)` and
            // `x="$(ls)"` are painted `assign` across the whole word by zsh, with the value
            // re-styled inside. The lexer emits `s=` and `$(…)` as two tokens because they
            // are genuinely different things; merging them here is the highlight model's job,
            // not the scanner's.
            if (t.kind == TokenKind.ASSIGN && t.text.endsWith("=") &&
                i + 1 < tokens.size && tokens[i + 1].isWordPart()
            ) {
                val next = tokens[i + 1]
                out.add(Region(t.start + 1, next.end, StyleKey.ASSIGN))
                out.addAll(regionsForPart(next, resolver))
                i += 2
                continue
            }

            if (!t.isWordPart()) {
                out.addAll(regionsForPart(t, resolver))
                afterRedirect = t.kind == TokenKind.REDIRECT
                i++
                continue
            }
            // A shell WORD can be several tokens: `"foo"*'bar'?` is five. zsh paints an
            // outer span across the whole word and then each piece on top, so the pieces
            // are grouped before they are coloured.
            var j = i
            while (j < tokens.size && tokens[j].isWordPart()) j++
            val group = tokens.subList(i, j)

            if (group.size > 1) {
                // The outer span's style depends on what the word IS. `--user="x"` is an
                // option wrapping a quote — the corpus paints the whole thing
                // `double-hyphen-option` (`option-with-quotes.zsh`), not `default`. A plain
                // composite like `"foo"*'bar'` gets `default`.
                val outer = when {
                    afterRedirect -> StyleKey.DEFAULT
                    else -> optionStyle(group.first().text)
                }
                out.add(Region(group.first().start + 1, group.last().end, outer))

                // **The option prefix is not separately painted.** For `--user="x"` zsh
                // emits the whole word as one `double-hyphen-option` span and then the
                // quoted run; it does NOT also paint `--user=` on its own. Emitting that
                // sub-span was the "extra" region in `option-with-quotes.zsh` and
                // `process-substitution.zsh`.
                val outerIsOption = outer == StyleKey.DOUBLE_HYPHEN_OPTION ||
                    outer == StyleKey.SINGLE_HYPHEN_OPTION
                val startAt = if (outerIsOption) 1 else 0
                for (k in startAt until group.size) {
                    out.addAll(regionsForPart(group[k], resolver, isRedirectTarget = afterRedirect))
                }
                afterRedirect = false
                i = j
                continue
            }

            // A single-token word: no outer span to layer, just its own regions.
            out.addAll(regionsForPart(t, resolver, isRedirectTarget = afterRedirect))
            afterRedirect = false
            i++
        }
        return out
    }

    /** Kinds that can sit inside one shell word, adjacent with no separator. */
    private fun Token.isWordPart(): Boolean = when (kind) {
        TokenKind.COMMAND_WORD, TokenKind.WORD,
        TokenKind.SINGLE_QUOTED, TokenKind.DOUBLE_QUOTED,
        TokenKind.DOLLAR_QUOTED, TokenKind.BACKQUOTED,
        // An expansion glues to its neighbours: `--foo=$(cmd)` and `x=$(cmd)` are ONE
        // shell word, and zsh paints the outer option/assign span across the whole thing.
        TokenKind.CMD_SUBST, TokenKind.ARITH, TokenKind.PROC_SUBST,
        -> true
        else -> false
    }

    /**
     * Regions for one token. **Plural, because zsh regions overlap.**
     *
     * The empty list means "do not paint" (whitespace, newline).
     */
    private fun regionsForPart(
        token: Token,
        resolver: Resolver,
        isRedirectTarget: Boolean = false,
    ): List<Region> {
        // Whitespace and newlines are tokens for coverage, never for colour.
        if (token.kind == TokenKind.WHITESPACE) return emptyList()

        val base = styleOf(token, resolver, isRedirectTarget)

        // Unclosed quotes get their own key: zsh distinguishes them, and that is the most
        // useful signal on a half-typed command — you see the unterminated string before
        // you press enter.
        val style = if (isUnclosed(token)) unclosedKeyFor(token.kind) ?: base else base
        val primary = Region(token.start + 1, token.end, style)
        val out = ArrayList<Region>(4)
        out.add(primary)

        when (token.kind) {
            // A bare word containing unescaped glob characters gets `globbing` over each
            // run, on top of the word's own span. `qux\?` is NOT a glob — the backslash
            // makes it a literal, and the corpus asserts that.
            TokenKind.WORD -> out.addAll(globRegions(token))

            // `"…"` recurses: a command substitution inside it is a whole command that
            // must be highlighted as such, and zsh emits the delimiters separately.
            TokenKind.DOUBLE_QUOTED -> out.addAll(substitutionRegions(token, quoted = true, resolver))
            TokenKind.BACKQUOTED -> out.addAll(substitutionRegions(token, quoted = false, resolver))

            // An assignment is TWO spans: `assign` over the whole word, and a second over
            // just the value. `A=1` is `assign` 1-3 and `default` 3-3.
            TokenKind.ASSIGN -> out.addAll(assignValueRegions(token, resolver))

            // The array parens are assignments AND reserved words on the same character.
            TokenKind.ARRAY_CLOSE -> out.add(Region(token.start + 1, token.end, StyleKey.RESERVED_WORD))

            // Expansions carry their own body. zsh emits the whole span, the delimiters, and
            // the inner command's regions — three layers on one token, which is why this
            // returns a list rather than a style.
            TokenKind.CMD_SUBST -> out.addAll(expansionRegions(token, resolver))

            TokenKind.PROC_SUBST -> out.addAll(expansionRegions(token, resolver))

            else -> Unit
        }

        if (token.kind == TokenKind.ASSIGN && token.text.endsWith("(")) {
            out.add(Region(token.end, token.end, StyleKey.RESERVED_WORD))
        }

        return out
    }

    // --- assignments -------------------------------------------------------

    /**
     * The value half of `NAME=value`.
     *
     * `s="foo"` is `assign` over the whole and `double-quoted-argument` over the quotes;
     * `A=1` is `assign` over the whole and `default` over the `1`. When the value is
     * itself a nested command substitution the recursion handles it, so the value span is
     * only emitted for the simple forms.
     */
    private fun assignValueRegions(token: Token, resolver: Resolver): List<Region> {
        if (token.text.endsWith("(")) return emptyList()
        val valueStart = token.text.indexOf('=') + 1
        if (valueStart <= 0 || valueStart >= token.text.length) return emptyList()

        val value = token.text.substring(valueStart)
        val style = when {
            value.length >= 3 && value.startsWith("$'") && value.endsWith("'") -> StyleKey.DOLLAR_QUOTED
            value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") -> StyleKey.DOUBLE_QUOTED
            value.length >= 2 && value.startsWith("'") && value.endsWith("'") -> StyleKey.SINGLE_QUOTED
            else -> StyleKey.DEFAULT
        }
        return listOf(Region(token.start + valueStart + 1, token.end, style))
    }

    // --- globbing ----------------------------------------------------------

    /**
     * `globbing` spans inside a bare word.
     *
     * Only `*`, `?` and `[…]` that are NOT backslash-escaped. An escaped character is a
     * literal to the shell and must stay `default` — `qux\?` is a filename, not a
     * single-character glob.
     */
    private fun globRegions(token: Token): List<Region> {
        val text = token.text
        val out = ArrayList<Region>(2)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2 // escaped: skip the literal
                continue
            }
            val isGlob = c == '*' || c == '?' || c == '['
            if (!isGlob) {
                i++
                continue
            }
            val start = i
            if (c == '[') {
                // A bracket expression runs to its `]`; if there is none it is a literal.
                val close = text.indexOf(']', i + 1)
                if (close < 0) { i++; continue }
                i = close + 1
            } else {
                // Consecutive glob characters are one run: `**` is a single span.
                while (i < text.length && (text[i] == '*' || text[i] == '?')) i++
            }
            out.add(Region(token.start + start + 1, token.start + i, StyleKey.GLOBBING))
        }
        return out
    }

    // --- command substitution ---------------------------------------------

    /**
     * `$(…)` and `` `…` `` inside a quoted or backquoted token.
     *
     * The inner command is a real command, so it is lexed and highlighted recursively and
     * its regions are shifted into place. zsh marks the delimiters separately from the
     * body, which is why the delimiter spans are emitted first.
     */
    private fun substitutionRegions(
        token: Token,
        quoted: Boolean,
        resolver: Resolver,
    ): List<Region> {
        val text = token.text
        val out = ArrayList<Region>(6)
        var i = 0
        while (i < text.length - 1) {
            // `$(…)`, but NOT `$((…))` — arithmetic expansion has its own key and its own
            // closing parens, and treating it as a command would lex numbers as programs.
            if (text[i] == '$' && text[i + 1] == '(' && !(i + 2 < text.length && text[i + 2] == '(')) {
                val close = matchingParen(text, i + 1)
                if (close > 0) {
                    out.addAll(emitSubstitution(token, i, i + 1, close, quoted, resolver))
                    i = close + 1
                    continue
                }
                i += 2
                continue
            }
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > 0) {
                    out.addAll(emitSubstitution(token, i, i, close, quoted, resolver))
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * `$( … )` and `<( … )` / `>( … )` as whole tokens.
     *
     * The lexer already balanced these into a single token (see `TokenKind.CMD_SUBST`), so
     * this only has to peel off the delimiters and recurse into the body. zsh emits three
     * layers per expansion — the whole span, the delimiters, then the inner command — which
     * is why the outer span is emitted by [regionsForPart] and the rest here.
     */
    private fun expansionRegions(token: Token, resolver: Resolver): List<Region> {
        val text = token.text
        if (text.length < 3) return emptyList()

        val processSubst = token.kind == TokenKind.PROC_SUBST
        val wholeStyle = if (processSubst) StyleKey.PROCESS_SUBSTITUTION else StyleKey.CMD_SUBST_UNQUOTED
        val delimStyle = if (processSubst) StyleKey.PROCESS_SUBSTITUTION_DELIM else StyleKey.CMD_SUBST_DELIM_UNQUOTED

        val out = ArrayList<Region>(6)
        // Both forms open with two characters: `$(` or `<(` / `>(`.
        val openLen = 2
        val closeAt = text.length - 1
        if (text[closeAt] != ')') return emptyList()

        // The outer span, re-stated with the right key (the primary used a fallback).
        out.add(Region(token.start + 1, token.end, wholeStyle))
        out.add(Region(token.start + 1, token.start + openLen, delimStyle))
        out.add(Region(token.start + closeAt + 1, token.start + closeAt + 1, delimStyle))

        // The body is a real command, so it is lexed and highlighted, then shifted. Doing it
        // by offset arithmetic on the substring is why the recursion is safe.
        val body = text.substring(openLen, closeAt)
        if (body.isNotBlank()) {
            for (r in regionsOf(Lexer.tokenize(body), resolver)) {
                out.add(Region(r.start + token.start + openLen, r.end + token.start + openLen, r.style))
            }
        }
        return out
    }

    private fun emitSubstitution(
        token: Token,
        outerStart: Int,
        delimOpenEnd: Int,
        close: Int,
        quoted: Boolean,
        resolver: Resolver,
    ): List<Region> {
        val suffix = if (quoted) "-quoted" else ""
        val out = ArrayList<Region>(4)
        // The whole substitution, delimiters included.
        out.add(
            Region(
                token.start + outerStart + 1,
                token.start + close + 1,
                if (quoted) "command-substitution-quoted" else "command-substitution-unquoted",
            )
        )
        // Delimiters: `$(` … `)`, or a single backtick on each side.
        val openEnd = if (delimOpenEnd > outerStart) delimOpenEnd + 1 else outerStart + 1
        out.add(
            Region(
                token.start + outerStart + 1,
                token.start + openEnd,
                "command-substitution-delimiter$suffix",
            )
        )
        out.add(
            Region(
                token.start + close + 1,
                token.start + close + 1,
                "command-substitution-delimiter$suffix",
            )
        )
        // The body, recursively. Shifting is the whole reason this is done on the
        // substring rather than by hand.
        val innerStart = openEnd
        val body = token.text.substring(innerStart, close)
        if (body.isNotBlank()) {
            for (r in regionsOf(Lexer.tokenize(body), resolver)) {
                out.add(Region(r.start + token.start + innerStart, r.end + token.start + innerStart, r.style))
            }
        }
        return out
    }

    /** Index of the `)` matching the `(` at [open], or -1. Respects nesting and quotes. */
    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\\' -> { i += 2; continue }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    // --- per-kind styling --------------------------------------------------

    private fun isUnclosed(token: Token): Boolean = when (token.kind) {
        TokenKind.SINGLE_QUOTED -> !token.text.endsWith("'") || token.text.length == 1
        TokenKind.DOUBLE_QUOTED -> !token.text.endsWith("\"") || token.text.length == 1
        TokenKind.DOLLAR_QUOTED -> !token.text.endsWith("'") || token.text.length == 2
        TokenKind.BACKQUOTED -> !token.text.endsWith("`") || token.text.length == 1
        else -> false
    }

    private fun unclosedKeyFor(kind: TokenKind): String? = when (kind) {
        TokenKind.SINGLE_QUOTED -> StyleKey.SINGLE_QUOTED_UNCLOSED
        TokenKind.DOUBLE_QUOTED -> StyleKey.DOUBLE_QUOTED_UNCLOSED
        TokenKind.DOLLAR_QUOTED -> StyleKey.DOLLAR_QUOTED_UNCLOSED
        TokenKind.BACKQUOTED -> StyleKey.BACKQUOTED_UNCLOSED
        else -> null
    }

    private fun styleOf(
        token: Token,
        resolver: Resolver,
        isRedirectTarget: Boolean = false,
    ): String = when (token.kind) {
        TokenKind.COMMAND_WORD -> when (resolver.resolveCommand(token.text)) {
            CommandKind.Builtin -> StyleKey.BUILTIN
            CommandKind.Function -> StyleKey.FUNCTION
            CommandKind.Alias -> StyleKey.ALIAS
            CommandKind.External -> StyleKey.COMMAND
            CommandKind.NotFound -> StyleKey.UNKNOWN_TOKEN
        }

        TokenKind.PRECOMMAND -> StyleKey.PRECOMMAND
        TokenKind.RESERVED_WORD -> StyleKey.RESERVED_WORD
        TokenKind.ASSIGN -> StyleKey.ASSIGN
        TokenKind.FD -> StyleKey.NUMERIC_FD
        TokenKind.SINGLE_QUOTED -> StyleKey.SINGLE_QUOTED
        TokenKind.DOUBLE_QUOTED -> StyleKey.DOUBLE_QUOTED
        TokenKind.DOLLAR_QUOTED -> StyleKey.DOLLAR_QUOTED
        TokenKind.BACKQUOTED -> StyleKey.BACKQUOTED
        TokenKind.COMMENT -> StyleKey.COMMENT
        TokenKind.SEPARATOR, TokenKind.AND_IF, TokenKind.OR_IF, TokenKind.PIPE -> StyleKey.COMMANDSEPARATOR
        TokenKind.REDIRECT -> StyleKey.REDIRECTION
        // **Zsh paints parens and braces `reserved-word`, not `default`.** Verified against
        // `subshell.zsh` (`(cd …)` → `14 14 reserved-word`) and `braces1.zsh`.
        TokenKind.BRACKET -> StyleKey.RESERVED_WORD
        TokenKind.ARRAY_CLOSE -> StyleKey.ASSIGN
        // The outer span of a `$(( … ))` is one arithmetic region; the body is not lexed.
        TokenKind.ARITH -> StyleKey.ARITHMETIC_EXPANSION
        // The outer span of `$( … )` / `<( … )`; [expansionRegions] adds the layers.
        TokenKind.CMD_SUBST -> StyleKey.CMD_SUBST_UNQUOTED
        TokenKind.PROC_SUBST -> StyleKey.PROCESS_SUBSTITUTION
        TokenKind.WORD -> when {
            // A redirect target is a filename, never an option: `> -x` passes no flag.
            isRedirectTarget -> when {
                resolver.classifyPath(token.text) == PathKind.Existing -> StyleKey.PATH
                else -> StyleKey.DEFAULT
            }
            // A path the resolver positively recognises wins over the option rules:
            // `./configure` is a path even though it starts with a dot.
            resolver.classifyPath(token.text) == PathKind.Existing -> StyleKey.PATH
            else -> optionStyle(token.text)
        }
        // **A newline between commands is a separator**, not invisible space. The corpus
        // asserts `commandseparator` for it (`comment-followed.zsh` gives `6 6
        // commandseparator` for the newline between `# foo` and `true`). It is also why the
        // SSH card's line split agrees with zsh about where one command ends.
        TokenKind.NEWLINE -> StyleKey.COMMANDSEPARATOR
        TokenKind.WHITESPACE -> StyleKey.DEFAULT
    }

    /**
     * `-x` vs `--long`.
     *
     * **A bare `-` IS a single-hyphen option.** The first version of this treated it as a
     * plain argument on the reasoning that it means stdin/stdout — but the corpus asserts
     * `single-hyphen-option` for it twice (`tar cf - *` and `tar xfp -`, both in
     * `subshell.zsh`), and matching zsh is the whole point of adopting its vocabulary.
     */
    private fun optionStyle(text: String): String = when {
        // `--` alone is the end-of-options marker and zsh paints it as a long option.
        text.startsWith("--") -> StyleKey.DOUBLE_HYPHEN_OPTION
        text.length > 1 && text.startsWith("-") && !text[1].isDigit() -> StyleKey.SINGLE_HYPHEN_OPTION
        text == "-" -> StyleKey.SINGLE_HYPHEN_OPTION
        else -> StyleKey.DEFAULT
    }
}

/**
 * The command split into display lines at token boundaries.
 *
 * This is what the SSH approval card needs, and it lives here rather than in the app
 * because "where may a command be broken" is a parsing question — a `;` inside quotes is
 * not a boundary, and `awk -F';'` must stay on one line.
 *
 * **It only ever inserts.** Each returned line is a slice of the original string; no
 * character is removed, reordered or replaced. The caller renders these lines and still
 * sends the untouched original — see docs/plans/SHELL-HIGHLIGHT-PLAN.md §9 (Q1).
 */
fun highlightLines(
    command: String,
    breakBeforeOperators: Boolean = false,
): List<String> {
    if (command.isEmpty()) return listOf(command)

    val cuts = Lexer.tokenize(command)
        .filter { isBreakToken(it.kind) }
        // A separator ends its line (default), or starts the next one.
        .map { if (breakBeforeOperators) it.start else it.end }
        // A cut at 0 would make an empty first line and a cut at length an empty last
        // one; neither is a real boundary.
        .filter { it in 1 until command.length }
        .distinct()
        .sorted()

    if (cuts.isEmpty()) return listOf(command)

    val out = ArrayList<String>(cuts.size + 1)
    var from = 0
    for (cut in cuts) {
        out.add(command.substring(from, cut))
        from = cut
    }
    out.add(command.substring(from))
    return out
}

/**
 * Token kinds that end a display line.
 *
 * Deliberately narrow: `;` `&&` `||` `|` and an explicit newline. A redirection does NOT
 * break, because `> out.txt` belongs to the command it modifies and splitting there would
 * suggest the file is a separate operation.
 */
private fun isBreakToken(kind: TokenKind): Boolean = when (kind) {
    TokenKind.SEPARATOR, TokenKind.AND_IF, TokenKind.OR_IF, TokenKind.PIPE, TokenKind.NEWLINE -> true
    else -> false
}
