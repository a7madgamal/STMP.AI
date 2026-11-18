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
| Submit with nothing typed | Disabled until at least one character (and the gate refuses blank text too) |
| When it denies | Immediately on submit |
| The clock | 90 seconds, ONE constant — no extension logic at all |
| Auto-dismiss | Never extends; the modal resolves at 90s like it did at 60s |
| Where the reason is stored | The 403 `detail`, plus `decision.reason` in the audit record |

**Why 90 and not a pause or an extension.** A pause is unbounded, and an unbounded pause on the
head of a FIFO queue starves every request behind it. An extension that is granted when the sheet
opens looks cheaper than that and is not: the latch is already waiting when the sheet can be
touched, so a moving deadline needs a re-await loop, a live re-read in the modal's tick, and a
second source of truth for "time left" — three ways for the bar to disagree with the thing that
eventually kills the request. A flat 90 costs thirty seconds on a modal nobody touches, and buys
one number that the bar, the latch and `/help` all read.

---

## The build

1. **`ApprovalGate.TIMEOUT_SECONDS` becomes 90.** One constant: the auto-deny latch, the draining
   bar and `/help` all read it, so the bar cannot disagree with the deadline it displays.
2. **`GateResult.Denied` gains `reason: String?`**, and `Pending` gains `denyReason` — the typed
text, or null for a plain deny. Null is "no reason given", never an empty string.
3. **`resolveWithReason(requestId, text)`** beside `resolve`. A reasoned deny is still a deny: it
   goes through the same latch and the same `approved = false`, so there is no second exit from
   the gate. It refuses blank text rather than storing it.
4. **The dispatch site** builds the 403 `detail` from the reason, falling back to
   `"request denied by user"`. `error` stays `denied`, so a client that maps the code is unaffected.
5. **`Decision` gains `reason`**, written to the record only when present. Additive; an older log
   still parses, and its absence means "none given".
6. **The sheet** is built programmatically like every other screen (this project has no
   `res/layout/`): a paper block with one single-line input, explicit text colours, and a submit
   that is disabled until the field is non-blank. Opened by `setOnLongClickListener` on the DENY
   control, returning `true` so the release does not also fire the plain deny.

## What this is not

- **Not a notify-back channel.** There is no mechanism to push the reason to a waiting agent, and
  this does not add one. The caller learns the reason when its request returns.
- **Not a gate-mode change.** `SECURE`/`NOTIFY`, biometrics and the exception button are
  untouched.
- **Not a pause.** Reopening the sheet after the extension is used grants nothing further, and
  the sheet closes itself at the deadline.

## Tests

- `DenyReasonTest` — the gate half: a plain deny carries no reason, a reasoned deny stores its text
  and still denies, blank text is refused (so the request stays open), and the 90s constant is
  pinned because the bar, the latch and `/help` all read it.
- A screenshot at `approval__deny_reason` with the sheet open, asserted to be hidden BEFORE the
  long-press — the state that could not be checked from source, and the same class of miss that
  shipped the invisible DENY drain.
- `LogDetailScreenshotTest` now installs a store whose retention cannot reach the fixture dates, so
  goldens that print a fixed date stop failing on their own after seven days.
