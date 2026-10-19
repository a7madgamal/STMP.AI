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

Current build: **v0.23.2 (build 106)**.

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

## 0.26.2 — the broker's verb comes from the OUTBOUND METHOD (build 116) — CURRENT
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
