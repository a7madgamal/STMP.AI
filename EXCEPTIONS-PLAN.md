# EXCEPTIONS-PLAN.md — the modal's third button, and renaming the rules screen

Status: **PLANNED, not built.** Decisions below are proposals; `Q` items are open and need Mido.

Mido's ask (2026-10-19):

> *"in the modal I want a third button to automatically add an exception for the current request.
> using our same system for per-pair rules, and change this stupid name while at it"*

Two things: a third decision button in the `SECURE` modal that writes a rule for the request in
front of you, and a rename of "PER-PAIR RULES".

---

## 1. Naming

**Recommendation: `EXCEPTIONS`.** One word for one concept — the button adds an exception, the
screen manages exceptions, and it explains itself ("everything asks, except these"). It also stops
describing the *implementation* (`per-pair`) and describes the *intent*.

The nuance to accept: the screen lists every rule, not only the ones that differ from the default.
That is fine, because the default **is** "ask" (`AUTOMATIC` with no rule → `SECURE`), so every
rule is an exception to it.

Alternatives if `EXCEPTIONS` reads wrong: `OVERRIDES` (accurate: overrides the default),
`RULES` (plain, honest, boring).

Renamed in:
- `SettingsActivity` heading + its class doc ("Where per-pair rules are authored").
- `MainActivity`'s entry button label.
- `gate-policy.md`, `phonebridge-api`, `approval-modal.md`, `known-status.md` — every "per-pair"
  mention. Grep the whole skill collection for the old term, not just the code.

## 2. What the button does — `Q1`

Two candidate behaviours, and this is the first thing to settle:

- **(A) Set the rule AND approve this request** — one tap, the "always allow" convention every
  permission dialog uses. The exception implies consent for this class, and the human is standing
  right there.
- **(B) Set the rule only** — the human still taps APPROVE separately. Cleaner separation (no
  single tap changes policy *and* decides a request), but two taps for one intent.

**Recommendation: (A)**, because it is what "automatically add an exception for the current
request" reads as, and it is fail-safe — see §5, an exception can never auto-approve.

## 3. Which pair, and which mode

- **Scope: the exact `(plugin, verb)` of the current request.** Narrowest exception, matching "for
  the current request". Broadening to the plugin's `ANY` is a deliberate act, done on the
  Exceptions screen. (`Q2`: offer ANY as a long-press / second option, or keep it screen-only?)
- **Mode: the one that is NOT the current resolved mode.** If the request was resolved `SECURE`,
  the exception is `NOTIFY` (quiet this pair); if `NOTIFY`, the exception is `SECURE` (make it
  loud). That is what "exception to the current behaviour" means, and it is one pure function:
  `exceptionModeFor(resolved: Mode): Mode`.
- The label states the mode it will set, so it never lies: **`ALWAYS NOTIFY`** / **`ALWAYS ASK`**
  (`Q3`: wording).

## 4. The catch — rules are INERT unless the switch is `AUTOMATIC` — `Q4`

`resolve()` only consults the rule table when `global == AUTOMATIC`. Under a forced `SECURE` or
`NOTIFY`, **every rule is ignored**. So if Mido is on `SECURE` and taps "add an exception", the
button would write a rule that changes nothing — a button that lies, which this project does not
ship.

Three honest options:

- **(i) Flip to `AUTOMATIC` as part of the tap.** The user's real intent ("everything as now,
  except this pair") *is* the `AUTOMATIC` model, so the button sets `global = AUTOMATIC` **and**
  adds the rule, both audited. Big side effect for one tap, but it is the only way the button
  keeps its promise.
- **(ii) Show the button only when the switch is `AUTOMATIC`.** Conservative, no hidden global
  change — but then the feature is invisible exactly when the user is annoyed by a `SECURE` modal.
- **(iii) Show it always; under a forced mode, the tap writes the rule and says it will apply
  once the switch is `AUTOMATIC`.** Honest, but a rule that does nothing yet is still a rule that
  does nothing yet.

**Recommendation: (i).** It is the only option where one tap does what the label says, and both
halves are audited so the log explains the switch move.

