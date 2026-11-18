# DENY with a typed reason — the long-press sheet

A second reject path that records *why*, and sends the why to the caller. The plain DENY keeps
its one tap, because the reject path is the one a half-asleep human has to hit correctly.

Locked in chat before any code was written. Every decision below was a question asked and
answered; this document exists so the next change has to argue with the reasoning, not the
shape.

---

## The findings

**What the reason does.** It travels. The 403 `detail` becomes the typed words, with `error`
still `"denied"`. A client that maps `denied` is unaffected; a client that prints `detail` gets
the why. This is not a new pattern — the `detail` already varies by cause, because a locked
phone answers `"gate unavailable: screen off or device locked"`.

**Why not a second button.** The item's title asked for one, but a third control in the decision
row re-creates the problem that 0.23.0 fixed by separating APPROVE from DENY: three boxes read
as three equal choices. A long-press keeps the row at two controls and puts the reason behind a
deliberate gesture rather than beside a fast one.

**Why the clock cannot just "pause".** `ApprovalGate` waits on `latch.await(maxWaitMs())`, and
that number is also the deadline the caller's socket is held to. A pause that is not reflected
in `maxWaitMs()` gives the caller a `408` while the sheet is still open on screen — the human
types a reason into a request the server already answered.

---

## The decisions

| Question | Answer |
|---|---|
| Placement | Long-press on DENY; short tap unchanged |
| Hint | One caption line under the button row: `hold DENY to say why` |
| Field contents | Always blank. No suggested reasons |
| Submit with nothing typed | Disabled until at least one character |
| When it denies | Immediately on submit |
| The clock | `+30s`, granted ONCE, not stackable on reopen |
| Auto-dismiss | At the extended deadline the sheet closes and the request resolves as a plain `408`; the text is discarded |
| The bar | Jumps up to the new level on extension |
| Where the reason is stored | The 403 `detail`, plus `decision.reason` in the audit record |

**Why `+30s` and not a pause.** A pause is unbounded, and an unbounded pause on the head of a
FIFO queue starves every request behind it: nothing is shown until the sheet closes. Thirty
seconds is enough to type a sentence, and the request still dies on its own if the human walks
away.

**Why the bar jumps.** After an extension the honest reading is "73s left of 90s", which is more
than the "43s left of 60s" it showed a moment earlier. A smooth bar that kept shrinking would be
showing a number that is not true; the jump is the extension being admitted rather than hidden.

---

## The build

1. **`DenyReason` (pure).** The rule that decides the window: `60s`, or `90s` once the sheet has
   been opened. Pure and Context-free so a JVM test can pin the boundary, exactly like
   `ArmGuard` and `CountdownMeter` — a rule enforceable only by watching a phone is a rule
   nobody re-checks.
2. **`ApprovalGate.Pending`** gains `reason: String?` and `extendedOnce: Boolean`, and
   `maxWaitMs()` consults the extension. `latch.await` becomes a re-await loop on the moving
   deadline, because the wait must never end before the window the UI is showing.
3. **`resolveWithReason(requestId, text)`** beside `resolve`. A DENY carrying a reason is still a
   DENY, so it must not be able to approve anything — and `needsPassword` is irrelevant here,
   because a deny is always accepted.
4. **`GateResult.Denied`** gains `reason: String?`. The dispatch site builds the detail from it
   and falls back to `"request denied by user"` when absent, so the existing wording survives for
   every deny that has no reason.
5. **`AuditRecord.Decision`** gains `reason: String`. Additive; written only when present, and the
   reader tolerates its absence — an older log must still parse.
6. **The sheet** is built programmatically like every other screen (this project has no
   `res/layout/`). A paper surface with one input, its text colours set explicitly, and a submit
   that is disabled until non-empty. Long-press is attached to the DENY control from item 2 —
   the same button, no second factory.

## What this is not

- **Not a notify-back channel.** There is no mechanism to push the reason to a waiting agent, and
  this does not add one. The caller learns the reason when its request returns.
- **Not a gate-mode change.** `SECURE`/`NOTIFY`, biometrics and the exception button are
  untouched.
- **Not a pause.** Reopening the sheet after the extension is used grants nothing further, and
  the sheet closes itself at the deadline.

## Tests

- `DenyReasonTest` — the window rule: not extended, extended once, and that a second open grants
  nothing.
- `DenyReasonGateTest` — a deny carrying text resolves as `Denied(reason)`; a deny without text
  resolves as `Denied(null)`, so the plain path cannot regress into an empty detail.
- A screenshot state with the sheet open, one with the submit disabled, and one showing the hint
  line under the row — visual claims that a golden can contradict.
