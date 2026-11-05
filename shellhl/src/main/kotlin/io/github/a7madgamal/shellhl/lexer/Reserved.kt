package io.github.a7madgamal.shellhl.lexer

/**
 * Words the shell itself owns, so a colourer can paint them differently from a
 * command name.
 *
 * **Why a table and not a `when` branch in the lexer.** These are language
 * keywords; they change with POSIX and with bash, and they are the one part of
 * the lexer with no structure to it. Keeping them in one list means "is this a
 * reserved word" has exactly one answer in this codebase, and adding `coproc`
 * later is one line rather than a hunt.
 *
 * Deliberately NOT here: `sudo`, `env`, `noglob`, `command`, `exec`, `time`.
 * Those are ordinary external programs that the shell happens to give a special
 * *parsing* meaning to (the next word is still the command). zsh calls that
 * concept `precommand`, and it is a mapping decision in `highlight/`, not a
 * lexer fact — the lexer must not know which words a theme paints differently.
 */
internal object Reserved {

    private val WORDS: Set<String> = setOf(
        // Compound-command openers and closers.
        "if", "then", "else", "elif", "fi",
        "for", "while", "until", "do", "done",
        "case", "esac", "in",
        "function", "select", "coproc",
        // Grouping and negation. `[[` is a bash keyword, not a test command.
        "!", "[[", "]]",
        "{", "}",
    )

    fun isReserved(text: String): Boolean = text in WORDS
}

/**
 * Programs that occupy the command position *without being the command*.
 *
 * `zsh-syntax-highlighting` styles these `precommand`, and the distinction is
 * load-bearing for highlighting: in `sudo -u root ls`, the command is `ls`, not
 * `sudo`, so the word after a precommand must still be treated as command
 * position. Getting this wrong paints every `sudo` line one colour hotter than
 * it should be.
 */
internal object Precommand {

    private val WORDS: Set<String> = setOf(
        "sudo", "doas", "env", "noglob", "command", "builtin", "exec",
        "time", "nice", "nohup", "stdbuf", "ionice", "chrt", "taskset",
    )

    fun isPrecommand(text: String): Boolean = text in WORDS
}
