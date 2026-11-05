package io.github.a7madgamal.shellhl.lexer

/**
 * The scanner: a shell command string into [Token]s.
 *
 * **Coverage is an invariant, not a nicety.** Every character belongs to exactly one token,
 * whitespace included, in source order. The SSH approval card renders from these tokens, so a
 * character missing from the stream would mean the human approves text they were not shown.
 *
 * **What this does not do.** No expansion, no `PATH` lookup, no validation. An unbalanced quote
 * yields a quoted token running to end-of-input; it does not throw. A lexer that refuses to
 * colour a half-typed line is a worse tool than one that colours what it can.
 *
 * **What it delegates upward.** Whether a command word is a builtin, an alias or a file on disk
 * is not knowable here — see `highlight/Resolver`.
 */
object Lexer {

    private fun isWordBreak(c: Char): Boolean = when (c) {
        ' ', '\t', '\n', '\r', ';', '&', '|', '<', '>', '(', ')', '\'', '"', '`' -> true
        else -> false
    }

    fun tokenize(command: String): List<Token> {
        val out = ArrayList<Token>(command.length / 3 + 8)
        var i = 0

        /** True when the next bare word would be the command name. */
        var commandPosition = true

        /** Depth inside `NAME=( … )`, where elements are values, not programs. */
        var arrayDepth = 0

        /**
         * True right after a redirection operator: the next word is its TARGET.
         *
         * A target is a filename, never the command name — `>/tmp command echo` redirects
         * first and then runs `command`. Without this the target eats command position and
         * the real command is painted as an argument.
         */
        var expectRedirectTarget = false

        while (i < command.length) {
            val c = command[i]

            // --- newline -----------------------------------------------------
            if (c == '\n') {
                out.add(Token(TokenKind.NEWLINE, i, i + 1, "\n"))
                i++
                commandPosition = true
                continue
            }

            // --- horizontal whitespace ---------------------------------------
            if (c == ' ' || c == '\t' || c == '\r') {
                val start = i
                while (i < command.length && (command[i] == ' ' || command[i] == '\t' || command[i] == '\r')) i++
                out.add(Token(TokenKind.WHITESPACE, start, i, command.substring(start, i)))
                continue
            }

            // --- comment ------------------------------------------------------
            // Only at a word start: `echo a#b` is one word, `echo # b` is a comment.
            if (c == '#' && (i == 0 || command[i - 1].isWhitespace())) {
                val start = i
                while (i < command.length && command[i] != '\n') i++
                out.add(Token(TokenKind.COMMENT, start, i, command.substring(start, i)))
                continue
            }

            // --- expansions ----------------------------------------------------
            // These must be checked BEFORE operators and words: `$(` contains a `(`,
            // which the word scanner treats as a break, and `>(` starts with `>`, which
            // the operator table would claim as a redirection. Scanning them as whole
            // balanced units is what lets the highlighter recurse into their bodies.
            //
            // `$(( … ))` is checked first: matchingParen counts nesting, so the doubled
            // open would otherwise end at the first `)`, splitting arithmetic in half.
            if (c == '$' && i + 2 < command.length && command[i + 1] == '(' && command[i + 2] == '(') {
                val close = matchingParen(command, i + 1)
                if (close > 0) {
                    out.add(Token(TokenKind.ARITH, i, close + 1, command.substring(i, close + 1)))
                    i = close + 1
                    commandPosition = false
                    continue
                }
            }
            if (c == '$' && i + 1 < command.length && command[i + 1] == '(') {
                val close = matchingParen(command, i + 1)
                if (close > 0) {
                    out.add(Token(TokenKind.CMD_SUBST, i, close + 1, command.substring(i, close + 1)))
                    i = close + 1
                    commandPosition = false
                    continue
                }
            }
            if ((c == '<' || c == '>') && i + 1 < command.length && command[i + 1] == '(') {
                val close = matchingParen(command, i + 1)
                if (close > 0) {
                    out.add(Token(TokenKind.PROC_SUBST, i, close + 1, command.substring(i, close + 1)))
                    i = close + 1
                    commandPosition = false
                    continue
                }
            }

            // --- quotes --------------------------------------------------------
            if (c == '\'') {
                val start = i
                i++
                while (i < command.length && command[i] != '\'') i++
                if (i < command.length) i++
                out.add(Token(TokenKind.SINGLE_QUOTED, start, i, command.substring(start, i)))
                commandPosition = false
                continue
            }
            if (c == '"') {
                val start = i
                i++
                while (i < command.length && command[i] != '"') {
                    i += if (command[i] == '\\' && i + 1 < command.length) 2 else 1
                }
                if (i < command.length) i++
                out.add(Token(TokenKind.DOUBLE_QUOTED, start, i, command.substring(start, i)))
                commandPosition = false
                continue
            }
            if (c == '$' && i + 1 < command.length && command[i + 1] == '\'') {
                val start = i
                i += 2
                while (i < command.length && command[i] != '\'') {
                    i += if (command[i] == '\\' && i + 1 < command.length) 2 else 1
                }
                if (i < command.length) i++
                out.add(Token(TokenKind.DOLLAR_QUOTED, start, i, command.substring(start, i)))
                commandPosition = false
                continue
            }
            if (c == '`') {
                val start = i
                i++
                while (i < command.length && command[i] != '`') {
                    i += if (command[i] == '\\' && i + 1 < command.length) 2 else 1
                }
                if (i < command.length) i++
                out.add(Token(TokenKind.BACKQUOTED, start, i, command.substring(start, i)))
                commandPosition = false
                continue
            }

            // --- operators -----------------------------------------------------
            val op = matchOperator(command, i)
            if (op != null) {
                val start = i
                i += op.text.length
                out.add(Token(op.kind, start, i, op.text))
                if (op.kind == TokenKind.REDIRECT && op.text.endsWith("&")) {
                    i = emitFdIfPresent(command, i, out)
                }
                // **A redirection does not consume command position.** In
                // `>/tmp >/tmp command echo foo` the prefixes are redirections and `command`
                // is still the command slot — the corpus asserts `13 19 precommand`. A
                // separator or a pipe does consume it. The target word is skipped below so it
                // cannot consume it either.
                expectRedirectTarget = op.kind == TokenKind.REDIRECT
                commandPosition = if (op.kind == TokenKind.REDIRECT) commandPosition else op.opensCommand
                continue
            }

            // --- bracket --------------------------------------------------------
            if (c == '(' || c == ')') {
                // `((` / `))` arithmetic command — unless preceded by `$`, where `$((` was
                // already scanned above as ARITH.
                val doubled = i + 1 < command.length && command[i + 1] == c
                if (doubled) {
                    out.add(Token(TokenKind.RESERVED_WORD, i, i + 2, command.substring(i, i + 2)))
                    i += 2
                    commandPosition = c == '('
                    continue
                }
                // The `)` closing an array literal carries two zsh styles; the highlighter
                // emits both, which is why it gets its own kind.
                if (c == ')' && arrayDepth == 1) {
                    out.add(Token(TokenKind.ARRAY_CLOSE, i, i + 1, ")"))
                    arrayDepth = 0
                    i++
                    commandPosition = true
                    continue
                }
                out.add(Token(TokenKind.BRACKET, i, i + 1, c.toString()))
                if (arrayDepth > 0) {
                    if (c == '(') arrayDepth++ else arrayDepth--
                }
                i++
                commandPosition = c == '(' && arrayDepth == 0
                continue
            }

            // --- bare word ------------------------------------------------------
            val start = i
            while (i < command.length && !isWordBreak(command[i])) {
                if (command[i] == '$' && i + 1 < command.length && command[i + 1] == '\'') break
                if (command[i] == '$' && i + 1 < command.length && command[i + 1] == '(') break
                i++
            }
            if (i == start) {
                // Defensive: nothing matched and nothing consumed. One character keeps the
                // coverage invariant true and the loop from spinning.
                out.add(Token(TokenKind.WORD, start, start + 1, c.toString()))
                i++
                commandPosition = false
                continue
            }

            // A digit run immediately before a redirect merges into it: zsh paints `9<foo`
            // as `9<` + `foo` (see `redirection-all.zsh`).
            val digitsOnly = (start until i).all { command[it].isDigit() }
            if (digitsOnly && i < command.length && (command[i] == '<' || command[i] == '>')) {
                val opAfter = matchOperator(command, i)
                if (opAfter != null && opAfter.kind == TokenKind.REDIRECT) {
                    val end = i + opAfter.text.length
                    out.add(Token(TokenKind.REDIRECT, start, end, command.substring(start, end)))
                    i = end
                    if (opAfter.text.endsWith("&")) i = emitFdIfPresent(command, i, out)
                    continue
                }
            }

            val text = command.substring(start, i)

            // `NAME=(` opens an array literal; the `(` belongs to the assignment token
            // because zsh paints `A=(` as one `assign` region. The word scanner has already
            // eaten the `=`, so the name is `text` minus its trailing `=`.
            if (commandPosition && arrayDepth == 0 && i < command.length && command[i] == '(' &&
                text.endsWith("=") && isAssignName(text.dropLast(1))
            ) {
                i++
                out.add(Token(TokenKind.ASSIGN, start, i, command.substring(start, i)))
                arrayDepth = 1
                commandPosition = false
                continue
            }

            val kind = when {
                commandPosition && arrayDepth == 0 && isAssignment(text) -> TokenKind.ASSIGN
                Reserved.isReserved(text) -> TokenKind.RESERVED_WORD
                commandPosition && Precommand.isPrecommand(text) -> TokenKind.PRECOMMAND
                // **An option is never the command.** `sudo -E ls`: `-E` sits where the
                // command goes but is a flag, so command position stays OPEN for `ls`.
                commandPosition && isOption(text) -> TokenKind.WORD
                commandPosition -> TokenKind.COMMAND_WORD
                else -> TokenKind.WORD
            }
            out.add(Token(kind, start, i, text))

            // A redirect target is consumed without touching command position, so the word
            // after it is still the command.
            if (expectRedirectTarget) {
                expectRedirectTarget = false
                continue
            }

            commandPosition = when {
                // `[[ … ]]` is a condition, not a command: after it the next word is a test
                // operand, never a program name.
                text == "[[" || text == "((" -> false
                kind == TokenKind.ASSIGN -> true
                kind == TokenKind.RESERVED_WORD -> true
                kind == TokenKind.PRECOMMAND -> true
                kind == TokenKind.WORD && isOption(text) -> commandPosition
                else -> false
            }
        }
        return out
    }

