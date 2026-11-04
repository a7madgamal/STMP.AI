# SHELL-HIGHLIGHT-PLAN.md — a zsh-compatible shell lexer, and the SSH card that uses it

Status: **PLANNED, not built.** No code exists. `Q` items are open and need Mido.

Mido's asks, in order, on 2026-11-04:

> *"cleanup how the commands field look / split each ; in a new line / search for a highlight
> library to color the command, arguments and other types differently"*

> *"i want a modern kotlin native code that's works as a separate heavily tested, easily imported
> open-source library. zsh has a good standard so let's be compatable with it if possible"*

Plus one instruction that overrides all the others:

> *"don't reinvent the wheel. how did other famous apps solve this"*

---

## 1. What the survey actually found

Three findings drive the whole design, and each one closed off an easier path.

**Terminal emulators do not highlight shell input.** Termux, jackpal's Android Terminal Emulator,
iTerm: they render ANSI SGR sequences *emitted by a running program*. There is no input buffer at
that layer, so there is nothing to copy. This is why a "how does Termux do it" search returns
nothing useful.

**Shells highlight because they already own a parser.** fish tokenizes (`src/tokenizer.rs`) into an
AST, then assigns *roles* to nodes. zsh-syntax-highlighting hooks ZLE and re-lexes with zsh's own
lexer. Neither writes a highlighter; both write a *mapping from parse result to style*. That is the
wheel worth reusing.

**Warp is the only genuine reimplementation**, and only because its custom input editor cannot use
ANSI escapes. Its parser is loosely based on Nushell, and is reused for Command Inspector,
autosuggestions and completions. Same lesson: one parser, several consumers.

**The Android library question is closed, with evidence.** `io.github.bonede:tree-sitter-bash`
exists on Maven Central (0.25.1), but its bundled natives are `aarch64-linux-gnu`, `x86_64-linux-gnu`,
macOS and Windows binaries. `strings` shows `GLIBC_2.17` symbols — the core jar's `.so` files are
glibc-only and will not load on Android's bionic. The bindings that *do* work on Android
(`com.itsaky.androidide.treesitter`, used by AndroidIDE and sora-editor) publish grammars for
Java, JSON, Kotlin, Logs, Python and XML — **no bash**. Getting tree-sitter-bash onto the phone
means compiling the grammar with the NDK and adding the first `.so` files to an app that has none.

Rejected: `KodeHighlighter` (no shell rulebook, JitPack-only), `CodeView-Android` (HTML font-tag
injection), `tiagohm/CodeView` (a full editor widget).

---

## 2. Locked decisions

| # | Decision | Value |
|---|---|---|
| 1 | Grammar parsed | **bash / POSIX** — that is what runs on the hosts over SSH |
| 2 | Style vocabulary | **zsh-syntax-highlighting's keys, verbatim** (~50 names) |
| 3 | Region format | **zsh's** `start end style`, 1-indexed, inclusive of both ends |
| 4 | Build target | **JVM/Android only.** No KMP, no Kotlin/Native, no JNI |
| 5 | Dependencies | **Zero.** No runtime dependency at all |
| 6 | Where it lives | Gradle module `:shellhl` **inside this repo**; extract to its own repo later |
| 7 | Android coupling | **None.** `android.*` import is forbidden in the module |
| 8 | Split of concerns | One artifact, two packages: `lexer` (tokens), `highlight` (regions) |
| 9 | Public entry points | `tokenize()` for structure, `highlight()` for colour |

Decision 1 is a deliberate limit, stated plainly: a zsh-accurate parser would be modelling a shell
that is not the one executing the command. See §9.

---

## 3. What the library is, and what it is not

**Is:** a pure function. `String → List<Token>` and `String → List<Region>`. No I/O, no filesystem,
no `PATH` lookup, no Android, no globals, no mutable state. Deterministic; same input, same output,
always.

**Is not:** a renderer, a Spannable, a theme, or a syntax validator. It never decides whether
`foo --bar` is a *valid* invocation of `foo` — only that `foo` is a command word and `--bar` is a
double-hyphen option.

**Is not:** a runtime shell classifier. See §6 — five zsh style keys require asking a live shell,
and this library answers "unknown" for them unless the caller supplies a resolver.

---

## 4. Module layout

