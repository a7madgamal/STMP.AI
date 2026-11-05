package io.github.a7madgamal.shellhl.highlight

/**
 * Answers the questions a pure lexer cannot: what kind of command is this word, and
 * is this text a real path?
 *
 * **Why this exists at all.** zsh-syntax-highlighting does not decide `builtin`,
 * `function`, `alias`, `hashed-command` or `path` by parsing — it asks the running
 * shell (`type -w`) and the filesystem. No amount of grammar gets you there. So the
 * honest design is a seam: the parser asks, the caller answers, and a caller who
 * cannot answer says so.
 *
 * **`Unknown` is optimistic, and that is a decision.** A command-position word with
 * no resolver becomes `command`, not `unknown-token`. Painting every command red
 * because we could not check the remote host's `PATH` would make the approval card
 * unreadable — and the card is the whole point. Red means "the resolver said this
 * does not exist", never "nobody told me".
 */
interface Resolver {

    /** What kind of thing is [name] in command position? */
    fun resolveCommand(name: String): CommandKind

    /** Is [text] an existing path, a prefix of one, or not a path at all? */
    fun classifyPath(text: String): PathKind

    /** The default: no information, so nothing is ever reported as missing. */
    object Unknown : Resolver {
        override fun resolveCommand(name: String): CommandKind = CommandKind.External
        override fun classifyPath(text: String): PathKind = PathKind.NotAPath
    }
}

/** How a command-position word should be styled. */
enum class CommandKind {
    /** A shell builtin: `cd`, `pwd`, `:`, `export`. → `builtin` */
    Builtin,

    /** A shell function. → `function` */
    Function,

    /** An alias. → `alias` */
    Alias,

    /** An external program, or one whose kind is unknown. → `command` */
    External,

    /** The shell positively reported that no such command exists. → `unknown-token` */
    NotFound,
}

/** What a searched text turned out to be on disk. */
enum class PathKind {
    /** An existing file or directory. → `path` */
    Existing,

    /** A prefix of an existing path, as in `cd /usr/lo`. → `path_prefix` */
    Prefix,

    /** Not a path, or not known to be one. → falls back to the token's natural style. */
    NotAPath,
}
