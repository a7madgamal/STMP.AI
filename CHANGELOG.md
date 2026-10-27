# Changelog — PhoneBridge

The full history of the PhoneBridge Android app (`com.mido.phonebridge`), from the first
commit to the current build. Chronological: oldest first, so it reads as the story of how the
app got here.

**This file is updated on EVERY commit** (see "Keeping this file honest" at the bottom). If you
changed behaviour, the entry goes here in the same commit — a version bump without a changelog
line is an incomplete change.

Format per entry: what shipped, and **why** — the reasoning is the part that stops a future
change from undoing it. Dates are on each heading; find a release's commit with
`git log --oneline --grep "<version>"` — see the note below on why an entry cannot carry its own
hash.

Current build: **v0.43.12 (build 166)**.

---

## Initial — Add the PhoneBridge Android app
`a57e0ad` · 2026-10-15

The app is born: a native Android HTTP server that runs on the phone so a remote caller over
Tailscale can read notifications and use Gmail.

- Notification bridge + plugin architecture, Gmail plugin, approval gate.
- UI repaint, full notification-extras extraction.
- Android Lint wired into the build.
- Fixed: login-activity manifest crash, and a listener-ref status bug.
- Gitignores Android build artifacts.

Committed as a snapshot **before** ripping out the loopback OAuth flow in favour of Google Play
Services / Credential Manager login.

## Housekeeping — snapshot
`e1f9332` · 2026-10-17

Snapshot taken before a job-finds-digest payload repair (the local unsloth endpoint was down).

## 0.3.3 — `not_found` maps to HTTP 404
`61c23c5` · 2026-10-17

A bad-id pre-fetch (Gmail trash, Calendar delete/update/move) threw a **correct** `not_found`
body but `httpStatusFor` had no entry for it, so it fell through to `500`. Caller-visible:
`500 → 404`, body identical. One missing mapper line, one wrong status.

## Plan — audit log + request policy
`74771e0` · 2026-10-17

The release plan for the two big features. Four isolated releases: R1 audit spine (Route gains a
required verb/scope, gate returns `GateResult` instead of throwing, append-only JSONL day-files,
log screen), R2 policy engine, R3 `NOTIFY`, R4 log UX.

Locked decisions: audit-append failure denies the request; unattended `SECURE`/`NOTIFY` deny
while `AUTO` approves; biometric is `SECURE`-only (supersedes v0.3.1); no hidden rules; log
everything with a derived noise flag.

## Plan — wildcard cascade replaced by global switch + exact rules
`c2422fc` · 2026-10-17

R2 becomes a global gate mode (`AUTOMATIC | SECURE | NOTIFY | OFF`, default `SECURE`) plus an
exact per-pair rule table keyed on `(plugin, verb)` — **no wildcards**. `AUTOMATIC` is the only
value that consults the table; every other value forces its mode on all requests, which is what
makes `OFF` a true panic switch.

Deleted: the specificity tuple, tie-breaks, the effective-mode matrix, the `scope` field on
`Route`, and Q11 (with no wildcards, two rules can never both match).

## Plan — per-pair `OFF` locked as global-only
`64b3cfb` · 2026-10-17

The pair picker stays `default | notify | secure`. Auto-approve is expressible **only** through
the global switch, so an accidentally-set auto-approve row cannot exist.

## 0.4.0 — the audit spine (R1)
`97cb455` · 2026-10-17

Every request now writes exactly one append-only JSONL record before the HTTP response returns,
and every route declares what it does.

- `core/Labels.kt`: `Verb` (5 values), `Origin`, `originOf()`, label `plugin.verb`. `Route` gains
  a **required** verb — not derived from the HTTP method, because `POST /notifications/{id}/action`
  is `EXECUTE` and `POST /gmail/messages/{id}/trash` is `DELETE`.
- `audit/`: `AuditRecord` + `AuditDraft` (one draft per request, written-once guard), `AuditLog`
  (day-files, `File` not `Context`, so retention/corruption handling is JVM-tested),
  `AuditNoise`, `AuditHealth`.
- `ApprovalGate.await()` returns `GateResult` (`Approved`/`Denied`/`TimedOut`) instead of
  throwing, and stamps shown/decided timestamps for the record.
- `serve()` runs one dispatch that sets a terminal outcome on every path, then appends. A failed
  append replaces the response with `500 audit_unavailable` and raises a home-screen banner.
- `LogActivity` + `LogDetailActivity`: newest-first list, noise hidden by default, verbatim
  detail incl. truncation sizes and the decision trace.
- **20 JVM tests.** One caught a real bug: a random id suffix could collide within a millisecond,
  and `line(id)` matches on prefix, so it could return the **wrong** record. Now a deterministic
  counter.

Gating behaviour unchanged: every request still opens the modal, biometrics still required, deny
still `403`, timeout still `408`.

## 0.4.1 — the noise chip label flips with its state
`df299a2` · 2026-10-17

The chip was hardcoded `hide noise`, so it said the same thing whether noise was visible or
hidden — a label that lies half the time. Now it names the action: `show noise` while hidden,
`hide noise` once shown. Caught on the device from two screenshots of the same screen in both
states.

## 0.4.2 — validate before the gate; stop mislabelling bad input
`80665f2` · 2026-10-17

Two device-log bugs, one cause: input validation lived inside route handlers, which run **after**
the approval gate.

1. `?count=-1` opened an approval popup. Input that can only ever produce a `400` should never
   cost a tap.
2. That same request was recorded `HANDLER_FAILED` — the loudest badge — because it failed after
   the gate ran. `HANDLER_FAILED` is deliberately not noise, so a client typo was promoted into
   the signal stream and hid itself from the default view.

Fix: `Route` gains an optional `validate` hook that dispatch invokes **before** the modal is
built. Each plugin reuses the **same** parser in the hook and the handler, so the paths cannot
drift. Failure before the gate is a `4xx` by construction.

The classification itself becomes `AuditClassify`, a pure tested function instead of four lines
inline in a `catch`. It keeps one deliberate asymmetry: a `4xx` arriving **after** a human
approved stays `HANDLER_FAILED`, because that wasted a real decision.

Two sibling defects fixed while wiring it:

- Calendar `require(timeMin < timeMax)` threw `IllegalArgumentException`, escaping as a generic
  `500`. Now a coded `bad_time_range` `400`.
- Gmail `max` was silently clamped (`(toIntOrNull() ?: 20).coerceIn(1,100)`): `max=5000` returned
  100 rows with no clue, `max=abc` returned 20. Now a pre-gate `bad_max` `400`.

**27 tests** (7 new for `AuditClassify`, pinning the exact device-log bug).

## 0.5.0 — the global gate switch, and OFF
`1f0f295` · 2026-10-17

R2, scoped to one thing after the notify cut: the switch and the mode it selects.

The cut's consequence: with only `secure` and `off` available, a per-pair rule could only say
`secure` — which is exactly what "no rule" already means. A rule table with one expressible value
is machinery with no observable effect, so rules moved to R3. Same reasoning removed `automatic`.
R2 ships `SECURE | OFF`.

- `policy/PolicyMode.kt` — `GlobalMode`, `Mode`, `resolveMode`
- `policy/Policy.kt` — strict parse; unknown value = error, not a default
- `policy/PolicyStore.kt` — atomic write (temp + fsync + rename), cached
- `audit/PolicyAudit.kt` — the `POLICY_CHANGED` record
- `audit/AuditId.kt` — id format extracted so policy records cannot collide
- `MainActivity` — the switch card, OFF confirmation, red banner

Design calls worth naming:

- `resolveMode` has **no `else` branch**. Adding `NOTIFY`/`AUTOMATIC` in R3 becomes a compile
  error at that `when` — the compiler asks where the new value goes.
- An unknown `global` string is a **parse error**, not a silent `SECURE`. Downgrading must fail
  loudly; quietly reattaching a gate the user turned off is a change they never asked for.
- A non-empty `rules` array is refused by this build. It cannot honour a rule, so running on the
  file would mean ignoring a decision sitting right there.
- The server refuses to start on an unreadable policy. "Assume secure" is still an assumption.
- The `rules` array is written from R2 and always empty, so R3 needs no file migration.
- `POLICY_CHANGED` is never noise — turning the gate off is the loudest line in the log.

**38 tests** (11 new; the happy path is one, the rest are failure modes).

## 0.5.1 — `GET /debug`, and record the bodies that never parsed
`8945914` · 2026-10-17

Two halves of the same complaint: the phone kept no evidence and exposed no state.

1. A body that failed to parse was answered `400 invalid JSON body` with **zero** record of what
   was sent — `readBody` parsed first and threw, so the draft never saw the bytes. Found by
   testing the malformed path, not by reading code. Fix splits reading from parsing: `readRawBody`
   returns the text as sent, `parseBody` throws. The raw text lands on the draft **before** the
   parse. Applies to empty bodies too.
2. `GET /debug` — a diagnostic report so questions about the phone do not each cost a round trip
   through chat. App/server/storage/account/policy/audit state, the last 50 access-log entries,
   the redacted debug ring, and the route table. **Shape-only** for anything sensitive: lengths
   and booleans, never a token value.

Supporting changes:

- `Route.auditResponseBody` (default `true`). `/debug` returns the log tail, so storing its
  response copies the log into the log — and at the 32 KiB blob cap it eats a large share of the
  20 MB retention budget.
- `Truncation.responseBodyAbsent` — an unrecorded response must not read as an empty one.
- `AuditStore.storageStats()` — on-disk day-files, read fresh.

**42 tests** (4 new). One test was written and then **deleted**: it re-declared the `/debug` route
inside the test to check the opt-out flag, so it would have passed even if the real plugin
changed. A test that cannot fail is worse than no test.

## 0.5.2 — one response-recording path, so OFF cannot bypass the opt-out
`623cadf` · 2026-10-17

Found on device, verifying the `OFF` switch — which is exactly why that switch needed verifying.

`Route.auditResponseBody` was honoured on the gated path and **ignored** on the `OFF` path: that
branch returned early and called `draft.response(...)` directly. Result: `/debug` stored its
entire ~11.5 KB report into the log on **every** call whenever the gate was off.

Measured twice independently: `+1 record, +13612 bytes` and `+1 record, +13846 bytes`, versus
~1 KB for the same record under `SECURE`.

Fix: the decision moved into `audit/AuditResponse.recordResponse()`, called by both paths — one
handler call and one recording site now. A first attempt also worked but put the decision in a
**private** method, which nothing could test and any future branch could bypass; that is the same
defect in a different hat, so it was thrown away for the shared function.

**44 tests.** The old `RouteAuditTest` constructed a `Route` and asserted on the flag — it passed
while the real dispatcher ignored that flag on half its paths. It now calls the same function the
dispatcher calls, including a test that the `OFF` and gated paths produce identical records.

Also device-verified: the `OFF` switch itself (`OFF_APPROVED`, `200`, 376ms, no popup;
`POLICY_CHANGED` on toggle).

## Plan — R3 expanded to full detail
`50194ea` · 2026-10-17

§7 was architecture-level while R1/R2 were fully specced. Worth doing before code: R3 is the first
release where a **stored decision changes behaviour**, so a policy bug stops being a display bug
and becomes a security bug.

Settled: the switch gains `AUTOMATIC` and `NOTIFY` (4 values); `AUTOMATIC + no rule → SECURE`
("silence means ask me"); `NOTIFY` mechanics (separate channel, 5s from POST, silence = approve,
`X` = reject, caller still gets plain `403 denied`); `isAttended()` checked **twice**; biometric
computed as `(mode == SECURE)`; `resolveMode(global, rules, plugin, verb)` is a `find` on a list;
a queue rule (NOTIFY never queues behind the 60s modal).

Caught while writing: the R2 forward-compat test asserting `"AUTOMATIC"` is a parse error becomes
vacuous the moment AUTOMATIC ships — flagged to switch to a genuinely unknown literal. And the doc
still claimed R2 shipped the rule table; it did not.

Plan only. No source touched.

## 0.6.0 — NOTIFY as a draw-over-apps toast, plus exact rules
`6b5ae7b` · 2026-10-17

R3. The first release where a stored decision changes behaviour.

**NOTIFY is an overlay, not a notification.** The plan said "separate channel, IMPORTANCE_HIGH";
Mido overruled it, correctly: a heads-up notification is a system-owned surface that hijacks the
shade, fights DND, and is suppressed while the phone is in use — exactly when a request arrives.
So the prompt is a small overlay drawn with `SYSTEM_ALERT_WINDOW`.

The item: a dark pill, two lines of monospace, a 2dp bar under the text that drains over the
window (the countdown **is** the bar, so the visual and timer cannot disagree), a tiny `x`
(28dp touch box), fade in 180ms, fade out 200ms, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` so it
never takes input.

Items stack up to 4, each with its own window. **Failing to draw is a deny** — if the overlay
cannot be shown, the request is `DENIED_UNATTENDED`. "We failed to ask" and "nobody objected" are
not the same fact, and only one of them is consent.

Policy: exact rules, `AUTOMATIC`, and the rules screen.

- `Rule(plugin, verb, mode)` is exact — no wildcard, no scope, no cascade, so two rules can never
  match one request and there is nothing to rank.
- `AUTOMATIC + no rule → SECURE`.
- Rules may carry `NOTIFY` or `SECURE` only. `OFF` stays global-only: making "no human, no
  fingerprint" settable twelve times invites one accidental row.
- Duplicate pair, unknown verb/mode, wildcard plugin, and `OFF`-as-a-rule are all parse errors.

Found and fixed while building:

- `applyGate` built `Policy(after)` from scratch, which would have **deleted every rule** on a
  switch flip. Rules are carried across now.
- The R2 test asserting `"AUTOMATIC"` is a parse error became vacuous — rewritten to a genuinely
  unknown literal with a comment saying why.
- `NotifyOverlay` had a placeholder-Item hack and a `val text` shadowing `TextView.text`.
- `shownAt` was stamped before `main.post` ran, inflating every recorded duration.

**56 tests.** The rules screen exists because `AUTOMATIC` without it is a switch position that
does nothing.

## 0.6.1 — NOTIFY is a queue, not a stack
`6352258` · 2026-10-17

Mido's device report was one line and it killed the design: *"3 at once I saw only one timer.
queue is better."*

Every in-flight prompt was drawn at the same coordinate, so three simultaneous requests produced
**one** visible card with **one** countdown. Two windows expired behind a bar the human could not
attribute to them. The stack was wrong twice over: it hid requests, **and** it burned their clocks
while unreadable. My own note had said "stacking confirmed" because I measured durations instead
of looking at the screen.

Reversed: one card on screen at a time, the rest wait invisibly in arrival order. A waiting
request has **no clock** — its window starts when it reaches the screen.

Ordering and capacity moved into `notify/NotifyQueue.kt`, which is **pure** (no Android, no views,
no clocks) — the broken part is now the part a unit test can prove. `NotifyQueueTest`, 12 tests,
covering the exact case that failed.

**No eviction, ever.** 0.6.0 dropped the oldest item when out of room, denying a request the human
had been shown. Now the line holds one on screen + three waiting; a fourth is **refused**, and the
newcomer is the one denied.

Also: rounded corners (14dp, outline provider), bottom-left anchor, draggable (8dp slop). The
countdown became the card's **background** so the whole card drains.

Caught while wiring: `ESCAPE_MS` was a hardcoded 15s, **shorter** than the worst legitimate wait
once four prompts are in the system (4×5s) — a request merely waiting its turn would have been
denied as "outcome never arrived". Now derived from the queue's own capacity.

**68 tests** (up from 56).

## 0.6.2 — the full-screen card, fixed
`de6ede6` · 2026-10-17

Mido saw it: *"a VERY long card, almost full screen height."*

The countdown fill was a child View added as `MATCH_PARENT × MATCH_PARENT` into a `FrameLayout`
whose height is `WRAP_CONTENT`. In a `WRAP_CONTENT` FrameLayout, a `MATCH_PARENT` child forces the
parent to match **its** parent — the full window. The card stretched to fill the screen.

Fix: move the drain from a child view into the card's own background as a `LayerDrawable`. A
background layer cannot participate in layout, so the card's size is decided by the text row alone
— 232dp wide, one or two lines high.

Also fixed the intermittent "1ms flash": the fade-out translated by `card.height / 4`, and when a
prompt ends before first layout, height is 0 — a zero-distance translate. Now a fixed 10dp.

**68 tests.** The layout bug was invisible to them for a reason: unit tests prove ordering and the
clock; they cannot prove the pixels.

## 0.7.0 — `GET /logs`, and the overlay starts describing itself
`5594131` · 2026-10-17

`GET /logs` — the logcat endpoint. A separate route from `/debug` deliberately: `/debug` answers
"what is the state", this answers "what happened, line by line".

- **Own process only.** Since Android 4.1 an app may read only its own UID's log lines, with no
  permission — so no manifest permission, and no way to leak another app's logs.
- `?lines=` (default 200, cap 500) and `?filter=`, both validated **pre-gate**.
- `logcat -d` (dump-and-exit), `-t` bound, char-bounded reader (256 KiB).
- A failure to read is a failure, not an empty list: `500 logcat_failed`.

**The overlay now describes itself.** Mido reported the prompt as a "1ms flash" twice, and both
times the app's in-memory ring could not say why. So the overlay logs its lifecycle permanently:
`attached`, `laid out` (with w/h/alpha/vis), `settle` (with reason + shownMs). That is the
difference between a mystery and a measurement.

## 0.8.0 — the invisible card, and a real fix for it
`81fb38b` · 2026-10-17

`/logs` paid for itself on the first run:

```
notify[5001] laid out w=435 h=86 alpha=0.0 vis=0
notify[5001] settle reason=EXPIRED shownMs=5015
```

Read it carefully: 435×86 px is 232×46 dp — **the card is small, the 0.6.2 layout fix worked**.
It lived 5.27s in the window manager — **the clock is correct**. It settled `EXPIRED` — the right
path. So the window is present, correctly sized, for the full five seconds — and the human still
sees a 1ms flash. **The card is there and invisible.** Both earlier fixes were about size and
timing, and size and timing were already fine.

Two candidates, both addressed:

1. `clipToOutline` (**removed**) — its outline provider read `view.width`/`view.height`, and an
   outline computed before first layout is an empty round-rect, which clips the entire card to
   nothing.
2. The fade-in never finishing — if that animator does not run, alpha stays 0 and the card is
   invisible for its whole window.

Why this is a real bug and not polish: **silence means APPROVE in this mode.** A prompt nobody can
see approves the very request it was supposed to ask about. An invisible card is a security defect
that happens to look like a UI defect.

A safety net forces the card visible if the fade-in has not completed, and the log says it had to
be forced. Four probes at t0/t100/t400/t2000 record alpha, isShown, visibility, size, and
`getGlobalVisibleRect`.

## 0.9.0 — no animators; the card and its countdown, driven by a clock
`869701b` · 2026-10-17

Three defects, one root cause, and it took `/logs` to see it:

```
notify[5001] t100  alpha=0.0 isShown=true onScreen=true rect=Rect(0,0-435,86)
notify[5001] fade-in STALLED alpha=0.0 -- forcing visible
```

Correctly sized, on screen, in the right place — and **the fade-in NEVER RAN**. Alpha sat at 0.0
through 100ms of a 180ms fade. The 0.8.0 safety net is the only reason anything was visible at
all, which is why Mido's next report was *"I see it, much better but we lost the progress"*.

Root cause, finally stated plainly: the card's entire appearance depended on animators actually
running, and on this device they do not run reliably. **Every failure was silent.** An animator
that never fires looks exactly like one that finished.

That is not cosmetic: silence means APPROVE, so three releases of "1ms flash" were three releases
of requests being auto-approved behind an invisible prompt.

The fix removes the dependency. **One main-thread tick, ~30fps**, reads
`System.currentTimeMillis()` and sets everything directly:

- fade-in alpha computed from elapsed time, not animated
- countdown `level` computed from **remaining** time (deadline − now), so a missed tick cannot
  make it drift
- fade-out and removal in the same tick

Two supporting changes from evidence: the drain is a `ClipDrawable`, not a layer inset
(`setLayerInsetRight` mutated bounds without reliably repainting — the frozen bar); and removal is
owned by the tick, so an animator that never fires cannot strand a card forever.

A `midpoint level=` log line proves the countdown moved rather than asking anyone to eyeball it.

## 0.10.0 — verb badge on the NOTIFY card
`cde6d77` · 2026-10-17

The badge: a small coloured tag reading `read`/`write`/`edit`/`del`/`exec`, authored from the
route's verb, never inferred from the HTTP method. Coloured by **consequence**:

- `read` green — nothing changes
- `write`/`edit` amber — something changes
- `del` red — something is destroyed
- `exec` amber — deliberately middle, since it covers both "send a message" and "act on a
  notification"

Light fills with near-black text, because the card is dark. The badge hugs its text
(`WRAP_CONTENT`, 5dp padding, 4dp corner) — a stretched badge would read as a header bar.

Padding 12/8/6/8 → 8/6/4/6, card 232dp → 216dp. The verb is threaded through the gate rather than
looked up on the card: the UI renders, it does not compute.

## 0.11.0 — error codes are total, and a 200 that was really a failure
`22f1dec` · 2026-10-17

Two bugs, both from one device log after Mido cancelled a prompt. The `403` was good news:
`NOTIFY_REJECTED` captured on device for the first time (`logs.read 403 1903ms`, card log
`settle reason=TAPPED shownMs=1850`).

But right before it, two records:

```
HANDLER_FAILED logs.read 500 2
HANDLER_FAILED logs.read 500 2
```

My own bad input: `/logs?lines=600` (cap 500), `bad_lines` thrown correctly — then mapped to
`500`, because it had no entry in `httpStatusFor`. A caller's typo was recorded as a **server
fault**, and `HANDLER_FAILED` is deliberately not noise, so two fake incidents landed in the log.

Fix 1: `ErrorCodes` — every code in one total map, pure Kotlin. Enforced rather than hoped for:
`ErrorCodesTest` walks the source tree, finds every `PluginError("...")` literal, and fails the
build if any code is missing. It also pins the direction: caller-fault codes are `4xx`, our-fault
`5xx`, unknown is `500` and never `4xx`.

Fix 2, found while writing fix 1: `statusOf(Int)` ended with `?: Response.Status.OK` — an unmapped
status silently became **200 OK**. A failure reported as success is the worst direction, and it had
sat there since R1. Unknown now answers `500`.

**73 tests.** `ErrorCodesTest` caught a real thing during this release: its first version called
`android.util.Log`, not mocked in JVM tests. The alarm moved behind a hook the app wires at
startup. A guard that cannot run is not a guard.

## 0.12.0 — NOTIFY is the floor; every auto-approve path removed
`df972bd` · 2026-10-17

Mido: *"you know what, I like notification mode, let's remove silent from the whole app
everywhere, notify is the minimum."* He had asked for a per-pair `silent` option, then reversed
it — so instead of **adding** a fourth mode, this **removes** the one auto-approve path.

Gone: `GlobalMode.OFF` (switch is now `AUTOMATIC | SECURE | NOTIFY`), `Mode.OFF`, the
unattended-approve branch (`isAttended` is now unconditional), the OFF confirmation dialog and the
persistent red banner.

**The invariant this buys: the switch can only move toward asking more.** No value and no
configuration can skip a human.

Two things deliberately kept:

- `AuditOutcome.OFF_APPROVED` survives, read-only — deleting it would turn honest history into
  apparent corruption. Never rewrite the log.
- A legacy `"OFF"` in `policy.json` loads as `NOTIFY`. A file written by ≤0.11.0 must not brick
  the app, and there is one safe direction: `OFF` meant "no gate", so it maps to the floor that
  still asks a human.

New test: **`no switch value can weaken a request below NOTIFY`** — loops `GlobalMode.values()`
and fails if any resolves below `NOTIFY`, so reintroducing an auto-approve value fails the build
until someone reopens the question on purpose.

**76 tests.**

## 0.12.1 — end-of-fade flash: remove+promote atomically
`dce196e` · 2026-10-18

Attempted the flash as a remove/promote handoff race. **The device disproved it** — see 0.12.2.

## 0.12.2 — the flash was window alpha; fade the window, not just the view
`1b8514a` · 2026-10-18

Mido: *"weird flashing near the notification end after the fade-out ends"*, then after the 0.12.1
fix: *"same, if fades out to 0, then a flash of full opacity card appears for a moment before card
disappears"* — so the remove/promote ordering was **not** the cause.

**Real cause:** the fade-out drove `View.alpha` while the **window's** own alpha stayed 1. The
compositor uses the window alpha, so the instant `removeView` destroyed the surface, one frame of
the full-opacity window buffer re-composited — "fade to 0, flash, gone".

Fix: drive `WindowManager.LayoutParams.alpha` through `updateViewLayout`, plus a ~3-frame commit
grace at alpha 0 before removal. A `teardown windowAlpha=0.0 viewAlpha=1.0` line logs it, so the
log proves the window reached 0 rather than asking the eye to be the instrument.

Three flash families now exist and must not be conflated: animator dependence (0.9.0), the
`MATCH_PARENT` child (0.6.2), and window alpha at teardown (0.12.2).

## 0.13.0 — timeZone is required on every calendar call, validated
`f1aec18` · 2026-10-18

Built because a live read exposed a silent-zone bug: an Oct 18–20 window (`+02:00`) returned an
event dated **2026-10-06**, and boundary probes proved the window was interpreted in the
**calendar's** zone (`Asia/Kuala_Lumpur`).

`timeZone` is now required **and validated** (real `ZoneId`, else `400 bad_timezone`) on the read
and on create/update. `quickAdd` requires it **and must match the calendar's** (Google's
`quickAdd` takes no zone at all, so accepting a different one would be the same silent
reinterpretation). `delete`/`move` take none.

**81 tests** (`TimeZonesTest` +5).

## 0.13.1 — remove legacy OFF shims
`0e26486` · 2026-10-18

Under the standing no-backward-compat rule (not live yet), the shims 0.12.0 carried are deleted:
`AuditOutcome.OFF_APPROVED` from the enum, and the `"OFF" → NOTIFY` load from `Policy.parse`.

Consequence, accepted on purpose: a pre-0.12.0 record carrying `OFF_APPROVED` now decodes as an
*unreadable line*, and a `policy.json` carrying `"OFF"` now **throws**.

## Plan — SECRET-BROKER-PLAN
`3ff8bf1` · 2026-10-18

The generic secret-broker design: a third-party API secret lives on the phone, Keystore-encrypted
and biometric-gated, never on the host, without a Kotlin plugin per service.

## Plan — D7/D8: one plugin per secret, fixed EXECUTE verb
`5861b89` · 2026-10-18 · `dd8e836` · 2026-10-18

D7: a plugin per secret, verb from the HTTP method (not the path). D8: one route per secret, fixed
`EXECUTE` verb (no verb coupling). Q6 answered.

## 0.13.2 — `specialUse` FGS + battery-optimization exemption
`880a04e` · 2026-10-18

Android 15 (targetSdk 35) caps `dataSync` foreground services at 6h/24h and then throws
`ForegroundServiceDidNotStopInTimeException`, killing the process. **That was the "random"
crash**: `BridgeService` declared `dataSync`, ran 24/7, and never overrode `onTimeout()`.

- manifest: `dataSync → specialUse` (with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`); swap
  `FOREGROUND_SERVICE_DATA_SYNC` for `FOREGROUND_SERVICE_SPECIAL_USE`.
