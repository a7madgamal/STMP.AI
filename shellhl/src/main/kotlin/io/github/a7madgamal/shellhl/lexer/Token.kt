package io.github.a7madgamal.shellhl.lexer

/**
 * One syntactic chunk of a shell command, located by character offsets.
 *
 * Offsets are **half-open and 0-indexed**: `[start, end)`. This is Kotlin's
 * convention, and it is NOT zsh's — zsh regions are 1-indexed and inclusive.
 * The conversion happens in exactly one place (`highlight`), because an
 * off-by-one between our tokens and zsh's regions is the single most likely
 * bug in this library and it is completely silent: every colour would be
 * shifted by one character and the output would still look plausible.
 */
data class Token(
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val text: String,
) {
    init {
        require(start in 0..end) { "start $start must be <= end $end" }
        require(text.length == end - start) {
            "text length ${text.length} disagrees with span ${end - start}"
        }
    }
}

/**
 * What a token IS, syntactically.
 *
 * This enum knows nothing about colour. A lexer that emitted style names would
 * be a lexer that cannot be used for structure — and structure is exactly what
 * the SSH card needs when it splits a command across lines. See `highlight/`.
 */
enum class TokenKind {
    /**
     * A word in **argument** position: a plain argument, a path, a glob.
     *
     * The split from [COMMAND_WORD] is deliberate. Command position is a fact about
     * the *syntax* — zsh's own lexer knows it — and a highlighter that re-derived it
     * would be a second state machine that could disagree with this one. One owner.
     */
    WORD,

    /**
     * A word in **command** position: where a program name goes.
     *
     * Which colour it gets (`command`, `builtin`, `alias`, `unknown-token`) depends on
     * the environment, so it is not decided here. See `highlight/Resolver`.
     */
    COMMAND_WORD,

    /**
     * `sudo`, `env`, `time`, `exec` … — occupies command position without being the
     * command. The word after one is still [COMMAND_WORD].
     */
    PRECOMMAND,

    /** `NAME=value` in command position. Position matters — `echo A=b` is a WORD. */
    ASSIGN,

    /** `if`, `then`, `do`, `esac`, … */
    RESERVED_WORD,

    /**
     * A numeric file descriptor prefix: the `2` in `2>&1`.
     *
     * Only when a redirect immediately follows, which is what distinguishes it from a
     * plain argument that happens to be a number (`head -n 2`).
     */
    FD,

    /** `'…'` — no expansion inside. */
    SINGLE_QUOTED,

    /** `"…"` — expansion and escapes inside. */
    DOUBLE_QUOTED,

    /** `$'…'` — ANSI-C quoting. */
    DOLLAR_QUOTED,

    /** `` `…` `` — legacy command substitution. */
    BACKQUOTED,

    /** `# …` to end of line. Only when `#` begins a word. */
    COMMENT,

    /** `;` — a command separator that is not a control operator pair. */
    SEPARATOR,

    /** `&&` */
    AND_IF,

    /** `||` */
    OR_IF,

    /** `|` and `|&` */
    PIPE,

    /** `<`, `>`, `>>`, `<<`, `<<-`, `>&`, `<&`, `<>`, `>|`, `&>`, `&>>` */
    REDIRECT,

    /**
     * `$( … )` — command substitution, balanced as ONE token.
     *
     * Scanned as a unit rather than left to the word scanner because it CONTAINS a `(`,
     * which is a word break, and because zsh paints the body as a real command and the
     * delimiters separately. Keeping it whole is what lets the highlighter recurse into it
     * with correct offsets.
     */
    CMD_SUBST,

    /**
     * `$(( … ))` — arithmetic expansion.
     *
     * Distinct from [CMD_SUBST] because the doubled parens change where the token ends:
     * treating `$((` as `$(` would stop at the first `)` of two and split the expression.
     */
    ARITH,

    /**
     * `<( … )` and `>( … )` — process substitution.
     *
     * Must be recognised before [REDIRECT], since it starts with the same character. zsh
     * paints the whole thing as one span, not as a redirect plus a subshell.
     */
    PROC_SUBST,

    /** `(`, `)`, `{`, `}` */
    BRACKET,

    /**
     * The `)` that closes an array literal opened by `NAME=(`.
     *
     * Its own kind because zsh paints it TWICE — `assign` and `reserved-word` on the
     * same character — and a single-style token cannot express that. The highlighter
     * emits both regions for this one token. Collapsing it to [BRACKET] would make the
     * overlap unrepresentable and silently drop one of the two spans.
     */
    ARRAY_CLOSE,

    /** A literal newline. Kept as a token so a caller can split lines on it. */
    NEWLINE,

    /**
     * Spaces and tabs.
     *
     * Emitted rather than skipped so that tokens cover every character of the input —
     * the coverage invariant is what lets a caller rebuild the exact string. It is
     * never painted: the highlighter returns no region for it.
     */
    WHITESPACE,
}
