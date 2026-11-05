package io.github.a7madgamal.shellhl.highlight

/**
 * The style vocabulary: zsh-syntax-highlighting's keys, adopted verbatim.
 *
 * **Why these names and not better ones.** The point of choosing zsh compatibility
 * (plan §2, decision 2) is that an existing zsh theme file can be reused unchanged
 * and that the 271-case upstream corpus can be replayed as our regression suite.
 * Both of those die the moment a key is renamed to something nicer. So
 * `commandseparator` stays, misspelling-adjacent hyphenation and all.
 *
 * Names are carried as `String` in [Region], not as this enum, because the corpus
 * contains keys that a pure lexer can never emit (`path`, `alias`, …) and a closed
 * enum at the API boundary would force us to lie about that. This object exists so
 * the keys we DO emit are typo-proof on the producing side.
 */
object StyleKey {
    // --- command position -------------------------------------------------
    const val COMMAND = "command"
    const val BUILTIN = "builtin"
    const val PRECOMMAND = "precommand"
    const val RESERVED_WORD = "reserved-word"
    const val ARG0 = "arg0"

    // --- arguments and options --------------------------------------------
    const val DEFAULT = "default"
    const val SINGLE_HYPHEN_OPTION = "single-hyphen-option"
    const val DOUBLE_HYPHEN_OPTION = "double-hyphen-option"

    // --- quoting -----------------------------------------------------------
    const val SINGLE_QUOTED = "single-quoted-argument"
    const val SINGLE_QUOTED_UNCLOSED = "single-quoted-argument-unclosed"
    const val DOUBLE_QUOTED = "double-quoted-argument"
    const val DOUBLE_QUOTED_UNCLOSED = "double-quoted-argument-unclosed"
    const val DOLLAR_QUOTED = "dollar-quoted-argument"
    const val DOLLAR_QUOTED_UNCLOSED = "dollar-quoted-argument-unclosed"
    const val BACKQUOTED = "back-quoted-argument"
    const val BACKQUOTED_UNCLOSED = "back-quoted-argument-unclosed"

    // --- structure ---------------------------------------------------------
    const val COMMANDSEPARATOR = "commandseparator"
    const val REDIRECTION = "redirection"
    const val ASSIGN = "assign"
    const val NUMERIC_FD = "numeric-fd"
    const val GLOBBING = "globbing"
    const val COMMENT = "comment"
    const val UNKNOWN_TOKEN = "unknown-token"

    // --- expansions --------------------------------------------------------
    // zsh splits a substitution into three spans — the whole thing, its delimiters, and the
    // body — and distinguishes quoted from unquoted. Callers theme these independently, so
    // they stay separate names rather than one "substitution" key.
    const val CMD_SUBST_QUOTED = "command-substitution-quoted"
    const val CMD_SUBST_UNQUOTED = "command-substitution-unquoted"
    const val CMD_SUBST_DELIM_QUOTED = "command-substitution-delimiter-quoted"
    const val CMD_SUBST_DELIM_UNQUOTED = "command-substitution-delimiter-unquoted"
    const val ARITHMETIC_EXPANSION = "arithmetic-expansion"
    const val PROCESS_SUBSTITUTION = "process-substitution"
    const val PROCESS_SUBSTITUTION_DELIM = "process-substitution-delimiter"

    // --- not emitted by a pure lexer, listed so a theme can key on them -----
    // These exist in zsh's vocabulary and a caller may want to style them; this
    // lexer can only produce them through a [Resolver]. See the plan §6.
    const val PATH = "path"
    const val ALIAS = "alias"
    const val FUNCTION = "function"
    const val HASHED_COMMAND = "hashed-command"
}
