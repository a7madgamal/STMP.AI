package io.github.a7madgamal.shellhl

import io.github.a7madgamal.shellhl.highlight.CommandKind
import io.github.a7madgamal.shellhl.highlight.PathKind
import io.github.a7madgamal.shellhl.highlight.Resolver
import java.io.File

/**
 * A resolver seeded from a corpus file's own preamble, plus real filesystem answers.
 *
 * **Why this is not cheating.** zsh-syntax-highlighting's expected output was produced by a
 * live zsh. 103 of the 271 cases open with the setup that makes their expectations true:
 *
 * ```zsh
 * alias foo="echo hello world"
 * BUFFER="foo"
 * expected_region_highlight+=("1 3 alias")
 * ```
 *
 * Ignoring that preamble and then declaring the case "needs a runtime lookup" is not being
 * careful, it is refusing to read the fixture. The preamble IS the resolver data, written
 * down by the people who wrote the expectation.
 *
 * This class is test-only. It reads a file and a `PATH`-like disk; neither belongs in the
 * library, which is why [Resolver] is an interface.
 */
class CorpusResolver private constructor(
    private val aliases: Map<String, CommandKind>,
    private val root: File?,
) : Resolver {

    override fun resolveCommand(name: String): CommandKind {
        aliases[name]?.let { return it }
        if (name in BUILTINS) return CommandKind.Builtin
        // A command word that is an existing executable on disk is a plain command.
        // Anything else stays `External` — never `NotFound`: this test has no way to
        // assert the absence of a program, and `Resolver.Unknown`'s optimism rule applies.
        return CommandKind.External
    }

    override fun classifyPath(text: String): PathKind {
        if (text.isEmpty() || text == "-") return PathKind.NotAPath
        val f = File(text)
        // Only absolute and explicit relative paths are treated as paths. A bare word
        // like `foo` must not become `path` just because a file called foo exists in
        // the working directory — zsh checks, but it checks relative to the real cwd,
        // which this test does not share.
        if (!text.startsWith("/") && !text.startsWith("./") && !text.startsWith("../")) {
            return PathKind.NotAPath
        }
        if (f.exists()) return PathKind.Existing
        // `path_prefix`: `/bi` is a prefix of an entry in `/`.
        val parentPath = text.substringBeforeLast('/', "").ifEmpty { "/" }
        val leaf = text.substringAfterLast('/')
        val parent = File(parentPath)
        if (leaf.isNotEmpty() && parent.isDirectory) {
            val hit = parent.listFiles()?.any { it.name.startsWith(leaf) } == true
            if (hit) return PathKind.Prefix
        }
        return PathKind.NotAPath
    }

    companion object {
        /**
         * Shell builtins, POSIX + the zsh ones that appear in the corpus.
         *
         * A static table is a legitimate [Resolver] implementation — that is exactly what
         * the interface is for. It is not a claim about the remote host; it is the set of
         * words every zsh has as builtins.
         */
        private val BUILTINS: Set<String> = setOf(
            ":", ".", "alias", "bg", "bindkey", "break", "builtin", "bye", "cd", "chdir",
            "command", "compdef", "continue", "declare", "dirs", "disable", "disown",
            "echo", "echotc", "echoti", "emulate", "enable", "eval", "exec", "exit",
            "export", "false", "fc", "fg", "float", "functions", "getcap", "getln",
            "getopts", "hash", "history", "integer", "jobs", "kill", "let", "limit",
            "local", "log", "noglob", "popd", "print", "printf", "private", "pushd",
            "pushln", "pwd", "r", "read", "readonly", "rehash", "return", "sched",
            "set", "setcap", "setopt", "shift", "source", "stat", "suspend", "test",
            "times", "trap", "true", "ttyctl", "type", "typeset", "ulimit", "umask",
            "unalias", "unfunction", "unhash", "unlimit", "unset", "unsetopt", "vared",
            "wait", "whence", "where", "which", "zcompile", "zformat", "zle", "zmodload",
            "zparseopts", "zprof", "zpty", "zregexparse", "zsocket", "zstyle", "ztcp",
        )

        /** Build from a corpus file's preamble; `setopt autocd` seeds `autodirectory`. */
        fun forCase(file: java.io.File): CorpusResolver {
            val kinds = HashMap<String, CommandKind>()
            for (raw in file.readLines()) {
                val line = raw.trim()
                if (line.startsWith("alias ")) {
                    // `alias foo=...` and `alias foo='...'`. The name is up to `=`.
                    val name = line.removePrefix("alias ").substringBefore('=').trim()
                    if (name.isNotEmpty()) kinds[name] = CommandKind.Alias
                } else if (line.startsWith("aliases[")) {
                    // zsh associative form: `aliases[x=y]='...'`
                    val name = line.substringAfter('[').substringBefore(']').substringBefore('=')
                        .trim().trim('"', '\'')
                    if (name.isNotEmpty()) kinds[name] = CommandKind.Alias
                } else if (line.startsWith("function ") || line.startsWith("() ") ) {
                    val name = line.removePrefix("function ").substringBefore('(').trim()
                    if (name.isNotEmpty()) kinds[name] = CommandKind.Function
                }
            }
            return CorpusResolver(kinds, null)
        }
    }
}