## 5. Security rules (non-negotiable)

- **The floor holds.** `Rule.ALLOWED_MODES` is `NOTIFY | SECURE`, so an exception can at most make
  a pair **quiet** (5s overlay). It can never auto-approve, and no tap on this button can create a
  path that skips a human. This is the invariant the whole policy layer rests on.
- **Every exception is audited** as a `POLICY_CHANGED` record via `PolicyAudit.recordRule(before,
  after, global, source)`, with `source = "modal"` — distinct from `"settings"`, so the log shows
  *where* a policy decision came from, not just that one happened. Under option (i), the switch
  move is recorded too.
- **The write is read-modify-write.** `Policy.withRule(...)`, never a fresh `Policy` — the exact
  bug `applyGate` already paid for once (a fresh object silently deleted every sibling rule).
- **`PolicyStore.set` only** — atomic temp+rename, cache updated after the bytes land.
- **Biometric: same gate as APPROVE.** The button approves a request, so it must clear the same
  fingerprint (and, once a vault exists, the same crypto-bound prompt). One prompt, not two.
- **Fat-finger protection.** The 0.23.0 pass deliberately put 12dp between DENY and APPROVE so
  they are never one slip apart; a third button *inside* that row would re-introduce the crowding
  it fixed. So the exception button goes on **its own full-width row above the DENY/APPROVE pair**
  (`Q5`: above, or below?), in the secondary style — visually distinct from APPROVE, so it cannot
  be hit by a reflexive tap meant for APPROVE. `Q6`: does it arm with the same delay as APPROVE?

## 6. Plumbing — what changes where

- `policy/` — one pure helper `exceptionModeFor(resolved: Mode): Mode`, unit-tested. The rule
  itself is written with the existing `withRule`.
- `ApprovalGate` — the modal needs the pair. `await(...)` gains `plugin: String` and
  `verb: Verb`, carried on `Pending` (the gate already receives `resolved.plugin.id` /
  `resolved.route.verb` at its call site in `BridgeService`). `GateResult` gains a third outcome,
  e.g. `ApprovedWithException(mode)`, so the decision travels back the same way `Approved` does.
- `ApprovalActivity` — the third button, its label from `exceptionModeFor(resolvedMode)`, and it
  resolves the gate with the new outcome. **The Activity does not write policy** — it renders and
  signals; the write happens at the gate/dispatch site, same rule as the rows.
- `BridgeService` — on `ApprovedWithException`, write the rule (via `PolicyStore` + `PolicyAudit`)
  and treat the request as approved. One site, audited.
- `SettingsActivity` / `MainActivity` — rename only.

## 7. Tests

- `PolicyTest`: `exceptionModeFor` maps SECURE→NOTIFY and NOTIFY→SECURE; an exception rule can
  never carry an auto-approve mode (`ALLOWED_MODES` still excludes it); `withRule` replaces rather
  than duplicates; the exact-beats-ANY precedence still holds after a modal write.
- A test that a modal-written rule round-trips through `Policy.parse` — the file must reload to
  the same decision.
- Under option (i): the flip-to-`AUTOMATIC` path is asserted, and the invariant test
  (`no switch value can weaken below NOTIFY`) still passes.

## 8. Open questions

- `Q1` — set + approve, or set only? (§2)
- `Q2` — exact verb only, or offer plugin-ANY? (§3)
- `Q3` — button wording: `ALWAYS NOTIFY` / `ALWAYS ASK`? (§3)
- `Q4` — the `AUTOMATIC` catch: flip, hide, or warn? (§4)
- `Q5` — button above the DENY/APPROVE pair, or below? (§5)
- `Q6` — does the button share APPROVE's arm delay? (§5)

## 9. Done when

- The modal shows a third button whose label names the mode it will set; tapping it writes an
  exact rule for that pair, audited with `source=modal`, and the request resolves the same way it
  does today (per `Q1`).
- The rule can never auto-approve, and a modal-written rule reloads identically after a restart.
- "PER-PAIR RULES" is gone from the app and from every skill that names it.