    /** After `>&` / `<&`, a run of digits is a file descriptor. Returns the new index. */
    private fun emitFdIfPresent(command: String, from: Int, out: MutableList<Token>): Int {
        var i = from
        val start = i
        while (i < command.length && command[i].isDigit()) i++
        if (i > start) out.add(Token(TokenKind.FD, start, i, command.substring(start, i)))
        return i
    }

    /**
     * Index of the `)` matching the `(` at [open], or -1.
     *
     * Respects nesting, backslash escapes and both quote styles, so `$(echo ")")` closes at
     * the right place. A `)` inside quotes is data, not structure.
     */
    private fun matchingParen(command: String, open: Int): Int {
        var depth = 0
        var i = open
        var inSingle = false
        var inDouble = false
        while (i < command.length) {
            val c = command[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> when (c) {
                    '\\' -> i++
                    '"' -> inDouble = false
                }
                c == '\\' -> i++
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** `-x`, `--long`. A mask for the command-position rule only; styling is separate. */
    private fun isOption(text: String): Boolean = text.length > 1 && text[0] == '-'

    /** `NAME` or `NAME+` — the part before `=` in an assignment. */
    private fun isAssignName(text: String): Boolean {
        val name = text.removeSuffix("+")
        if (name.isEmpty()) return false
        if (!(name[0].isLetter() || name[0] == '_')) return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }

    /** `NAME=value`, per POSIX. */
    private fun isAssignment(text: String): Boolean {
        val eq = text.indexOf('=')
        if (eq <= 0) return false
        val name = text.substring(0, eq)
        if (!(name[0].isLetter() || name[0] == '_')) return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }

    private class Operator(
        val text: String,
        val kind: TokenKind,
        /** Does the next word sit in command position? */
        val opensCommand: Boolean,
    )

    /**
     * Operators, longest-first. An ordered list rather than a regex because the ordering IS
     * the rule: `>>` must beat `>`, `<<<` must beat `<<`.
     */
    private val OPERATORS: List<Operator> = listOf(
        Operator("<<<", TokenKind.REDIRECT, false),
        Operator("<<-", TokenKind.REDIRECT, false),
        Operator(">>&!", TokenKind.REDIRECT, false),
        Operator("&>>", TokenKind.REDIRECT, false),
        Operator(">>&", TokenKind.REDIRECT, false),
        Operator(">>", TokenKind.REDIRECT, false),
        Operator("<<", TokenKind.REDIRECT, false),
        Operator("<>", TokenKind.REDIRECT, false),
        Operator(">&", TokenKind.REDIRECT, false),
        Operator("<&", TokenKind.REDIRECT, false),
        Operator(">|", TokenKind.REDIRECT, false),
        Operator("&>", TokenKind.REDIRECT, false),
        Operator(">", TokenKind.REDIRECT, false),
        Operator("<", TokenKind.REDIRECT, false),
        Operator("&&", TokenKind.AND_IF, true),
        Operator("||", TokenKind.OR_IF, true),
        Operator("|&", TokenKind.PIPE, true),
        Operator(";;", TokenKind.SEPARATOR, true),
        // `&!` — zsh's disown separator. Two characters, and the corpus asserts it as ONE
        // `commandseparator` span (`commandseparator.zsh`: `8 9`). Without it the `&` and
        // the `!` land in different regions and neither matches.
        Operator("&!", TokenKind.SEPARATOR, true),
        Operator(";", TokenKind.SEPARATOR, true),
        Operator("&", TokenKind.SEPARATOR, true),
        Operator("|", TokenKind.PIPE, true),
    )

    private fun matchOperator(command: String, at: Int): Operator? =
        OPERATORS.firstOrNull { command.startsWith(it.text, at) }
}
