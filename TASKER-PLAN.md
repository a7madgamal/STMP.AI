# Tasker — findings and decision (v2)

Investigation only. Researched 2026-10-26 from Tasker's own documentation and source
(tasker.joaoapps.com), not from third-party summaries.

**v1 of this doc answered the wrong question.** It recommended "run the user's saved tasks" and
declared inline action construction unbuildable/unreviewable. Mido corrected the goal: *"i wanted to
be able to use tasker's capabilities not run my tasks"*. That is a different — and better — target,
and it **is** reachable. This version supersedes v1 entirely.

**Decision in one line: build a curated inline-action catalogue. PhoneBridge constructs an
ephemeral action list and broadcasts it, so no saved task is needed — the blocker is not the
mechanism, it is that Tasker never published an argument schema, so each exposed action must be
verified empirically and shipped as a typed route.**

---

## 1. Inline actions are real — the mechanism

`TaskerIntent` (v1.3.3, Tasker's own published source) has two constructors:

```java
public TaskerIntent() { super(ACTION_TASK); setRandomData(); putMetaExtras(getRandomString()); }
public TaskerIntent(String taskName) { ... putMetaExtras(taskName); }
```

**The no-arg constructor names the task with a random string.** That is the whole trick: the intent
carries its own actions and the name is throwaway, so nothing has to exist in Tasker's task list.

```java
TaskerIntent i = new TaskerIntent();          // random name, no saved task
i.addAction(ActionCodes.VIBRATE).addArg(500); // one action, one typed arg
sendBroadcast(i);
```

`addAction(int code)` stores a Bundle with `ACTION_CODE = code`; `addArg` fills `arg:1`, `arg:2`…
as String, int, boolean, or a `pkg,cls` app pair. Tasker's own docs describe this as *"performs a
task which is constructed within the TaskerIntent object"* — i.e. explicitly ephemeral.

So **the capability surface is genuinely available**, and my v1 claim that it was off the table was
wrong. The real obstacles are below, and they are narrower than v1 implied.

### What the obstacles actually are

**1. There is no published argument schema.** This is the real blocker. `ActionCodes` gives integers
(`TORCH = 511`, `VIBRATE = 61`, `SAY = 559`) but not argument order, count, or types. Tasker's docs
say only: *"You can find a list of the available actions are their arguments in the Tasker UI."*

The argument order is therefore **not documented anywhere**, and the prose A-Z help pages do not
reliably imply it. Worked example: `SAY`'s help page lists Text, Engine:Voice, Stream/File, Network,
Continue Task Immediately — five described fields — while Tasker's own sample code passes **six**
args (`"Finished!"`, `"com.google.android.tts:eng-gbr"`, `0`, `5`, `5`, `false`). Doc prose does not
map 1:1 onto argv positions.

**2. `MAX_NO_ARGS = 10`.** A hard cap per action. Any action with more fields than that cannot be
driven inline at all.

**3. Silent misfires, not errors.** Wrong arg order does not fail cleanly — `addArg("500")` vs
`addArg(500)` differ in type, and a misplaced arg makes the action do something wrong rather than
return an error. This is the property that makes an unverified schema dangerous.

**4. `ActionCodes` is sample code, not a library.** Same as `TaskerIntent`: it is published as a
`.java` file to copy into your project and re-package. So we vendor it, and we own its drift.

**5. A bug in the published helper.** `validatePriority` reads
`(pri >= MIN_PRIORITY) || (pri <= MAX_PRIORITY)` — `||` where `&&` was meant, so it always returns
true and never validates. Harmless if we never set priority; worth knowing before trusting that
file.

---

## 2. The reviewability objection — and why it does not survive contact

v1 said inline actions are "strictly less reviewable" than a saved task, and used that to reject
them. That reasoning was wrong, and it is worth saying exactly why, because it is the crux.

The objection only holds for a **raw passthrough** — a route where the caller sends
`{"code": 511, "args": []}` and PhoneBridge relays it. In that shape the approval card can only show
a number.

But **if we own the schema, we own the rendering.** The catalogue is ours, so the card can say:

```
Tasker · Vibrate 500ms            [EXECUTE]
```

instead of `61 / arg:1=500`. A curated catalogue is *more* reviewable than a saved task, because the
task name is a label the human chose long ago and may have forgotten, while the action card states
the concrete operation and its parameters at approval time.

**That inverts the v1 conclusion.** Inline actions, curated, are the reviewable option. Saved tasks
are the opaque one. So the ranking is: curated inline > saved task > raw passthrough.

---

## 3. How to actually recover the argument schema

Not from documentation — empirically, per action. The procedure:

1. In Tasker's UI, build the action with known distinct values (e.g. Vibrate 500ms).
2. Export the task (Tasker → task → menu → Export) and read the XML. Tasker writes each action with
   its `<code>` and `<arg>`/`<Int>`/`<Str>` elements **in positional order**.
3. That gives the exact arg count, order and type for that action.
4. Repeat for the curated set only — not all ~400.

This is archaeology, but it is bounded, one-time per action, and verifiable. It is also the only
honest route: guessing from prose is how you ship a feature that vibrates for 5 seconds when asked
for 500ms.

**Cost that must be stated: each exposed action is a small research task.** That is why the
catalogue is curated rather than complete, and why it should grow on demand.

---

## 4. Recommended curated catalogue (v1 of the feature)

Chosen because they are the actions a caller plausibly wants, they are low-arg (so schema recovery
is cheap and the 10-arg cap is a non-issue), and none of them are irreversible.

| Action | Code | Args | Why it earns a slot |
|---|---|---|---|
| Vibrate | 61 | 1 (ms) | Cheapest possible proof of the whole mechanism |
| Torch | 511 | 0 | Zero args — validates the pipeline with no schema risk |
| Say | 559 | ~6 | The flagship: arbitrary speech from the caller |
| Flash | 548 | 1 (text) | On-screen text, cheaper than a scene |
| Volume (per stream) | 303–311 | 2 | Concrete device state |
| WiFi status | 425 | 1 | Toggle-class, needs care |
| Bluetooth status | 294 | 1 | Same |
| Media control | 443 | 1 | Play/pause/next |
| Load app | 20 | 1 (pkg) | App launch |
| Beep | 171 | ~2 | Trivial, useful for signalling |

**Deliberately excluded:** file operations, shell (`RUN_SHELL` 123), scene manipulation, anything
with a `*` in its doc warning, and every action whose arg count exceeds the cap. `RUN_SHELL` in
particular would make this a remote-code route wearing a Tasker costume — if a caller wants shell,
`/ssh/{name}` already exists and is honest about it.

---

## 5. Routes

- `GET /tasker/actions` — READ. The catalogue: for each exposed action, its name, its typed
  parameters, and its verb. This is what makes the feature discoverable rather than magic.
- `GET /tasker/state` — READ. Installed / enabled / `ext_access`, mapped from the five
  `TaskerIntent.Status` values, so a failure says *why*.
- `POST /tasker/actions/{name}` — EXECUTE. Body is the named parameters, e.g.
  `{"action":"vibrate","ms":500}`. Validated against the schema before the gate.

**Verb `EXECUTE`** for the same reason as `/ssh/{name}`: a side effect the bridge cannot describe in
data terms. Keeps Tasker actions off auto-approvable paths.

**Validation is the safety property, not the gate.** Because parameters are typed and schema-checked
*before* the card is shown, the card can display exactly what will run. An unvalidated passthrough
would have to show a code, which is the shape we rejected.

---

## 6. Concerns, named

**Argument order is unverifiable from documentation, so it must be verified on device.** Every action
in the catalogue needs one round-trip: fire it, confirm the effect. Until that is done for an action,
it is not shipped.

**Wrong args misfire silently.** A schema error does not throw; it makes the phone do the wrong
thing. So the schema is the critical artifact, and it deserves a test per action.

**`ActionCodes` and `TaskerIntent` are vendored sample code.** They will drift with Tasker releases,
and `validatePriority` already ships with a `||`/`&&` bug. Vendor them, fix what we rely on, and
never assume they are maintained.

**`MAX_NO_ARGS = 10`** caps which actions are drivable. Some useful ones are simply out.

**`Allow External Access` is a user gate we cannot flip.** Report `AccessBlocked` as its own code
with a hint, not a generic failure.

**Completion is asynchronous.** `sendBroadcast` returns immediately; only the per-task completion
receiver proves the task ran. A timeout is mandatory, and "broadcast sent" is not "it worked" — the
same rule learned from `tailscale file cp` exiting 0.

**Tasker may not be installed, and it is paid.** `NotInstalled` is a normal state: register the
plugin, report unavailable.

---

## 7. What was wrong in v1, recorded so it is not repeated

- **Wrong goal.** v1 optimised for "safely wrap what exists" when the ask was "reach the capability
  surface". The saved-task design was safe and beside the point.
- **Wrong claim.** "Never expose inline `ActionCodes`" was justified by unreviewability. That holds
  only for raw passthrough. A curated catalogue is *more* reviewable than a saved task.
- **Wrong blocker.** v1 implied the mechanism was unavailable. It is available; the missing piece is
  an argument schema, which is recoverable per action by reading Tasker's own export.

The one thing v1 got right and still stands: **raw passthrough of arbitrary codes must not ship.**
Not because it is unreviewable in principle, but because we cannot validate arguments we have no
schema for, and a silent misfire is worse than a refusal.

---

## 8. Decision

**Build the curated inline-action catalogue.** Start with the table in §4, three routes, typed
parameters validated pre-gate, one device verification per action before it ships.

**Not built yet — this doc is the deliverable.** Next step if approved: vendor `TaskerIntent` +
`ActionCodes` (re-packaged, `validatePriority` fixed or removed), add the manifest permission and
`<queries>`, recover the arg schema for the first 3–4 actions from a Tasker export, and ship those
routes.

---

## Sources

- `https://tasker.joaoapps.com/code/TaskerIntent.java` — v1.3.3. The no-arg constructor with a random
  task name, `addAction`/`addArg`/`addParameter`, `MAX_NO_ARGS = 10`, the five `Status` values, the
  `validatePriority` `||` bug, and the completion-receiver protocol.
- `https://tasker.joaoapps.com/code/ActionCodes.java` — the integer codes (`TORCH = 511`,
  `VIBRATE = 61`, `SAY = 559`, …). No argument information.
- `https://tasker.joaoapps.com/invoketasks.html` — `PERMISSION_RUN_TASKS`, the inline-construction
  example, and the sentence that argument lists live only in the Tasker UI.
- `https://tasker.joaoapps.com/userguide/en/help/ah_index.html` — Action A-Z prose. Field
  descriptions, not positional argv.
- `https://tasker.joaoapps.com/userguide/en/help/ah_say.html`,
  `…/ah_torch.html`, `…/ah_vibrate.html` — used to show prose does not map 1:1 onto arg positions
  (Say describes 5 fields; sample code passes 6 args).
- `https://tasker.joaoapps.com/contentprovider.html` — task/prefs providers and the `<queries>`
  requirement for API 30+.
- `https://tasker.joaoapps.com/userguide/en/help/eh_http_request.html` — the 6.2 HTTP server, still
  rejected: access is network position, not the gate.
