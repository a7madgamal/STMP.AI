package com.mido.phonebridge.plugins.ssh

import com.mido.phonebridge.core.PluginError

/**
 * Decides whether a command invokes `sudo`, and whether it can be fed a password at all.
 *
 * **Why this is a parser and not a `contains("sudo")`.** The password is written to the
 * command's stdin. If this predicate says "yes" for a command that is not actually sudo,
 * that password is handed to whatever the command really is — and a command like `cat`
 * would print it straight back into the HTTP response. So a false positive is a LEAK,
 * not a nuisance. `echo "x; sudo y"` must not be read as two segments, which is exactly
 * what a naive `split(";")` would do.
 *
 * A false NEGATIVE is harmless by comparison: the password is simply not sent, `sudo`
 * refuses with *"A terminal is required to authenticate"*, and the human sees a real
 * error instead of a silent escalation.
 *
 * Both directions therefore fail safe, and the asymmetry is why the parsing is careful
 * in one direction only — quotes are honoured, operators are honoured, and the head of
 * each segment is the only thing that can match.
 *
 * **The known limit, stated rather than hidden:** a nested invocation such as
 * `bash -c 'sudo rm -rf /'` is NOT detected, because `bash` is the segment head. It fails
 * safe (sudo refuses without a terminal) and it cannot leak the password, since nothing
 * here would have supplied one.
 */
object SudoCommand {

    /** `FOO=bar` prefixed to a command — skipped when looking for the segment head. */
    private val ENV_ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""")

    /**
     * Does [command] run `sudo` as the head of any segment?
     *
     * Segments are split on the shell's control operators (`;`, `&&`, `||`, `|`, newline)
     * with quoting respected, so a `sudo` that appears inside a quoted string is text, not
     * a command.
     */
    fun invokes(command: String): Boolean = segments(command).any { seg ->
        val head = seg.firstOrNull() ?: return@any false
        head.substringAfterLast('/') == "sudo"
    }

    /**
     * Does the `sudo` in [command] carry `-S` (or `--stdin`)?
     *
     * Without it, sudo refuses to read stdin at all — verified on this host:
     * `printf 'x\n' | sudo -k true` answers *"A terminal is required to authenticate"*,
     * while the same with `-S` reaches the password check. So a sudo command that lacks
     * it can never succeed when driven from here, and asking a human to approve one would
     * be asking them to approve a guaranteed failure.
     */
    fun hasStdinFlag(command: String): Boolean {
        val sudoSeg = segments(command).firstOrNull { seg ->
            seg.firstOrNull()?.substringAfterLast('/') == "sudo"
        } ?: return false
        return sudoSeg.drop(1).any { tok ->
            when {
                tok == "--stdin" -> true
                // A short-flag cluster such as `-nS` or `-Su`. A `-p` that takes a prompt
                // as its own token cannot match here, and a cluster false positive is
                // harmless: sudo simply never reads the stdin we offered.
                tok.length > 1 && tok[0] == '-' && tok[1] != '-' && tok.drop(1).contains('S') -> true
                else -> false
            }
        }
    }

    /**
     * Refuse a `sudo` command that cannot receive a password.
     *
     * Pre-gate, so the human never sees a card for something that can only fail. The
     * message names the fix rather than the rule, because the caller is the one who has
     * to add the flag.
     */
    fun requireFeedable(command: String) {
        if (invokes(command) && !hasStdinFlag(command)) {
            throw PluginError(
                "sudo_needs_stdin",
                "a sudo command must pass -S so the password can be read from stdin; " +
                    "this command would fail with \"a terminal is required to authenticate\""
            )
        }
    }

    /**
     * Split [command] into segments of tokens, honouring quotes and escapes.
     *
     * Quotes are stripped from the tokens: the head only needs to be compared to `sudo`,
     * and `'sudo'` as a command name is the same command. Env assignments are dropped
     * from the front so `FOO=1 sudo x` is still seen as sudo.
     */
    private fun segments(command: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        val word = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0

        fun flushWord() {
            if (word.isNotEmpty()) {
                cur.add(word.toString())
                word.setLength(0)
            }
        }

        fun flushSegment() {
            flushWord()
            if (cur.isNotEmpty()) {
                out.add(cur.dropWhile { ENV_ASSIGN.matches(it) })
                cur = mutableListOf()
            }
        }

        while (i < command.length) {
            val c = command[i]
            when {
                inSingle -> {
                    if (c == '\'') inSingle = false else word.append(c)
                    i++
                }
                inDouble -> {
                    if (c == '"') inDouble = false else word.append(c)
                    i++
                }
                c == '\'' -> { inSingle = true; i++ }
                c == '"' -> { inDouble = true; i++ }
                c == '\\' -> {
                    // A trailing backslash escapes nothing; keep it rather than drop it.
                    if (i + 1 < command.length) {
                        word.append(command[i + 1])
                        i += 2
                    } else {
                        word.append(c)
                        i++
                    }
                }
                c == ';' || c == '\n' -> { flushSegment(); i++ }
                c == '&' && i + 1 < command.length && command[i + 1] == '&' -> {
                    flushSegment(); i += 2
                }
                c == '|' && i + 1 < command.length && command[i + 1] == '|' -> {
                    flushSegment(); i += 2
                }
                c == '|' -> { flushSegment(); i++ }
                c.isWhitespace() -> { flushWord(); i++ }
                else -> { word.append(c); i++ }
            }
        }
        flushSegment()
        return out
    }
}