```
stmp/
  settings.gradle.kts          + include(":shellhl")
  shellhl/
    build.gradle.kts           kotlin("jvm"), no android plugin, no dependencies
    src/main/kotlin/io/github/a7madgamal/shellhl/
      lexer/
        Token.kt               Token + TokenKind
        Lexer.kt               the scanner
        Reserved.kt            the reserved-word table
      highlight/
        StyleKey.kt            the zsh vocabulary, as a sealed enum
        Region.kt              Region(start, end, style)
        Highlight.kt           tokens -> regions; the mapping rules
        Resolver.kt            the runtime seam
    src/test/kotlin/...
    src/test/resources/zsh-corpus/*.zsh    the 271 ported cases
```

`:app` gains one line: `implementation(project(":shellhl"))`.

**Why a module and not a package inside `:app`.** Extraction later must be a one-line change. As a
module, splitting means: copy `shellhl/` to a new repo, publish, replace `project(":shellhl")` with
a coordinate, delete the directory. As a package inside `:app`, extraction means pulling files out
from under a module that also carries JSch, LiteRT and the whole application — and the module graph
is what makes the "no Android import" rule *enforced* rather than remembered: `:shellhl` cannot see
`android.*` because the Android plugin is not applied to it, so a stray import fails at compile
time.

`build.gradle.kts` carries that rule as a comment at the top, the same way this repo records other
constraints: the reason must survive the person who wrote it.

---

## 5. Public API

```kotlin
// Structure — for anything that needs to reformat, not colour.
fun tokenize(command: String): List<Token>

// Colour — the headline call, the one the README shows first.
fun highlight(
    command: String,
    resolver: Resolver = Resolver.Unknown,
): List<Region>
```

`Token(kind: TokenKind, start: Int, end: Int, text: String)` — half-open `[start, end)`, matching
Kotlin convention. `Region(start, end, style)` — **1-indexed inclusive, matching zsh**, converted at
the boundary so the corpus files can be compared without arithmetic on either side.

The offset convention mismatch is deliberate and must be documented in both files: an off-by-one
between our tokens and zsh's regions is the single most likely bug in the port, and it is silent.

No token carries a style and no region carries a token kind. The two are separate on purpose — a
caller that wants structure should not have to learn a colour vocabulary, and a theme should not
depend on how the lexer happened to slice a word.

---

## 6. Style keys and the resolver seam

Keys are adopted verbatim from `highlighters/main.md`. Not a subset, not renamed, not "cleaned up" —
the whole point of A over B is that an existing zsh theme file works unchanged.

**Those with a runtime dependency, and therefore unreachable by pure parsing:**

| Key | How zsh decides | Our behaviour |
|---|---|---|
| `command` / `builtin` / `function` / `alias` / `hashed-command` | `type -w` against the live shell | `Resolver.resolveCommand()` |
| `path` / `path_prefix` / `path_pathseparator` | file existence on disk | `Resolver.classifyPath()` |
| `autodirectory` | `AUTO_CD` option + directory exists | always absent |
| `rc-quote`, history expansion, `setopt INTERACTIVE_COMMENTS` | shell options | option flags on the call, default off |

```kotlin
interface Resolver {
    fun resolveCommand(name: String): CommandKind   // Builtin, Function, Alias, External, NotFound
    fun classifyPath(text: String): PathKind        // Existing, Prefix, NotAPath

    object Unknown : Resolver   // every command External, every text NotAPath
}
```

**Two behaviours, and the difference matters for the app.** `Resolver.Unknown` maps a command-position
word to `command`. That is *optimistic* — it colours an unknown binary like a valid one rather than
marking it `unknown-token` (red). This is chosen for the phone: a card where every command renders
in the error colour is worse than useless, and the phone has no way to check a remote `PATH` anyway.
`unknown-token` is emitted only when a resolver explicitly returns `NotFound`.

This is a real design choice, not an implementation detail, and it is the one place where "zsh
compatible" is knowingly approximate. Recorded here so it is not rediscovered as a bug.

**Env-assignment detection is positional, not regex.** `FOO=bar cmd` is an `assign` only in command
position; `echo FOO=bar` is a plain argument. The existing `SudoCommand.ENV_ASSIGN` regex is
command-position-agnostic and would misclassify the second — the lexer decides by position, which
is what zsh does.

