package io.github.a7madgamal.shellhl

import io.github.a7madgamal.shellhl.lexer.Lexer
import io.github.a7madgamal.shellhl.lexer.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lexer's own invariants, plus a first pass over the upstream corpus.
 *
 * **Coverage is asserted, not assumed.** `tokenize` must return tokens that, read in
 * order, reproduce the input exactly — whitespace included. That single property catches
 * the whole class of "the scanner skipped a character" bugs, and it matters beyond
 * tidiness: the SSH approval card renders from these tokens, so a dropped character
 * would mean the human approves text they were not shown.
 */
class LexerTest {

    /** Whitespace is a real token (coverage) but never interesting to assert on. */
    private fun kinds(input: String): List<TokenKind> =
        Lexer.tokenize(input).map { it.kind }.filter { it != TokenKind.WHITESPACE }

    private fun texts(input: String): List<String> =
        Lexer.tokenize(input)
            .filter { it.kind != TokenKind.WHITESPACE }
            .map { it.text }

    private fun assertCovers(input: String) {
        val tokens = Lexer.tokenize(input)
        assertEquals("tokens must reproduce the input exactly", input, tokens.joinToString("") { it.text })
        tokens.forEach { t ->
            assertTrue("token start ${t.start} must be >= 0", t.start >= 0)
            assertTrue("token end ${t.end} <= length", t.end <= input.length)
        }
    }

    @Test
    fun `empty input yields no tokens`() {
        assertTrue(Lexer.tokenize("").isEmpty())
    }

    @Test
    fun `whitespace yields only whitespace tokens`() {
        val tokens = Lexer.tokenize("   \t  ")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.all { it.kind == TokenKind.WHITESPACE })
    }

    @Test
    fun `coverage holds for tricky inputs`() {
        listOf(
            "",
            " ",
            "ls",
            "ls -la /tmp",
            "echo 'unclosed",
            "echo \"unclosed",
            "echo \$'a\\'b'",
            "echo `date`",
            "a;b;c",
            "a && b || c | d",
            "cmd 2>&1 >out",
            "FOO=1 BAR=2 sudo -E ls",
            "echo a#b # trailing comment",
            "# leading comment",
            "if true; then echo y; fi",
            "awk -F';' '{print \$1}'",
            "echo \"x; sudo y\"",
            "cat <<<foo",
            "A=(hello world) ls",
            "a;;b",
            "echo \${#arr[@]}",
        ).forEach { assertCovers(it) }
    }

    @Test
    fun `coverage holds for the whole upstream corpus`() {
        val dir = File("src/test/resources/zsh-corpus")
        assertTrue("corpus directory is missing", dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "zsh" }
        assertTrue("corpus is empty", files != null && files.size > 200)
        files!!.forEach { f ->
            val buffer = Corpus.bufferOf(f)
            if (buffer != null) {
                try {
                    assertCovers(buffer)
                } catch (e: AssertionError) {
                    throw AssertionError("coverage failed for ${f.name}: ${e.message}", e)
                }
            }
        }
    }

    @Test
    fun `command position survives a precommand and its options`() {
        // `FOO=1 sudo -E ls -la`: ls is the command, not -E. Without holding command
        // position open across precommands AND their flags, this whole line paints
        // wrong — and `sudo` lines are the ones most worth reading on the card.
        assertEquals(
            listOf(
                TokenKind.ASSIGN,
                TokenKind.PRECOMMAND,
                TokenKind.WORD,
                TokenKind.COMMAND_WORD,
                TokenKind.WORD,
            ),
            kinds("FOO=1 sudo -E ls -la"),
        )
    }

    @Test
    fun `a later assignment is an argument, not an assignment`() {
        // The app-side regex this replaces would call A=b an assignment: it has no
        // concept of position, and position is the whole rule.
        assertEquals(listOf(TokenKind.COMMAND_WORD, TokenKind.WORD), kinds("echo A=b"))
    }

    @Test
    fun `a semicolon inside quotes is not a separator`() {
        assertTrue(TokenKind.SEPARATOR !in kinds("""echo "x; sudo y""""))
    }

    @Test
    fun `fd prefix merges into its redirect`() {
        // zsh paints `2>&` as one redirection and the trailing `1` as numeric-fd.
        assertEquals(
            listOf(TokenKind.COMMAND_WORD, TokenKind.REDIRECT, TokenKind.FD),
            kinds("cmd 2>&1"),
        )
        // Same digit, no redirect after it: a plain argument.
        assertEquals(listOf(TokenKind.COMMAND_WORD, TokenKind.WORD, TokenKind.WORD), kinds("head -n 2"))
    }

    @Test
    fun `longest operator wins`() {
        assertEquals(listOf("<<<", "foo"), texts("<<<foo"))
        // `>>&!` is a zsh spelling kept in the operator table because the corpus asserts it
        // as ONE redirection span (`redirection.zsh`: `13 16 redirection`). Longest match
        // means it must beat `>>&`, which is why the table is ordered rather than a regex.
        assertEquals(listOf("cmd", ">>&!", "bar"), texts("cmd >>&!bar"))
        assertEquals(listOf("cmd", "&>>", "bar"), texts("cmd &>>bar"))
    }

    @Test
    fun `an array literal does not put its elements in command position`() {
        // `hello` is a value, not a program. Painting it as a command would make an
        // approval card readable but wrong.
        assertEquals(
            listOf(TokenKind.ASSIGN, TokenKind.WORD, TokenKind.WORD, TokenKind.ARRAY_CLOSE, TokenKind.COMMAND_WORD),
            kinds("A=(hello world) ls"),
        )
        // The word after the literal IS the command — `A=(x) ls` runs ls.
        assertEquals(TokenKind.COMMAND_WORD, kinds("A=(hello world) ls").last())
    }

    @Test
    fun `unclosed quote does not throw`() {
        val tokens = Lexer.tokenize("echo 'oops")
        assertEquals(TokenKind.SINGLE_QUOTED, tokens.last().kind)
    }
}
