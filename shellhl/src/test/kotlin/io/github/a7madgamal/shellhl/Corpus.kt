package io.github.a7madgamal.shellhl

import java.io.File

/**
 * Reads zsh-syntax-highlighting's golden test files.
 *
 * Each file is zsh source, not data, so this is a small reader rather than a parser:
 * it pulls `BUFFER` and the `expected_region_highlight` array out of the text. The
 * files are never executed — that would need zsh, and a test suite that needs zsh
 * installed is a test suite that does not run.
 *
 * **The format is the contract.** `"1 1 builtin"` means: characters 1 through 1
 * inclusive are `builtin`. 1-indexed, inclusive. It is the same convention
 * [io.github.a7madgamal.shellhl.highlight.Region] uses, so a mismatch is a real
 * defect rather than an offset disagreement.
 */
object Corpus {

    /** One expected region from a golden file. */
    data class Expected(val start: Int, val end: Int, val style: String, val todo: String?)

    /** One golden case. */
    data class Case(
        val name: String,
        val buffer: String,
        val expected: List<Expected>,
        val skipReason: String?,
        /**
         * The source file. Carried because the file's own preamble IS the resolver data:
         * 103 cases open with `alias foo=…` or `setopt autocd`, which is exactly what the
         * expected output assumes. Dropping it strands those cases as "needs a runtime".
         */
        val file: File,
    )

    /**
     * The `BUFFER=` value, or `null` when a file has none (a few are helpers).
     *
     * **Three quoting forms appear upstream, and they are not interchangeable.**
     * 149 files use `'…'`, 8 use `"…"`, and **111 use `$'…'`** — zsh's ANSI-C quoting,
     * which carries escapes like `\'` and `\n`. Reading the `$'` form as a plain
     * double-quoted string (the first draft did) leaves the literal `$'` in the buffer,
     * which shifts every offset and makes 111 cases fail for a reason that has nothing
     * to do with the lexer.
     */
    fun bufferOf(file: File): String? {
        val lines = file.readLines()
        for ((index, raw) in lines.withIndex()) {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("BUFFER=")) continue
            // **`BUFFER+=` appends a continuation.** `double-quoted.zsh` builds its buffer
            // from two statements:
            //
            //     BUFFER=': "foo$bar:…'
            //     BUFFER+=\\\\\:"
            //
            // Ignoring the second line truncated the buffer, which shifted every expected
            // offset past the cut and made that case look like a lexer bug.
            var text = decodeBuffer(trimmed.removePrefix("BUFFER="))
            var next = index + 1
            while (next < lines.size) {
                val more = lines[next].trim()
                if (!more.startsWith("BUFFER+=")) break
                text += decodeBuffer(more.removePrefix("BUFFER+="))
                next++
            }
            return text
        }
        return null
    }

    private fun decodeBuffer(body: String): String {
        if (body.startsWith("$'")) {
            // ANSI-C quoting: strip `$'` … `'` and decode the escapes it can contain.
            val inner = body.substring(2, body.lastIndexOf('\'').takeIf { it >= 2 } ?: body.length)
            return unescapeAnsiC(inner)
        }
        if (body.startsWith("'")) {
            // Single quotes are literal in zsh; no escapes to decode.
            val end = body.lastIndexOf('\'')
            return if (end > 0) body.substring(1, end) else body.substring(1)
        }
        if (body.startsWith("\"")) {
            val end = body.lastIndexOf('"')
            val inner = if (end > 0) body.substring(1, end) else body.substring(1)
            return unescapeDouble(inner)
        }
        return body
    }

    /**
     * Decode the escapes `$'…'` can carry.
     *
     * Deliberately narrow: only the ones that appear in this corpus. An unknown escape
     * is kept verbatim (backslash and all) rather than silently dropped — a decoder
     * that eats characters would corrupt the very offsets these tests compare.
     */
    private fun unescapeAnsiC(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c)
                i++
                continue
            }
            val n = s[i + 1]
            when (n) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '\'' -> { out.append('\''); i += 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun unescapeDouble(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\' || s[i + 1] == '$')) {
                out.append(s[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Parse one file into a case, or `null` if it carries no buffer. */
    fun caseOf(file: File): Case? {
        val buffer = bufferOf(file) ?: return null
        val expected = parseExpected(file)
        val skip = parseSkip(file)
        return Case(file.name, buffer, expected, skip, file)
    }

    private fun parseSkip(file: File): String? {
        for (line in file.readLines()) {
            val t = line.trim()
            // `skip_test=...` / `fail_test=...`: upstream's own way of saying "this
            // case needs something a pure lexer cannot provide". We keep the reason
            // instead of deleting the case, so the gap stays visible.
            if (t.startsWith("skip_test=") || t.startsWith("fail_test=")) {
                return t.substringAfter('=').trim().trim('"', '\'')
            }
        }
        return null
    }

    private fun parseExpected(file: File): List<Expected> {
        val lines = file.readLines()
        val out = ArrayList<Expected>()
        var inside = false
        for (line in lines) {
            val t = line.trim()
            // **Both forms.** Upstream writes `expected_region_highlight=(` when a case
            // sets it once and `expected_region_highlight+=(` when it appends — the
            // latter is used by every case that inherits a shared preamble. Handling
            // only the first form silently returned zero expectations for those files,
            // which read as "our output has extra regions" on 197 of 271 cases. A
            // parser bug wearing a lexer bug's clothes.
            val opener = when {
                t.startsWith("expected_region_highlight+=(") -> "expected_region_highlight+=("
                t.startsWith("expected_region_highlight=(") -> "expected_region_highlight=("
                else -> null
            }
            if (opener != null) {
                inside = true
                val rest = t.removePrefix(opener)
                // A one-line array closes on the same line.
                if (rest.endsWith(")")) {
                    parseEntries(listOf(rest.removeSuffix(")"))).forEach { out.add(it) }
                    inside = false
                }
                continue
            }
            if (!inside) continue
            if (t == ")" || t.startsWith(")")) break
            parseEntry(t)?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntries(lines: List<String>): List<Expected> =
        lines.mapNotNull { parseEntry(it) }

    /**
     * One entry: `"1 3 redirection" # <<<` or `'1 3 redirection' # <<<`.
     *
     * **Both quote styles appear, and the split is not cosmetic.** Most files use single
     * quotes; a minority use double. Matching only one silently yields zero expectations,
     * which then reads as "our output produced unexpected regions" on ~200 cases — a
     * reader bug wearing a lexer bug's clothes. That cost a full debugging cycle; the
     * check for either delimiter is the fix.
     */
    private fun parseEntry(line: String): Expected? {
        val open = line.indexOfFirst { it == '"' || it == '\'' }
        if (open < 0) return null
        val delim = line[open]
        val close = line.indexOf(delim, open + 1)
        if (close < 0) return null
        val body = line.substring(open + 1, close)

        val parts = body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = parts[1].toIntOrNull() ?: return null
        val style = parts[2]
        val todo = if (parts.size >= 4) parts.drop(3).joinToString(" ") else null
        return Expected(a, b, style, todo)
    }

    /** Every golden case under `src/test/resources/zsh-corpus`. */
    fun all(): List<Case> {
        val dir = File("src/test/resources/zsh-corpus")
        require(dir.isDirectory) { "corpus directory missing: ${dir.absolutePath}" }
        return dir.listFiles { f -> f.extension == "zsh" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { caseOf(it) }
    }
}