---

## 7. The golden corpus

zsh-syntax-highlighting ships **271** files in `highlighters/main/test-data/`. Each is:

```zsh
BUFFER=':; pwd &! ls'

expected_region_highlight=(
  "1 1 builtin"              # :
  "2 2 commandseparator"     # ;
  "4 6 builtin"              # pwd
  "8 9 commandseparator"     # &!
  "11 12 command"            # ls
)
```

That is exactly our `Region` format, already written down, already reviewed upstream. Porting them
as `src/test/resources/zsh-corpus/*.zsh` gives a real regression suite instead of 271 tests invented
by hand.

**Licence.** BSD-3-Clause (`COPYING.md` in that repo). The per-file copyright header must be kept
intact — every corpus file already carries it — and `THIRD-PARTY-NOTICES.md` gains an entry. That
file's own rule applies: it is checked by `ThirdPartyNoticesTest`, and a notices file that is stale
is worse than none.

**Two tiers, because pretending otherwise would be dishonest.** Many cases call `type -w`; the
corpus was written against a real zsh. Running them all with `Resolver.Unknown` would fail dozens
for reasons that are not parser defects.

- **Tier 1 — resolver-free.** Every purely syntactic key: quotes, operators, redirects,
  expansions, assignments, comments, reserved words, fds, globbing. These must pass **exactly**,
  with `Resolver.Unknown`. This is the tier that proves the lexer.
- **Tier 2 — resolver-seeded.** Command-word classification, run with a `TableResolver` built from
  the commands the corpus actually uses (`ls`, `pwd`, `:`, `echo`, …). Legitimate: it is what the
  shell would answer, with a fixed table instead of a `PATH` walk.

Cases that depend on shell options (`RC_QUOTES`, `INTERACTIVE_COMMENTS`, `AUTO_CD`) are ported and
run with the flag set to what the case assumes, or marked skipped with the reason — never silently
dropped. A skipped case is a documented gap; a deleted one is a lie.

---

## 8. What STMP does with it

### 8.1 The renderer bug this exposed

`ApprovalActivity.row()` sets `mono = style == MONO || style == WARN`. `Style.LONG` therefore renders
in the **handwritten STMP face** — so the SSH command row, the one the entire safety argument rests
on, is currently drawn in the least legible font in the app. That is a live defect, independent of
this feature.

Fix: add `Style.CODE` → monospace, selectable, wrap-preserving, and move the SSH command row to it.
`LONG` stays for prose (message bodies, event descriptions), which is what it was for.

### 8.2 Splitting on `;`

Token-driven, not string-driven. Line breaks are inserted after tokens whose kind is a separator:
`SEPARATOR` (`; \n &`), `AND_IF` (`&&`), `OR_IF` (`||`), `PIPE` (`|`, `|&`). The separator itself
stays visible at the end of its line.

The forbidden approach is `command.split(";")`, which breaks on `echo "x; sudo y"` and on
`awk -F';' …`. `SudoCommand` already documents why — its `segments()` honours quotes for exactly
this reason, because a false positive there leaks a sudo password. The lexer generalises that
scanner instead of adding a second, dumber one beside it.

### 8.3 Colour

`highlight(command)` → `List<Region>`, applied in the app as `ForegroundColorSpan` over a
`SpannableStringBuilder`. The library never sees a `Spannable`; the mapping from style key to
`Theme` colour lives in the app, where the paper palette already is.

