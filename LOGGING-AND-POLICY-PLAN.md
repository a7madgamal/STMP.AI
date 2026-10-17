# PhoneBridge — Audit Log + Request Labels/Policy: Release Plan

Status: **R1 + R2 shipped** (`0.5.2`, build 71). R3 (`0.6.0`) specced in full below, not started.
R4 is architecture only.
Author's constraint: MVP, fail fast and loud, small isolated releases, architecture-first beyond R1.

---

## 0. Goal & non-goals

### Goal

Two features, shipped as four isolated releases:

1. **Audit log** — every request produces exactly one durable local record: accepted, denied,
   timed out, auto-approved, rejected-invalid, unrouted, handler-failed. Full verbose data
   (headers, query, request body, response body) on tap. Survives restart.
2. **Request labels + policy** — every route declares what kind of act it is
   (`read` / `write` / `edit` / `delete` / `execute`), which plugin owns it, and where the
   request came from. A rule set maps label patterns → one of three modes:
   - `SECURE` — today's behaviour: full-screen modal + biometric.
   - `NOTIFY` — heads-up notification, 5s silence = approve, tap `✕` = reject.
   - `OFF` — no UI, log only.

### Non-goals (explicitly out of scope for now)

- Security hardening of the log (encryption at rest, tamper evidence, key management).
  Mido deferred it. The store must nevertheless *record* whether an entry was redacted, so a
  later redaction pass can be applied honestly instead of rewriting history.
- Multi-device / multi-user identity. Origin is an IP-derived label today, not an authenticated
  principal.
- Remote/cloud log shipping.
- Per-resource rules ("this one Gmail thread", "this calendar"). Plugin + verb only.

---

## 1. Current pipeline (as-built, 0.3.2)

```
NanoHTTPD.serve
  └─ PluginRegistry.resolve(method, path)        → 404 not_found if no route
  └─ readBody()                                  → 400 bad_request on bad JSON
  └─ describe() + approvalView() + pluginRows()  → 400/500 BEFORE the modal (fail-fast)
  └─ ApprovalGate.await()                        → blocks; throws Denied/Timeout
  └─ route.handler(req)                          → Resp / PluginError
  └─ json(statusOf(...))
```

Facts that shape the design:

- **One `serve()` call = one request = one decision.** There is exactly one place to instrument.
- **Gating is central** and plugins cannot opt out. The policy router must live at the same seam,
  or the invariant dies.
- `ApprovalGate.await()` currently only signals *outcome* via exceptions; it does not expose
  timing (enqueued / shown / decided), which the audit needs to be useful.
- `Debug` is a 200-line in-memory ring buffer at the bottom of `MainActivity.kt`. It is a
  debug toy, not a durable log. Do not build the audit on it.
- Every route already carries a human `describe` string. That is a *sentence*, not a taxonomy —
  it cannot drive rules (`"TRASH EMAIL"` vs `"SEND EMAIL"` vs `"Modify Gmail labels"`).

---

## 2. Target architecture

Three seams, added in this order. Each one is useless alone, which is why the order matters.

```
                      ┌─────────────────────────────┐
  HTTP request ──────▶│  RequestPipeline (serve)    │
                      └──────────────┬──────────────┘
                                     │ Route.verb + plugin.id + origin
                                     ▼
                      ┌─────────────────────────────┐
                      │  Label(origin, plugin, verb)│  ← R1 (metadata, no behaviour)
                      └──────────────┬──────────────┘
                                     ▼
                      ┌─────────────────────────────┐
                      │  Policy.resolveMode()       │  ← R2 (global switch + exact rules)
                      └──────┬───────────┬──────────┘
                   SECURE    │           │  NOTIFY / OFF
                             ▼           ▼
                  ApprovalGate (queue)  DecisionRouter (no queue)
                             └─────┬─────┘
                                   ▼
                      ┌─────────────────────────────┐
                      │  AuditLog.append(record)    │  ← R1, written at EVERY terminal state
                      └─────────────────────────────┘
                                   ▼
                            LogActivity (R1) + filters/export (R4)
```

**The invariant that must survive every release:** a request reaches a handler only if it has a
decision, and every decision writes exactly one audit record before the HTTP response is sent.
An audit write failure is a request failure (see §10 Q1 — pending confirmation).

---

## 3. Vocabulary (locked in R1, do not churn)

### Verb (`Verb`, 5 values, exhaustive `when`)

| Verb | Meaning | Examples |
|---|---|---|
| `read` | returns data, mutates nothing | `GET /notifications`, `/gmail/messages/{id}`, `/calendar/events` |
| `write` | creates or modifies state other than delete/edit | `POST /gmail/messages/send`, `POST /calendar/events` |
| `edit` | modifies an existing object in place | `POST /gmail/messages/{id}/modify`, `POST /calendar/events/{id}`, `/move` |
| `delete` | removes / trashes | `DELETE /calendar/events/{id}`, `POST /gmail/messages/{id}/trash` |
| `execute` | invokes a foreign app's action (remote control) | `POST /notifications/{id}/action`, `/dismiss` |

No `OTHER`. A route that does not fit is a design problem, not a bucket. `Verb` is a required
constructor argument on `Route`, so "I forgot to classify it" is a **compile error**, not a
runtime surprise — the same rule that made `describe` required.

### Origin (`Origin`)

`OPENCLAW` (`100.64.0.10`), `LOCAL` (loopback), `TAILNET` (other 100.64/10), `UNKNOWN`.
Derived from the remote IP by one pure function. **Recording only in R1–R3.** Not a rule
dimension until there is real identity (§10 Q8).

### Label

`label = "plugin.verb"`, e.g. `gmail.write`, `calendar.delete`, `notifications.execute`.
Origin is a separate field, never folded into the label string.

### Mode

`SECURE` | `OFF` in R2; `NOTIFY` joins in R3 (§7). Produced by the policy (§6). Default in every
ambiguous case: **`SECURE`**.

### Global gate mode

`SECURE` | `OFF` in R2; `AUTOMATIC` and `NOTIFY` join in R3 (§7.1) — the one switch on the home
screen. Default: **`SECURE`**.
`AUTOMATIC` is the only value that consults the rule table; every other value ignores it and forces
that mode on every request (§7.1, §7.6).

### Outcome (one per record)

`APPROVED`, `DENIED`, `DENIED_UNATTENDED` (R3; screen off/locked, §7.2), `TIMEOUT`,
`NOTIFY_APPROVED`, `NOTIFY_REJECTED`, `OFF_APPROVED`, `REJECTED_INVALID` (4xx before the gate),
`UNROUTED` (404), `HANDLER_FAILED` (5xx), `CLIENT_GONE` (R2+; see §11 C4).

`DENIED` and `DENIED_UNATTENDED` are never merged: "he tapped no" and "nobody was there" are
different facts, and the second one is how a mis-scoped `NOTIFY` rule announces itself.

---

## 4. Release map

| Release | Version | Content | Behaviour change | Risk |
|---|---|---|---|---|
| **R1** | 0.4.0 | `verb` metadata on every route + audit spine + log screen + retention | **None.** Gating identical. | Low |
| **R2** | 0.5.0 | Global gate switch (`secure`/`off`) + `OFF` mode + settings/home switch + confirmation + red banner. **Rules and settings screen cut to R3** — with only `secure` to express, a rule table has no observable effect. | Gating can be *disabled* — the first release that can hurt | Medium-High |
| **R3** | 0.6.0 | `NOTIFY` mode (heads-up, 5s silence=approve, `✕`=reject, unattended deny) **and** the exact `plugin × verb` rules + `AUTOMATIC` switch value + settings screen + modal/log rule control — together, because `NOTIFY` is what makes a rule express a decision | New gating mode + stored decisions now change behaviour | Medium-High |
| **R4** | 0.7.0 | Log UX: filters, search, export, counters, decision trace, retention controls | None | Low |