- `BridgeService`: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+, `onTimeout → stopSelf()` as a
  safety net regardless of type.
- `MainActivity`: battery-optimization row in the setup checklist.
- `gradle.properties`: `kotlin.compiler.execution.strategy=in-process` — this box is RAM-tight and
  a forked Kotlin daemon fails to connect under pressure.

## Plan — close out SECRET-BROKER-PLAN
`f6a2e28` · 2026-10-18

Session unlock (D9), resolved edges, error codes.

## 0.14.0 — the secret broker: vault, generic broker, CRUD screen
`5baa30c` · 2026-10-18

The generic secret broker (Todoist first), so a secret lives on the phone and never on the host.

- `core/SecretVault`: **envelope encryption**. VK (Keystore, auth-per-use, `BIOMETRIC_STRONG`,
  StrongBox with TEE fallback) wraps a random DK that does all secret crypto. One fingerprint per
  **session**, not per secret — the platform demands auth for encryption too, so a per-secret
  auth-per-use key would have cost a prompt per write.
- `plugins/secrets/SecretBroker`: injects the `***` placeholder, refuses redirects, boundary-correct
  host match, blocks loopback/private ranges, scrubs the secret from headers **and** body.
- `plugins/secrets/SecretPlugin`: one plugin per secret, `POST /secrets/{name}/call`, fixed
  `EXECUTE` verb.
- `plugins/secrets/HostAllowlist`: exact or `*.domain`.
- `VaultGate`: the load prompt carries the vault `CryptoObject`, so one scan is both app lock and
  vault unlock.
- `SecretsActivity`: CRUD with `FLAG_SECURE`, password-style input, autofill off, reveal-on-demand,
  never logs a value.
- `ErrorCodes`: 11 new codes.

**81 tests.**

## 0.14.1 — two links per secret (docs + key page)
`cf9b203` · 2026-10-18

Each secret carries two non-secret links: where the API is documented, and where you issue the
key. Plaintext index (`vault_meta`), because they are pointers, not credentials — so they stay
readable while the vault is locked, which is exactly when you want them.

Editing a link needs **no fingerprint** — demanding one would be theatre. `https`-only on write.
Reads the old bare-array index too, so a 0.14.0 vault keeps its names.

## 0.15.0 — per-secret hosts + crypto-bound approvals
`af1d49e` · 2026-10-18

Two decisions from chat.

1. **Decision A — every approval scan is now `CryptoObject`-bound to the vault key.**
   `promptBiometric` prepares the vault cipher first and passes it as `CryptoObject`; the
   authorised cipher unwraps the vault when locked, so **one modal unlocks AND approves**. Falls
   back to the plain prompt only before the vault exists. Fails closed when the key is invalidated.
   Accepted cost: a crypto-bound prompt cannot accept `DEVICE_CREDENTIAL`, so approvals become
   **fingerprint-only** once a vault exists.
2. **Allowed hosts moved from a GLOBAL list onto each secret.** The global list meant the `todoist`
   secret could be sent to any host some *other* secret had approved. `HostAllowlist` is now pure
   (`allows`/`requireRule`), no `Context`, unit-testable.

**81 tests.**

## 0.15.1 — fix vault unlock: store the DK wrap IV
`d86a73b` · 2026-10-18

Bug: *"tap to unlock"* did nothing. Log:
`vault unlock not completed: could not prepare the vault cipher: IV required when decrypting.`

Two defects in the envelope key-wrap: `prepareUnlockCipher` never passed an IV to
`Cipher.init(DECRYPT_MODE, …)`, **and** `initialize()` wrapped the DK but never **stored** the IV
it generated. Since GCM uses a random IV per wrap, that made every 0.14.x vault **permanently
unopenable** — the ciphertext is there, the IV needed to read it is not.

Fixes: `F_DK_IV` stores the wrap IV beside the wrapped DK; `isUnrecoverable()` detects a 0.14.x
vault and offers a reset that says plainly the secrets cannot be recovered (a silent fresh vault
would look like success while discarding a credential). New code `vault_unrecoverable`.

**89 tests** (`HostAllowlistTest` +8).

## 0.15.2 — hosts in the add/replace dialog
`4e55c91` · 2026-10-18

Merge the secret and its API domains into **one** dialog instead of a separate host list.

- `SecretsActivity.addSecretDialog`: new "allowed hosts" field, comma/space separated, prefilled
  from what is stored so a replace does not silently drop the rules.
- `SecretVault.put`: `hosts` param (`null` = keep existing, list = replace). Validated before any
  write, so a typo in the last field cannot half-update the secret.

**Verified end to end on the phone:** `POST /secrets/todoist/call` with `Authorization: Bearer ***`
returned `200` with the real task list, token scrubbed.

## 0.16.0 — `GET /secrets`, read a secret's non-secret data
`ad69c41` · 2026-10-18

Requested explicitly: fetch a secret's links through a proper route, not a field bolted onto
`/debug`.

New `SecretIndexPlugin`, registered once: `GET /secrets` and `GET /secrets/{name}`. Returns name,
docs, key, hosts, callRoute. Deliberately does **not** require an unlocked vault — names/links/hosts
are plaintext by design. The **value** is never returned and no parameter can ask for it.

No route collision: calls are `POST /secrets/{name}/call` (3 segments), the index is 1–2 segments.

## 0.16.1 — fix 204: an empty success is not an unsupported type
`e6a1313` · 2026-10-18

Mido: *"don't do shitty workarounds, fix the 204."* Correct — the `/sync` detour was a workaround.

`POST /tasks/{id}/close` and `DELETE /tasks/{id}` answer `204 No Content` with
`Content-Type: text/html`. The broker refused anything non-JSON as `unsupported_content_type`, so
completing or deleting a Todoist task was unreachable.

Root cause was conceptual: the rule conflated "I cannot read this payload" with "this payload is
empty". A 204 is a **success** with nothing to say.

Fix — the decision is now a pure function, `ResponseBody.classify(status, body)`:

- a 2xx with a blank body → `EMPTY_SUCCESS`, **Content-Type ignored**;
- otherwise the **body** decides, not the header (parses as a JSON object → `JSON`, else
  `UNSUPPORTED`);
- a non-2xx with an empty body is still `UNSUPPORTED`.

It also stops trusting `Content-Type` for non-empty bodies, so a server mislabelling real JSON as
`text/plain` no longer breaks. The refusal for a genuinely non-empty non-JSON body (PDF/image/zip/
HTML, or a bare scalar/array) stays.

Extracted for the same reason `HostAllowlist` was: it lived inside an `HttpURLConnection` call and
was untestable, and the untested branch was the wrong one. `ResponseBodyTest`: 9 cases.

**98 tests.**

## 0.17.0 — design + AI release (build 93)
`b076cc8` → `9d39a6f` · 2026-10-19 (overnight run)

A deep UI/AI pass, built as separate commits for easy revert. Every one a green build.

- `b076cc8` **Material 3 foundation.** `material:1.12.0`; `ui/Theme.kt` as the single source of
  design truth (semantic colours, dp metrics, ≥48dp targets, rippled surfaces), replacing 24
  scattered hex literals and the pixel-radius `pill()` helper.
- `bf43d1e` **Migrate every screen to the Theme; delete `UiKit`.** Five duplicated button helpers
  gone. Fixed the hero-card bug: the state colour was set per branch then overwritten with
  `NEUTRAL_BG` two lines later, so it was always grey. Buttons tint via `backgroundTintList`,
  preserving the ripple.
- `f02c353` **1s decision arm delay + arming progress bar** — the wait is visible instead of a
  dead-looking disabled button. Driven by a handler tick (no animator dependency).
- `6734216` **Alert sounds + haptics.** `SECURE` rides the approval channel (notification-class,
  respects silent/DND) plus a short double-tap vibration; `NOTIFY` plays one quiet tick, gated on
  `isAttended()`.
- `530c57d` **Summariser seam.** `ai/SummaryModel.kt` (never throws, returns null on failure),
  `HeuristicSummarizer` (the honest baseline), `MediaPipeGemma`, `ModelRegistry` (one constant
  names the model file).
- `ba7309e` **Wire summary + risk into the SECURE modal** (display-only).
- `69dcf4e` **Fetch-first NOTIFY card, tap-to-approve, AI line + risk tint.** The 5s clock starts
  only after the fetch lands; a tap approves and skips the wait; drag slop 8dp → 16dp (a tap now
  approves, so the threshold is security-relevant).
- `8dea3e5` **Heuristic risk tests** (98 → 107).
- `9d39a6f` **Version 0.17.0 / build 93 + `VIBRATE` permission** — without it every haptic written
  this release would have no-opped silently.
- `a1b3ecd` **Build modal rows once** — they were built twice per request, costing a second live
  Google round-trip for a pre-fetching route.

## 0.18.0 — migrate the summariser to LiteRT-LM (build 94)
`8525b43` · 2026-10-19

MediaPipe `tasks-genai` is maintenance-only; Google's replacement is LiteRT-LM. Swap the runtime,
keep the seam.

- `tasks-genai:0.10.35 → litertlm-android:0.18.0`; new `ai/LiteRtLm.kt` (Engine/Conversation API),
  `MediaPipeGemma.kt` deleted.
- **Model choice forced by gating, not taste:** every small Gemma in `litert-community` is gated
  (401 without an HF token, which this box has none of), so an ungated `Qwen3-0.6B` mixed-int4
  shipped instead.
- Toolchain: the artifact carries Kotlin 2.4.0 metadata, so the plugin moved 2.0.21 → 2.4.21 and
  `kotlinOptions` moved to `kotlin { compilerOptions { } }`. That stricter compiler flagged a real
  redundant `.toString()` on `Person.uri`.

**107 tests.** Version 0.18.0 / build 94.

## 0.19.0 — the AI is core; the app self-provisions the model (build 95)
`88501ce` · 2026-10-19

Mido: *"the app should be able to download the model if not found"* and *"it shouldn't be able to
work without the model. treat it as core functionality, not a nice to have."* Decision A: the
bridge is **not up** without it.

- **Self-provision** (`ai/ModelProvisioner.kt`): fetch the `.litertlm` once from Hugging Face,
  resumable (HTTP `Range`), staged to `<name>.part` and renamed onto the real path only after size
  **and** sha256 match — a truncated download can never be mistaken for a model.
- **Fail-fast:** readiness now **gates** the bridge. Every route answers `503 model_not_ready`
  until the model is present. `/debug` and `/logs` are exempt, because they are how a not-ready
  bridge is diagnosed.
- **The heuristic is GONE** — deleted with its test. It was the silent fallback the fail-hard rule
  forbids. `aiSummary` now throws `ai_unavailable` (500), and the NOTIFY card is withdrawn rather
  than shown undescribed and silently approvable.
- **Warm + release:** `BridgeService.onCreate` warms the engine; `onTrimMemory` releases it —
  deliberately with **no level comparison**, since `TRIM_MEMORY_RUNNING_LOW` is deprecated as of
  API 34/35 (apps are no longer notified of it).

**112 tests.** Version 0.19.0 / build 95.

## 0.20.0 — per-route AI opt-out, ANY verb in rules, re-download guard (build 96)
`6a22f8f` · 2026-10-19

Three fixes from Mido's first device pass.

1. *"the model downloaded, I touched it it started downloading again"* — `provision` is now
   idempotent; the home screen confirms before a re-download. A tap on a ready row was silently
   starting a 474 MB fetch.
2. *"debug call waited for the model"* — **the real bug.** `/debug` was exempt from the readiness
   gate but still ran inference, so with a broken model the diagnostic route answered
   `ai_unavailable` and the one path that explains why was gone. Fixed at the source: a `Route`
   declares `aiSummary: Boolean = true`, and that **one** flag is both the AI opt-out and the
   readiness exemption, so the two cannot drift.