Proposed palette (ink analogues, consistent with `Theme`'s existing re-tints):

| Key | Colour |
|---|---|
| `command`, `builtin`, `function` | `SUCCESS` |
| `single-/double-/dollar-quoted-argument` | `WARNING` |
| `redirection`, `commandseparator` | `ACCENT` |
| `assign`, `globbing`, expansion delimiters | `ON_SURFACE_VARIANT` |
| `comment` | `ON_SURFACE_VARIANT`, italic |
| `unknown-token` | `DANGER` |
| `default` | `ON_SURFACE` |

### 8.4 After integration, `SudoCommand`

`segments()` becomes a thin adapter over `tokenize()`. Its doc comment is emphatic that a naive
split is a *password leak*, not a nuisance — so this is a behaviour-preserving refactor with
`SudoCommandTest` as the gate, and it must not land in the same commit as anything else.

---

## 9. Risks, and the one that needs a decision

### Q1 — RESOLVED (Mido, 2026-11-04): the render is a display copy

`SshPlugin.describeRows` states the invariant:

> *"It is rendered from the same string that goes to `channel.setCommand`, with no quoting,
> trimming or escaping in between — so what the human reads is what the far end executes,
> character for character."*

Mido's ruling:

> *"this rendering shouldn't touch or affect what we send, all this is purely a formatted copy, so
> it doesn't matter"*

The point the draft missed: the display is *derived from* the command, never a rewrite of it.
`channel.setCommand(command)` receives the caller's original string, and nothing about how a row is
drawn can reach it. Line breaks in the render are display-only; the wire string is byte-identical to
what arrived.

So the invariant is **restated to say what it always meant**, and the restatement is narrower than
the old sentence, not looser:

> *"The wire string is exactly the string the caller sent — nothing in this card can alter it. The
> render shows every character of it, in order, with nothing truncated, ellipsised, reordered or
> hidden. The only addition is line breaks at token boundaries, for legibility."*

**What still binds the implementation:** the renderer may only *insert*. No truncation, no `…`, no
dropped prefix, no case folding, no collapsing of repeated separators. Those would break the claim
for real, because the human would be approving something they were not shown — the precise failure
the original sentence existed to prevent. A line break adds; truncation removes.

Implementation note: the row builder takes `command: String` and `SshExec.run` receives that same
value. The pretty form is produced inside the row builder and never assigned back. Adjacent locals
with similar names (`command`, `display`) are the one place a careless edit could invert this, so
they must be named distinctly.

### Q2 — RESOLVED (Mido, 2026-11-04): the corpus is committed

271 files, ~600 KB with headers, into `shellhl/src/test/resources/zsh-corpus/`.

> *"commit"*

They are a genuine asset and BSD-3 permits redistribution with the notice kept. Each file already
carries its own copyright header, so the notice travels with the data; `THIRD-PARTY-NOTICES.md`
gains an entry naming the source, the licence and the copyright line.

The alternative — a build-time fetch — was rejected for the reason a test suite exists at all: a
test that needs the network is a test that sometimes does not run, and this box is frequently
offline or memory-constrained. The 600 KB is paid once, in a git object store that is already
large.

### Other risks, smaller

- **Offset conventions.** Ours half-open 0-indexed; zsh 1-indexed inclusive. One conversion, one
  place, tested at the boundary.
- **RAM.** This box builds with `-Xmx1536m` and `kotlin.compiler.execution.strategy=in-process`
  because forking a Kotlin daemon fails under memory pressure. A second module adds configuration
  and a compile task; keep the module's test run out of any full-project task.
- **Scope creep.** The corpus will suggest features bash does not have. Decision 1 holds: we speak
  zsh's vocabulary, we read bash's grammar, and a zsh-only construct is a documented gap, not a
  backlog item.

---

## 10. Sequence

1. **Module skeleton.** `:shellhl`, `settings.gradle.kts`, empty `build.gradle.kts` with the no-Android
   rule. Verify `:app` builds unchanged.
2. **Lexer + Tier-1 corpus.** Tokens, then the syntactic mappings, against the resolver-free cases.
   This is the bulk of the work and the point at which "heavily tested" is either true or not.
3. **Resolver + Tier-2.** `TableResolver` in tests only.
4. **STMP renderer.** `Style.CODE`, the `LONG` bug fixed, palette mapping.
5. **The card.** Line breaks per §8.2.
6. **`SudoCommand` refactor** onto `tokenize()`, alone in its own commit.
7. **Version bump + CHANGELOG.**

Steps 1–3 are invisible to the user; 4–6 are what Mido asked for on 2026-11-04. They can be
delivered in that order without ever leaving the card worse than it is today.

---

## 11. Non-goals

- Not a zsh parser. No `=(...)`, no history expansion, no glob qualifiers, no `setopt` modelling.
- Not a shell. No execution, no expansion, no `PATH` resolution.
- Not a validator. Unbalanced quotes produce an `-unclosed` style, not an exception.
- Not a renderer. No `Spannable`, no `TextView`, no colour values anywhere in the module.
- Not published yet. Maven Central is a later step, after the API has survived contact with the app.