Each release is independently shippable and independently revertable. R1 changes nothing the
caller can observe, which makes it safe to land first and trust the data it produces before any
policy is allowed to act on it.

---

## 5. R1 — Audit spine (FULL DETAIL)

**Theme: observe, do not change.** If R1 is right, every later decision is explainable from the
log alone. If R1 is wrong, everything after it is built on fiction.

### 5.1 Files

New:

```
core/Labels.kt              Verb, Origin, Label, originOf(ip), labelOf(route)
audit/AuditRecord.kt        immutable record + toJson/fromJson (org.json, no new deps)
audit/AuditLog.kt           append-only writer + in-memory ring + retention (takes java.io.File, not Context)
audit/AuditOutcome.kt       enum (see §3)
LogActivity.kt              list + filters (outcome / plugin / day)
LogDetailActivity.kt        one record, verbatim, expandable blobs
```

Modified:

```
core/Plugin.kt              Route gains required `verb`, see 5.2
plugins/*/*.kt              ~15 Route(…) call sites get the new required `verb` argument
ApprovalGate.kt             await() returns GateResult instead of throwing (5.3)
BridgeService.kt            serve() → RequestPipeline; capture audit at every terminal state
MainActivity.kt             "LOG" button → LogActivity; kill nothing else
app/build.gradle.kts        version 0.4.0 / 65 ; + androidx.recyclerview
```

Deliberately **not** touched: plugin handlers, Google API, auth, token store, approval modal UI
(except the plumbing in 5.3).

### 5.2 Route metadata

```kotlin
enum class Verb { READ, WRITE, EDIT, DELETE, EXECUTE }

data class Route(
    val method: String,
    val path: String,
    val describe: String,
    val verb: Verb,           // NEW, required, no default
    val handler: suspend (Req) -> Resp
)
```

`verb` alone is the whole taxonomy: rules key on `(plugin.id, verb)` exactly (§7.5), so anything
finer would be a dimension no screen can set. An earlier draft carried a `scope` string
(`"gmail.messages"`) to support a wildcard cascade — **removed with the cascade.** Do not
reintroduce it until a screen exists that can set it.

Rationale for keeping `describe` untouched: it is the modal's sentence and is already
human-approved. Do not overload it with machine meaning — that is how the old gate-side `when`
table drifted.

### 5.3 Gate returns a result instead of throwing

```kotlin
sealed class GateResult {
    data class Approved(val enqueuedAt: Long, val shownAt: Long, val decidedAt: Long) : GateResult()
    data class Denied(val enqueuedAt: Long, val shownAt: Long, val decidedAt: Long) : GateResult()
    data class TimedOut(val enqueuedAt: Long, val shownAt: Long, val expiredAt: Long) : GateResult()
}

fun await(ctx, remote, endpoint, summary, detail, account, view): GateResult
```

- `DeniedException` / `TimeoutException` are deleted. Callers stop guessing from exceptions.
- `Pending.createdAtMs` already holds the enqueue time; `shownAt` is stamped in
  `postApprovalNotification`; `decidedAt` in `resolve()`.
- Queue depth at enqueue is recorded too (`queueDepth`), because "how long did it sit behind
  another popup" is the first question anyone asks about a slow approval.
- Why now and not later: R2's router needs to bypass the gate entirely for `OFF`, and R3 needs
  to interleave a 5s path with a 60s path. A gate that only returns by throwing makes both
  awkward. It is a contained refactor with exactly **one** call site today.

### 5.4 Record schema (JSONL, one object per line, written once)

```json
{
  "id": "20261017T110503.482-7f3a",
  "ts": 1760699103482,
  "outcome": "APPROVED",
  "label": "gmail.write",
  "plugin": "gmail",
  "verb": "WRITE",
  "origin": "OPENCLAW",
  "remote": "100.64.0.10",
  "requester": "OpenClaw (this box)",
  "route": "POST /gmail/messages/{id}/modify",
  "method": "POST",
  "path": "/gmail/messages/18c2f.../modify",
  "query": {},
  "summary": "MODIFY EMAIL  add=[STARRED] remove=[]",
  "requestHeaders": { "user-agent": "curl/8.5.0", "content-type": "application/json" },
  "requestBody": "{\"addLabels\":[\"STARRED\"]}",
  "responseStatus": 200,
  "responseBody": "{\"ok\":true,\"id\":\"18c2f...\",\"labelIds\":[...]}",
  "error": null,
  "decision": {
    "mode": "SECURE",
    "source": "GLOBAL",
    "globalMode": "SECURE",
    "rule": null,
    "enqueuedAt": 1760699102001,
    "shownAt": 1760699102140,
    "decidedAt": 1760699103400,
    "queueDepth": 0
  },
  "durationMs": 1481,
  "truncated": { "requestBody": 0, "responseBody": 0 },
  "redacted": false,
  "noise": false,
  "app": { "versionName": "0.4.0", "versionCode": 65 }
}
```

Rules:

- **Written once, at the terminal state, before the HTTP response is returned.** No update path,
  no second write. Appending is `O(1)` and cannot corrupt earlier entries by design.
- `truncated` carries the *original byte count* when a blob was capped (cap: 32 KiB each, 64 KiB
  total). Never silently truncate; the detail screen shows
  `body truncated — 214 KiB original`.
- `redacted:false` for now, set by the same switch that will later redact. Honest history.
- `noise` is **derived at write time, never user-editable**, by one pure function
  `AuditNoise.isNoise(outcome, path, query)` (§5.12). Deriving it at write time means the list can
  filter by a stored flag instead of re-classifying 2,000 rows on every render, and means the
  classification for a given record can never change retroactively.
- `requestHeaders` captures everything NanoHTTPD exposes minus obvious hop-by-hop junk
  (`host`, `content-length`). No auth header exists today; when one does, it is redacted.
- `responseBody` is captured **only** for records the client actually received. A handler that
  throws records the mapped error body, not the exception stack (stack goes to logcat).

### 5.5 Storage

`AuditLog` — append-only JSONL, one file per UTC day:

```
filesDir/audit/2026-10-17.jsonl
```

- **Constructor takes `java.io.File`, not `Context`.** Same lesson as the plugin-rows guard:
  anything testable on the JVM must not need an Android `Context`. `AuditLog(dir)` is unit
  testable; a thin `AuditStore.from(ctx)` wraps it in the app.
- Single writer, `@Synchronized append()`; `BufferedWriter` per append with `flush()`.
  `fsync`-level durability is deliberately **not** attempted (MVP); a power cut can lose the
  last line, never earlier ones.
- In-memory ring of record *headers* (id, ts, outcome, label, summary, duration) capped at
  2,000 for the list view. Detail reads the file line by the id prefix (sortable id).
- Retention enforced at init and on first open of the log screen: delete day-files older than
  **7 days**, and if the directory exceeds **20 MB**, drop oldest whole days. Both constants in
  one `AuditRetention` object, not scattered.
- Corrupt line on read: **skip it and show a `⚠ N unreadable entries` banner.** Do not fail the
  whole log. (This is the one place a tolerant read is correct: the writer never produces a
  partial line, so a corrupt line means external damage, and hiding it would be worse.)