3. *"in the rules verb selection allow ANY"* — `Rule.verb` is now nullable; null means ANY. A
   **bounded** reintroduction of precedence: one axis, two levels (an exact verb rule wins over the
   plugin's ANY rule; plugin stays exact; precedence is order-independent). A **missing** verb is
   still a parse error — never a silent wildcard.

**122 tests.** Version 0.20.0 / build 96.

## 0.20.1 — model loader is a button-scoped progress fill (build 97)
`ab74500` · 2026-10-19

Mido: *"tabbing download again still shows the message"* and *"the loader should be button scoped,
using a bg color progress fill style."*

- `Theme.progressButton`: a MaterialButton whose **own** background is a two-layer drawable
  (dimmed track + `ClipDrawable` fill) drained by `level`. Built from `background` rather than
  `backgroundTintList`, which costs the ripple while loading — correct, because the button is
  disabled mid-download (a second writer on one `.part` file corrupts the model).
- The fill is driven by the real byte count, never a timer.
- The `"Downloading the AI model..."` toast is gone — since `provision` became idempotent it was a
  **lie** on every no-op tap. The result toast now carries the provisioner's own detail.

Version 0.20.1 / build 97.

## 0.20.2 — swap the model artifact (build 98)
`25512ff` · 2026-10-19

The real cause of "the model is not summarising" was **not** memory, GPU or speed. The phone's own
log named it:

```
Failed to apply template: invalid operation: tried to use + operator on
unsupported types string and sequence (in template:14)
```

The mixed-int4 Qwen3 artifact's chat template concatenates `'\n' + content` — valid for a string
but fatal when content arrives as a list of parts, which is what `Contents` always serialises to.
Every inference failed, and because the AI is core the whole bridge went down with it. Reproduced
with the official Python binding, so it was the model file.

Swapped to `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` (344,671,744 bytes, sha256 `03e7da1e…`),
verified by hand to accept a system instruction and return valid JSON on READ, DELETE and EXECUTE.
**The system prompt stays and is now longer** — it is what the old template choked on, so keeping
it is what the swap proves works.

Also removed constrained decoding (`ResponseFormat.json`): with `LL_GUIDANCE` this model truncates
mid-object (a real run returned the literal `{"description":"}`), while the same prompt
unconstrained produced valid JSON 3/3.

Version 0.20.2 / build 98.

## 0.21.0 — queued fixes: row spacing, auto-start server (build 99)
`1a9a8c2` · 2026-10-19

Both from Mido's device pass, queued per his workflow rather than fixed mid-test.

1. *"add margin around list items they are touching"* — `Theme.ROW_GAP_DP` (8dp) is now one
   constant applied inside `Theme.statusRow`, and reused by Settings/Log rows. That drift is how a
   UI starts looking assembled rather than designed.
2. *"start server on app load automatically"* — `maybeAutoStartServer()` guarded twice: **once per
   process** (so a manual STOP stays stopped — otherwise STOP would be impossible to keep) and
   **never over a running server**.

Version 0.21.0 / build 99.

## 0.21.1 — fix GPU, and fail hard: no CPU fallback (build 100)
`dca3ee4` · 2026-10-19

Mido: *"no, fix gpu"* and *"I said fail hard a million times. only the best most efficient
performance for my phone is accepted."*

The GPU was never broken — the app could not **see** the vendor OpenCL driver, because an Android
app cannot `dlopen` `libOpenCL.so` without declaring it:

```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
<uses-native-library android:name="libvndksupport.so" android:required="false" />
```

The engine loaded and logged `engine on GPU` regardless, then failed every inference with
`Can not find OpenCL library on this device` — a load-time success with a run-time failure, which
is why it read as a slow model. `required="false"` so a device without a vendor driver still
installs.

**And the CPU fallback added an hour earlier is REMOVED** — exactly the silent degradation this
project forbids. Now `createEngine` builds the GPU and nothing else; `warm()` **proves** the
backend with a throwaway inference (loading is not proof); no retry, no demotion. The reason is
kept in `LiteRtLm.lastEngineError` so `/debug` can name the cause.

Version 0.21.1 / build 100.

## 0.21.2 — SECURE budget 3s → 10s (build 101)
`31477c4` · 2026-10-19

Measured on device, twice: the 0.6B model on the S22 Ultra's GPU infers a summary in ~3.2s
(3194ms, 3176ms). The SECURE budget was 3s, so every answer was discarded as "too late" and the AI
looked broken when it was merely 0.2s over. Mido: *"make it 10 seconds."*

The budget is **not a performance target** — it is the point at which a late answer stops being
worth having. 10s clears the measured cost with room for a colder GPU. `NOTIFY` was already 10s,
so the two paths now agree.

Version 0.21.2 / build 101.

## 0.21.3 — bundle the GPU sampler .so (build 102)
`0ee2bb0` · 2026-10-19

`sampler_factory` `dlopen`s `libLiteRtTopKOpenClSampler.so` for GPU sampling — and that library is
**not** in `litertlm-android`'s AAR, which ships only `liblitertlm_jni.so`. So the factory fell back
to the CPU-side sampler:

```
OpenCL sampler not available, falling back to statically linked C API
```

That fallback is not free: with a CPU sampler the GPU hands logits back for **every generated
token**. That is the 3.2s.

Fix: bundle the v0.18.0-tagged library at `app/src/main/jniLibs/arm64-v8a/libLiteRtTopKOpenClSampler.so`
(sha256 `0f77c888…`, verified against the LFS pointer for that tag — **not** `main`, since it is a
`dlopen`ed C ABI that must match the runtime version). Self-contained: `readelf` shows only system
deps.

Version 0.21.3 / build 102.

## 0.22.0 — the modal arms on the fetch, not a timer (build 103)
`ce15b9b` · 2026-10-19

Mido: *"change the static waiting mechanism for modal activation, instead use whatever time needed
to enable the buttons"* + *"fetch alone"* + *"60 at enable"* + *"add fake loading feeling, claude
style some word like thinking... wondering..."* + *"if AI fails show error but unlock modal for
user."*

The modal used to be built **after** the summary, so a request waited up to 10s for nothing and
then another 1s of dead buttons — two static waits stacked. Now the modal appears **immediately**
with the deterministic rows, and the gate fetches in the background.

- `ApprovalGate` owns the fetch, not `BridgeService` — only the gate knows when a request actually
  becomes the one on screen.
- `ApprovalGate.AiState { Loading | Ready | Failed | NotNeeded }` is read **live** by the modal
  each tick.
- `DECISION_DELAY_MS` is **DELETED**. The only enable trigger is `enabledAtMs > 0`.
- **The 60s clock starts at enable**, not at show, so model latency is never charged to the
  human's decision time. `Pending.maxWaitMs()` derives the server's total wait.
- **DENY is enabled immediately and independently** — denying is fail-closed.
- The loading state cycles a word + dots; the indicator is **indeterminate** on purpose (inference
  is a blocking call with no token stream, so a determinate fill would be an invented number).
- **AI failure unlocks the modal and shows the reason.** Not a fail-hard weakening: a **missing**
  model still refuses the whole bridge with `503` before any modal exists.

Version 0.22.0 / build 103.

## Housekeeping — snapshot before factchecker prompt change
`bf2a7e1` · 2026-10-19

Snapshot only.

## 0.23.0 — modal UI pass: insets, bigger summary, tighter rest (build 104)
`8e03c4c` · 2026-10-19

Mido: *"improve modal ui / Add top padding so that when I get a notification with the modal it
doesn't overlap / And make the ui summary more concise and larger / the rest of the modal data gets
smaller / add margins between the buttons."*

1. **System-bar insets.** The modal drew under the status bar and nav bar. Now
   `ViewCompat.setOnApplyWindowInsetsListener` on the root pads by `systemBars().top/bottom` —
   insets, not a fixed dp, since the bar height varies per device and orientation.
2. **The summary is now the largest thing on the screen:** 26sp bold (was 17sp), bounded to 3 lines
   and ellipsized rather than trimmed in code.
3. **Everything else shrank**, so the summary dominates by contrast (title 22→20, WHAT 20→16, row
   values 16→13, etc.).
4. **12dp between DENY and APPROVE**, so the two decisions are never one fat-finger apart.

Also tightened the model prompt to produce shorter text (`"max 8 words"`, "a headline, not a
sentence", no filler).

Version 0.23.0 / build 104.

## 0.23.1 — soften the summary word cap into a suggestion (build 105)
`0a4166c` · 2026-10-19

Mido: *"remove the hard cap 8 words, or make it a suggestion instead."*

A hard ceiling makes a small model either contort the sentence or drop the very noun that matters,
and a truncated summary is worse than a slightly long one — the human reads it as the whole truth.
Now: `"<brief, ideally under ~8 words>"` with an explicit escape hatch. Length is **guided by the
prompt** and **bounded by the renderer** (3 lines, ellipsized), which degrades visibly rather than
silently.

Version 0.23.1 / build 105.

## 0.23.2 — arm64-only ABI (build 106)
`4b6b4f0` · 2026-10-19

Two things, both from Mido's 2026-10-19 messages.

1. The word-cap softening (above) landed here too.
2. **`abiFilters = arm64-v8a` ONLY.** The litertlm AAR ships native libs for **both** `arm64-v8a`
   and `x86_64`, and both were being packaged: 25.1 MB of `lib/x86_64/liblitertlm_jni.so` that can
   never load on the target phone, on every APK — 36% of the download. This is what made the 0.23.x
   Taildrop transfers stall: two 150s timeouts, then a background run that moved ~10 MB of 69 MB
   and froze (tx counter static across three samples).

   **APK: 69 MB → 42.7 MB.** The target is a Samsung S22 Ultra (arm64-v8a), sideloaded by hand, so
   `x86_64` has no use here. If an emulator is ever needed, add it back deliberately — do **not**
   delete this filter to "fix" an x86_64 install and ship the dead weight to the phone again.

**122 tests green.** Version 0.23.2 / build 106. APK taildropped, exit 0.

## 0.24.0 — the modal's third button, and "PER-PAIR RULES" → "EXCEPTIONS" (build 107)
`e89de3e` · 2026-10-19

Mido: *"in the modal I want a third button to automatically add an exception for the current
request. using our same system for per-pair rules, and change this stupid name while at it"*.

**The rename.** The rules screen was `PER-PAIR RULES`, which named the *implementation*. It is now
`EXCEPTIONS` — one word for one concept, and it explains itself, because the default **is** "ask"
(`AUTOMATIC` with no rule → `SECURE`), so every rule is literally an exception to it.

**The button.** A third decision in the `SECURE` modal: it approves the request **and** writes an
exception for its pair in one tap. Details that matter:

- **Its own full-width row above DENY/APPROVE, not a third column in that pair.** The 0.23.0 pass
  put 12dp between DENY and APPROVE precisely so the two decisions are never one fat-finger apart;
  wedging a third button between them would undo it. Secondary style, so a reflexive tap meant for
  APPROVE cannot land here.
- **The label names the behaviour the tap produces** — `ALWAYS NOTIFY` / `ALWAYS ASK` — because the
  exception is the *other* mode, computed by the new pure `exceptionModeFor(resolved)`.
- **Scoped to `AUTOMATIC` — and ONLY `AUTOMATIC`.** `resolve()` reads the rule table in that mode
  alone, so under a forced `SECURE`/`NOTIFY` a written rule is inert. Mido's call (2026-10-19):
  *"just add it, it should work with automatic and that's it"* — so the button is **absent**, not
  disabled, outside `AUTOMATIC`. No flipping the switch behind the human's back, no button that
  lies about what it just did.
- **The floor holds.** `Rule.ALLOWED_MODES` is `NOTIFY | SECURE`, so an exception can at most make a
  pair *quiet*. It can never auto-approve, and no tap here can create a path that skips a human.
- **The Activity never writes policy.** It renders the offer and hands the finished `Rule` back;
  `BridgeService.writeException` persists it — the single place policy is written, read-modify-write
  (`withRule`, never a fresh `Policy`), audited with `source = "modal"` so the log says *where* a
  policy decision came from. A failed write does not fail the request (the human did approve) but is
  surfaced through the audit-health banner, never swallowed.
- **Same biometric gate as APPROVE** — the tap approves a request, so it clears the same finger,
  and arms on the same signal (never before the summary the human is meant to read).
- `GateResult.ApprovedWithException` carries the rule back. It is checked **before** plain
  `Approved` in the gate, because the exception tap also sets `approved = true` — testing approval
  first would silently downgrade it and lose the rule the human asked for.

Planned in `EXCEPTIONS-PLAN.md`; the open questions there (Q1/Q2/Q3/Q5/Q6) were settled in chat as:
set-and-approve, exact verb only, `ALWAYS …` labels, own row above the pair, shared arm delay.

**126 tests green** (4 new: the other-mode mapping, the file round-trip, siblings untouched, exact
beats ANY). Version 0.24.0 / build 107.

## 0.24.1 — the exception button never rendered on calendar/gmail (build 108)
`2aa39ab` · 2026-10-19

Found by testing on the device, and it is the "computed then discarded" trap this project has
already paid for twice.

`exceptionOffer` was built at the call site and passed to **two of the three**
`ApprovalView(...)` returns. The generic branch — the one calendar and gmail use — took the
constructor default of `null`, so the button silently did not exist on exactly the routes that
went through it. Notifications worked, which is why it looked fine.

**Fixed by passing it in the third branch, and by adding `ApprovalViewWiringTest`: a source scan
that counts `ApprovalGate.ApprovalView(` constructions against `exceptionOffer = exceptionOffer`
pass-throughs and fails the build when they disagree.** The regex is anchored to end-of-line on
purpose — the call site writes `exceptionOffer = exceptionOfferFor(...)`, and an unanchored match
counts it and fails on a correct tree (that happened on the first run).

The guard was **proven to fail**: breaking one pass-through produced
"Found 3 construction(s) but 2 offer pass-through(s)".

## 0.24.2 — a settled NOTIFY card could not be dismissed (build 109)
`2aa39ab` · 2026-10-19

Reported on device: *"I have a notification card still stuck!"*, then *"it doesn't even dismiss on
x click"*.

Both symptoms, one cause: **removal was not guaranteed.** `settle()` marks the entry `settled`
(so the outcome is reported and every tap path early-returns), then starts a fade-out as a chain
of posted runnables. If any link in that chain never ran, the card stayed on screen and already
`settled` — so the card body ignored taps, and the `x` called `settle()` again, which
early-returned. A card with every control dead, and no code path left that would remove it.

This is the same class of defect the overlay has produced before (animator-dependence in 0.9.0):
**appearance and lifecycle must not hang on a chain that can silently stop.**

Three changes, all pointing at one idea — removal gets its own guaranteed path:

- **`Entry.removed` is separate from `Entry.settled`.** Settled means "the outcome is decided";
  removed means "the window is gone". Conflating them is what made a stuck card unreachable.
- **`teardown(entry)` is idempotent and unconditional**, reachable from four places: fade
  completion, the settle backstop, the `x`, and `dismissAll()`. It is deliberately NOT guarded by
  `settled` — a settled card still on screen is the bug.
- **A backstop fires at `FADE_OUT_MS + COMMIT_FRAMES_MS + BACKSTOP_SLACK_MS`** and tears the
  window down whether or not the fade got there; and a throw inside the fade falls straight to
  teardown instead of dying quietly. The `x` now calls `settle()` **and** `teardown()`.

`dismissAll()` uses the same path, so service shutdown cannot race a fade and both remove one
window.

127 tests green. Version 0.24.2 / build 109.

## 0.24.3 — the exception button killed the modal before the fingerprint (build 110)
`31b727f` · 2026-10-19

Reported on device: *"I clicked always notify like 5 times, modal kept coming back, only when I
clicked approve I got a fingerprint"*.

The report contained the diagnosis. **No fingerprint and the modal returning mean the request was
never resolved** — a prompt that never appears means the Activity died before it could show one.

The handler called `finish()` **unconditionally, immediately after** starting the biometric
prompt:

```kotlin
if (requiresBiometric) {
    promptBiometric { ApprovalGate.resolveWithException(id, off.rule) }
} else {
    ApprovalGate.resolveWithException(id, off.rule)
}
finish()          // <-- runs at once, before the async prompt can appear
```

`promptBiometric` is **asynchronous**: it shows the system prompt and returns immediately, and the
resolve happens later in its callback. So `finish()` destroyed the Activity first — the callback
never fired, the request was never resolved, and the gate re-posted the modal. Five taps, five
dead ends.

`APPROVE` never had this bug because its `finishWith()` lives **inside** the callback. The fix
mirrors that exactly: resolve, then finish, both in the success callback; on a dismissed or failed
prompt nothing happens at all, so the request stays pending and can be retried.

**The durable rule: any code that starts an ASYNC confirmation must put its completion (and its
`finish()`) inside that confirmation's callback — never on the line after the call.** `finish()`
after `promptBiometric(...)` is a no-op against an Activity that is about to be destroyed; the
prompt is a system surface and cannot outlive its host.

127 tests green. Version 0.24.3 / build 110.

## 0.25.0 — policy rules are readable over HTTP; FSI state is observable (build 111)
`6b95325` · 2026-10-19

Mido: *"test, and add policy rules to logs but read only"*.

**`/debug` now reports the rules.** `policy` was `{global, readable}` and nothing else, so a
`plugin × verb` decision could not be inspected from outside — every rules question meant the
human reading the screen aloud, which is exactly what this endpoint exists to prevent.

```
policy: {
  global, readable, ruleCount,
  rules: [ { plugin, verb, mode, key } ]
}
```

`verb` carries the literal `"ANY"` (matching the on-disk format and `Rule.key`), so a reader
never interprets a null; `key` is the same `plugin/VERB` string an audit record's `rule` field
carries, so a log line and this report compare without hand-reconstruction.

**Read-only by construction.** No write route, and no parameter here can change anything. Policy
is written in exactly two places — the EXCEPTIONS screen and the modal's exception button — and
both audit it. A diagnostic surface that could mutate the gate would be a security hole, not a
feature. That constraint is stated in the code so a future change has to argue with it.

**`permissions` block added** (`fullScreenIntent`, `drawOverApps`, `notifications`), because the
FSI question needed to be observable rather than guessed. `fullScreenIntent` reports **JSON null
below API 34** rather than `false` — "not required" and "revoked" are different facts.

Why this matters beyond diagnostics: `USE_FULL_SCREEN_INTENT` is a **special app access** on
Android 14+, not a runtime permission — its default is re-derived from the app's category when the
package is added, and the user's own grant is what keeps it on. That is the platform rule, and it
is why this permission deserves a report field rather than a guess. The gate also has a second
delivery path (`ctx.startActivity`, legal via draw-over-apps), so whether FSI is actually
load-bearing is now a question the report can answer.

**Measured, correcting an earlier claim in this file:** installing 0.25.0 over 0.24.x did **NOT**
revoke it — `permissions.fullScreenIntent` read `true` immediately after the update. So the
"every update revokes it" theory is **not supported by the evidence on this device**. The Play
Store's automatic revocation applies to Play-installed apps; these are sideloaded, and the app-op
survived. Treat a lost grant as something to investigate (a reinstall under a different signing
key, a Settings toggle, a system cleanup) rather than as routine update behaviour.

127 tests green. Version 0.25.0 / build 111.

## 0.25.1 — the NOTIFY card went full-height the moment the summary landed (build 112)
2026-10-19

Reported on device: *"worked, but it looked good until summarize finished then it went back to full
height shit"*.

That sentence is the whole diagnosis — **correct until the AI answered** means the fault was in
something that only becomes visible when the summary arrives. That is the risk strip.

The strip was added to the card as a **`MATCH_PARENT`-height child of a `WRAP_CONTENT` card**. In a
`WRAP_CONTENT` parent a `MATCH_PARENT` child forces the parent to match *its* parent, so the card
grows to the whole window. It stayed hidden because the strip is `GONE` until `applyAi`, so the
card measured fine, the fade-in and countdown looked right, and the screen only snapped the instant
the model returned.

**This is the 0.6.2 bug, reintroduced.** The rule from that release — *a decorative fill belongs in
the background or a fixed-size view, never as a `MATCH_PARENT` child of a `WRAP_CONTENT` parent* —
was written down and then not applied when the strip was added in 0.17.0. A rule that lives only in
a changelog entry is not a rule.

Fix: the strip is `WRAP_CONTENT` height in the layout tree, and `applyAi` stretches it to the
card's **measured** height after the text is laid out (`root.post`). Only the strip's own height
changes — the card's size stays decided by its text, which is what keeps it small.

Verified before rebuilding that no other `MATCH_PARENT`-height child remains under the card: every
remaining `MATCH_PARENT` in `NotifyOverlay` is width-only or paired with `WRAP_CONTENT`.

127 tests green. Version 0.25.1 / build 112.

## 0.25.2 — full-screen intent is OPTIONAL, and the checklist stops nagging (build 113)
2026-10-19

**Measured on device:** with FSI revoked and draw-over-apps granted, a SECURE calendar request
still opened the modal and was approved — `APPROVED` in 8797ms, and `APPROVED` can only come from
the modal Activity. So the `ctx.startActivity` path alone is sufficient **while the screen is on**.

That turns the old "Full-screen popup: tap to grant" row into a false alarm: it reported a warning
for a permission the app demonstrably does not need for the common case, and a checklist row that
cries wolf is one the user learns to ignore.

The row is now **optional**, and honest about why it still exists:

- granted → `Lock-screen popup · granted (wakes a locked screen)`, green
- not granted → `Lock-screen popup · optional — tap if approvals miss on a locked screen`, muted,
  **not** a warning colour

**The permission is NOT removed**, and that restraint is the point: the test ran with the screen
on. FSI is what wakes a **locked** screen, so the locked-screen path is still unproven. Removing the
permission on evidence that does not cover its only remaining job would be the same mistake as
trusting it in the first place. Remove it once a locked-screen test passes.

Also corrected here: an earlier entry in this file claimed an update silently revokes FSI. It does
not — `permissions.fullScreenIntent` read `true` straight after installing 0.25.0 over 0.24.x. The
Play Store's auto-revocation applies to Play-installed apps; these are sideloaded.

127 tests green. Version 0.25.2 / build 113.

## 0.26.0 — the summary describes the PAYLOAD, not the route (build 114)
2026-10-19

Mido, after writing 8 todos: *"I didn't like the AI summary. I expected the todolist adding one
to say Adding 8 todos to PhoneBridge or something"* — then, naming why it matters: *"the payload
is the whole reason I added AI, so it's critical to describe it too"*.

He is right, and the cause was upstream of the model. The facts handed to it were:

```
summary: todoist: /secrets/todoist/call
facts:
- TARGET: POST https://api.todoist.com/api/v1/sync
- BODY: {"commands": [{"type": "item_add", ...      <- truncated at 200 chars
```

Two failures stacked:

1. **The deterministic summary described a ROUTE.** `describe()`'s fallback branch produced
   `todoist: /secrets/todoist/call`, which says nothing about what is happening.
2. **The payload was reported as a truncated string.** A 5.3 KB body cut at 200 characters shows
   the model that *something* is being sent and gives it no way to count what.

No amount of prompt engineering fixes that: the model cannot name a number it was never shown.

**Fix — describe the shape, deterministically.** New `core/PayloadShape` (pure, Context-free,
unit-tested) turns a JSON body into `8 commands (item_add ×8)`. Rules: an array of objects is
counted BY `type` (the convention every batch API uses), an array of scalars is a count, a scalar
names the FIELD and never its value, an empty array contributes nothing, and a non-object body
returns null so the caller falls back to an honest byte count.

`describe()` gains a `/secrets/` branch that produces
`todoist · POST /api/v1/sync · 8 commands (item_add ×8)` — true with or without the AI, and
readable in the audit log on its own.

The prompt now says: *describe what the PAYLOAD does, not the endpoint; if the summary names a
count, say it — "Adding 8 todos" beats "updating tasks"*.

**A test that cannot fail, caught and fixed.** The first version of `PayloadShapeTest` inlined a
COPY of the logic and asserted against it — it could never fail. Extracting `PayloadShape` into a
real object made the test real: breaking `items` → `WRONG` fails it (verified), and 133 tests pass
when restored.

133 tests green. Version 0.26.0 / build 114.

## 0.26.1 — the prompt cap is a BUDGET, and the shape is ADDITIVE (build 115)
2026-10-19

Two corrections from Mido, both right.

**1. "the itemadd x 8 thing should be an addition to the context not a replacement."**
In 0.26.0 the payload shape was folded into the `summary:` line, which meant it displaced what
that line would otherwise have said. It is now its **own fact row** in the secrets plugin:

```
- TARGET: POST https://api.todoist.com/api/v1/sync
- PAYLOAD: 8 commands (item_add ×8)      <- the shape, as its own fact
- BODY: {"commands": [...]}              <- and the payload, still there
```

Additive, so the model gets both the shape AND the content. The human gets both rows in the modal
too, which is the more valuable half: the shape is what makes a 5 KB payload judgeable at a glance.

**2. "what's the model limit? and what's the longest cap you recommend."**

The real numbers, from `LiteRtLm.kt`:

```
MAX_CONTEXT_TOKENS = 1024   (KV cache: input + output)
MAX_OUTPUT_TOKENS  =  128
-> input budget    =  896 tok
   system prompt   ~  265 tok  (1,061 chars)
   scaffolding     ~   30 tok
   -> facts        ~  601 tok  ~ 2,400 chars
```

**A flat per-row cap was the actual bug**, not the number 200. It was fine for six short rows and
catastrophic for one 5.3 KB body, because the constraint is on the TOTAL and a per-row number
cannot know how many rows exist. New `core/PromptBudget` divides the budget across however many
rows there are: **600 chars max each, 120 floor, 2,000 total.** Two rows get long excerpts, eight
get short ones, and the total always fits.

Two things fell out of writing it:

- **A clipped value is now marked `…`.** The old cap cut silently, so the model asserted
  confidently about half a payload. Silence is the bug as much as the length.
- **Past ~16 rows the floor multiplied out exceeds the budget**, which would silently overflow the
  context window — a failed request, since the AI is core. The renderer therefore stops adding rows
  when the next would break the budget and prints `(+N more facts not shown)`. An honest omission
  beats a prompt the model cannot read.

139 tests green (6 new for the budget: the total-fits property across 1–20 rows, the floor, the
max, the clip marker, and that an unclipped value is left untouched). Version 0.26.1 / build 115.

## 0.26.2 — the broker's verb comes from the OUTBOUND METHOD (build 116)
2026-10-19

Mido tapped "always notify" on a Todoist read and it silenced deletes too: *"I clicked meaning it
as a read only operation."*

**Not a mis-tap — the pair had no read in it.** The broker is ONE route for every third-party call:

```kotlin
Route("POST", "/secrets/$secretName/call", ..., Verb.EXECUTE, ...)
```

So policy, the audit label, the exception button and the AI facts all keyed on `todoist/EXECUTE`.
"Reads only" was **inexpressible**, and the audit log said `todoist.execute` for a GET.

**The fix: derive the verb from the method in the payload.**

```
GET / HEAD / OPTIONS  -> READ
DELETE                -> DELETE
everything else       -> WRITE
```

New `core/OutboundVerb` (pure, unit-tested). The mapping is by **effect**, and it errs loud: only
methods that provably change nothing are READ, and anything unrecognised is a WRITE — because the
one direction that can hurt is guessing quiet, which would make a destructive call approvable by
silence on a NOTIFY card. A POST-based search API therefore reads as WRITE; visible and
interruptive is the safe way to be wrong.

**Designed for the smallest possible diff**, as asked:

- `Route.verbFor: ((Req) -> Verb)?` plus `Route.effectiveVerb(req)` — opt-in per route, so every
  other route keeps its static verb and the gate is not rewritten.
- Dispatch computes the verb **once** into a local and the six consumers read that local, so they
  cannot disagree about what the request was. That single local IS the fix; the alternative was
  six independent derivations.
- A second `bindRoute` after the body is parsed, because the first bind ran before the body
  existed and could only record the static fallback. `bindRoute` is a plain field-set, so the
  later call wins with no duplicate label.

**Consequence, accepted:** existing `todoist/EXECUTE` rules stop matching. Fine under the
no-standing-backward-compat rule — not live, so the fix is to re-tap.

144 tests green (5 new: the read set, DELETE as its own verb, the mutating set, and that the
fallback is LOUD for `TRACE`/`PROPFIND`/empty — plus case/whitespace tolerance). Version 0.26.2 /
build 116.

## 0.26.3 — the prompt's worked example was being parroted (build 117)
2026-10-19

Mido: *"still says adding 8 todos which is VERY misleading"* — on a **read**.

**I caused this, one release after fixing the last one.** The prompt said:

```kotlin
"say it: 'Adding 8 todos' beats 'updating tasks'"
```

To a 0.6B model a concrete example is not an illustration, it is a **template**. It copied the
sentence onto every request, including reads that add nothing. The previous release taught it the
word "8"; this release taught it the whole phrase.

**Two fixes, because removing the example is not enough on its own.**

1. **No worked example in the prompt.** The rule is stated abstractly — *"Do not state a quantity
   unless the summary or facts contain it"* — because a model that needs an example will copy it.

2. **`core/SummaryGuard` — a deterministic check the model cannot talk its way past.** Two lexical
   rules, both cheap:

   - **The verb must match the language.** A `READ` must not claim to mutate (`add`, `delet`,
     `updat`, …). Read vocabulary is checked first, so "listing tasks, adding nothing" survives —
     an accurate sentence must not be discarded for a word used in negation.
   - **A stated quantity must exist in the facts.** If the description says "8", something the
     model actually saw must contain "8". A believable number is the most dangerous kind of error
     because "8 todos" reads as measured.

   Rejection yields **no AI line**, not a corrected one. The modal still renders every
   deterministic row, so a blank summary section is honest where an invented one is not — the same
   fail-closed choice as `parse` returning null on malformed JSON.

**The lesson worth keeping:** for a small model, an example in a prompt is instruction-shaped. Any
future prompt change should be checked against "will it copy this sentence verbatim?" — and the
guard now catches the answer when it does.

152 tests green (8 new for the guard, including the exact reported case). The guard test was
**proven to fail**: breaking the empty-description check fails `an empty description is not shown`.
Version 0.26.3 / build 117.

## 0.26.4 — the facts named a URL, so the summary could only restate it (build 118)
2026-10-19

Mido: *"says read todoist api both times, absolutely useless"*.

And it was **accurate** — that is the problem. For a GET the model received only:

```
TARGET: GET https://api.todoist.com/api/v1/tasks
SECRET: ***
HOST: api.todoist.com
```

There is nothing else to say about that. **A bigger model would produce the same sentence**, because
it is being asked to describe a URL. So this is a FACTS bug, not an intelligence bug — the third time
in this feature that the model was blamed for what the pipeline fed it.

Two additions, both deterministic (true with or without the AI):

- **The summary names the RESOURCE.** `describeSecretCall` now takes the last meaningful path
  segment — `todoist · GET tasks` instead of `todoist · GET /api/v1/tasks`. Derived from the path
  rather than a per-API dictionary: this route is generic (any secret, any host), so a table would
  be a second thing to maintain and would silently miss every API nobody added.
- **The query is part of the prompt AND the modal.** `QUERY: project_id=…, limit=…` is often the
  only substantive content of a read — a project scope, a filter, a search term. Without it the
  modal says "read todoist api" and the human has nothing to judge.

The summary line carries the query too: `todoist · GET tasks · query: project_id=…, limit=50`.

**The standing lesson, now three releases deep:** when a summary is unhelpful, read the facts before
touching the model. Every time so far the pipeline was the fault — a truncated body, a route string,
a bare URL.

152 tests green. Version 0.26.4 / build 118.

## 0.26.5 — the AI's text now reaches the log (build 119)
2026-10-19

Mido: *"let's add the AI text to logs"*.

Every round of tuning this feature has been blocked on the same missing fact. The log recorded
`summarised in 1198ms: risk=LOW` — the timing and the bucket, never **the sentence**. So "what did
the modal actually say?" was a question only a human holding the phone could answer, and four
releases went by asking it.

Two log points now carry the text, through `Debug.log` so it lands in **both** channels — the
on-screen ring (`GET /debug` → `logTail`) and logcat (`GET /logs`):

- **Accepted:** `ai[todoist/READ] 1198ms risk=LOW why="…" said="…"`
- **Rejected by the guard:** `ai[REJECTED verb=READ] said="Adding 8 todos"`

The rejected line matters most. A guard rejection is the model being wrong, which is precisely the
event worth seeing, and it was previously one `Log.w` line that `/logs` could not reach.

Redaction runs inside `Debug.log`, so a summariser that echoes something token-shaped cannot leak
it through the new line.

Recorded because it generalises: **a diagnostic that reports a duration but not the produced text
answers "did it run", never "was it right".** Both are needed, and only one was being logged.

152 tests green. Version 0.26.5 / build 119.

## 0.27.0 — insets on every screen, and more room above the model row (build 120)
2026-10-19

Mido: *"fix inset. Add more top margin to the model"*.

**Insets — all six remaining screens.** Only `ApprovalActivity` had system-bar handling (added in
0.23.0); `MainActivity`, `SettingsActivity`, `LogActivity`, `LogDetailActivity`, `SecretsActivity`
and `GoogleLoginActivity` had none. The app is edge-to-edge at targetSdk 35, so content drew under
the status bar and pinned controls sat under the gesture bar. This was the third item on the
test-sweep list and had been open since it was reported.

One shared helper — `Theme.inset(root)` — rather than six copies:

- **Insets, never a fixed dp.** Bar height varies per device, OEM and orientation.
- **Adds to the existing padding** instead of replacing it. Several roots already pad for breathing
  room, and clobbering that would have made every screen flush to the edges — the fix would have
  traded one layout bug for another.
- **Top and bottom only.** Horizontal insets are each screen's own concern; adding them here would
  double-pad.
- Returns its argument, so a call site reads `setContentView(Theme.inset(root))`.

`LogDetailActivity` has three `setContentView` sites (aged-out, unparseable, normal) but each
branch returns immediately, so only one ever runs and the listener cannot stack.

**Model row — 16dp above it, on BOTH branches.** The model control can be a large action button
(during download) or a plain status row (when ready). The margin is applied to both, because
applying it to only the button makes the spacing collapse the instant the download finishes — a
layout jump at exactly the moment the user is watching that row.

Also in this release: **8 OpenClaw todos closed**, verified against the code first rather than from
memory (Material 3, the UiKit cleanup, the AI engine, the risk badge, the modal arm delay, both
alert sounds, and the NOTIFY card's AI line). Each closed individually with its own `sync_status: ok`.

152 tests green. Version 0.27.0 / build 120.

## 0.27.1 — the prompt is logged, and content leads the facts (build 121)
2026-10-19

Mido, on a write: *"ai desc says POST https://api.todoist.com /api/v1/tasks this is BS. Are you sure
we are passing all body text to the model?"*

**I was not sure, and I had no way to become sure.** I had been asserting "the body reaches it" by
reading the code, three times across three releases. That is the exact failure this project keeps
paying for, so the first fix is not the prompt — it is the evidence.

**1. The prompt itself is now logged.**

```
ai-prompt[todoist/WRITE] plugin: todoist ⏎ verb: WRITE ⏎ … ⏎ facts: ⏎ - TARGET: … ⏎ - BODY: …
```

Through `Debug.log`, so it reaches both `/debug` → `logTail` and `/logs` → logcat. Truncated at 600
chars, which is enough to see the STRUCTURE — which rows exist and what leads — without flooding the
ring. "What did the model see?" is now answerable from the log instead of from confidence.

**2. Content-first fact ordering, which is a real fix and not a tidy-up.** A 0.6B model reads the
first fact and largely describes THAT. With `TARGET: POST https://api.todoist.com/api/v1/tasks`
first, every write summarised as the URL — the body was present and simply never reached. The
prompt now orders rows by audience: `PAYLOAD`/`BODY`/`QUERY` lead, `TARGET`/`HOST`/`SECRET` trail.

**Two audiences, two orders, one source of facts.** The MODAL keeps URL-first because a human
scanning an approval wants the address. The MODEL gets content-first because it will restate
whatever it reads first, and "POST tasks" says more than a URL ever will.

Recorded because it generalises: **a prompt is read in order, and a small model weights the
beginning.** Fact ordering is a prompt-engineering decision, not a data-model one.

152 tests green. Version 0.27.1 / build 121.

## 0.28.0 — swapped to Qwen3-1.7B (build 122)
2026-10-19

Mido: *"swap"* — after the 0.6B copied `TARGET` verbatim even with the body FIRST in the prompt
(`ai-prompt: - PAYLOAD: content ⏎ - BODY: {"content": "TEMP — prompt-log test"} ⏎ - TARGET: …` →
`said="POST https://api.todoist.com/api/v1/tasks"`).

**The diagnosis, from evidence rather than belief.** Six releases of fact-tuning could not fix it:
truncation, route strings, bare URLs, resource names, query rows, content-first ordering. Each was a
real defect and each helped. The 0.6B still pattern-matched a distinctive string out of the prompt
instead of reading it. That is a model ceiling, not a pipeline bug — and the prompt log from 0.27.1
is what finally made it provable instead of arguable.

**The swap is four constants, by design:**

| | 0.6B | 1.7B |
|---|---|---|
| file | 0.34 GB | 0.98 GB |
| inference | ~1.2 s measured | ~3.4 s est |
| RAM idle | ~700 MiB | ~2.0 GiB |
| gated | no | no |

Same family, same `dynamic_wi4b32_afp32` quantisation recipe — so the chat template that killed
`mixed_int4` is the one already proven working. `MODEL_ASSET` names a different file, so the app
re-downloads automatically and the old 0.6B file is simply unused; reverting is one constant.

Both budgets still fit (10s SECURE, 10s NOTIFY). **The real cost is memory, not latency:** ~2 GiB
resident on a 24/7 foreground service makes the app a prime LMK target. The `onTrimMemory` release
valve already exists for exactly this and a killed engine reloads rather than denying — so the
failure mode is "slower next time", not "bridge down".

**What this is expected to fix, and what it is not.** It should compose a sentence from the facts
instead of echoing one. It will not make a bad fact set good — the deterministic summary already
carries the payload, and that work stands on its own with the AI switched off.

152 tests green. Version 0.28.0 / build 122.

## 0.28.1 — engine load no longer counts as inference time (build 123)
2026-10-19

The 1.7B swap appeared to fail on the first request:

```
04:19:34  engine creation starts (OpenCL init)
04:19:49  engine ready                        <- ~15s
04:19:52  inference starts
04:19:54  "inference 19843ms exceeded budget 10000ms; discarding"
```

**The timer was started before `createEngine()`.** So a cold start — mapping a 1 GB model and
initialising OpenCL — was charged to the *inference* budget, and a perfectly good answer was
discarded as "too slow" purely for being the first one.

That is a measurement bug, and it had been there since the summariser was written. It only became
visible now because the 0.6B warmed fast enough (~0.4 s) that the load cost never mattered.

**Fix:** `val start` moved to AFTER the engine exists, so the budget measures what it claims to
measure — the human's wait for a summary, not the one-off cost of setting up the runtime.

**Why this is not a fudge to make the bigger model pass.** The budget exists to bound how long a
decision waits for a helpful line. Engine construction is setup: `warm()` is supposed to have paid
it at service start, and when it has not, the correct answer is to make the FIRST request slow, not
to silently return no summary. What was happening before was the worst of both: the work completed
and then got thrown away.

**Still true and worth stating:** ~2 GiB resident on a 24/7 foreground service means more cold
reloads after `onTrimMemory`. This release makes each one cost time rather than a missing summary.

152 tests green. Version 0.28.1 / build 123.

## 0.29.0 — APPROVE wears the risk colour (build 125)
2026-10-19

Mido: *"lame description but it increased the risk detection which is alone very useful. make the
approve button color match the risk label color"*.

Worth recording his first clause: **the swap half-worked.** The 1.7B's description is still weak —
it echoed a prompt field until that field was removed in 0.28.2 — but its RISK assessment is
genuinely better: `risk=HIGH why="Writes to external API"` on a write, where the 0.6B said `LOW`.
That is the one output a small model can be trusted with, because it is a single label rather than
composition. The swap is justified on that alone.

**The change:** APPROVE is tinted by the risk bucket — green / amber / red for LOW / MEDIUM / HIGH.

Three design decisions, each to avoid a specific trap:

- **One mapping, in `Theme.riskColor`.** The card had its own private palette (`0xFF81C784` and
  friends) while the modal used the semantic colours, so "HIGH" was literally two different reds
  depending on where you looked. Both now delegate to one function; a second copy is how two
  surfaces start disagreeing about what a word means.
- **DENY stays NEUTRAL.** Two coloured decision buttons read as a pair of equal choices — exactly
  what the 0.23.0 pass separated them to avoid. Red APPROVE beside grey DENY still says
  "approve, carefully".
- **No risk = no colour.** While loading, and when the fetch FAILED, the button keeps its resting
  green rather than implying safety. An absent assessment is not "safe", and painting it green
  would say that it was.

Implemented through an `applyRisk` holder rather than touching `allow` directly: the tick that
paints is created ~150 lines before the button exists, and `applyArmed` already solved that exact
ordering problem the same way.

152 tests green. Version 0.29.0 / build 125.

## 0.30.0 — PAYLOAD quotes values, so the model can name the thing (build 126)
2026-10-20

Mido: *"Says Create task with title and description. This is toooo generic from what i want, I want
something like Creating a test todo item or something. experiment with the prompt"*.

**The prompt was not the problem — the FACT was.** `PAYLOAD` reported field NAMES only:

```
- PAYLOAD: content · description
- BODY: {"content": "TEMP - write test 2026-10-20", "description": "delete after test"}
```

So the model could only restate the field list. It could not say "Creating a test todo item" because
that text existed in `BODY` and nowhere it was reading first. **Same lesson as six releases of this
feature: when a summary is generic, read the facts before touching the model.**

`PayloadShape` now quotes each scalar, clipped to 60 chars:

```
- PAYLOAD: content="TEMP - write test 2026-10-20" · description="delete after test"
```

The full text still lives in `BODY`; this row exists to be *read*, not to carry the payload.

**Credential-shaped keys are named but their values are withheld.** A blunt substring check
(`token`, `secret`, `password`, `auth`, `key`, `credential`, `cookie`, `session`). This string
reaches both the model prompt AND the on-screen AI line, so echoing a token would print it. The
field is still reported, so the model knows something was set — it just does not see what. A false
positive costs one uninformative field; a false negative leaks a credential into a log and a screen.

Tests went 152 → 154: the old "names the field, never its value" assertion was replaced (it encoded
the behaviour being fixed), plus coverage for clipping and the credential guard.

Also this session: **3 temp tasks deleted** (verified by re-read, not by trusting `sync_status`), and
one false positive correctly ignored — "Secrets: **temp**lates + 10 famous integrations prefill" is a
real backlog item that merely contains the word.

154 tests green. Version 0.30.0 / build 126.

## 0.31.0 — the model gets the request body VERBATIM (build 127)
2026-10-20

Mido: *"no fucking processing of any kind. the full json as is + plugin name + url + our system
prompt"*.

He is right, and this closes an eight-release arc of getting it wrong.

**Every previous version transformed the payload first.** Truncated it at 200 chars; reordered its
rows; derived a "shape" from its arrays; quoted its scalar values; rendered it through a budget.
Each step was a judgement about *what mattered*, made by us, before the model ever saw the request.
Each produced a summary that described our view of the request rather than the request.

**Now: `plugin`, `verb`, `route`, `url`, and the body exactly as the caller sent it.** Nothing
derived, nothing reshaped, no rows. `RequestFacts.rawBody` carries the bytes captured by
`readRawBody` before parsing, so it is what arrived — not a re-serialisation.

The only remaining concession is `PromptBudget`, and it is a hard platform limit rather than a
judgement: an input that overflows the context window fails the whole request, because the AI is
core. So the window went **1024 → 8192 tokens** (the old value could not hold a 4.9 KB body), and
the fact budget **2,000 → 16,000 chars**. When it does bite, the truncation is *marked* — a silent
cut presenting half a payload as whole is the exact failure this feature kept paying for.

Also raised `MAX_PER_ROW` 600 → 12,000, which existed to stop one row starving others but was
clipping real bodies to their first few fields.

**The system prompt was rewritten for what it now receives:** "You are given the request body
exactly as the caller sent it. Read it and say what the request actually does, naming the SPECIFIC
thing it acts on… 'Creating a test todo item' beats 'Create task with content and description'."

`SummaryGuard` now checks stated quantities against the raw body rather than a rendered row list —
it validates against what the model actually saw.

155 tests green. Version 0.31.0 / build 127.

## 0.32.0 — the detail is GUARANTEED from the body, not requested (build 128)
2026-10-20

Mido: *"no you shit, even the example you gave is not what i want. I want FUCKING DETAILS FROM THE
BODY"*.

He was right twice. The example I wrote — "Creating a test todo item" — was **my invented phrase**,
not text from his body. And prompt wording had failed eight releases running, so asking a ninth
time was not a plan.

**So the detail is no longer requested. It is supplied.**

New `core/BodyDetail`: given the verb and the raw body, it produces the line itself —
`Writing "test todo item"`, `Deleting "old notes"`, `Reading "today"`. `LiteRtLm.summarize` keeps
the model's sentence only when it actually quotes a body value; otherwise the model's **risk** is
kept (which it is genuinely good at — a single label, not composition) and the description comes
from the body.

**Three real bugs surfaced while testing it, all in code I had just written:**

1. **`optString` on a container returns RAW JSON, not ""** — so the walk quoted `["a@b.com"]` and
   `{"query":"today"}` at the user as if they were prose. Fixed with a `scalar()` gate that accepts
   only strings and numbers.
2. **The fallback quoted routing metadata.** A brokered GET carries `{"url":…, "method":"GET"}`,
   and the fallback happily returned `"GET"` — producing `Reading "GET"`. Fixed with a
   `NOT_CONTENT` deny-list (url, method, headers, ids, version).
3. **`Sending` was wrong phrasing** for a create; `Writing` is true of every write without guessing
   which kind.

All three were caught by `BodyDetailTest`, not by reading the code — which is the point of writing
tests for the part that decides what the human reads.

Also removed the blunt `namesSomethingFromTheBody` rule I had added to `SummaryGuard`: it broke four
existing tests and, worse, **rejection means no line at all** — so it punished the human for the
model's failure. Guaranteeing the detail is strictly better than refusing the summary.

165 tests green (10 new for `BodyDetail`). Version 0.32.0 / build 128.

## 0.32.1 — reverted to Qwen3-0.6B (build 129)
2026-10-20

Mido: *"too slow and I think it crashed. new plan: recert to the old small one."* — and *"revert,
only model"*, so nothing else changed.

**The 1.7B was the wrong trade.** It measured ~3.4s per inference and ~2 GiB resident, and the
bridge process died three times in one morning: twice while idle and once mid-request, during an
inference. Whatever the precise kill — low-memory reaper or a native abort, unconfirmed because the
process took its log with it — the memory was the only difference from the 0.6B, which had run for
days at ~700 MiB.

**What the 1.7B actually bought, in the end: better risk labels.** That is all. Its descriptions
were still structural restatements — *"Create task with content and description"* — which is exactly
why the description is now built deterministically by `BodyDetail` rather than asked of any model.
Once composition stopped riding on the model, the remaining job was a single risk label, and that
is the kind of task a small model handles reliably. So 1.3 GiB of extra resident memory was buying
a marginal label improvement and an unstable service.

Four constants, as designed for a swap:

```
MODEL_ASSET    qwen3_1_7b → qwen3_0_6b
MODEL_URL      Qwen3-1.7B → Qwen3-0.6B
EXPECTED_SIZE  977,184,032 → 344,671,744
EXPECTED_SHA   2a72330b… → 03e7da1e…
```

The filename differs, so the app re-downloads the 345 MB file. **The old 1.7B file stays on disk and
is dead weight** (~1 GB) — nothing deletes it, because deleting a user's file to reclaim space is
not this app's call.

The doc comment in `ModelRegistry` was rewritten: it still argued for the 1.7B, which would now be a
lie sitting next to the code.

165 tests green. Version 0.32.1 / build 129.

## 0.32.2 — a failed start says WHY (build 130)
2026-10-20

Mido: *"on server fail user should see clear error"* — after reporting *"i click start server
nothing happens"*.

He was right on both counts, and they were the same defect seen from two sides. The service
refused to bind (no tailnet address) and called `stopSelf()`, which left `serverUp=false` and the
reason in `lastError`. The hero card then rendered with `!serverUp` checked **before**
`lastError`, so the branch that prints the reason was unreachable in exactly the situation that
sets it. Tapping START looked like a dead button: the service started, refused, stopped, and the
screen kept saying *"Tap START SERVER to begin"*. The refusal was never missing — it was
computed, stored, and then thrown away one line before it reached the screen.

**The fix is structural, not a reordered `when`.** The precedence rule now lives in
`ui/ServerStatus.kt` — a pure object with `state`/`title`/`detail`/`checklistValue` — because an
ordering rule that decides what a human sees cannot be tested from inside an Activity, and this
one had no test at all. The project has paid for this shape twice before (`ResponseBody.classify`,
`HostAllowlist`): a decision inlined in the view or the I/O path is a decision nobody can
exercise, so the wrong branch is the one that ships. `ServerStatusTest` pins the ordering, and
it was proven to fail — sabotaging the `FAILED` branch (`lastError.isNotBlank() && serverUp`, so
it could never fire with the server down) turned 4 of its 8 tests red before being reverted.

What the user sees now:

- `● Could not start` on a **DANGER** surface, with the reason underneath in the danger colour —
  not the muted body colour it used to share with log lines.
- The Setup checklist row says *"not running — see the error above"* instead of "stopped", which
  read like a contradiction under a red heading.
- `startBridge()` clears `lastError` first, so a retry after fixing the cause cannot briefly show
  the old reason while the new start is still binding.

The error strings themselves were also rewritten to be actionable rather than diagnostic. The
tailnet refusal — the one that fired here — now reads *"No Tailscale address on this phone. Open
the Tailscale app, connect it, then tap START SERVER again."* It previously said *"no tailnet
address found; refusing to bind 0.0.0.0"*, which names an internal decision the user cannot
act on. Same treatment for the bind failure, the unreadable policy file, and the unreadable
secret index.

**Not changed: the refusal itself.** Binding to the tailnet address and refusing to fall back to
`0.0.0.0` is a deliberate security decision (S8) and stays. A bridge that silently binds every
interface when Tailscale is down would be reachable from any network the phone joins.

173 tests green (8 new in `ServerStatusTest`). Version 0.32.2 / build 130.

## 0.32.3 — four leaks that were killing the phone (build 131)
2026-10-20

Mido: *"my phone was DYING until I uninstalled the shit out of the app!"* — and he was right,
which makes this the release where the most important bug was not the one being chased. Two
hours went into the Tailscale path because that is what the *symptom* looked like: the bridge
would not bind, so the server never came up. Tailscale was real, but third-order. The app was
the disease.

Four defects, all confirmed by reading the code after he uninstalled (so none of this is
device-measured — see the honesty note at the end):

**1. `NotifStore` had no cap at all.** It was `items[sbn.key] = sbn` on a `LinkedHashMap`, with
nothing ever evicting. Every notification the listener saw stayed resident for the life of the
process, and each value is a whole `StatusBarNotification` — a `Bundle` of extras plus whatever
bitmaps the posting app attached. The process is a `specialUse` foreground service, i.e. one the
OS is *obliged* to try to keep alive, so the growth had no ceiling and no pressure to stop it.
This is the one that made a phone feel like it was dying. Now bounded at
`NotifCapacity.MAX_ITEMS` (200), oldest-first, applied on both `put` and `seed` — `seed` because
a busy shade on first bind is exactly the bulk path that would otherwise bypass the cap.

**2. The model was never released when the service stopped.** `onTrimMemory` released it, but
that is a HINT the OS may never send, so a stopped service could hold ~700 MiB indefinitely.
`onDestroy` now calls `ModelRegistry.release()`. The model FILE is untouched; the next start
reloads it.

**3. The warm-up ran BEFORE the bind check, so every failed start paid for it.**
`onCreate` spawned the warm thread, and `onCreate` runs before `onStartCommand` — so a start
that immediately refused (no tailnet address, unreadable policy, bad port) still loaded the
engine and ran a full throwaway GPU inference, then called `stopSelf()`. He tapped START
repeatedly while the bind was failing, so each tap bought an engine load for a service that
never served a request. The warm moved into `onStartCommand`, after `markRunning(true)`: only a
server that exists pays for it.

**4. `warm()` was check-then-act with no lock.** `engine ?: createEngine()` means two concurrent
callers can each build a full engine — two ~700 MiB mappings for one model. `warm()` and
`release()` now share the same monitor, so a release cannot race a load and leak the engine it
was about to assign.

**Why the capacity rule is a separate pure object.** `NotifStore` holds `StatusBarNotification`,
which does not exist on the JVM — so the unbounded map shipped for dozens of releases while
`testDebugUnitTest` stayed green, because no test *could* see it. `NotifCapacity` is the
Android-free half and `NotifCapacityTest` pins it, asserting on the returned keys rather than
counts: a count-only test passes when the code evicts the newest entry, which would quietly drop
the notification a human just received — a worse bug than the leak being fixed.

**Honest scope note:** these are code-reading findings. He uninstalled the app before any of it
could be measured, so there is no `/debug` memory figure, no `/logs` trace, and no device
confirmation that 0.32.3 behaves better. It ships as an untested-on-device fix and should be
described that way until he confirms it.

181 tests green (8 new in `NotifCapacityTest`). Version 0.32.3 / build 131.

## 0.33.0 — keep the config, never the secrets (build 132)
2026-10-20

Mido: *"I didn't like losing all my config on a fresh install. can we keep everything except the
secrets on uninstall?"*

`android:hasFragileUserData="true"`. Android then shows a **"keep app data?"** checkbox on
uninstall; ticked, `filesDir` and SharedPreferences survive — the gate policy and its rules, the
audit log, and the account email. That is the config that was actually being lost.

**Secrets cannot survive, and this is not an exclusion — it is the platform.** The vault's
wrapping key lives in `AndroidKeyStore`, and a Keystore entry is deleted on uninstall no matter
what this flag says. So a kept-config reinstall restores the wrapped data key and its IV while
the key that unwraps them is gone. `allowBackup` also stays `false`: this keeps data on THIS
phone across a reinstall and sends nothing off the device, so the refresh token still never
leaves.

**That half-state was a trap, and fixing it is the real work in this release.** Two things
combined to make it a permanent dead end:

1. `vaultKey()` **generates a key when the alias is missing**. So the act of trying to unlock
   would mint a fresh VK, then fail to unwrap the old blob with it (GCM tag mismatch) and throw
   `vault_unwrap_failed` — an error that reads like corruption.
2. `isUnrecoverable()` only checked for a **missing wrap IV** (the 0.14.x bug), so this new case
   answered `false` and went down the ordinary unlock path — which can never succeed.

Result without a guard: a prompt that accepted a fingerprint and failed, every single time,
forever, with no reset offered. The same dead end as the 0.14.x vault, reached a new way.

So `VaultState` was added — a pure object over three booleans (`wrappedExists`, `ivExists`,
`keyExists`) resolving `ABSENT` / `UNLOCKABLE` / `UNRECOVERABLE` / `ORPHANED`. `SecretVault.state`
reads the key presence with `containsAlias`, **never** by calling `vaultKey()`, because that
getter would create the key and erase the evidence. `lockOnLoad` routes `ORPHANED` to the reset
dialog with its own wording, because "an old build destroyed your vault" is a lie when the truth
is "you reinstalled and kept the config".

`VaultStateTest` pins all eight combinations and was proven to fail: deleting the `ORPHANED`
branch sent it red exactly there, then back to green.

188 tests green (7 new in `VaultStateTest`). Version 0.33.0 / build 132.

## 0.33.1 — the KV cache was eating the phone (build 133)
2026-10-20

Mido: *"something is definitely wrong all this shit happened after we reverted our large AI model
with the small one, something was fucked up badly it's like the server eats ALL memory"*. He was
right, and it took me two wrong answers to get there.

**The revert was incomplete.** 0.32.1 changed exactly one source file — `ModelRegistry.kt`, four
constants naming the 0.6B artifact. It never touched `LiteRtLm.kt`, so the context window stayed at
the value 0.31.0 had raised it to **while the 1.7B was live**. 0.31.0's own comment said the quiet
part: *"The cost is KV cache: this is the dominant memory term."*

The arithmetic, for Qwen3-0.6B at fp16 (28 layers, 8 KV heads, 128 head dim):

```
KV bytes/token = 2 * 28 * 8 * 128 * 2 = 112 KiB

 1024 tok -> ~112 MiB
 8192 tok -> ~896 MiB   <- what 0.32.1 actually shipped
 2048 tok -> ~224 MiB   <- this release
```

So "revert to the small model" cut the weights by ~650 MB and left the cache **~800 MB larger**
than the 0.6B had ever run with. Net heavier than the 1.7B it was meant to replace.

That explains the whole afternoon, which none of the earlier fixes could: a foreground service
holding ~1.3–1.5 GB applies memory pressure → Android's low-memory killer reaps background
processes → **Tailscale is a background VPN and a prime victim** → the phone loses its `100.x`
address → `tailnetAddress()` returns null → the app correctly refuses to bind → no server. Fixes
aimed at the bind and the error message changed nothing because the bind was never the problem.

**What changed:**

- `MAX_CONTEXT_TOKENS` 8192 → **2048**.
- `PromptBudget.MAX_TOTAL_CHARS` 16,000 → **4,000** and `MAX_PER_ROW` 12,000 → **4,000**. The
  budget's own comment already said it "has to be derived from the CONTEXT WINDOW, not picked by
  feel" — so leaving it at 16,000 against a 2,048-token window would have overflowed the window and
  failed the request outright, since the AI is core.
- The stale doc block in `PromptBudget` that still justified its numbers against a 1024-token
  window is rewritten. A limit outliving the constraint that justified it is exactly how this bug
  happened once already.
- `PromptBudgetTest`'s "a 5 KB payload fits without clipping" was **inverted into two tests**,
  because the trade changed deliberately: a 4.9 KB body is now clipped AND marked, while a 3.5 KB
  body still passes whole. Both halves are asserted, so neither can drift silently.

**`/debug` now reports real memory.** `storage.memoryFreeBytes` was never an answer to "how much is
the app using" — it reports the device's free RAM, which says the phone has less left but never
what took it. Every question about this app's footprint therefore needed a calculation from the
model constants, and the calculation was wrong for weeks because nothing on the device contradicted
it. The new `memory` section reports `totalPssBytes`, `javaHeapBytes`, `nativeHeapBytes`,
`otherPssBytes`, `javaUsedBytes`/`Committed`/`Max`, plus `aiContextTokens` (the window the running
build was compiled with) and `aiModelResident`. Native is split out on purpose: a ~896 MiB KV cache
is native and would be invisible in a heap-only view — precisely the mistake that hid this.

189 tests green. Version 0.33.1 / build 133.

**Still unmeasured.** The ~896 MiB figure is arithmetic from the model configuration, not a reading
from the device: the bridge would not come up long enough to answer `/debug` while this was being
diagnosed. The new `memory` section is what makes the next check a measurement — 0.33.1 should show
`totalPssBytes` dropping by roughly 670 MB against 0.33.0.

## 0.34.0 — Options screen, and "off" that means off (build 134)
2026-10-20

Two preferences, one new screen, and one seam that replaces nine scattered call sites.

**Rename first, so the diff stays readable.** `SettingsActivity` was the EXCEPTIONS screen and had
been misnamed since it was created — the skill docs refer to it as "the rules author" while its
own class comment says where exceptions are authored. It is now `ExceptionsActivity`, and the new
screen is `OptionsActivity`. One file each, no ambiguity about which one a setting belongs to.

**The two toggles, both defaulting OFF** (Mido, 2026-10-20):

- **Fingerprint for approval modals.** `requiresBiometric` becomes `mode == SECURE && policy.fingerprintForModals`.
  The secret vault is deliberately untouched: its key is `setUserAuthenticationRequired(true)`, so
  no setting can remove its fingerprint, and `lockOnLoad` still unlocks it at app start. A locked
  vault answers `secret_locked` exactly as it already does under NOTIFY — which never touched
  biometrics either, and is the precedent that made this a one-line rule rather than a new consent
  model. The first draft of this plan assumed a dilemma ("two taps, or let the modal ignore the
  option") that did not exist; NOTIFY had already answered it.
- **AI on/off.** Off means no model loaded, no download offered, no request described, and no
  `503 model_not_ready` — ever.

**`AiGateway` is the seam.** The root problem was `Route.aiSummary` carrying two meanings at once:
"this route does not want a summary" AND "this route is exempt from the readiness gate". Two
meanings in one flag is why a *global* off switch had nowhere to live — the gate consulted the
route, callers consulted the route, and `ModelRegistry` was reached directly from six places.
Now `AiGateway` owns readiness, warming, releasing, summarising and status. A route keeps one
honest meaning — *does this route want a description* — and every question about whether an AI
exists is answered in one function from one setting. `BridgeService`'s three `requiresAi` sites,
`ApprovalGate`'s and `NotifyOverlay`'s `SummaryProvider.run` calls, and the home screen's model
row all go through it.

**The preferences live in `policy.json`, and that was a deliberate trade with a specific fix.**
Mido: *"single file for now"*. The risk is that policy is fail-hard — an unreadable file refuses to
start the server, which is correct for a security decision and disproportionate for a preference.
So strictness is now split per field rather than per file: `global` and `rules` still throw,
while `fingerprintForModals` and `aiEnabled` fall back to their default and record a warning. And
`VERSION` did **not** move: an existing file parses unchanged, which matters because that file now
survives a reinstall.

`Policy` stays pure, and the first attempt did not. Logging a bad preference from `parse` reached
`android.util.Log`, which throws "not mocked" on the JVM — so the preference test failed with a
`RuntimeException` instead of an assertion. The warning now travels as `preferenceWarnings` data and
`PolicyStore` logs it. That is the same split this project keeps rediscovering: decide in a pure
function, act where Android exists.

The carry-over trap was closed in the same pass: `withRule`, `withoutRule` and the new `withGlobal`
/ `withFingerprintForModals` / `withAiEnabled` all pass every field explicitly, and `applyGate` now
calls `existing.withGlobal(after)` instead of `Policy(after, existing.rules)`. A positional
constructor is one edit away from silently resetting a toggle — which is exactly how the rule table
was wiped once before.

196 tests green (7 new). Version 0.34.0 / build 134.

## 0.35.0 — secret templates, and the rules made testable (build 135)
2026-10-21

Three built-in templates (Mido, 2026-10-20: *"add github, notion only"* — plus `todoist`, which is
the one actually in use). A new **ADD FROM TEMPLATE** button opens a picker; choosing an entry
opens the EXISTING add dialog prefilled with name, docs, key and hosts.

**The value field stays empty and required.** A template cannot create a working secret on its
own, which is the property that makes shipping a fixed list safe — there is no credential in the
source tree, and `SecretTemplatesTest` asserts the data class has no value-shaped field so one
cannot appear later by accident.

**Hosts are prefilled but editable** (Mido chose option 1). The concern was that a template
would make a security decision on the human's behalf: `HostAllowlist` is what stops a stored key
leaving the device somewhere it should not, so a wrong entry widens the blast radius permanently,
at creation. Exact api hosts, never wildcards — `*.todoist.com` would also permit every other
subdomain the vendor runs, which is not what "let this key call the API" means.

**The real work was making the rules testable.** `requireName` and `requireUrlOrEmpty` were
PRIVATE methods on `SecretVault`, so the only way to check a built-in template was to save it on a
device — inside a dialog, after pasting a real API key, where a typo in a list the human was told
to trust surfaces as a `bad_request` toast that reads like their mistake. They are now
`SecretRules`, a pure object, with `SecretVault` delegating to it. One implementation, so "what the
vault accepts" and "what the templates are checked against" cannot drift — the same move already
made for `HostAllowlist`, `ResponseBody` and `VaultState`.

`SecretTemplatesTest` (10 tests) checks every entry against those same objects: name legality,
https-only links, every host passing `HostAllowlist.requireRule`, hosts being exact rather than
wildcards, and a shared-suffix host (`evil-api.todoist.com`) still being refused. **Proven to
fail**: changing Notion's host to `*.notion.com` turned two tests red, then back green.

Two smaller decisions worth recording:

- A template's NAME is read-only in the dialog. The name is the plugin id, and the docs/hosts were
  chosen for that one service — renaming it would attach Notion's hosts to a secret called
  `todoist`.
- The picker refuses to start a template whose name already exists and points at the row's
  `replace` action instead. Opening "Replace" silently could wipe a host list someone had
  deliberately narrowed.

206 tests green (10 new). Version 0.35.0 / build 135.

## 0.36.0 — Drive, and the plumbing that makes bytes possible (build 136)
2026-10-21

Google Drive, with download and upload as the headline. Full `https://www.googleapis.com/auth/drive`
scope, no picker (Mido, 2026-10-21) — so **one re-login is required** after installing this.

**`drive.file` was rejected, and the reason matters.** It is the narrower scope and it cannot SEE a
file the app did not create, so "download my document" would answer not-found for everything already
in the account — defeating the point. The trade is stated plainly rather than buried: the token lives
on the phone, and the approval gate is the only thing between a caller and every file in the account.

**The plumbing refactor.** Every route returned `Resp(status, JSONObject)` and the server wrote
`application/json` — there was no path for bytes at all. `RespPayload` is now a sealed variant
(`Json` | `Stream` with its own content type and length), `Dispatched` carries it, and
`writeResponse` checks the payload before falling back to JSON. The ~13 existing construction sites
are untouched because `Dispatched.body` stayed a `JSONObject`.

**Three leaks were closed BEFORE any byte could flow**, because "streaming is fine as long as it
doesn't leak secrets" is a property, not a promise:

1. **File bytes into the audit log.** `AuditRecord` stores request bodies verbatim, capped at 32 KiB,
   so a streamed upload would have written the first 32 KB of the user's document permanently into an
   append-only log. New `Route.auditRequestBody`, mirroring the existing `auditResponseBody`.
2. **File bytes out of the audit log.** Same cap on responses; `auditResponseBody = false` on the
   download route.
3. **Caller credentials in the log.** `HOP_BY_HOP` filtered four harmless headers and NOT
   `authorization`, so a caller's bearer token was written verbatim into the request log. Now
   filtered. That one was never Drive-specific — it had been true for every route since the audit
   existed.

`auditRequestBody` and `streamsRequest` are deliberately two flags: one is about what the LOG keeps,
the other about MEMORY (`readRawBody` buffers the whole body into a String, fatal for a large file).
An upload needs both, for unrelated reasons.

**Drive routes.** List/search, metadata, create folder, rename, move, trash, download, upload. Trash
rather than delete: `DELETE` is permanent and unrecoverable, `trashed=true` is recoverable for 30 days,
and a destructive action should be the recoverable one unless someone explicitly asks otherwise. The
modal says so. Every mutating route FETCHES the file first so the card names it — an id is not
something a human can approve — and a bad id throws `not_found` before the modal opens.

**The flags live in `DriveRouteFlags`, a pure table `routes()` builds from.** The test cannot
construct a `Context`, so `routes()` is unreachable from the JVM suite — and the tempting fix (copy
the flags into the test) passes no matter what the plugin does, which this project has already paid
for twice. Deriving them means the test reads the same values production uses. `DrivePlugin.route()`
throws when a route has no flags entry, so a new route whose audit behaviour nobody decided fails at
registration instead of inheriting a default that merely looks safe.

212 tests green (6 new in `DriveAuditFlagsTest`). Version 0.36.0 / build 136.

**Not done, deliberately:** the phone-path routes (save-to-Downloads, upload-from-path). Streaming
covers caller↔Drive; those two are for "put this file on my phone" and need scoped-storage handling,
so they are their own pass.

## 0.36.1 — uploads read our own socket to death (build 137)
2026-10-21

First real device test of Drive: **download verified end-to-end, upload failed with
`500 internal_error: Read timed out` in 10.3 s.**

**The bug was `InputStream.copyTo`, and it is entirely mine.** `copyTo` reads until END OF STREAM.
An HTTP request body does not end at end-of-stream — it ends at `Content-Length`, and a keep-alive
client holds the socket open afterwards waiting for the response. So the first upload copied its
20,232 bytes correctly, then blocked on a read that could never return, and died five seconds later
inside `SocketInputStream.read`.

The stack made it unambiguous: `copyTo` → `GoogleApi.putStream` → `DrivePlugin.uploadFile`. Google
was never involved. We were waiting on our own client, which had already sent everything it was
going to send.

**Fix: `BoundedCopy.exactly`.** Copy exactly the declared length, never more, and ask only for what
remains on the final partial read — a full-buffer read with fewer bytes left is the same hang in a
smaller form. A stream that ends early throws `bad_request` naming how much arrived, because a
half-file that reports success is worse than a refusal: nobody goes looking for the missing half.

It is a function rather than an inline loop for the usual reason — the rule is "the body has exactly
this many bytes", which a unit test can prove and an inline copy inside an I/O path cannot.
`BoundedCopyTest` (7 tests) covers the stop-at-length case, the untouched remainder, the truncated
stream, a multi-buffer payload, and the zero-length case.

**What the device test proved before the failure:**

- `GET /drive/files` → 200, real files, correct metadata.
- `GET /drive/files/{id}/content` on a PDF → 200, `Content-Type: application/pdf`,
  `Content-Length: 20232`, and the saved file was `PDF document, version 1.4, 1 page` at exactly
  20232 bytes. **Byte-exact, no truncation** — the streaming download path works.

Also confirmed by that run: the Drive API had to be enabled in the Cloud project first (403
`accessNotConfigured`, not an auth error — a scope failure would have been 401), which is a setup
step rather than a code issue.

219 tests green (7 new). Version 0.36.1 / build 137.

**Still unverified:** the upload path after this fix. The download half is measured; the upload half
is a code-reading fix with a unit test, until a real file lands in Drive.

## 0.37.0 — save a Drive file straight to the phone (build 138)
2026-10-21

`GET /drive/files/{id}/content?to=phone` saves the file to **`Downloads/PhoneBridge/`** and answers
JSON instead of bytes. No `?to=phone` (or any other value) still streams to the caller exactly as
before, so the existing verified download path is untouched.

**One route, two sinks, and merging them was the point** (Mido, 2026-10-21: *"think about merging both
so that i can download a file from drive to phone directly"*). Metadata lookup, MIME resolution, the
Google-native refusal and the failure paths are identical; only where the bytes land differs. Two
routes would have been two copies of that, drifting.

**The permission question had a better answer than the one asked for.** Mido asked how to prompt for a
missing permission on a modal. There is no permission: contributing a NEW file to a MediaStore
collection is always allowed on API 29+, and the restriction applies to modifying files you do not
own, which this never does. So "modern only" costs the user no dialog and no grant — which is why no
prompt flow was built. Worth recording because the alternative (legacy
`getExternalStoragePublicDirectory` + `WRITE_EXTERNAL_STORAGE`) would have needed one, and below API 29
the route now REFUSES with `unsupported_storage` naming the level rather than silently writing to an
app-private directory and calling that "saved".

**Two refusals that happen BEFORE any bytes move**, because a human has already approved by then and
an opaque `google_error` afterwards is the worst place to learn the file was never downloadable:

  - **Google-native docs** (`application/vnd.google-apps.*`) have no binary payload — `alt=media`
    answers `403 cannotDownloadFile`. Refused with a message naming the type and pointing at export.
  - **Folders** are not files, and this release deliberately does not walk trees (Mido: *"ignore
    folders for now"*).

**The filename rule bit immediately, and the test caught it.** Drive names are arbitrary user input;
`"2026/10 report.pdf"` is legal in Drive and an illegal MediaStore `DISPLAY_NAME`. So separators
become `-` and NUL is stripped. But the first implementation also called `.trim()`, which silently ate
the leading space off a real file in the account named `" Cover tmp.pdf"` — legal, and the name the
user would search for. `PhoneSaveTest` failed on exactly that, and the fix was to strip only what is
genuinely illegal. A blank name still falls back to `drive-<id>`, because an unnamed entry in Downloads
is unfindable.

The MediaStore insert is rolled back on a write failure: a half-written row appears in the Files app as
a truncated file with nothing saying it was our fault.

**Still out of scope, deliberately:** folder download (mirror-tree or zip — undecided), upload-from-a-
phone-path, and lazy-load/idle-release for the AI engine.

228 tests green (9 new in `PhoneSaveTest`). Version 0.37.0 / build 138.

## 0.38.0 — the modal names the file properly (build 139)
2026-10-21

Mido: *"the modal needs improvements. i need full file details: full path and name, size(if possible)"*.

**The bug behind the request was bigger than missing fields: the download modal showed NOTHING.**
`GET /drive/files/{id}/content` is a GET, and `rowsFor` returned an empty list for every GET — correct
for "list my messages", where there is no target to name, and wrong here, where the human is deciding
about one specific file. The card was asking someone to approve an opaque request id.

**`rowsFor` now lets an explicit entry win over the read default.** If a plugin DECLARED rows for a
route, they are rendered — GET or not. Only when it did not does the read default apply. A forgotten
entry for a WRITE still throws `internal_error`, which is the guarantee that mattered: an approval
nobody can judge must not exist. The old rule conflated "reads have no target" with "this route is a
read", and Drive is the first plugin where those differ.

**The card now carries path, name, size and type.** A bare name cannot identify a file — two files can
be called `report.pdf` in different folders — so `detailsOf` fetches the metadata and walks the parent
chain to build `My Drive / Projects / 2026 / report.pdf`. Drive has no path field; the path only exists
by resolving each ancestor, which is why the walk is real work rather than a string join. It is bounded
at 12 levels so a malformed chain cannot spin forever inside a modal, and a missing ancestor truncates
the path rather than failing the card — a partial path beats a bare name.

**Size has its own trap, and it is tested.** Drive omits `size` for folders and Google-native docs, so
the caller passes -1. `DriveFormat.humanSize` renders that as **`unknown`**, never `-1 B` or `0 B`:
"unreported" and "empty" lead a human to opposite conclusions and must not look the same. Zero bytes is
a real size and still renders as `0 B`.

One formatter (`fileCard`) serves both the download and trash cards, because a card that shows the size
on trash but not on download is the same bug in a different place.

`DriveFormat.folderOf` was wrong on the first attempt: it trimmed trailing slashes instead of dropping
the filename, so a rename card would have said the file lived in `My Drive / Projects / report.pdf`.
`DriveFormatTest` caught it, and the fix shares one `SEPARATOR` constant with `fullPath` so the two
cannot disagree about what a separator is.

239 tests green (11 new in `DriveFormatTest`). Version 0.38.0 / build 139.

## 0.39.0 — the misclick guard is back (build 140)
2026-10-21

Mido: *"when AI is disabled, we need the 3 seconds protection again to avoid misclicking the dialog"*.

**The regression was a side effect of removing the AI timer.** Before 0.22.0 there was a fixed
`DECISION_DELAY_MS` before the buttons armed. That was replaced by "arm when the summary lands",
which is better in every way — except that with no summary to wait for there is nothing to arm on,
so the gate stamped `enabledAtMs = shownAtMs` and the buttons were live the instant the modal
appeared. A tap already in flight when the card arrives lands on APPROVE.

**`ArmGuard` is one rule used by BOTH arm paths**, not a fix applied only to the branch that broke.
The rule is a MINIMUM time since shown: `armAt = max(readyAt, shownAt + 3000)`. Applying it
per-branch is exactly how these two paths drift apart, and this project has paid for that shape
repeatedly — the audit flag honoured on one dispatch path and ignored on the other is the same bug
with a different field.

The guard can only ever DELAY arming, never bring it forward, so a slow summary is not punished
twice: a request that took 6s to summarise arms at 6s, while one that took 1.2s waits out the
remaining ~1.8s.

**Three things had to change together, or the guard would have been cosmetic:**

1. **The UI predicate.** It tested `enabledAtMs > 0`, which a FUTURE timestamp satisfies — so the
   buttons would have gone live instantly and the guard would have done nothing. Arming is now
   `ArmGuard.isArmed(enabledAt, now)`.
2. **A third countdown phase.** The modal now distinguishes `waiting for the summary` (no arm time),
   `ready in Ns` (guard window), and `auto-deny in Ns` (armed). The guard phase deliberately does
   NOT show the auto-deny clock: the 60s window has not started yet, and showing it would claim time
   the human has not been given. It is labelled rather than silent, because a modal with a dead
   button and no explanation reads as broken, and a human who thinks it is broken taps harder.
3. **`maxWaitMs()`.** The gate's latch budget did not include the guard, so a no-AI request would
   arm at +3s and then get 60s of countdown — 63s of legitimate life against a 60s latch. The
   caller would time out at the exact moment the human still had 3 seconds left, and the UI would
   be showing a window the server had already abandoned. The guard term is now part of the budget.

`ArmGuardTest` (7 tests) pins the boundaries, including the one that matters most — that the guard
only ever pushes arming later. One of my own test assertions was wrong on the first run (arguments
to `isArmed` reversed); the implementation was right and the test was corrected.

246 tests green (7 new). Version 0.39.0 / build 140.

## 0.40.0 — the SSH gate (build 141)
2026-10-21

Mido: *"build a generic ssh gate"* — refined over the session into: raw command string, ed25519
keys generated on the phone, host-key verification, empty allowlist, its own screen.

**It is a mechanism, not a policy.** Mido: *"the bridge offer a mechanism. the gate's role is
showing me exactly what I'm approving, if I approved something stupid that's on me"*. So there is
no command allowlist and no quoting: the string that arrives is the string that runs, and the card
shows it verbatim. The safety property is that card and wire are the SAME string — not that the
string is filtered.

**A premise in the original idea was wrong, and it was the one the safety argument rested on.**
"Send an argv array, no shell, nothing to inject" is not something SSH offers: the `exec` request
carries one string and the server hands it to `$SHELL -c`. There is no argv transport in the
protocol. Rather than fake it with our own quoting — which Mido rejected in favour of a raw string
— the design leans on the card being literal and on the human being the judge. That is stated
plainly here because the argv framing is what made the earlier draft sound safer than it was.

**Keys are generated on the phone and never displayed.** `SshKeygen` produces an ed25519 keypair;
the private half goes straight into the vault and the public half is shown once for
`authorized_keys`. JSch 2.28.8 carries a pure-Java `KeyPairEd25519`, so no BouncyCastle dependency —
verified by inspecting the jar, and the build confirmed D8 is happy with it.

**The private key would have been one tap from the screen, and that is why `SecretMeta.kind`
exists.** `SecretsActivity` offers reveal and replacement for every vault entry; for an SSH key
both would render or accept the private half. Entries are now marked `SECRET` or `SSH`, and SSH
entries show only delete plus a line saying why. One vault, one unlock, and the claim stays true.

**Host keys are confirmed, never assumed** (Mido: *"I confirm first"*). The first connection to a
host REFUSES with `ssh_host_key_unknown` and reports the fingerprint and algorithm; the caller
repeats the call with `trustHostKey` naming it, which pins it. A later mismatch refuses with
`ssh_host_key_changed` — the message says both readings (rebuild or impersonation) rather than
assuming either. Implemented as a `HostKeyRepository`, so JSch aborts the handshake itself; the
earlier draft tried to read `session.hostKey`, which does not exist in this version.

**`/ssh/{name}`** takes `{"command": ..., "trustHostKey"?: ..., "timeoutMs"?: ...}`. Output is
capped at 64 KiB with a 30s default timeout, and the session is always torn down. The command is
stored in the audit log verbatim (Mido: *"store"*) — a remote-exec feature with no record of what
ran would be worse than a log that might contain a secret.

254 tests green (8 new in `SshConnectionTest`). Version 0.40.0 / build 141.

**Not verified on device:** that ed25519 generation actually works on the S22, and that a real
connection authenticates. Both are runtime facts. The first thing to try is creating a connection
and reading back its public key — that proves the generator without needing a server.

**Known gap, stated rather than hidden:** there is no UI to import an existing private key, so
"generate, or use one if already generated" is currently generate-only.

## 0.40.2 — the upload streams, and SSH keys generate (build 143)
2026-10-24

Two independent first-run failures, both reported from the device, both proven on the JVM before
any fix was written.

**A 385 MB Drive upload died at ~150 MB with a connection reset, and the reset was a lie.** The
body was never being streamed: `HttpURLConnection` with `doOutput = true` buffers the ENTIRE body
into a `ByteArrayOutputStream` and only opens the socket afterwards — setting a `Content-Length`
*request property* does not change that. Against this app's 256 MB `javaMaxBytes`, a 385 MB file is
an `OutOfMemoryError` on the request thread, and a JVM dying mid-request looks exactly like a
network reset from the far end. Nothing in the phone's log mentioned memory, which is why the first
diagnosis (the network) was wrong.

Measured at the real size and the real heap ceiling:

```
property only:                        OOM java heap space, 128 MB sent, heapUsed 195 MB
  at java.io.ByteArrayOutputStream.write
  at sun.net.www.http.PosterOutputStream.write
setFixedLengthStreamingMode(385 MB):  385 MB sent, heapUsed flat at 3 MB
```

`HttpStream.fixedLength` now owns that call, and it is made BEFORE `getOutputStream()`. It also
emits `Content-Length`, so the explicit header is gone — two sources for one number invite a
mismatch. `HttpStreamTest` proves the rule structurally rather than by timing: with streaming the
server receives body bytes WHILE the client's output stream is still open; with the old code it
receives exactly zero until the close.

**`generate key` threw `UnsupportedOperationException` on the very first SSH connection.**
`SshKeygen` called `KeyPair.writePrivateKey`, which is built on `KeyPairEdDSA.getPrivateKey` — and
that class does not implement it. Every key this app creates is ed25519, so the failure was total:

```
Exception in thread "main" java.lang.UnsupportedOperationException
  at com.jcraft.jsch.KeyPairEdDSA.getPrivateKey(KeyPairEdDSA.java:75)
  at com.jcraft.jsch.KeyPair.writePrivateKey(KeyPair.java:163)
```

The fix is `writeOpenSSHv1PrivateKey(out, null)`, which encodes from `prv_array` directly and works
for every key type; the null passphrase is the documented no-passphrase case. `SshKeygenTest` now
covers generation, the PEM frame, the ed25519 round-trip, and that `JSch.addIdentity` accepts the
result — the last one mattering because a key that generates but cannot be loaded would fail every
run as `ssh_bad_key`.

**Both of these were unit-testable and untested.** JSch is plain JVM code and `putStream` uses a
JDK class, so neither needed the phone. The lesson recorded rather than implied: the one call that
could not possibly work was the only one with no test.

263 tests green (5 new in `SshKeygenTest`, 4 in `HttpStreamTest`). Version 0.40.2 / build 143.

**Still not verified on device:** that the 0.40.2 APK uploads the 385 MB file, and that a real SSH
connection authenticates. The streaming change is proven at the HTTP layer, not through Drive.

## 0.40.3 — trust-on-first-use actually works (build 144)
2026-10-24

The SSH gate had a THIRD defect, behind the two in 0.40.2, and this one made the feature's central
safety story unreachable.

**A refused host key was classified by its message text, and the message did not match.**
`SshExec` searched `e.message` for `"UnknownHostKey"` / `"not included"` to decide whether a
failure was `ssh_host_key_unknown`. JSch 2.28.8 says neither — an untrusted host throws
`JSchUnknownHostKeyException` with the message `reject HostKey: <host>`. So the first connection to
ANY new host fell into the generic `ssh_failed` branch, which reported no fingerprint and no
`trustHostKey` instruction. The confirmation step — the entire point of "host keys are confirmed,
never assumed" — could never be reached.

Measured against a real sshd on this host:

```
no pin   -> com.jcraft.jsch.JSchUnknownHostKeyException   "reject HostKey: 127.0.0.1"
bad pin  -> com.jcraft.jsch.JSchChangedHostKeyException   "HostKey has been changed: 127.0.0.1"
```

The fix classifies by **exception type**. `JSchHostKeyException` is caught first, and the two
subclasses map to `ssh_host_key_unknown` and `ssh_host_key_changed`. Anything else that is a
host-key refusal becomes `ssh_host_key_refused` — a new code, deliberately NOT a generic failure,
because it is still a decision a human must make and the fingerprint must stay visible. That code
had to be added to `ErrorCodes.MAP` (409, not 500); the existing guard test caught the omission
before the build did.

**Also fixed:** the plain `JSchException` branch was rethrowing the raw exception, so an auth
rejection escaped as an unmapped 500. It is now a coded `ssh_failed`.

**Verified end to end against the real sshd on this box**, driving the app's own compiled classes
(not a reimplementation) with a key generated by the fixed `SshKeygen`:

```
STEP 1  first contact, no pin   -> ssh_host_key_unknown + SHA256:c6PYkM0Z...
STEP 2  trust it, run a command -> exitCode=7, stdout "openclaw|exit-test", 104ms
STEP 3  reconnect, pin stored   -> exitCode=0, stdout "pinned-run-ok"
STEP 4  wrong pin               -> ssh_host_key_changed, nothing ran
```

The reported fingerprint was cross-checked with `ssh-keygen -lf` on the host key and matched
byte for byte. The temporary test key was removed from `authorized_keys` afterwards.

271 tests green (8 new in `SshHostKeyTest`, including one that runs against a real sshd when one is
present and skips otherwise). Version 0.40.3 / build 144.

**Not verified on device:** that the APK's vault unlock, key generation and network path behave the
same as the JVM harness. The logic is proven; the phone is not.

## 0.40.4 — ed25519 is implemented here, because JSch's cannot run on Android (build 145)
2026-10-24

**The real reason `generate key` never worked, after three releases of "fixes".**

`com.github.mwiede:jsch` is a **Multi-Release JAR**. Its Java-8 base classes are stubs that throw,
and the real implementations live under `META-INF/versions/15/`, which the Android runtime does not
read:

| config key | base class (what Android loads) | real class (unreachable on Android) |
|---|---|---|
| `keypairgen.eddsa` | `jce.KeyPairGenEdDSA` — every method throws "requires Java15+" | `META-INF/versions/15/...` |
| `ssh-ed25519` | `jce.SignatureEd25519` — the **constructor** throws | same |

The fallbacks are `com.jcraft.jsch.bc.*`, which need BouncyCastle — not on this classpath.
Reproduced by deleting `META-INF/versions` from the jar (exactly what Android sees):

```
FAILED: JSchException: java.lang.NoClassDefFoundError:
  org/bouncycastle/crypto/params/Ed25519PrivateKeyParameters
```

Every earlier JVM verification loaded the versioned classes and proved nothing about the phone. That
is the mistake this release is mostly about.

**The fix: ed25519 implemented directly from RFC 8032** (`Ed25519.kt`), with `MessageDigest` as the
only dependency, registered into JSch's two hooks by `SshKeygen`'s `init` block. No provider, no
BouncyCastle, no API-33 requirement (this app's `minSdk` is 26 and platform Ed25519 arrived in 33).

Correctness is pinned by RFC 8032's published vectors, for **both** halves:

- key derivation: TEST 1/2/3 public keys match exactly
- signatures: TEST 1 (empty message) and TEST 2 (one byte) match byte for byte

**Two bugs the vectors caught while writing this:** `verify` compared `ByteArray`s with `==`
(reference equality — it rejected every valid signature), and the verification equation was written
as `[S]B − [k]A` with a twisted-Edwards negation in the wrong coordinate. Signing was right the
whole time; only verification was wrong, which is exactly the class of bug a shape-only test misses.

**Also fixed:** `addDialog` caught `Exception`, but the failure was a `NoClassDefFoundError` — an
`Error`. So the dialog threw, nothing was caught, and **nothing was logged**. That is why the
symptom was "a key never appears" with no error anywhere. It now catches `Throwable` and logs to
`/debug`'s ring buffer.

**Proven on the Android-shaped jar** (app classes, `META-INF/versions` removed):

```
PEM_HEAD=-----BEGIN OPENSSH PRIVATE KEY-----   PUB=ssh-ed25519 AAAA...IFJljvkE crab
DERIVED_MATCH=true   TYPE=ssh-ed25519
ssh-keygen -y -f <generated>  -> the same public key
ssh -i <generated> openclaw@127.0.0.1 'echo OPENSSH_OK'  -> OPENSSH_OK
```

**Not proven:** authentication through JSch itself. Generation and signing are individually
correct (RFC vectors, and the adapter's signature verifies against its own public key), but a full
JSch session against the local sshd still answers `Auth fail for methods 'publickey,password'`
while the OpenSSH client accepts the same key. So JSch is producing a signature the server rejects
even though our `sign()` matches the RFC — unresolved, and NOT claimed as working.

281 tests green. Version 0.40.4 / build 145.

## 0.40.5 — the hooks register on the CONNECTION path too (build 146)
2026-10-24

The last blocker, found by asking the right question: *what loads when the app restarts and a
command is run, without anyone opening the SSH screen?*

**Registration lived in the wrong place.** `SshKeygen`'s `init` block installed JSch's two ed25519
hooks. But `SshKeygen` is only loaded by the SSH screen — a run that connects with an already-stored
key never touches it. So after a restart, `SshExec` connected with JSch's DEFAULT hooks, which are
the BouncyCastle classes that do not exist on the device:

```
hook BEFORE SshExec loads: com.jcraft.jsch.bc.SignatureEd25519
```

JSch swallows the resulting `NoClassDefFoundError` inside its signer and reports only
`Auth fail for methods 'publickey,password'` — indistinguishable from a rejected key, which is why
this looked like a crypto problem for hours. Generation worked (the screen had loaded), and signing
worked (the tests generated a key in the same JVM first), so every isolated test passed while the
real path stayed broken.

`JschHooks.install()` is now idempotent and called by BOTH `SshKeygen` and `SshExec`, so whichever
loads first is enough.

**Verified in restart order** — `SshExec` touched first, `SshKeygen` never loaded before the run:

```
hook before anything: com.jcraft.jsch.bc.SignatureEd25519
hook after SshExec:   com.mido.phonebridge.plugins.ssh.JschEd25519Signature
STEP1 ssh_host_key_unknown
STEP2 exit=9 stdout=[openclaw|SSH_GATE_WORKS]
STEP3 exit=0   (pinned reconnect)
```

All of it on the Android-shaped jar (app classes, `META-INF/versions` removed). The earlier
`Auth fail` in the same harness was a test-setup artifact: `authorized_keys` had been restored
between generating the key and connecting, so the key genuinely was not authorized.

**Also fixed in this release:** `addDialog` caught `Exception` around a call that failed with
`NoClassDefFoundError` — an `Error`. The dialog threw, nothing was caught, and nothing was logged;
the symptom was a key that simply never appeared. It now catches `Throwable` and writes to the
`/debug` ring buffer.

281 tests green. Version 0.40.5 / build 146.

**Still not verified on the device**: that the APK's vault, biometric unlock and network path behave
like the harness. Everything below the UI is now proven on the Android-shaped jar.

## 0.40.6 — a JSON null is not the string "null" (build 147)
2026-10-24

First real device call after 0.40.5, and it failed in a way that contained its own contradiction:
the error code said the host key **changed**, while the message printed `pinned: null`.

Both cannot be true. `ssh_host_key_changed` is only produced when a pin exists. So the pin was not
null — it was the four-character STRING `"null"`, which renders identically.

**`optString` on a JSON null differs between implementations.**

```
toJson writes JSONObject.NULL

Android org.json  -> optString returns "null"   <- the phone
reference org.json -> optString returns ""       <- what the unit tests run
```

`fromJson` did `optString("hostKeyFingerprint").takeUnless { it.isBlank() }`. On the phone that
yielded the string `"null"`, which can never equal a real fingerprint, so **every connection to a
never-pinned host reported `ssh_host_key_changed` forever** — and trust-on-first-use could never
complete, because the first call has to be `ssh_host_key_unknown` for the caller to learn the
fingerprint at all.

This is the second bug of the session with the identical root shape: **the unit tests run a
different implementation than the device.** The first was the Multi-Release JAR; this is `org.json`.

The fix reads the field with `has` + `isNull` (identical on both) and treats a literal `"null"` as
absent. A real fingerprint is asserted to survive the round trip, because a guard eager enough to
eat a legitimate pin would silently disable host-key verification — worse than the bug.

**Verified against the real target** (the NUC, `100.64.0.12`):

```
ssh-keyscan -> SHA256:NqIEY3V6FtVJXDRJmCn76lciI5r/IUg6pnOuAQg3PAk
app reported -> SHA256:NqIEY3V6FtVJXDRJmCn76lciI5r/IUg6pnOuAQg3PAk
```

So the connection reached the host and read its key correctly; only the classification was wrong.

285 tests green (4 new in `SshConnectionTest`). Version 0.40.6 / build 147.

**Still not verified:** a full approved round trip that runs a command on the NUC. The gate returned
`408 approval_timeout` on the first attempts (nobody at the phone), and the tap that did land hit
the classification bug above before any command could run.

## 0.40.7 — tests run Android's runtime now (build 148)
2026-10-24

Mido, after the third bug of the day with the same root: *"can you stop this fucking different env
shit then?"* — and that was the correct call. The bug class was never the individual defects, it was
that **`./gradlew testDebugUnitTest` did not run the code the phone runs.**

Two shipped failures came from exactly this:

| divergence | test JVM | Android |
|---|---|---|
| `optString` on a JSON null | `""` (reference `org.json`) | `"null"` |
| JSch ed25519 classes | reads `META-INF/versions/15/` | ignores it, loads the stub |

**Unit tests now run under Robolectric** (4.14.1), against real Android framework classes, and
`org.json:json` is REMOVED from the test classpath — keeping it would let the reference
implementation win and re-open the hole.

That change immediately did its job: **the moment it was enabled, dozens of previously-green tests
failed** with `Method opt in org.json.JSONObject not mocked`. They had been passing against a
different implementation the whole time. Every test class now carries
`@RunWith(RobolectricTestRunner::class)`.

`AndroidJsonProofTest` makes the runtime itself an assertion — it checks that `optString` returns
the Android string `"null"`, and that `Build.VERSION.SDK_INT` exists. If the suite ever drifts back
to a bare JVM, it fails with a clear message instead of the difference surfacing as a phantom bug
on the phone.

288 tests green, all on Robolectric. Version 0.40.7 / build 148.

**Unchanged and still true:** the SSH feature's own status. Host-key reading works (the phone
reported the NUC's real fingerprint), generation and signing are proven, and the JSON-null fix from
0.40.6 is what unblocks first contact. What has NOT happened is a full approved round trip that
executes a command on the NUC.

## 0.40.8 — the NOTIFY card gets the same 3s guard (build 149)
2026-10-24

Mido: *"add the same 3 seconds waiting period before ANY touch event on the notification works so
that I don't approve or cancel by mistake"*.

**The overlay had no guard at all.** `ArmGuard` existed and was wired into the SECURE modal, but the
NOTIFY card's touch listener approved on `ACTION_UP` unconditionally:

```kotlin
} else if (ev.actionMasked == MotionEvent.ACTION_UP) {
    settle(entry, Reason.APPROVED)     // no time check
}
```

So a card arriving under a finger already in motion — exactly the case the guard was written for —
approved a request nobody had read. The modal was protected; the surface that appears WITHOUT
deliberate navigation was not.

Both decisions are now gated through `ArmGuard.decisionsAllowed(shownAt, now)`:

- the tap that approves
- the `x` that dismisses (it answers REJECTED, so it is equally a decision)

**Dragging is deliberately still allowed while unarmed.** Moving the card is not an answer to the
request, and blocking it would read as a frozen UI rather than a careful one.

**`shownAt == 0` is refused.** `Entry.shownAt` is 0 until the view is attached, and a real clock
reading is far past `0 + 3000`, so treating it as a timestamp would leave an unattached card fully
armed. There is an explicit guard and a test for it.

A refused tap is not silent: it logs `tap ignored: still in the 3s guard` to `/debug`, so a card
that seems unresponsive can be told apart from one that is broken.

291 tests green (3 new in `ArmGuardTest`, including one asserting the overlay and modal guards are
the SAME duration so the two surfaces cannot drift). Version 0.40.8 / build 149.

## 0.41.0 — auto-start after reboot, and a status label that stops lying (build 150)
2026-10-25

Two outages the same morning, both from the app never re-checking anything it claimed.

**1. The bridge stayed down after a reboot.** The manifest had **no `<receiver>` at all** and no
`RECEIVE_BOOT_COMPLETED` permission, and `MainActivity.startBridge()` was the only
`startForegroundService` call site in the codebase. `START_STICKY` keeps the service alive through
a system process kill; it cannot survive a reboot, because after one nothing ever asks the service
to run. The phone rebooted at ~05:40 (tailscaled logged `peer disco key changed`, which is an
ephemeral key per tailscaled process) and the bridge stayed down until it was noticed hours later.

- New `BootReceiver` on `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`, gated by a new
  `autoStartOnBoot` preference (**default ON**, parsed with the existing `lenientFlag` so a garbled
  value falls back rather than refusing to start).
- `MY_PACKAGE_REPLACED` is half the value on its own: sideloading a build kills the process, so
  without it every update left the bridge down until someone opened the app.
- `exported="true"` is required and is not a hole — both actions are protected broadcasts that
  only the system can send.
- **`specialUse` is permitted from a boot receiver on Android 15.** Only `dataSync`, `camera`,
  `mediaPlayback`, `phoneCall`, `mediaProjection` and `microphone` are banned, so the service's
  existing type is fine. Checked against the platform docs rather than assumed.
- **A boot start races Tailscale**, and the old code turned that race into permanent death: no
  `100.x` address yet → `stopSelf()` → `START_NOT_STICKY`. That path is now a bounded retry ladder
  (10s / 30s / 90s / 180s) in `core/StartupRetry.kt`, kept pure and unit-tested so the schedule can
  be asserted without an Android runtime — the same extraction rule as `HostAllowlist` and
  `VaultState`. Exhausting it posts a notification instead of dying silently.
- The notification is its own channel (`bridge_status`, `IMPORTANCE_DEFAULT`) and its own id
  (4243). Reusing the running notification's `IMPORTANCE_LOW` channel would have made the reminder
  silent, which is the same as not having one. Posted **before** `stopSelf()` — after that there is
  nothing left to post it from.
- The toggle gates app-load auto-start too: **one toggle, one meaning**, "the server starts
  itself". A setting whose OFF state still started the server on every app open would not mean off.

**2. The app painted a green "Running" while every caller got `Connection refused`.** `serverUp`
was an **assertion**: set `true` once after `start()` and `false` only in `onDestroy`, never
re-verified. NanoHTTPD ships `isAlive()` (socket open + accept thread alive) and the app called it
**zero times**. `ServerStatus.state()` consumed the flag as truth, and `render()` ran only on
`onResume` plus two one-shot 600ms callbacks, so the label could be arbitrarily stale. `/debug`
reported `up` from the same flag, so a remote caller was misled too.

- New watchdog probes every 60s with `isAlive()` **plus a real TCP connect** — `isAlive()` alone
  only proves the objects exist, not that a caller would get through. On failure it rebinds, since a
  stale tailnet address is the likeliest cause; it notifies only when a rebind also fails.
- `ServerStatus` gains **`UNRESPONSIVE`**, checked BEFORE the healthy/attention pair, so "up but not
  answering" can no longer render as "Running". Its title is `● Not answering`, deliberately not
  containing the word the state exists to retract.
- `render()` ticks every 5s while resumed (cancelled in `onPause`). It reads the CACHED probe
  result — a fresh connect there would be network I/O on the main thread.
- `/debug` gains `alive`, `boundTo` and `lastProbeAtMs`, so "process up, not serving" and "process
  dead" are distinguishable from outside.

302 tests green (was 288): 5 in `StartupRetryTest`, 4 new in `ServerStatusTest` (including the
2026-10-25 bug stated as an input), and preference coverage for the new field in `PolicyTest`.
Version 0.41.0 / build 150.

**Not device-verified yet.** The reboot path needs an actual reboot, and the unresponsive path
needs a server that dies while the screen is on. Both are unproven until then.

---

## 0.41.1 — the bridge now waits for Tailscale instead of dying (build 151)
2026-10-25

**Found by the 0.41.0 device test itself.** Mido turned Tailscale off, got the "not running"
notification as designed, turned it back on — and the bridge stayed down until he opened the app.
The retry ladder had done its job and then called `stopSelf()`, which destroyed the only thing that
could have noticed the tailnet returning. Boot and install were covered; reconnect was not.

- **The ladder no longer stops the service on exhaustion.** It enters a waiting state instead:
  the service stays alive, the notification switches to "PhoneBridge is waiting for Tailscale",
  and it starts serving the moment a `100.x` address exists. The cost is a foreground service
  that was already running; the cost of the old behaviour was a bridge needing a human to poke it.
- **Two wake paths, deliberately.** `registerDefaultNetworkCallback` is the fast one — a VPN
  connecting becomes the default network, which is exactly the event to react to. A 5s interface
  poll backs it up, because `tailnetAddress()` reads interfaces directly and not every interface
  change produces a `Network` object. The poll is one enumeration per tick, no network I/O.
- **`BridgeStatusNotice` is now a pure, tested object.** The copy and the ongoing/autoCancel pair
  used to be inline literals inside `BridgeService`, so a new state could be added while the
  notification still described the old one — no error, no log. Three kinds now, each with a
  distinct title, and `WAITING_FOR_TAILNET` is the one that must tell the human that doing
  NOTHING is correct.
- **The watchdog hands over rather than dead-ending.** A rebind with no tailnet address used to
  post a "not running" notice and return; it now enters the same waiting state, since Tailscale
  coming back is the only thing that resolves it.
- `ACCESS_NETWORK_STATE` added (normal permission, granted at install).

308 tests green (was 302): 6 in `BridgeStatusNoticeTest`, including that ongoing and autoCancel
are always opposites and that the waiting copy says it recovers by itself.
Version 0.41.1 / build 151.

**Not device-verified yet.** The reconnect path needs Tailscale toggled off and back on.

---

## 0.42.0 — NOTIFY runs while the phone is locked, if the vault is open (build 152)
2026-10-25

Mido, 2026-10-25: *"for notification level calls it shouldn't matter if the screen was off or the
phone locked or not, as long as the app was unlocked once for storage unlock"* — and, asked whether
it should be reads-only: *"regardless of verb"*.

**This reverses the original attendance rule, and the reversal is the feature.** Before it, an
unattended request was denied in EVERY mode: `isAttended()` ran pre-gate for all requests, so
`debug/READ` answered `403 denied: gate unavailable: screen off or device locked` while the phone
sat in a pocket. Reads could not run overnight at all.

**The consequence, stated plainly rather than buried:** `NOTIFY` approves on SILENCE. A dark screen
guarantees silence, so this is unattended approval — not "the card was ignored" but "the card was
never legible". The consent model is now *unlocked once, trusted after*, not *someone saw this*.
That is the accepted trade.

- **The bound is the vault session.** `SecretVault.isUnlocked()` is `dataKey != null` — a live
  session, not a stored flag. It dies with the process (restart, reboot, system kill) and on
  `lock()`. So the trust window is exactly "since you last unlocked this app, while this process
  is alive". A cold-booted phone is back to denied, which is what keeps this defensible.
- **`unattendedAllowed(mode, vaultUnlocked)` is pure and tested.** `NOTIFY` → the vault decides.
  `SECURE` → always false, whatever the vault says. A vault session is NOT a substitute for a
  present human, and if that ever returned true the fingerprint gate would be silently off.
- **Unattended requests do not draw a card.** Drawing one would report "shown" for something that
  was never readable. The NOTIFY branch approves, holds the window, and records
  `detail = "unattended"` so the log never reads like an observed decision.
- **The overlay's expiry re-check had to change too**, or the rule would depend on WHEN the screen
  went off: a request that started attended and lost the screen mid-window would still have
  settled `UNATTENDED`. Both the tick and the backstop now accept an open vault.
- **Verb-agnostic by explicit instruction.** The blast radius is bounded by POLICY instead: only a
  route someone marked `NOTIFY` behaves this way, and `NOTIFY` is a deliberate, audited per
  `plugin × verb` choice.

314 tests green (was 308): 6 in `AttendanceTest`, covering the bound (locked vault still denies),
that `SECURE` can never proceed unattended, and that the rule takes no Verb parameter.
Version 0.42.0 / build 152.

**Not device-verified yet.** Needs a locked phone with the vault session open.

---

## 0.42.1 — restore the audit and logcat packages that git never had (build 153)
2026-10-25

**The tree did not compile, and no commit was responsible.** `app/.../audit/` (13 files) and
`app/.../plugins/logs/` (1 file) were missing, so seven sources failed on unresolved
references — `BridgeService`, `MainActivity`, `LogActivity`, `LogDetailActivity`,
`ExceptionsActivity`, `OptionsActivity`, `DebugPlugin`. Nothing in either repository's history
ever contained them.

**Why: the ignore rules were aimed at OpenClaw's runtime data and landed on the source.**
`~/.openclaw/.gitignore` carried bare `audit/` and `logs/` (they exist to exclude the agent's
own audit and log directories). A bare directory pattern matches at EVERY depth, so
`git check-ignore` resolved
`app/src/main/java/com/mido/phonebridge/audit/AuditLog.kt` → `.gitignore:28:audit/` and
`.../plugins/logs/LogcatPlugin.kt` → `.gitignore:16:logs/`. Those files were therefore never
staged, in the in-tree copy or in the new repo, and deleting the in-tree copy during the move
took them with it. The compiled output kept working, which is why nothing noticed until a
clean build.

**Recovered from the Gradle build cache, not rewritten from memory.** The last good build's
`compileDebugKotlin` output still held all 31 classes, so the reconstruction was checked
against real bytecode rather than recollection: every member present in the recovered classes
is present in the restored sources, and the only difference is one private nested `Companion`
that is now inlined. **314 tests green, unchanged** — the same suite, over the same contracts.

- **The doc comments are the loss, and they were rewritten deliberately.** Bytecode carries no
  comments, and in this project the comments are where the reasoning lives — why absence is
  distinct from emptiness, why the id is a counter rather than a random suffix, why a 4xx after
  an approval is signal. Each was restated from the behaviour the code actually has, so a future
  reader still inherits the reason and not just the mechanism.
- **`AuditRecord`/`AuditHeader`/`Decision`/`Truncation`/`AuditDay` are `data class`es again.**
  The bytecode shows generated `copy`/`componentN`/`equals`, and `ExceptionsActivity.save` takes
  an `AuditRecord` as a parameter type — a plain class would compile but change identity
  semantics for no reason.
- **`AuditLog.from` reads the version from `BuildConfig`** rather than the literals the
  decompiler showed (`"0.42.0"`, `152`), which were that build's constants baked in. The
  original source used `BuildConfig`, and a hardcoded pair would go stale on the next bump.
- **`PolicyAudit` likewise** takes the version from `BuildConfig` for the same reason.

**The durable fix is a `.gitignore` that cannot do this again.** The repository's own file uses
`build/`, `.gradle/`, `keystore/`, `apk-out/` — patterns that cannot match a source directory —
and the restored packages are now tracked, so `git status` shows them and a clean clone builds.
The `~/.openclaw` rules are unchanged: they are correct for that repo, and the mistake was
carrying a project inside it, which 07e94200 already ended.

Version 0.42.1 / build 153. Behaviour is unchanged from 0.42.0 — this release restores source,
it does not alter it.

---

## 0.43.0 — a sudo password typed at the modal, never sent over HTTP (build 154)
2026-10-26

**The password does not exist on the wire.** It is typed on the phone, held only in the
Activity and the gate, and written to `sudo -S`'s stdin. A caller cannot supply it, because
there is no request field for it — which is the whole design. A credential the API accepted
would be a credential in transit, and that is the thing this feature exists to avoid.

**Why this shape rather than `NOPASSWD`.** The alternative — a dedicated `bridge` user with a
scoped `NOPASSWD` line — gives the same root with a smaller blast radius, and I argued for it.
Mido chose this one. Both were verified safe; this is the shape that was picked, so this is what
ships, and the reasoning below is what makes it safe rather than merely chosen.

**Detection is a parser, not `contains("sudo")`, because a false positive is a LEAK.** The
password goes to the command's stdin. If the predicate said "yes" for a command that is not
sudo, that password would be handed to whatever it really is — and `cat` would print it into
the HTTP response. So [SudoCommand] tokenizes with quotes and operators honoured, and only the
HEAD of each segment can match: `echo sudo hi` is not sudo, `echo 'x; sudo id'` is not two
commands, `/var/lib/sudo` is not sudo. A false NEGATIVE is harmless by comparison — sudo
refuses for lack of a terminal — and the asymmetry is why the tests are lopsided on purpose.

- **`sudo` must carry `-S`, and that is checked PRE-GATE.** Verified on this host:
  `printf 'x\n' | sudo -k true` answers *"A terminal is required to authenticate"*, while the
  same with `-S` reaches the password check. A sudo command without it can never succeed, so
  refusing it as `sudo_needs_stdin` (400, noise) costs the human no tap. We do not add the flag
  ourselves — that would rewrite a command the card promised to show verbatim.
- **The floor is enforced in the dispatcher, not in `resolve()`.** A password request is forced
  to `SECURE`. `NOTIFY` approves on five seconds of silence from an overlay card with no
  Activity and no biometric: there is nowhere to type, so a NOTIFY rule must not be able to
  reach such a request. `resolve()` sees only plugin + verb and cannot express this, which is
  why the override sits where the route is known.
- **`CharArray`, never `String`, end to end.** `String` cannot be wiped. The Activity reads the
  chars out of the `Editable` directly, the gate copies them, `SshExec` encodes through a
  `CharsetEncoder` over a `CharBuffer`, and every copy is zeroed — including in a `finally`, so
  a throwing handler cannot leave one alive. The audit records THAT a password was supplied (one
  clause in the summary), never the value: nothing to redact, no schema change.
- **The field cannot be harvested.** `FLAG_SECURE` (recents thumbnails, screen recorders),
  `TYPE_TEXT_VARIATION_PASSWORD` with `IME_FLAG_NO_PERSONALIZED_LEARNING` (the keyboard's
  dictionary), `setSaveEnabled(false)` (rotation), autofill excluded, and the copy/cut selection
  menu disabled. APPROVE stays disabled while the field is empty, so an empty password is never
  submitted and rejected three seconds later by sudo.
- **The card states the elevation twice.** A `ELEVATION` row in the target block (a `sudo`
  mid-command is easy to miss at a glance) and one line under the field saying the password goes
  to stdin on this device, not over the network. Without that sentence the field reads as a
  credential harvest, which is exactly what it must not look like.

**335 tests green (was 314): 21 in `SudoCommandTest`.** Most of them pin negatives — that a
quoted `sudo`, a path containing `sudo`, and a mid-command `sudo` are NOT treated as elevation —
because those are the cases where a wrong answer would have handed the password to the wrong
process.

**Known limit, stated rather than hidden:** `bash -c 'sudo rm -rf /'` is not detected, because
`bash` is the segment head. It fails safe (sudo refuses without a terminal) and it cannot leak
the password, since nothing here would have supplied one.

**Not device-verified.** Needs a real phone, a real sudo prompt, and a command whose output would
visibly differ between root and non-root.

Version 0.43.0 / build 154.

---

## 0.43.1 — the misclick guard is 1s, and the card dims while it waits (build 155)
2026-10-26

**One second instead of three**, on Mido's instruction. The guard was never really about a number
of seconds: it exists so a tap already travelling when a card appears cannot approve something
nobody has read. A deliberate tap is ~100ms, so 1s still catches the case; the extra two seconds
were charged to every human for a risk the guard already bounded.

**The reason 1s is safe is the DIM, not the clock.** A disabled control that LOOKS enabled is what
turns a short guard into a misclick. The NOTIFY card previously refused taps in silence — it sat at
full opacity and simply ignored them, which reads as a broken card rather than a careful one. It now
renders at 45% opacity until the guard expires and snaps to full, and that snap is itself the
signal: the card became live and looks like it.

- **Driven from the same predicate the touch handler uses** (`ArmGuard.decisionsAllowed`), not a
  second timer. The picture and the behaviour cannot disagree about when the card went live — the
  same one-source-of-truth rule this project has paid for repeatedly.
- **Multiplied into the fade, not replacing it.** The fade is about appearing; the dim is about
  being live. A card still fading in is also still unarmed, so the two compose cleanly.
- **The modal already had this.** `ApprovalActivity` dims APPROVE to 0.4 alpha while unarmed and
  shows "ready in Xs", so only the overlay was missing the cue. The two surfaces now agree, which
  is what `ArmGuardTest` asserts.

**A consequence worth recording rather than discovering later: lowering the guard does not make an
AI-on modal answerable in 1s.** The measured summary latency is ~1.2s, so the FETCH is now the
binding constraint in the ordinary case — it used to be hidden underneath the 3s guard. That is not
a regression (the guard was never meant to mask the model), but it means the visible wait with AI
enabled is unchanged. A dedicated test now pins this so a future "why is it still ~1.2s" lands on
the explanation instead of a bisect.

`ArmGuardTest` went red on the old 3s assumption exactly as it should, and the stale "3s guard" log
lines now say "misclick guard" — a log that names a duration it no longer has is a log that lies.

336 tests green (was 335).

Version 0.43.1 / build 155.

---

## 0.43.2 — a touch can no longer act while the card still looks dimmed (build 156)
2026-10-26

**Reported on device within a minute of 0.43.1 shipping:** *"wait, I can still dismiss in cool
down"*. The dim and the guard had drifted apart by one frame.

**The bug, exactly.** The tick repaints the card every 33ms, but it only runs on the main thread's
next frame. The touch handler checked `ArmGuard.decisionsAllowed` against the REAL clock. So a touch
landing in the gap between the guard expiring and the next paint was **allowed while the card still
displayed the unarmed alpha** — live control behind a locked-looking surface.

**The log proved it, which is why this was fixable rather than arguable:**
`notify[5013] settle reason=TAPPED shownMs=1023 alpha=0.45`. Tapped 23ms *past* a 1s guard, on a
card rendering at 45% opacity. Behaviour had unlocked; the picture had not caught up.

- **One function now answers "what should this look like"**, `ArmGuard.cardAlpha`, exactly as
  `decisionsAllowed` answers "may this be tapped". The tick and every touch path call the same one,
  so they cannot disagree.
- **Every touch path recomputes the alpha BEFORE it decides.** The gap is closed in the only
  direction that matters: the picture is brought up to date before the decision it gates is taken.
  Both the tap-to-approve and the ✕ dismiss, since both answer the request.
- **Only ever raises alpha.** The guard cannot un-expire, so no touch should ever make a card dimmer.

**Worth stating plainly: the 0.43.1 tests passed while this bug was live.** They pinned the pure
predicate, and the predicate was right — the defect was in the WIRING between predicate and paint.
A unit test on a pure function cannot see a frame boundary. The new test pins the invariant instead:
*at any instant a decision is permitted, the alpha the card would render is full*. That is the
contract the bug violated, and it fails if someone removes the undim.

339 tests green (was 336).

Version 0.43.2 / build 156.

---

## 0.43.3 — STMP rebrand: paper skin, stamp animation, smaller controls (build 157)
2026-10-26

**The app is being rebranded to STMP.** Warm paper/ink palette, Handlee handwriting font, a
rubber-stamp launcher icon, a full-screen stamp animation on approve/deny, and the homepage's
four loose nav buttons collapsed into a Tools row. This build additionally shrinks button text so
longer labels stop overflowing.

- **Palette**: cold near-black/blue toned to warm cream paper + ink, one stamp-red accent.
- **Typography**: Handlee (SIL OFL 1.1) wired into `Theme.text/sectionHeader/button`.
- **Icon**: rubber-stamp vector (absolute `M/L/C` path only — the first arc/quadratic attempt
  failed inflate and made the install report "corrupt" with no icon).
- **Stamp animation**: fires on both APPROVED and DENIED, with a physical thud + haptic.
- **Homepage**: `Tools` row replaces four full-width nav buttons; audit banner moved beside log.
- **Font size**: buttons reduced (small 12f, full 14f) so longer labels fit.

Version 0.43.3 / build 157.

---

## 0.43.4 — stamp voice in the approval copy (build 158)
2026-10-26

**Copy pass, subtle:** the approval header now reads "awaiting your stamp" instead of the flat
"APPROVE?", and the gate/hero sub-lines carry the same stamp voice ("needs your stamp", "waits for
your stamp"). Wording only — no layout, no behaviour.

Version 0.43.4 / build 158.

---

## 0.43.5 — hand-drawn ink underline on section headers (build 159)
2026-10-26

**Drawn-accent pass, first piece.** Section headers now carry a hand-drawn ink underline (a
two-tone translucent `GradientDrawable` stroke under the label) so group labels read as ink on
paper rather than a printed hairline. Pure vector, no raster asset.

Version 0.43.5 / build 159.

---

## 0.43.6 — torn-note card corners (build 160)
2026-10-26

**Drawn-accent pass, second piece.** The hero and gate cards now use `Theme.tornCard`, which
layers a hand-torn paper edge (`R.drawable.torn_edge`, a tintable vector with absolute M/L path
commands — no arcs, same constraint that fixed the launcher icon) over the standard surface, so
cards read as notes torn out of a pad rather than rounded rectangles.

Version 0.43.6 / build 160.

---

## 0.43.7 — the section header is a rule again, not a red bar (build 161)
2026-10-26

**Two defects caught on device, both from 0.43.5/0.43.6.**

**The red bar.** 0.43.5 set a `GradientDrawable` as the section header's *background*, so it
filled the whole TextView — every section label ("SETUP", "ACCOUNT", ...) sat on a solid salmon
block. A background drawable has no concept of "just the bottom edge", so the shape has to be
painted. `Theme.InkUnderline` is a small `Drawable` that draws a ~60%-width rule at its own
bottom, plus a fainter offset second pass for an uneven ink edge.

**The torn card was a no-op.** 0.43.6 layered a 24dp `torn_edge` vector into a `LayerDrawable`
under a card surface — but a tile stretched to card size, drawn in paper colour on paper, is
invisible. It changed nothing on screen. Removed rather than shipped as decoration, along with
`R.drawable.torn_edge`; a torn edge needs to be part of the card shape itself, not an overlay.

Version 0.43.7 / build 161.

---

## 0.43.8 — torn cards, drawn as the card's own outline (build 162)
2026-10-26

**The torn edge is back, built the way it has to work.** 0.43.6 tried to layer a 24dp
`torn_edge` vector over a card surface: a tile stretched to card size, in paper colour on paper,
is invisible, so it changed nothing and was removed in 0.43.7.

A torn edge is a property of the SHAPE, not an overlay. `Theme.TornPaper` is a `Drawable` that
builds a rounded path whose bottom edge steps through a fixed jagged pattern, so the hero and
gate cards are torn paper themselves. The pattern is a constant array rather than `Random`: a
random tear would re-roll on every repaint and the card would visibly crawl. The path is cached
per bounds, so repaints cost one draw call.

Version 0.43.8 / build 162.

---

## 0.43.9 — the stamp mark is set in Handlee (build 163)
2026-10-26

The APPROVED / DENIED word inside the stamp overlay was drawn in
`Typeface.create("sans-serif-condensed", BOLD)` — a system face, so the app's single most
prominent moment was the one place the rebrand did not reach. It now uses `Theme.handFont`, the
same Handlee the rest of the app is set in.

Version 0.43.9 / build 163.

---

## 0.43.10 — the icon's letters are letters, not blocks (build 164)
2026-10-26

The launcher icon's "STMP" was built from filled rectangles after the arc-syntax rewrite, and the
`S` path in particular was malformed — a block of pixels that only read as a letter if you already
knew the word.

The glyphs are now STROKED paths (`strokeLineCap`/`Join` round): a stroke follows the drawn
centreline, so S, T, M and P each read as a letter at 48dp on a home screen. Path data stays
absolute M/L/C only, which is the constraint that keeps the vector inflating on every device.

Version 0.43.10 / build 164.

---

## 0.43.11 — rebrand reaches every screen (build 165)
2026-10-26

The rebrand had stopped at the home screen. Two surfaces still carried the old identity:

**The NOTIFY overlay was still dark.** Its private palette (`BG`, `TEXT`, `MUTED`, the three verb
badge colours) was the pre-rebrand near-black set, so the one card that appears while you are in
another app was the only screen that did not look like the app. Repalletted to the same paper/ink
tokens; the badge fills are now the light paper-tinted variants with dark ink text, which is the
correct polarity on a light card.

**Every remaining `DEFAULT_BOLD` heading is now Handlee** — the home hero and gate titles, and the
title rows on EXCEPTIONS, OPTIONS, the request log and its detail screen. A system bold face next
to Handlee body copy reads as two apps stitched together, and the headings are exactly where the
eye lands first.

Compile-verified only. Not device-verified.

Version 0.43.11 / build 165.

---

## 0.43.12 — the last visible "PhoneBridge" is gone (build 166)
2026-10-26

The notification-listener service still advertised itself to Android as "PhoneBridge
Notifications", so the name a user sees in Settings › Notification access was the one place the
old brand survived. Now "STMP Notifications".

The remaining `PhoneBridge` occurrences are the application id, the package name and the theme
style names — identifiers, not user-visible strings. Renaming those is the parked pre-launch task,
not a copy change: a new application id is a different app to Android, which resets every runtime
permission grant and the notification-listener binding.

Version 0.43.12 / build 166.

---

## 0.43.13 — SSH: keep the tail, bound stderr, return partial output, and detach jobs (build 167)
2026-10-26

Four fixes to the SSH route, all found by using it for real work rather than by reading it.

**A timeout no longer discards what the command said.** The read loop filled a buffer and then
threw `ssh_timeout` at the deadline, which dropped every byte captured so far. A timeout was
therefore indistinguishable from a command that printed nothing — the caller could not tell "slow"
from "silent", and an answer that had already been produced was thrown away with the error. Now
the result comes back with `timedOut: true` and whatever output arrived, and `exitCode: -1`
(JSch's own "no status") rather than a fake `0`. Proven against a real run that printed its answer
and then hung: the text is now visible instead of being replaced by an error.

**Truncation keeps the tail, not the head.** At the 64 KiB cap the old code answered with the
FIRST 64 KiB — for a build log or a test run, exactly the part that does not contain the error.
`SshTail` is a ring buffer that retains the LAST 64 KiB and reports `truncated: true`.

**The reader always drains, so much output cannot become a timeout.** The old loop stopped reading
once the cap was hit, leaving the remote command blocked on a full pipe until the deadline — so
"too much output" surfaced as a timeout. A ring has nothing to lose by continuing to read.

**stderr is bounded.** stdout was capped at 64 KiB while stderr was a plain `ByteArrayOutputStream`
with no limit at all. A command that keeps warning (`curl` retrying, a failing `ssh`) could grow it
until the OS killed the app. Both streams now use the same capped tail, and `truncated` reflects
either one. This asymmetry was an oversight, not a decision.

**Detached jobs: `POST /ssh/{name}/detach` and `GET /ssh/job/{id}`.** A synchronous call is capped
at 300s and torn down at the deadline, so a build, a clone or an agent run either fits or dies with
no exit code; the workaround was composing `nohup … & echo $!` by hand and polling with `tail` in
separate calls. The route starts the command and returns a job id immediately, and the poll route
returns the retained output so far. Bounded on every axis: at most 4 running jobs
(`ssh_job_limit`), a capped tail per job, and finished jobs expire after 30 minutes
(`ssh_job_not_found`). The table is in memory and does not survive a process restart — a job dies
with the app, which is the honest contract rather than pretending a restart kept a socket.

**A detached command may not use sudo, and the route refuses it pre-gate.** The dispatcher wipes
the sudo password the instant the handler returns, and the job runs after that — so it would either
prompt into a closed stdin or keep a live password in a thread nobody is watching. Refused before
the modal, so it costs no tap.

Version 0.43.13 / build 167.

---

## 0.43.14 — paper you can feel: grain, a real thud, the stamp on NOTIFY, and a 3D slam (build 168)
2026-10-27

The rebrand shipped a warm palette, Handlee and torn edges, and it still read as "beige UI" rather
than paper. The stamp and its sound existed but were unreachable in the default gate mode, and the
"3D" never existed at all. Four changes, each fixing one of those.

**Paper grain.** Real paper is never one flat value: it has fibre, sparse darker specks and slight
density variation, and the eye reads that variation as material. A single `setColor` cannot produce
it. `PaperGrain` builds one 96x96 tile from a fixed seed and repeats it through a `BitmapShader`,
so the texture is identical on every repaint — a `Random` inside `draw()` would re-roll each frame
and the surface would visibly crawl, the same trap the torn edge already documents. One tile costs
~37 KB however large the surface is. It is layered ON TOP of the existing drawable rather than
wrapping it, and clipped to the card's radius: an unclipped shader paints square corners over a
rounded card and the corners go visibly dirty.

**A real thud.** The old sound was a 90ms `ToneGenerator` beep — audible, but it read as a
notification chirp rather than something hitting a desk. `res/raw/stamp.ogg` is now a synthesised
knock (low sine burst with a falling pitch plus a short noise transient, ~220ms), played through a
`SoundPool` decoded once and reused. `SoundPool`, not `MediaPlayer`: a `MediaPlayer` allocates a
codec per instance with hundreds of ms of startup latency, which cannot land on the same frame as
the stamp. Before the sample decodes (first stamp after a cold start) it falls back to the platform
tone rather than going silent.

**The stamp now exists in NOTIFY.** It used to fire only on the SECURE modal, so anyone on the
default `AUTOMATIC` mode — where a small card is drawn instead — never saw the moment at all. The
same overlay now fires on `APPROVED` and `TAPPED`, in its own `FLAG_NOT_TOUCHABLE` window: the card
is `WRAP_CONTENT`, so a `MATCH_PARENT` child would force it to full window height (the 0.6.1 bug),
and the stamp has to survive the card's 200ms fade-out to read as one gesture. It never fires on
`EXPIRED`, `UNATTENDED` or `UNAVAILABLE` — those are the absence of a decision, not one.

**The 3D slam.** `rotationX` from -55 degrees to 0 with `cameraDistance` set once, so the stamp
comes DOWN out of the screen rather than merely scaling up. The tilt lands slightly before the
scale settles, so the face is flat when the ink hits — a stamp still angled at contact reads as a
hovering sticker. Set on the View, not the canvas: `Canvas` has no X-axis rotation, and faking it
with a matrix would bypass the camera that makes the perspective correct.

Version 0.43.14 / build 168.

---

## 0.43.15 — the grain reaches the homepage (build 169)
2026-10-27

0.43.14 added paper grain and the homepage still looked identical. Three reasons, all mine.

**The grain was on the wrong drawable.** It went into `surface()`, but the homepage's SETUP cards
use `tornCard()` — a different drawable entirely. So the one screen the user was looking at got no
texture at all. The grain now paints inside `TornPaper`, clipped to the same torn path, so the
texture follows the tear instead of stopping at a rectangle.

**The root was flat colour.** `setBackgroundColor(Theme.BG)` is the largest surface on the screen,
and a flat fill there dominates the whole view. It is now `PaperSheet(BG)`: the base colour plus the
same grain, full-bleed.

**The texture was too timid to see.** Alpha 22 over cream, at phone brightness, is invisible — the
comment claimed "felt rather than seen", which in practice meant not seen. Raised to 34 and the
speck density from 18% to 26%, which is the range where it reads as material without looking like
dirt on the glass.

The lesson worth keeping: a visual change is verified on the screen that was complained about, not on
the helper that looked like the right one.

Version 0.43.15 / build 169.

---

## 0.43.16 — one way to paint a background, enforced by a test (build 170) — CURRENT
2026-10-27

Grain reached the approval gate and the screen root and nothing else, because it lived in two
helpers that some screens use and some do not. A rule that depends on which helper a screen happened
to call is not a rule.

**`Theme.sheet(color, radiusPx)` is now the only way to paint a background.** It returns the base
fill with grain layered over it, and `Theme.sheetDp(ctx, color, radiusDp)` is the dp convenience.
`surface()` and `outlined()` now route through the same two layers, so the three constructors cannot
drift apart.

**Every raw background in the activities is gone.** Eight `setBackgroundColor(Theme.BG)` calls —
Approval, Secrets, SSH, Log, LogDetail, Options, Exceptions, GoogleLogin — now assign
`Theme.sheet(Theme.BG)`. The NOTIFY card, its verb badge and its risk strip were the remaining flat
surfaces, and the card now carries a third `GrainOverlay` layer.

**`GrainCoverageTest` fails the build on a bypass.** It scans `src/main/java` for
`setBackgroundColor(`, `GradientDrawable()` and `ColorDrawable(` outside the `ui` package, and
asserts every hit is either absent or explicitly allowed with a stated reason. The allowlist is
three entries, each saying why it cannot carry grain: a 3dp tint strip, the countdown drain that
draws *under* the card's grain, and the card's own base fill which the risk tint recolours at
runtime.

Two details in that test worth keeping:

- **It guards the guard.** An empty scan fails, because a scanner that finds nothing has broken, not
  a codebase that is clean.
- **Allowlist entries match on file plus a code prefix, never a line number.** The first version
  keyed on `file:line:code`, and every entry stopped matching as soon as anything above it shifted
  — the allowlist failed open, which is the worst way for one to fail. It caught this itself on the
  first run by reporting three "unexplained" sites that were in fact its own allowlist entries.

Version 0.43.16 / build 170.

---

## Keeping this file honest

- **Every commit updates this file.** If a change alters behaviour, adds or removes a feature, or
  fixes a bug, the entry goes in **in the same commit** as the change — not a later one.
- **A version bump without a changelog line is an incomplete change.** The version rule
  (`versionCode` +1, `versionName` semver on every change) and this file travel together.
- **Newest entry at the BOTTOM.** This file is chronological (oldest first) so it reads as a
  history; add the new release under the last one, above this section.
- **Write the WHY, not just the what.** The reasoning is what stops a future change from undoing a
  fix that was paid for. "Fixed the flash" is useless; "the fade drove `View.alpha` while the
  window alpha stayed 1, so the opaque buffer flashed at teardown" is the entry that prevents a
  regression.
- **Do NOT write the entry's own commit hash.** A commit's hash is computed FROM its content, so
  writing the hash in changes the hash — it cannot contain itself, and amending only produces a
  new hash the file would then contradict. The entry lands **in the same commit as the change**
  and records the **date**; find the commit later with:
  `git log --oneline --grep "0.25.0"`. A hash already in an entry (from before this rule) points
  at the commit that CHANGED THE APP, which is the useful one to `git show`.
- **`— CURRENT` lives on the newest entry and nowhere else.** A stale marker makes shipped
  releases look unshipped. `tools/changelog-check.py` enforces this, that the version and build
  match `app/build.gradle.kts`, and that no `<hash>` placeholder survives — and a `pre-commit`
  hook runs it and BLOCKS any commit that touches `app/` without also touching this file.
- **Do not rewrite history here.** If a release is later corrected, add a new entry that names the
  correction; do not edit the old one into being right.
