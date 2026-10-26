# Tasker — findings and decision

Investigation only (the todo's deliverable is *"a findings list of what PhoneBridge can drive on the
phone through Tasker … and a recommended subset to expose as bridge routes"*). Researched
2026-10-26 from Tasker's own documentation (tasker.joaoapps.com) and the 6.2 release notes. Nothing
below is inferred from a third-party summary where a primary source existed.

**Decision in one line: the integration is worth building, but NOT as "expose Tasker's actions" —
it should be a thin `tasker` plugin with three routes (run task, list tasks, state) that lets the
phone's existing automation become a verb the caller can invoke. The capability surface is Tasker's
own tasks, not Tasker's 400 actions.**

---

## 1. Three ways in, and only one fits PhoneBridge

Tasker exposes three distinct interfaces. They are not interchangeable, and picking the wrong one
decides the whole design.

### (a) Broadcast intents — run a task (`PERMISSION_RUN_TASKS`)

The documented, first-class path. An app declares
`net.dinglisch.android.tasker.PERMISSION_RUN_TASKS` in its manifest and broadcasts a `TaskerIntent`
naming a task; Tasker runs it asynchronously. Parameters arrive as `%par1`, `%par2`…; arbitrary
lower-case variables can be attached with `addVariable`.

- **Fire-and-forget by default.** `sendBroadcast` returns immediately. Completion is observable only
  by registering a receiver on `i.getCompletionFilter()` and reading `EXTRA_SUCCESS_FLAG` — one
  receiver per task, so it is a real callback, not a broadcast you can share.
- **Requires a user preference.** *Misc / Allow External Access* must be enabled in Tasker's UI.
  This is a deliberate second gate: the app cannot grant it to itself.
- **Status is queryable before sending:** `NotInstalled`, `NoPermission`, `NotEnabled`,
  `AccessBlocked`, `NoReceiver`. That maps cleanly onto bridge error codes instead of a generic
  failure.

**This is the one to build on.** It is the interface Tasker documents for exactly this purpose, it
carries parameters, and it reports completion.

### (b) HTTP Request event — Tasker runs a server

From 6.2. If a profile has an *HTTP Request* event, **Tasker itself starts an HTTP server** on the
configured port, matching on port, method and path, and answers via a Quick Response field or the
*HTTP Response* action keyed by `%http_request_id`.

**This looks like the obvious fit and is the wrong choice.** Two reasons:

- **It inverts the direction.** PhoneBridge is the server; making Tasker a second server on the
  same device means two ports, two auth stories, and two things to keep alive. The caller would have
  to know which of the two to talk to, and PhoneBridge's gate would not see Tasker's requests at
  all — so a Tasker-side action would bypass the approval gate entirely.
- **Its access control is network position, not the gate.** Tasker's server has no per-request
  approval. Whatever the network can reach, it obeys. That is precisely the property this project
  exists to avoid.

Worth knowing it exists, and it is the right tool for the *reverse* direction (Tasker calling out),
not for exposing capability to the caller.

### (c) Plugin interface — Tasker calls *you*

Tasker's plugin API lets an app appear as an action inside a Tasker task. This is how AutoApps etc.
work. It is the recommended path in Tasker's own docs *for plugins*, and it is the opposite
direction from what this todo asks for: it lets Tasker drive PhoneBridge, not PhoneBridge drive
Tasker. Not this task, but it is the natural answer to "let my automations call the bridge".

---

## 2. Task discovery is supported, which is what makes a good route

`content://net.dinglisch.android.tasker/tasks` returns `name` and `project_name` for every task,
alphabetically. `content://net.dinglisch.android.tasker/prefs` returns `enabled` and `ext_access`.

This matters more than it looks: it means the bridge can **enumerate what is runnable** and report
whether external access is actually on, instead of failing at call time with a Tasker-side error the
caller cannot interpret. A `GET /tasker/tasks` route that lists real task names is the difference
between a discoverable API and a magic string the caller has to be told out-of-band.

**Requires `<queries><package android:name="net.dinglisch.android.taskerm" /></queries>`** in the
manifest because the app targets SDK 35 (package-visibility filtering since API 30). Confirmed
`targetSdk = 35` in `app/build.gradle.kts`.

---

## 3. What PhoneBridge could drive — the honest list

The todo asks for a capability list. The important finding is that **the list is not Tasker's action
catalogue; it is whatever tasks the user has already written.** Tasker can do ~400 things, but the
bridge should not model them — that would mean reimplementing Tasker's UI as JSON, and every Tasker
release would break it.

What the bridge can genuinely reach, in ascending order of risk:

| Capability | Mechanism | Risk |
|---|---|---|
| List tasks / check Tasker state | ContentProvider | none (read-only) |
| Run a named task, no params | `TaskerIntent(name)` | low — bounded by the task's own content |
| Run with parameters | `addParameter` → `%par1`… | low, but caller-controlled data reaches the task |
| Run with named variables | `addVariable("%name", v)` | same, and variable injection is a real concern |
| Arbitrary actions without a saved task | `addAction(ActionCodes.…)` | **high — do not expose** |

That last row is the trap. Tasker's docs show constructing a task inline from action codes, which
would let a caller invoke any Tasker action directly. It is strictly more powerful than "run my
saved task" and strictly less reviewable: the human approving the request sees an action code and an
argument list, not a task they wrote and understand. **Recommendation: never expose it.** The saved
task is the unit of approval precisely because the human wrote it and its name is meaningful.

---

## 4. Recommended subset, as routes

Three routes, mirroring the existing plugin shape:

- `GET /tasker/tasks` — READ. Lists `project_name/name` plus `enabled`/`ext_access`. This is the
  route that makes everything else usable, and it is pure read.
- `GET /tasker/state` — READ. Whether Tasker is installed, enabled, and whether external access is
  on. Distinguishes the five `TaskerIntent.Status` values so the caller gets *why*, not just *no*.
- `POST /tasker/tasks/{name}/run` — EXECUTE. Body carries optional `params` (array → `%par1`…) and
  `variables` (object). Returns after the completion broadcast, or times out.

**Verb mapping is the design decision worth stating.** Running a task is `EXECUTE`, not `WRITE`: it
is not a data mutation the bridge can describe, it is a side effect the user defined. `EXECUTE` is
already the verb used for `/ssh/{name}`, which is the same shape — "run something the user
pre-authored, whose effects the bridge cannot enumerate". Consistent, and it keeps Tasker tasks off
the auto-approvable paths.

---

## 5. Concerns, named rather than buried

**Approval is weaker than it looks, and that is the main caveat.** The modal can show the task name
and the parameters, but *not what the task does*. `/ssh` has the same property, so this is
consistent with the existing design — but it should be stated on the card, not discovered later.
A task named `Backup` may `rm -rf` something; the human approving sees only "Backup".

**Parameters are caller-controlled data entering a user's automation.** `%par1` may be used in a
shell action, a URL, or a file path. The bridge must not sanitise (it cannot know the semantics) but
it must not present parameters as safe either. Parameters should be visible on the approval card.

**Completion is asynchronous and Tasker's queue can be full.** A timeout is mandatory, and "sent" is
not "ran". The route must not claim success on the broadcast alone — the same rule already learned
from `tailscale file cp` exiting 0.

**`Allow External Access` is a user gate the app cannot flip.** If it is off, every run fails with
`AccessBlocked`. This is correct behaviour (a deliberate second gate) and should be reported as its
own error code with a hint, not as a generic failure.

**Tasker is a paid third-party app and may not be installed.** `NotInstalled` is a normal state, not
an error. The plugin should register but report unavailable, like a plugin whose secret is missing.

**Variable injection.** `addVariable("%name", value)` lets the caller name a variable. Lower-case
only, but a caller could shadow a variable the task expects. Restricting variable names to a known
prefix, or dropping `variables` support in v1 and shipping `params` only, is the safer first cut.

---

## 6. Decision

**Build it, as a `tasker` plugin with the three routes above, `params`-only in v1.**

The reasoning, stated plainly: the capability is real (Tasker already holds a large amount of
device automation the user built), the interface is documented and stable, and the route shape is
identical to `/ssh/{name}` — run something pre-authored, verb `EXECUTE`, approval shows the command
but not its effects. That last property is the known cost, and it is the same cost already accepted
for SSH.

What makes it worth doing rather than just "more surface": **`GET /tasker/tasks` makes it
discoverable.** Most integrations fail because the caller must be told a magic string. Here the
bridge can say what exists.

What should NOT be built: inline action construction (`ActionCodes`), and any auto-approve path for
`/tasker/*`. The saved task is the reviewable unit; an action code is not.

**Not built yet — this doc is the deliverable.** Next step, if approved: add the manifest
permission + `<queries>`, a `TaskerIntent`-equivalent broadcaster (the convenience classes are
sample code, not a library — the package name must be changed and the class vendored), the
ContentProvider reader, and the three routes with the five status values mapped to error codes.

---

## Sources

- `https://tasker.joaoapps.com/invoketasks.html` — broadcast interface, `PERMISSION_RUN_TASKS`,
  status values, parameters, completion receiver, the `Allow External Access` preference.
- `https://tasker.joaoapps.com/contentprovider.html` — task list and prefs providers, the
  `<queries>` requirement for API 30+.
- `https://tasker.joaoapps.com/userguide/en/help/eh_http_request.html` — the HTTP Request event,
  Quick Response, `%http_request_id`, and the same-port-and-path collision note.
- `https://tasker.joaoapps.com/userguide/en/intents.html` — intent send/receive semantics, extra
  name mangling into variables, and the limitations on what Tasker can receive.
- `https://joaoapps.com/tasker-6-2/` — the 6.2 release introducing the HTTP server.