Why JSONL over Room for MVP: zero annotation processing (the project has no KSP/kapt wiring),
no schema migration, append-only, trivially exportable, and it satisfies "list + detail + filter
by chip" with a 2,000-row in-memory index. If R4 needs real queries, `AuditStore` is an
interface and Room can replace the implementation without touching the pipeline. That escape
hatch is the reason for the interface from day one.

### 5.6 Pipeline wiring (`HttpServer.serve`)

One method, one record, one `try/finally`. Shape:

```kotlin
fun serve(session): Response {
    val t0 = elapsedRealtime()
    val ctx = AuditContext(t0, remote, method, path, query, headers)   // mutable outcome holder
    try {
        val resolved = resolve(...) ?: { ctx.outcome = UNROUTED; return 404 }
        ctx.bindRoute(resolved)                       // label, plugin, verb
        val body = readBody(session)                   // may throw → REJECTED_INVALID
        ctx.body = body
        val summary = describe(...); ctx.summary = summary
        val view = approvalView(...)                   // may throw → REJECTED_INVALID / 500
        val gate = ApprovalGate.await(...)             // R1: always SECURE
        ctx.decision = gate
        ctx.outcome = when (gate) { Approved → APPROVED; Denied → DENIED; TimedOut → TIMEOUT }
        val resp = route.handler(req)
        ctx.response(resp)
        return json(...)
    } catch (e: PluginError) { ctx.outcome = REJECTED_INVALID if status<500 else HANDLER_FAILED ... }
      catch (e: Exception)    { ctx.outcome = HANDLER_FAILED ... }
    finally {
        AuditLog.append(ctx.toRecord())   // see 5.7 — failure policy is Q1
    }
}
```

Detail: the `finally` must run **after** the response object exists but **before** `serve()`
returns, so a slow disk write is inside the request's latency (and therefore visible), not
racing the client. NanoHTTPD sends the response when `serve()` returns.

### 5.7 Audit write failure

**LOCKED (Q1, Mido 2026-10-17): fail closed. Deny.**

If `AuditLog.append` throws, the response is replaced with
`500 {"ok":false,"error":"audit_unavailable"}`. Nothing is ever presented as succeeded without
a record on disk.

Implementation rules this decision forces:

- `audit_unavailable` is added to `httpStatusFor` → 500 **in the same change** (invariant 7).
- Ordering: the handler's side effect has already happened when the append fails, so the 500 is
  honest but not a rollback. The recorded message is exactly
  `audit_unavailable: action may have executed, no record` — never a bare code.
- The append must run in `finally` **before** `serve()` returns (see 5.6), so the failure is
  inside the request and the client sees it. A background append would make this policy
  unenforceable.
- A failed append also: writes the record to logcat (`Diagnostics`, untruncated), sets a
  `auditDegraded` flag, and shows a persistent red banner in `MainActivity` until a successful
  append clears it. A degraded audit log must be visible without opening the log screen.
- No retry loop. One attempt; a second failure inside a failing disk is noise, not recovery.
- Consequence accepted: a full disk now takes the bridge fully offline for mutations *and* reads.
  That is the intended trade (Q1).

### 5.8 Log screen (MVP UI)

List (`LogActivity`, RecyclerView):

```
11:05:03  ✅ APPROVED   gmail.write    MODIFY EMAIL add=[STARRED]      1.4s
11:04:51  ⛔ DENIED     calendar.delete DELETE CALENDAR EVENT — Lunch    3.2s
11:03:12  ⏱ TIMEOUT    gmail.read     Read a Gmail message            60.0s
```

- Filter chips: outcome, plugin, today/7d, **noise toggle** (noise hidden by default). The chip label
  names the action, so it reads `show noise` while noise is hidden and `hide noise` once shown. No
  text search in R1 (R4).
- Noise is one tap away, never deleted: a record's `noise` flag is a lens, not a filter at write
  time. Showing noise reveals every probe and typo in place, in time order.
- Long-press a record → **`mark as noise` / `mark as signal`** writes `noise_override` on that
  record only (see §5.12) — the classifier will be wrong about something, and the answer must not
  be "edit the code and rebuild".
- Row tap → detail. Long-press → copy record JSON (cheap, and it is the thing you actually want
  when reporting a bug).
- Detail: header (outcome, label, duration, mode), WHO, WHAT, REQUEST (method/path/query/headers/
  body), RESPONSE (status/body), DECISION (enqueued/shown/decided/queue depth), raw JSON toggle.
- Empty state: `No requests yet.` — never a fake row.
- Entry point: button on `MainActivity` next to the existing debug dump, plus a "View log" action
  on the denied/timeout notification? No — R1 keeps one entry point. Less surface, less to break.

### 5.9 Tests (JVM, no device)

1. `LabelTest` — `originOf()` maps 100.64.0.10 → OPENCLAW, 127.0.0.1 → LOCAL, 100.64.0.11 →
   TAILNET, garbage → UNKNOWN. Never throws, never guesses.
2. `AuditCodecTest` — record → JSON → record round-trip with Unicode, newlines, quotes, emoji,
   and a 40 KiB body (asserts the 32 KiB cap **and** the `truncated` original size).
3. `AuditLogTest` — temp dir: append 3 records, re-open, read 3 headers; retention deletes a
   day-file older than 7 days; a hand-written corrupt line is skipped and counted.
4. `AuditNoiseTest` — the noise table is exhaustively asserted, so adding an outcome without
   deciding its noise status fails the build rather than defaulting into the hidden view.

(`PolicyResolveTest` arrives with R2, where `resolveMode()` exists. It is NOT an R1 test — an
earlier draft listed it here, which would have meant either a skipped test or a stub engine.)

All four run on a plain JVM. The store takes a `File` rather than a `Context` for exactly this
reason: retention and corruption handling are the two paths that decide whether the log can be
trusted, and neither should be verifiable only by hand on a device.

### 5.10 Device acceptance (must all be observed, not assumed)

1. Six requests → `APPROVED` read, `DENIED` read, 60s `TIMEOUT`, `REJECTED_INVALID` (`?count=-1`
   on `/notifications`), `UNROUTED` (`/status`), a forced handler failure. Log shows **exactly
   six** records, each with the right outcome and a plausible duration, and no duplicates.
2. Kill the app process, reopen → all six still listed; detail opens.
3. Body cap: send a Gmail message with a 200 KiB body → detail shows the truncation notice with
   the real original size.
4. `adb shell` not required; the UI is the proof.
5. Gating unchanged: modal still appears for every request, biometric still required, deny still
   403, timeout still 408. **Compare against the 0.3.2 behaviour table, not against vibes.**

### 5.11 R1 done criteria

- Build green, lint green (`allWarningsAsErrors = true` stays on).
- All §5.10 items observed on the device and written into `references/known-status.md`.
- Version 0.4.0 shipped as `PhoneBridge-v0.4.0-debug.apk`, signer SHA-1 unchanged.
- Skill doc updated: the "read/deny/timeout" behaviour table now also states that every request
  is logged and where.

### 5.12 Noise classification — **LOCKED (Q9, Mido 2026-10-17): log everything, make noise easy to filter**

Everything is written. Nothing is dropped at write time. "Every single action" stays literally
true, and the log never develops a blind spot where the misbehaving thing lives.

`AuditNoise.isNoise()` — the entire rule, one pure function, unit-tested:

| Outcome | Noise? |
|---|---|
| `UNROUTED` | **yes** (probes, `/status` liveness checks, wrong URLs) |
| `REJECTED_INVALID` | **yes** (typos, missing fields — the caller's mistake, not a decision) |
| `HANDLER_FAILED` | no |
| everything else (`APPROVED`, `DENIED`, `TIMEOUT`, `DENIED_UNATTENDED`, `OFF_APPROVED`, `NOTIFY_*`) | no |

Two exceptions to the table:

- A `GET` to the known liveness probe path list (empty in R1) is noise. Left as a hook, not a
  feature. Do not invent paths to special-case.
- A record flagged `noise_override` (long-press in the UI) wins over the derived value, in both
  directions. Stored on the record.

Why derived-and-stored rather than a UI-only filter: the R2/R4 counters ("`gmail.write` 142 total,
3 denied") must not be diluted by 400s, and those counters will be computed from the same flag.
One definition of noise, used by the list, the counters, and any future export.

Explicitly rejected: sampling, dropping 404s before they hit disk, and a "quiet mode" that stops
writing. All three recreate the blind spot Q9 just removed.

### 5.13 Tuning defaults — **LOCKED (defaults accepted 2026-10-17 unless reversed)**

- Storage: JSONL day-files, no Room (Q5).
- Retention: 7 days / 20 MB hard cap, whole days dropped oldest-first (Q10).
- `✕` in `NOTIFY`: a single notification action labelled `✕ Reject`, not an overlay (Q6).
  `TYPE_APPLICATION_OVERLAY` stays unused until a real complaint justifies it.

### 5.14 Opportunistic defect fix — `not_found` status mapper — **LOCKED (Mido 2026-10-17: fix it)**

`httpStatusFor` in `BridgeService.kt` has no `not_found` entry. A bad-id pre-fetch answers **HTTP
500** with a correct `not_found` body — documented in `phonebridge-api` as a known build defect.

The audit log is about to turn that into a visible stream of 500s, so fix it in R1 as its **own
commit**, before the audit spine lands:

- add `"not_found" -> Response.Status.NOT_FOUND` to the mapper;
- response body unchanged (`{"ok":false,"error":"not_found"}`);
- this is a caller-visible status change: 500 → 404, body identical. Note it in the release notes
  and remove the "reported as 500" caveat from the `phonebridge-api` skill in the same change;
- verify live with a bogus calendar id (expect 404, no modal) and confirm no client keys on 500.

---

## 6. R2 — Gate switch + `OFF` (FULL DETAIL)

**Scope cut — Mido, 2026-10-17.** `NOTIFY` is not in this release. That cut has one consequence
worth stating plainly: with only `secure` and `off` available, a per-pair rule can only say
`secure` — which is exactly what "no rule" already means. The rule table would be machinery with no
observable effect, and a settings screen that cannot change anything. So **the rules move to R3**,
in the same release that gives them a second outcome to express.

R2 ships one thing: **the global switch, and the `OFF` mode it can select.**

### 6.1 The switch — **LOCKED (Mido 2026-10-17)**

```
SECURE | OFF          default: SECURE
```

- Sticky across restart.
- **`SECURE`** — every request goes through `ApprovalGate.await()`, exactly as in R1.
- **`OFF`** — no gate at all: no popup, no fingerprint, no FIFO. The handler runs and the record
  says `OFF_APPROVED`.
- Changing it writes a `POLICY_CHANGED` audit record with `{before, after, source}`. The switch
  change is a fact in the log, stamped in time, sitting next to the traffic it changed.
- Selecting `OFF` requires a confirmation dialog.

| Value | Home screen |
|---|---|
| `SECURE` | neutral — the resting state |
| `OFF` | red banner, persistent while selected: `OFF — every request is approved and executed. The gate is disabled.` |

The banner is not a toast. It stays up for as long as `OFF` is selected.

### 6.2 What R3 adds

`AUTOMATIC` and `NOTIFY` join the switch, the exact per-pair table arrives, the settings screen and
the modal rule control are built (§7.8, §7.5). R2's job is to make the switch real, audited and
loud, so that R3 only has to add values to something already proven.

R2 shipped `SECURE | OFF` only — `AUTOMATIC`/`NOTIFY` were cut from R2 precisely because a rule
table whose only legal value is `secure` has no observable effect (§7). The store is written
**forward-compatible**: a `rules` array exists from R2 and is always empty. R3 fills it. No
file-format migration.

### 6.3 Resolution — one lookup, no engine

```kotlin
fun resolveMode(global: GlobalMode): Mode = when (global) {
    GlobalMode.SECURE -> Mode.SECURE
    GlobalMode.OFF    -> Mode.OFF
}
```

Total, exhaustive, no default branch. Adding `AUTOMATIC`/`NOTIFY` in R3 is a compile error at this
`when`, which is the point: the compiler asks where the new value goes instead of a `else -> SECURE`
quietly absorbing it.

### 6.4 Fail-hard on a bad store

A malformed `policy.json` (unknown value, wrong type, unreadable file) **refuses to start the
server**: `lastError` is set, the home screen shows a red banner, and no HTTP listener comes up. A
policy you cannot read is not a policy. No default, no fallback to `SECURE`, no "repair and
continue" — the same rule R1 applies to the audit log.

### 6.5 Files

- `policy/GlobalMode.kt` — `SECURE`, `OFF`. R3 adds two values here.
- `policy/Mode.kt` — `SECURE`, `OFF` (the *effective* mode, distinct from the switch because R3's
  `AUTOMATIC` is a switch value that is not a mode).
- `policy/Policy.kt` — `{global, rules: []}` + strict parse.
- `policy/PolicyStore.kt` — atomic read/write of `filesDir/policy.json`, cached after first load.
- `BridgeService` — resolves the mode after validation, before the gate.
- `MainActivity` — the switch, the banner, the confirmation dialog.
- `audit/` — `POLICY_CHANGED` outcome + append helper.

### 6.6 Where it plugs in

`serve()` resolves the mode **after** `validate` (so bad input still costs nothing) and **before**
the gate:

- `SECURE` → `ApprovalGate.await()` (unchanged).
- `OFF` → no gate; `outcome = OFF_APPROVED`; handler runs. Never enters the FIFO, so an ungated
  request can never wait behind a human thinking about a modal.

`requiresBiometric` stays `true` in R2 — and that is not laziness. An `ApprovalView` only exists on
the `SECURE` path, so inside the gate the mode is always `SECURE`. R3 computes it from the mode
because R3 is the first release where a view can exist under a non-`SECURE` mode.

### 6.7 `POLICY_CHANGED` records

Policy history lands in the same JSONL day-file as traffic — that is the whole reason R1 shipped
before R2. A policy record is not a request, so it is shaped as one deliberately:

| Field | Value |
|---|---|
| `plugin` | `policy` |
| `verb` | `EXECUTE` |
| `label` | `policy.changed` |
| `outcome` | `POLICY_CHANGED` |
| `summary` | `switch: SECURE → OFF (settings)` |
| `decision.mode` | the mode **after** the change |
| `decision.source` | `GLOBAL` |

It is **never noise** (`AuditNoise` must return false): turning the gate off is the single most
important line in the log.

### 6.8 Tests (JVM)

- `resolveMode` is total over the enum and never returns a value outside `Mode`.
- A malformed store throws; it never falls back to `SECURE`.
- `{"global":"OFF","rules":[]}` round-trips through write → read.
- An unknown `global` string (e.g. `"AUTOMATIC"` written by a future build) is a **parse error**,
  not a silent `SECURE`. Downgrading to R2 must fail loudly, not quietly re-enable the gate.
- `POLICY_CHANGED` is classified as signal, not noise.

### 6.9 Device acceptance

1. Switch to `OFF` → confirmation dialog → red banner on home.
2. Fire a gated read → **no popup**, 200, log line `OFF_APPROVED`.
3. `POLICY_CHANGED` appears in the log with before/after.
4. Kill and relaunch the app → still `OFF`, banner still there.
5. Switch back to `SECURE` → popup returns; `POLICY_CHANGED` again.
6. Corrupt `policy.json` (via adb) → server refuses to start, red banner, no listener.

---

## 7. R3 — `NOTIFY` mode + exact rules (FULL DETAIL)

**Theme: the first release where the log decides behaviour.** R1 observed, R2 gave one blunt
switch. R3 is the first time a *stored decision* changes how a request is treated — and therefore
the first release where a policy bug is a security bug rather than a display bug.

**Scope.** Two things, shipped together because one is useless without the other:

1. **`NOTIFY`** — a third gate mode: heads-up notification, 5s silence = approve, `✕` = reject,
   unattended = deny.
2. **Exact rules** — `plugin × verb → mode`, readable only when the global switch is `AUTOMATIC`.

Why together: in R2 a rule could only say `secure`, which is exactly what "no rule" already says.
A rule table with one legal value is machinery with no observable effect — a screen that cannot
change anything. `NOTIFY` is what makes a rule express a *decision*, so the picker, the settings
screen and the modal control all arrive with the second outcome they need.

### 7.1 The switch gains two values — **LOCKED (Mido 2026-10-17)**

```
AUTOMATIC | SECURE | NOTIFY | OFF          default: SECURE
```

| Value | Meaning | Rules |
|---|---|---|
| `AUTOMATIC` | The per-pair table decides. No rule for a pair → `SECURE`. | **read** |
| `SECURE` | Every request: modal + biometric. | ignored |
| `NOTIFY` | Every request: heads-up, 5s silence = approve. | ignored |
| `OFF` | No gate at all. Log only. | ignored |

- Only `AUTOMATIC` consults the rules. The other three force one mode on every request, which is
  what makes `OFF` a **true panic switch** rather than "log-only for unconfigured pairs".
- No rule for a pair under `AUTOMATIC` resolves to `SECURE`. Silence means *ask me*; nothing
  auto-approves by accident. This is the §12 invariant applied to the rule table.
- `AUTOMATIC` needs the rules screen to be useful at all, so the two ship in one release even
  though the switch value is one line.

**UX: unsecure must be loud.** `NOTIFY` gets an amber banner on home while selected; `OFF` keeps
its confirmation dialog and permanent red banner from R2 (§6.1). `AUTOMATIC` and `SECURE` are
neutral. The rule is: *the home screen must never show a switch position without showing what it
implies.* A user who cannot tell at a glance whether they are gated has an unusable gate.

### 7.2 `NOTIFY` mechanics

- Separate channel `approval_notify`, `IMPORTANCE_HIGH`, `setSound(null)`, no vibration beyond the
  heads-up. The point of the mode is *subtle*; a sound would defeat it.
- Content: `🔔 <summary>`, subtext `<label> · <duration>`, one action `✕` =
  `PendingIntent.getBroadcast` → reject. No full-screen intent, no Activity, no overlay.
- 5s timer starts **when the notification is actually posted**, not at enqueue — the same rule the
  modal's clock follows (`shownAtMs`). A request queued behind another must not burn its window
  while invisible.
- Silence → **approve**. `✕` → **reject**.
- `setTimeoutAfter(windowMs)` removes the notification when the window closes, so an approved
  notification does not linger on the shade.
- **The caller-visible contract does not change.** A `✕` rejection answers `403
  {"ok":false,"error":"denied"}` — the *same* body as a modal DENY. Clients do not need a new
  branch; the distinction lives in the log (`NOTIFY_REJECTED` vs `DENIED`) and in the record.
  Introducing a new error code for a new UI would make every client care about our internals.
- If the screen goes off inside the window the request is **denied, not approved** (§7.3), and the
  notification is cancelled. A notification whose answer is already `denied` must not linger.

**Q8 (still open, blocks this release):** is the window a flat 5s, or 5s plus a short grace (8s) so
a mistaken `✕` is recoverable? *Recommendation unchanged: 5s flat. A countdown you can learn beats
a window that varies.* This must be decided before `NotifyGate` is written, because the value is
baked into the timer, the audit duration and the acceptance test.

### 7.3 Gate availability — **LOCKED (Q2, Mido 2026-10-17)**

There is **no escalation**. A request that cannot be shown to an attending human is **denied**.

```kotlin
fun isAttended(ctx: Context): Boolean
    = powerManager.isInteractive && !keyguardManager.isKeyguardLocked
```

| Mode | Attended | Not attended (screen off or locked) |
|---|---|---|
| `OFF` | approve | **approve** — the gate is deliberately off, no human expected |
| `NOTIFY` | heads-up, silence = approve | **deny** |
| `SECURE` | modal + biometric | **deny** |

One line, and that is the point: *if a human cannot see it, nobody approves it — unless you have
explicitly turned the gate off for that class of request.*

Rules this forces:

- Checked at decision time, **after** all fail-fast validation (`Route.validate` and
  `describeRows`), so a malformed request still gets its honest 400 and never a misleading
  `denied`.
- Checked a **second time at approve time** in `NOTIFY`: if the screen turns off during the
  window, the request is denied. Silence is only consent while someone could have seen it.
- New outcome `DENIED_UNATTENDED` (already declared in `AuditOutcome`, and already classified
  non-noise in `AuditNoise`). The log must never confuse "he tapped no" with "nobody was there" —
  the second is how a mis-scoped `NOTIFY` rule announces itself.
- Caller still receives `403 denied`; the reason lives in `detail` and the record. HTTP contract
  stable.
- `NOTIFY`'s other failure escalations — channel disabled, notification permission blocked, DND —
  all collapse into the same answer: **deny**. There is no fallback path that approves invisibly.
- Only `OFF` approves unattended, and `OFF` is reachable only through the global switch: a single
  explicit act with a confirmation dialog, a persistent red banner, and a `POLICY_CHANGED` record.
  A per-pair rule can never produce that behaviour, because `OFF` is not a rule value (§7.5).
- **Consequence for R2's code:** `ApprovalGate.await()` is now only ever called when
  `isAttended()` is true, so the full-screen intent can no longer fire onto a locked screen. That
  was always the intent; R3 is where it becomes guaranteed rather than assumed.
- Mid-modal lock: a request already on screen keeps its original 60s clock (`createdAtMs`); if the
  human locks the phone it expires as `TIMEOUT`. **Do not cancel on lock.** The clock rule is
  already load-bearing and a cancel-on-lock would make the modal's countdown lie.

### 7.4 Biometric — **LOCKED (Q3, Mido 2026-10-17)**

**`BiometricPrompt` exists only in `SECURE`. `NOTIFY` and `OFF` never prompt.**

This supersedes the v0.3.1 rule ("APPROVE on every request, reads included"), which now reads
*every `SECURE` request, reads included*. A 5s silence-approve that demands a fingerprint is a
contradiction, and `OFF` has no human at all.

Implementation rules:

- `ApprovalView.requiresBiometric` stops being a hardcoded `true`. It is computed from the
  resolved mode (`mode == SECURE`) and passed into the view the gate builds. The Activity still
  only renders the flag — it never decides. Same principle as the rows.
- **Honest note on observable effect:** in R3 this computes to `true` at every site that builds a
  view, because a view only exists on the `SECURE` path — `NOTIFY` has no Activity and `OFF` never
  builds one. So this is *correctness plumbing*, not a behaviour change: it stops a future mode
  that reuses the modal from silently inheriting a fingerprint. Do not claim a behaviour change
  here in the release notes. The three `requiresBiometric = true` sites in `BridgeService` become
  `requiresBiometric = (mode == SECURE)`.
- Do **not** add a guard in `NOTIFY` that pretends a biometric could happen. It is unreachable by
  construction; a guard would be a lie about the design.
- Degraded case to keep honest: with no biometric enrolled, `SECURE` still cannot approve
  (unchanged v0.3.1). `NOTIFY`/`OFF` are unaffected — they never needed one. So a phone with no
  fingerprint enrolled can now use `NOTIFY` and `OFF`, which is impossible today. Harmless:
  `NOTIFY` still requires an attended screen and `OFF` is the switch you turned off on purpose.
  Worth one line in the acceptance list.
- **Docs must change in this release.** `android-phone-bridge/references/approval-modal.md` and
  the `phonebridge-api` skill both state that every request needs a fingerprint. That is false
  from 0.5.0 for `OFF` and from 0.6.0 for `NOTIFY`. A doc that teaches the wrong model is how the
  next debugging session goes wrong.

### 7.5 Exact rules — **LOCKED (Mido 2026-10-17)**

```kotlin
data class Rule(
    val plugin: String,   // exact: "gmail" | "calendar" | "notifications" | "debug". Never "*".
    val verb: Verb,       // exact: READ | WRITE | EDIT | DELETE | EXECUTE. Never null.
    val mode: Mode        // SECURE | NOTIFY.  OFF is NOT a rule value.
)

data class Policy(
    val global: GlobalMode,   // AUTOMATIC | SECURE | NOTIFY | OFF
    val rules: List<Rule>
)
```

- **The picker offers `default | notify | secure`.** `default` *deletes* the rule; `notify` and
  `secure` store one. The settings list therefore reads back as a list of decisions rather than a
  list of exceptions.
- Stored `SECURE` and no rule are **behaviourally identical** (`rule?.mode ?: SECURE`). Say it out
  loud in the UI so nobody hunts for a difference that does not exist: a saved `secure` row renders
  as `gmail · delete — secure (default)`.
- **`OFF` cannot be set per pair — LOCKED (Q12).** It exists only as a global switch value. The
  per-pair picker is exactly three options, so there is no way to auto-approve one pair in
  isolation; "log only for `gmail · delete`" means turning the gate off for the entire app.
  Accepted trade: `OFF` is the one value meaning "no human, no fingerprint", and one switch is
  easier to remember to turn back than twelve rows. Do not add a 4th picker option without a fresh
  decision — doing so reopens whether an accidentally-set `OFF` row can silently bypass the gate,
  and it would need the same confirmation + banner treatment the global switch has.
- **One rule per `(plugin, verb)` pair**, enforced on load. A duplicate pair is a **startup
  error**, never last-wins: the same file must not resolve differently depending on write order.
- **No `scope`, no wildcards.** `(plugin, verb)` is the whole key and the whole screen. An earlier
  draft carried a `scope` string and a `*` cascade; both were removed with the cascade (§7.6).
  Q11 (equal-specificity conflict) is **void** — with exact keys two rules can never both match.
- **Unknown values are parse errors.** An unrecognised `plugin`, an unrecognised verb, or a mode
  that is not `SECURE`/`NOTIFY` refuses to load (§6.4 applies unchanged: unreadable policy = no
  server).

### 7.6 Resolution — the whole policy system

```kotlin
fun resolveMode(global: GlobalMode, rules: List<Rule>, plugin: String, verb: Verb): Mode =
    when (global) {
        AUTOMATIC -> rules.find { it.plugin == plugin && it.verb == verb }?.mode ?: SECURE
        SECURE    -> SECURE
        NOTIFY    -> NOTIFY
        OFF       -> OFF
    }
```

That is the entire policy engine. It is `find` on a list.

- Total and exhaustive over the enum, **no `else` branch**. R2 deliberately wrote this `when` with
  two arms so adding `AUTOMATIC`/`NOTIFY` would be a **compile error right here** — that is exactly
  what happens now, and it is why the new values cannot be absorbed silently.
- The audit records `Decision(mode, source, rule?)` with `source ∈ {GLOBAL, RULE, DEFAULT}`.
  `DEFAULT` is precisely "`AUTOMATIC` and no rule matched". There is no `considered` list, no
  specificity, nothing to explain beyond one lookup — because nothing else could have won.
- **`resolveMode` gains three parameters, and that is a signature change, not an overload.** The
  one call site in `BridgeService` passes the resolved route's plugin and verb. Keeping a
  two-arg version around for the tests would be a second code path deciding a security question.
  Delete the old signature.

### 7.7 Queue interaction

`NOTIFY` requests **do not enter the `SECURE` FIFO**. Two independent serializers:

- `ApprovalQueue` (modal) — unchanged, 60s, FIFO, one visible.
- `NotifyQueue` — at most one visible notify at a time, 5s each, drained in arrival order.

They share **only** the audit log. A request classified `SECURE` while a notify is on screen still
queues for the modal — different paths, no interaction. This is deliberate: making them share one
queue would mean a 5s notification could block behind a 60s modal, which is a worse failure than
either mode alone.

If a mode mix makes two requests wait, the *notify* one must not silently expire while queued — its
window starts when it is posted (§7.2), so a queued notify simply takes longer to be posted, never
loses its window invisibly.

### 7.8 Screens

**Settings screen** — the only place rules are authored:

- Global switch (4 radio options) with the security treatment from §6.1/§7.1.
- When global ≠ `AUTOMATIC`, the rule table renders **greyed out** under one line:
  `Rules are ignored while the switch is SECURE / NOTIFY / OFF.` Greyed, not hidden — you must be
  able to see what you configured while you cannot use it, or the switch becomes a memory test.
- When global = `AUTOMATIC`: one row per configured pair (`gmail · delete → NOTIFY`) plus an
  `add rule` picker (plugin → verb → mode).

**Modal rule control** — decide from the prompt you are already looking at:

- One compact row: `This request: gmail · delete` + a 3-way control `default | secure | notify`.
- `default` deletes the rule for that pair. `secure` also stores nothing (SECURE is absence) but is
  offered anyway, because a human picking from a list expects to see their current choice.
- Applying it takes effect **from the next request** and **never decides the request on screen**. A
  control that silently resolved the prompt you are looking at would be a second, invisible
  decision — the exact thing this whole design exists to avoid. Tapping it shows
  `saved — applies from the next gmail · delete request`, then leaves the countdown alone.
- Always present, including when global ≠ `AUTOMATIC`, with `ignored while global = SECURE` in the
  same row. One line of text beats making the user hunt for why it did nothing. **C7 is the risk
  here:** if this still reads as broken in real use, the fix is to *disable* the control, not to
  annotate it harder.

**Log screen** — the same control on a record's detail view (§5.8), pre-filled with that record's
`(plugin, verb)`. The log is where you notice a decision you dislike, so the fix belongs one tap
from it.

### 7.9 Files

New:

```
policy/Rule.kt            Rule(plugin, verb, mode) + strict parse
policy/Attendance.kt      isAttended(ctx) — the one-line gate-availability check
notify/NotifyGate.kt      post / wait / resolve the 5s path + NotifyQueue
SettingsActivity.kt       the rules screen (programmatic views, no res/layout — see §5.1)
```

Modified:

```
policy/PolicyMode.kt      GlobalMode += AUTOMATIC, NOTIFY ; Mode += NOTIFY ; resolveMode(global, rules, plugin, verb)
policy/Policy.kt          parse/emit NON-EMPTY rules (VERSION stays 1 — see below) ; duplicate-pair = error
policy/PolicyStore.kt     unchanged storage; may gain rule upsert helpers
BridgeService.kt          resolve with plugin+verb ; branch NOTIFY before the gate ; isAttended guard ;
                          requiresBiometric = (mode == SECURE) at the three view sites
MainActivity.kt           switch becomes 4 values + amber/red banners
ApprovalGate.kt           unchanged behaviour; the OFF/NOTIFY branching stays in BridgeService
ApprovalActivity.kt       NO change — it already renders `requiresBiometric` from the view (§7.4)
app/build.gradle.kts      version 0.6.0 / 72
docs                      approval-modal.md + phonebridge-api skill: fingerprint is SECURE-only
```

**`VERSION` stays 1 — no migration.** R2 wrote `version: 1` with an empty `rules` array *on
purpose* so R3 could fill it without a format change (§6.2). Two consequences to keep honest:

- An R3 file with rules read by an R2 build is already a **parse error** ("this build has no rule
  support"), so a downgrade refuses to start rather than silently ignoring saved decisions. That
  behaviour was designed in R2 and must not be weakened now.
- An R2 file with an empty array read by R3 is valid and simply has no rules. Nothing to migrate.

Also update: the R2 test *"an unknown `global` string (e.g. `AUTOMATIC` written by a future build)
is a parse error"* — `AUTOMATIC` is a known value from R3, so this test **must** switch to a
genuinely unknown literal (e.g. `"PANIC"`) or it stops testing forward-compatibility and starts
testing nothing. Flagged because a test that silently changes meaning is how the §5.9 lesson
repeats.

### 7.10 Where it plugs in

`serve()` gains one step, in this order — the ordering is the security property:

1. `resolve(method, path)` → `bindRoute` → read raw body → parse → `validate` (pre-gate, §5.2).
2. `describe` + `describeRows` (pre-gate; a missing row set still throws before any UI).
3. **Resolve policy:** `global = policy.global`; `mode = resolveMode(global, rules, plugin, verb)`.
4. **Attendance check** (§7.3): if `!isAttended()` and mode ≠ `OFF` → `DENIED_UNATTENDED`, 403,
   done. No notification is posted at all.
5. Branch on `mode`:
   - `SECURE` → `ApprovalGate.await()` (unchanged) → `APPROVED` / `DENIED` / `TIMEOUT`.
   - `NOTIFY` → `NotifyGate.await()` → `NOTIFY_APPROVED` / `NOTIFY_REJECTED` / `DENIED_UNATTENDED`
     (screen-off mid-window).
   - `OFF` → no gate; `OFF_APPROVED`.
6. Handler runs, `recordResponse(...)` (§7.10 / `audit/AuditResponse.kt`), one record written.

A policy read that throws mid-request still yields `HANDLER_FAILED` + `policy_unavailable` → 503,
exactly as R2 wired it. R3 does not change that path.

### 7.11 `POLICY_CHANGED` for rule changes

Every change writes `POLICY_CHANGED` with `{before, after, source: SETTINGS|MODAL|LOG}`, the same
record shape R2 introduced (§6.7) — `plugin=policy`, `verb=EXECUTE`, never noise. Policy history
lands in the same file as traffic, so "why did this run unapproved?" is answered by the line above
it.

### 7.12 Tests (JVM)

- `resolveMode` is **total over all four** globals and never returns a value outside `Mode`.
- `AUTOMATIC` + a matching rule → that rule's mode; `AUTOMATIC` + no rule → `SECURE` (**the
  invariant, tested explicitly**).
- `SECURE`/`NOTIFY`/`OFF` **ignore the rules** even when a matching rule exists.
- `Policy` with a non-empty `rules` array round-trips through write → read; rule order is preserved.
- A **duplicate `(plugin, verb)` pair** is a parse error, not last-wins.
- An unknown **verb** or an unknown **mode** inside a rule is a parse error.
- The R2 forward-compat test is updated to a genuinely unknown `global` (see §7.9).
- `isAttended` is false when the keyguard is locked or the screen is off (pure wrapper, injected
  state — do not call the real PowerManager in a JVM test).
- `NOTIFY` classification: silence → `NOTIFY_APPROVED`, `✕` → `NOTIFY_REJECTED`, screen-off
  mid-window → `DENIED_UNATTENDED`; **all three non-noise** in `AuditNoise` (already declared, now
  asserted).
- `AuditClassify` still maps a pre-gate 4xx to `REJECTED_INVALID` and a post-gate failure to
  `HANDLER_FAILED` — R3 must not disturb this.
- Golden record: a `NOTIFY` record carries the right `mode`, `source` (`RULE` vs `DEFAULT`) and
  `rule` string, so a future reader can explain the decision from the file alone.

### 7.13 Device acceptance (must all be observed, not assumed)

1. `SECURE` + rule `gmail · read → NOTIFY` → fire a Gmail read → **heads-up, no modal**.
2. Leave it → `200`, log `NOTIFY_APPROVED`, `source=RULE`, `rule=gmail/READ`.
3. Fire again and tap `✕` → `403 denied`, log `NOTIFY_REJECTED`.
4. Fire again and **lock the phone inside the window** → `403 denied`, log `DENIED_UNATTENDED`,
   notification gone.
5. Set global `NOTIFY` → every request is heads-up, the rule table greys out with its one-line
   reason, `POLICY_CHANGED` recorded.
6. Set global `OFF` → identical to R2 (§6.9) — no popup, `OFF_APPROVED`.
7. Change a rule from the **modal** and from the **log detail** → `POLICY_CHANGED` with
   `source=MODAL` / `LOG`, and the *request on screen is unaffected*.
8. Hand-edit a duplicate pair into `policy.json` → server refuses to start, red banner.
9. Fire two requests at once under `NOTIFY` → both get their own 5s window, neither expires
   invisibly.

### 7.14 R3 done criteria

- Every item in §7.13 observed on device, not inferred.
- Every §7.12 test green, and **no test whose meaning silently changed** (§7.9).
- All four outcomes R3 introduces appear in a real log: `NOTIFY_APPROVED`, `NOTIFY_REJECTED`,
  `DENIED_UNATTENDED`, `POLICY_CHANGED` (rule source).
- The skill docs and `approval-modal.md` state the SECURE-only fingerprint rule.
- The R2 file format loads unchanged; an R3 file with rules refuses to load on R2.

---

## 8. R4 — Log UX (architecture)

- Text search over summary/body (index in memory, 7 days of data is small).
- Filters: origin, label multi-select, outcome, duration range.
- Counters per pair: `gmail · write — 142 total, 3 denied, 1 timeout, 96% ungated`.
- "Why this decision": for each record, show `source` (GLOBAL / RULE / DEFAULT) and, when `RULE`,
  the exact pair — `gmail · delete → NOTIFY`.
- Export: share the selected day-file(s) as JSONL via `ACTION_SEND` (no server).
- Retention settings screen (days, MB) replacing the R1 constants.
- Optional: Room migration behind `AuditStore` **only if** search proves too slow. Not before.

---

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Audit record written twice on retry paths | One `finally`, one record object, a `written` guard |
| R2 | `OFF` selected and forgotten | Confirmation dialog + persistent red home banner + `POLICY_CHANGED` record (§6.1) |
| R2 | A rule is set while the global switch ignores rules, and appears broken | Greyed table + `ignored while global = …` on the modal row itself (§6.5) |
| R2 | Fat-fingering the rule control mid-approval | Applies from the *next* request; never decides the request on screen (§6.5) |
| R2 | Rules silently widen because a pair was already set | Only 12 pairs exist and every one is an explicit row; `rules.size` is on the home screen |
| R3 | Silent auto-approve when phone is pocketed | Screen/lock check denies unless mode is `OFF` (§7.2) |
| R3 | Notification permission silently off | Deny; never approve invisibly |
| R4 | JSONL scan gets slow | 7d / 20MB cap; `AuditStore` interface for a Room swap |
| All | Log becomes a de-facto secret store (email bodies) | Deferred by request; `redacted` flag + export control land with it |
| R1 | Full disk takes the whole bridge offline (Q1 consequence) | Accepted by design; red banner + `audit_unavailable` makes cause obvious in one look |

---

## 10. Open decisions

Closed on 2026-10-17, with where each one landed.

| # | Question | Decision |
|---|---|---|
| Q1 | Audit append fails | **Deny** — `500 audit_unavailable` (§5.7) |
| Q2 | Unattended (screen off / locked) | `SECURE` and `NOTIFY` **deny**; `OFF` approves (§7.2) |
| Q3 | Biometric scope | **`SECURE` only** — supersedes v0.3.1 (§7.3) |
| Q5 | Storage | JSONL day-files behind `AuditStore`; no Room (§5.5, §5.13) |
| Q6 | `✕` affordance | Notification action `✕ Reject`; no overlay (§5.13) |
| Q7 | Auto-approve ceiling | **No hidden rules, no ceilings** (§7.6 — now moot, see Q11) |
| Q9 | Log scope | **Log everything**; `noise` is a derived, stored, overridable flag (§5.12) |
| Q10 | Retention | 7 days / 20 MB (§5.13) |
| Q11 | Equal-specificity conflict between `gmail/*` and `*/delete` | **Void.** Exact rules removed wildcards, so two rules can never match one request. Nothing to rank, nothing to refuse (§7.5, §7.6) |
| Q12 | Per-pair `OFF` | **Global only.** Picker stays `default \| notify \| secure`; auto-approve is always app-wide (§7.5) |
| — | `not_found` mapper (500 → 404) | Fixed in R1, own commit `61c23c5` (§5.14) |

Still open, both non-blocking for R1 (decide before R2/R3 ships):

**Q8 — `NOTIFY` window.** **STILL OPEN — blocks R3.** Flat 5s, or 5s plus a short grace (8s) so a
mistaken `✕` is recoverable? *Recommendation: 5s flat. A countdown you can learn beats a window
that varies.* Must be settled before `NotifyGate` is written (§7.2), because the value is baked
into the timer, the audit duration and the acceptance test.

**Q12 — per-pair `OFF`.** ✅ **DECIDED 2026-10-17: global only.** The picker stays
`default | notify | secure`. `OFF` is reachable only through the switch, so auto-approve is always
an app-wide, deliberate act. Consequence accepted: "log only for one pair" is not expressible —
the gate is all-or-nothing. Re-litigating this later means adding a 4th picker option, and doing so
reopens the question of whether an accidentally-set `OFF` row can silently bypass the gate; it
would need the same confirmation + banner treatment the global switch has.

**Q4 — origin as a rule dimension.** Origin is recorded on every record but is not part of a rule
key. Adding it means a 3-tuple key and a screen to set it. *Recommend: not yet — there is no
identity behind the IP, so the rule would encode a guess.*

## 11. Concerns worth stating out loud

**C1 — R2 is a bigger security change than the log.** R1 is safe; R2 can turn the gate off. It
should not ship until R1 has produced real records proving the verbs are classified correctly —
`OFF` on a mis-classified read is one thing, on a mis-classified `delete` it is not.

**C2 — Labels must be authored by the plugin, never inferred from the HTTP method.** `POST
/notifications/{id}/action` is `execute`, not `write`; `POST /gmail/messages/{id}/trash` is
`delete`, not `write`. Any inference layer reintroduces exactly the GET/DELETE-vs-POST bug class
the gate-side table already cost.

**C3 — The `describe` sentence and the label must not diverge silently.** R1 adds a test: for
every route, the label is a member of the known set. A future route whose verb is genuinely
ambiguous should fail the build, not get a new enum value quietly.

**C4 — Client disconnect is invisible today.** If the caller's curl times out while the modal is
open, the modal still waits its full 60s and the log says `TIMEOUT`. That is a lie in the data.
NanoHTTPD does not surface a mid-handler socket close, so detecting it needs a heartbeat or a
socket probe — **not MVP**, but it belongs in R4, and until then `TIMEOUT` means "no decision",
not "no client".

**C5 — ✅ FIXED.** `httpStatusFor` had no `not_found` entry, so a bad-id pre-fetch answered 500.
Fixed and shipped in 0.3.3 (commit `61c23c5`): 500 → 404, body identical. The `phonebridge-api`
skill still carries the "reported as 500" caveat and must be corrected when R1 lands.

**C7 — A per-pair rule can be set while the global switch silently ignores it.** The mitigation is
the greyed table plus a literal `ignored while global = SECURE` on the modal row (§6.5), but it is
still the most likely way to lose ten minutes wondering why nothing changed. Watch for it in real
use; if it bites, the fix is to disable the control rather than annotate it.

**C6 — Logging full bodies is a privacy decision made today that is hard to walk back.** Day
files are deleted after 7 days, but they will contain email bodies and calendar descriptions.
The `redacted` flag exists so a later redaction pass is honest; the export path (R4) must ask
before sharing.

---

## 12. Invariants (apply to every release)

1. Every request produces exactly one audit record, written before the response is returned.
2. A route without a `verb` does not compile.
3. No silent fallback: unreadable policy → server refuses to start; unreadable log line → counted
   and shown, never hidden.
4. Any ungated path is still recorded, with `mode` and `source` (`GLOBAL` / `RULE` / `DEFAULT`).
5. `SECURE` is the default in every ambiguous case: unset pair, unreadable policy, fresh install,
   and the global switch's resting value.
6. No side effect is ever reported as succeeded without an audit record. Append failure → 500,
   never a silent success (Q1, locked).
7. Gate decisions remain central. A plugin still cannot gate itself, and a plugin cannot opt out
   of the label either.
8. Every new error code lands in `httpStatusFor` in the same change.

---

## 13. Decision ledger

Every decision below is closed. Anything not listed is still open and named in §10.

| # | Decision | Date | Where |
|---|---|---|---|
| Q1 | Audit append fails → deny with `500 audit_unavailable` | 2026-10-17 | §5.7 |
| Q2 | Unattended (screen off/locked): `SECURE`/`NOTIFY` deny; `OFF` approves. No escalation. | 2026-10-17 | §7.2 |
| Q3 | Biometric is `SECURE`-only; supersedes v0.3.1 "every request" | 2026-10-17 | §7.3 |
| Q7 | No hidden rules, no ceilings | 2026-10-17 | §7.6 |
| Q9 | Log everything; `noise` is a derived, stored, overridable flag | 2026-10-17 | §5.12 |
| — | `not_found` mapper fix (500 → 404), own commit in R1 | 2026-10-17 | §5.14 |
| Q5/Q6/Q10 | JSONL day-files · `✕ Reject` action · 7d / 20 MB | 2026-10-17 | §5.13 |

**Q8** and **Q4** are the only ones still open; both are non-blocking for R1. See §10.
