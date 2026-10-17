# PhoneBridge — Audit Log + Request Labels/Policy: Release Plan

Status: **plan only, nothing implemented.** Target: `com.mido.phonebridge`, current `0.3.2` (build 64).
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
   - `AUTO` — no UI, log only.

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
                                     │ Route.action + Route.scope + origin
                                     ▼
                      ┌─────────────────────────────┐
                      │  Label(origin, plugin, verb)│  ← R1 (metadata, no behaviour)
                      └──────────────┬──────────────┘
                                     ▼
                      ┌─────────────────────────────┐
                      │  PolicyEngine.evaluate()    │  ← R2 (SECURE | AUTO), R3 (+NOTIFY)
                      └──────┬───────────────┬──────┘
                     SECURE  │               │ AUTO / NOTIFY
                             ▼               ▼
                  ApprovalGate (queue)   DecisionRouter (no queue)
                             └───────┬───────┘
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

`SECURE` | `NOTIFY` | `AUTO`. Default when nothing matches: **`SECURE`**.

### Outcome (one per record)

`APPROVED`, `DENIED`, `DENIED_UNATTENDED` (R3; screen off/locked, §7.2), `TIMEOUT`,
`NOTIFY_APPROVED`, `NOTIFY_REJECTED`, `AUTO_APPROVED`, `REJECTED_INVALID` (4xx before the gate),
`UNROUTED` (404), `HANDLER_FAILED` (5xx), `CLIENT_GONE` (R2+; see §11 C4).

`DENIED` and `DENIED_UNATTENDED` are never merged: "he tapped no" and "nobody was there" are
different facts, and the second one is how a mis-scoped `NOTIFY` rule announces itself.

---

## 4. Release map

| Release | Version | Content | Behaviour change | Risk |
|---|---|---|---|---|
| **R1** | 0.4.0 | Verb/scope metadata on every route + audit spine + log screen + retention | **None.** Gating identical. | Low |
| **R2** | 0.5.0 | Policy store, rule engine, settings UI, effective-mode matrix, `AUTO` mode, panic switch | Gating can be *reduced* for chosen labels | Medium |
| **R3** | 0.6.0 | `NOTIFY` mode: heads-up, 5s silence=approve, `✕`=reject, fallbacks | New gating mode | Medium-High |
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
res/layout/activity_log.xml
res/layout/activity_log_detail.xml
res/layout/item_audit_row.xml
```

Modified:

```
core/Plugin.kt              Route gains role/verb+scope (required), see 5.2
plugins/*/*.kt              ~15 Route(…) call sites get the two new arguments
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
    val scope: String,        // NEW, required: "gmail.messages", "calendar.events",
                              //      "notifications.live" — the resource family
    val handler: suspend (Req) -> Resp
)
```

`scope` exists because the first cascade level people actually want is "all of Gmail" and the
second is "all deletes anywhere". With `plugin.id` + `verb` alone, "gmail write" works but
"gmail messages write" vs "gmail drafts write" (a future surface) does not. Cost today: one
string per route. Cost later if omitted: a taxonomy migration.

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
- Why now and not later: R2's router needs to bypass the gate entirely for `AUTO`, and R3 needs
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
  "scope": "gmail.messages",
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
    "ruleId": null,
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
        ctx.bindRoute(resolved)                       // label, plugin, verb, scope
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

- Filter chips: outcome, plugin, today/7d, **`hide noise`** (on by default). No text search in R1
  (R4).
- Noise is one tap away, never deleted: a record's `noise` flag is a lens, not a filter at write
  time. Flipping `hide noise` off shows every probe and typo in place, in time order.
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
4. `RouteMetadataTest` — every registered plugin's routes have non-blank `scope` and a `verb`
   (compile-time for presence, this test guards the *values*, e.g. no empty scope).

Note: test #4 needs plugins constructible without `Context` — they already are for
`routes()`; keep it that way (the reference doc's rule). If a plugin needs `ctx` for route
declaration, that is a defect to fix in R1, not a test to skip.

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
| everything else (`APPROVED`, `DENIED`, `TIMEOUT`, `DENIED_UNATTENDED`, `AUTO_APPROVED`, `NOTIFY_*`) | no |

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

## 6. R2 — Policy engine + `AUTO` (architecture)

### 6.1 Data

```kotlin
data class Rule(
    val id: String,          // stable uuid, never re-derived
    val enabled: Boolean,
    val plugin: String,      // "*" | "gmail" | "calendar" | "notifications"
    val verb: Verb?,         // null = any verb ("*")
    val mode: Mode
)
```

MVP rule dimensions: **plugin × verb × enabled**. No origin, no scope-string, no time windows.
`scope` is stored on the record (useful) but not yet a matcher (YAGNI; add when a real need
appears — that is the smallest step that cannot be wrong).

### 6.2 Resolution — lexicographic specificity, no tie-breaks

A rule matches a request when `plugin` is `"*"` or equal, and `verb` is `null` or equal. Among
matching rules, the winner is decided by a **specificity tuple compared lexicographically**:

```
specificity = (pluginIsSpecific ? 1 : 0, scopeIsSpecific ? 1 : 0, verbIsSpecific ? 1 : 0)
```

Highest tuple wins. Comparison is left-to-right, first differing element decides.

Why this and not a numeric score: **a numeric score can tie, and every tie needs a tie-break rule.**
I originally wrote "tie → the rule with the later `updatedAt` wins", which is wrong — it makes the
same rule set resolve differently depending on the order you happened to edit it. A policy engine
whose answer depends on edit history cannot be debugged from the log. Lexicographic comparison
over independent dimensions can never tie, so no tie-break exists to get wrong.

Why plugin outranks verb: `gmail/*` beats `*/write`. When you narrow to one plugin you have said
something specific about that plugin; "all writes" is the broader statement about everything
else. Stated once here so it is never re-litigated.

Adding a dimension later (origin, scope, time window) means appending one element to the tuple —
it slots in behind plugin and verb without renumbering anything. That is the whole extension
story, and the reason the tuple is not collapsed into an int.

Worked example — rules: `*/* → SECURE` (implicit default), `gmail/* → AUTO`, `*/delete → SECURE`,
`calendar/delete → NOTIFY`:

| Request | Candidates | Specificity | Winner |
|---|---|---|---|
| `gmail.read` | gmail/*, */* | (1,0,0) vs (0,0,0) | `gmail/*` → AUTO |
| `gmail.delete` | gmail/*, */delete, */* | (1,0,0) vs (0,0,1) | `gmail/*` → AUTO |
| `calendar.delete` | calendar/delete, */delete, */* | (1,0,1) vs (0,0,1) | `calendar/delete` → NOTIFY |
| `notifications.execute` | */* | (0,0,0) | default → SECURE |

Note row 2: `gmail/*` wins over `*/delete`. If you want one gmail verb pulled back to SECURE, you
must write `gmail/delete` explicitly — a cell being AUTO is never overridden by a broader rule.
That is the direct consequence of "no hidden rules": broadening a rule can never silently claw
back a narrower one, and narrowing always costs an explicit rule.

The engine returns `Decision(mode, ruleId, considered: List<RuleId>)` — `considered` is the ordered
list it walked, so the UI can show *"why: `gmail/*` (specificity 1,0,0) beat `*/delete` (0,0,1)"*
from the same data the server used. `ruleId == null` means nothing matched → `SECURE`.

Fail-hard: rules are parsed on load into `List<Rule>`; a malformed store throws and the server
**refuses to start** (`lastError` + red banner) rather than silently running on defaults. A
policy you cannot read is not a policy.

The matrix is computed by calling this same `evaluate()` once per `(plugin, verb)` cell. There is
no second implementation of the cascade — the screen that explains the policy *is* the policy.

### 6.3 Where it plugs in

`serve()` calls `PolicyEngine.evaluate(label)` immediately after `pluginRows()` (i.e. after all
fail-fast validation, before any gate). Then:

- `SECURE` → `ApprovalGate.await()` (unchanged).
- `AUTO` → no gate; `ctx.outcome = AUTO_APPROVED`; handler runs.
- `NOTIFY` → R3.

The gate's FIFO stays the single serializer for `SECURE` only. This is the important structural
decision: an auto-approved request must never wait behind a human thinking about a modal.

### 6.3 No hidden rules — **LOCKED (Q7, Mido 2026-10-17)**

The engine has **no special cases**. It does not know that `delete` is scarier than `read`; it
knows plugin, verb, enabled, and mode. Anything a human would recognise as "the tool protected me
here" must instead appear as a rule they can see, or it does not exist.

This is a deliberate trade and it pushes three obligations onto the UI:

1. **The matrix is not optional.** Every `plugin × verb` cell shows its resolved mode and the rule
   that won. A cell whose mode came from `*/*` must look different from one set explicitly, so
   "I thought I only whitelisted reads" is visible in one glance instead of a surprise at 3 a.m.
2. **`AUTO` cells must be visually loud.** Not a colour that reads as "fine" — this is the one
   place the app is allowed to be ugly.
3. **Rule changes are audited** (`POLICY_CHANGED`, before/after JSON) and the panic switch is one
   tap from the main screen.

What is still forbidden in the engine, even under "flexible": a mode with no matching rule
(`SECURE` is the default, so that cannot happen), and a rule that cannot be displayed (a stored
rule that does not parse refuses to start the server — §6.1).

### 6.4 UI

- Rules list (add/edit/delete/enable).
- **Effective-mode matrix** — a grid of `plugin × verb` showing the resolved mode and the winning
  rule for each cell, computed by the same engine the server uses. This is the anti-footgun: you
  cannot believe `gmail.write` is AUTO when it is actually `gmail/*` doing it.
- **Panic switch**: one toggle that forces SECURE for everything, sticky across restart, visible
  on the main screen. Toggling it writes an audit record (`POLICY_CHANGED`).
- Every rule change writes an audit record: before/after JSON. Policy history in the same log as
  traffic is the whole point of building R1 first.

### 6.4 New outcome + record field

`AUTO_APPROVED`, and `decision.mode/ruleId` already exist in the schema (R1 reserved them).
No schema version bump needed — that is why they were reserved.

---

## 7. R3 — `NOTIFY` mode (architecture)

### 7.1 Mechanics

- Separate channel `approval_notify`, `IMPORTANCE_HIGH`, silent-ish (no sound/vibration beyond
  heads-up — the point is "subtle").
- Notification content: `🔔 <summary>`, subtext `<label> · <duration>`, one action `✕` =
  `PendingIntent` broadcast → reject.
- 5s timer starts **when the notification is actually posted**, not at enqueue (same rule as the
  modal's clock).
- Silence → approve. `✕` → reject → the client gets `403 denied` with a distinct
  `error: "notify_rejected"`? **No** — keep `denied` so clients do not need a new branch; the
  distinction lives in the log (`NOTIFY_REJECTED`) and in the record. Caller-visible contract
  stays stable.
- `setTimeoutAfter(5000)` removes the notification when the window closes.

### 7.2 Gate availability — **LOCKED (Q2, Mido 2026-10-17)**

There is **no escalation**. A request that cannot be shown to an attending human is **denied**.

```
isAttended() = PowerManager.isInteractive() && !KeyguardManager.isKeyguardLocked()
```

| Mode | Attended | Not attended (screen off or locked) |
|---|---|---|
| `AUTO` | approve | **approve** — the rule whitelisted it, no human needed |
| `NOTIFY` | heads-up, 5s silence = approve | **deny** |
| `SECURE` | modal + biometric | **deny** |

The rule is one line, which is the point: *if a human cannot see it, nobody approves it — except a
rule that explicitly says nobody needs to.*

Implementation rules this decision forces:

- Checked at decision time, **after** all fail-fast validation, so a malformed request still gets
  its honest 400 and never a misleading `denied`.
- Also checked once more at **approve time** in `NOTIFY`: if the screen turns off during the 5s
  window, the request is denied, not approved. Silence is only consent while someone could have
  seen it.
- New audit outcome `DENIED_UNATTENDED` so the log never confuses "he said no" with "nobody was
  there". Caller sees `403 {"ok":false,"error":"denied","detail":"gate unavailable: screen off
  or device locked"}` — the HTTP contract stays stable (no new client branch), the reason lives in
  `detail` and the log.
- `NOTIFY`'s other escalations (channel disabled, notification permission blocked, DND) all
  collapse into the same answer: **deny**. There is no fallback path that approves invisibly.
- Consequence: `ApprovalGate.await()` is only ever called when `isAttended()` is true, so the
  full-screen intent path no longer fires onto a locked screen.
- Mid-modal lock: a request already on screen keeps its original 60s clock (§4 `createdAtMs`); if
  the human locks the phone it expires as `TIMEOUT`. Do not cancel early — the clock rule is
  already load-bearing, and a cancel-on-lock would make the modal's countdown lie.

### 7.3 Biometric — **LOCKED (Q3, Mido 2026-10-17)**

**`BiometricPrompt` exists only in `SECURE`. `NOTIFY` and `AUTO` never prompt.**

This supersedes the v0.3.1 rule ("APPROVE on every request, reads included"). That rule now reads:
*every `SECURE` request, reads included.* A 5s silence-approve that demands a fingerprint is a
contradiction, and `AUTO` has no human at all.

Implementation rules this decision forces:

- `ApprovalView.requiresBiometric` stops being a constant `true`. It is computed by the **policy
  engine**, not the Activity, and passed into the view the gate builds:
  `requiresBiometric = (mode == SECURE)`. The Activity still only renders the flag — it never
  decides. Same principle as the rows.
- `NOTIFY` has no activity at all, so the biometric code path is unreachable there by
  construction, not by an `if`. Do not add a guard that pretends it could happen.
- The main-screen status must state the effective biometric rule per mode, because the docs and
  the app now disagree with the old skill text. Update
  `android-phone-bridge/references/approval-modal.md` and the `phonebridge-api` skill in the same
  release that ships R2 — an API doc claiming "every request needs a fingerprint" is wrong from
  0.5.0 onward.
- Degraded case to keep honest: if no biometric is enrolled, `SECURE` still cannot approve
  (unchanged v0.3.1 behaviour). `NOTIFY`/`AUTO` are unaffected — they never needed one. So a
  phone with no fingerprint enrolled can still use `NOTIFY` and `AUTO`, which is a real
  behaviour change worth one line in the R2 acceptance list.

### 7.4 Interaction with the queue

Notify requests do not enter the `SECURE` FIFO. Two independent serializers:
`ApprovalQueue` (modal) and `NotifyQueue` (max 1 visible). A request classified `SECURE` while a
notify is on screen must still be queued for the modal — that is fine, they are different
paths. The only shared state is the audit log.

---

## 8. R4 — Log UX (architecture)

- Text search over summary/body (index in memory, 7 days of data is small).
- Filters: origin, label multi-select, outcome, duration range.
- Counters per label: `gmail.write 142 total, 3 denied, 1 timeout, 96% auto`.
- "Why this decision": for each record, show the exact rule + specificity that matched.
- Export: share the selected day-file(s) as JSONL via `ACTION_SEND` (no server).
- Retention settings screen (days, MB) replacing the R1 constants.
- Optional: Room migration behind `AuditStore` **only if** search proves too slow. Not before.

---

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Audit record written twice on retry paths | One `finally`, one record object, a `written` guard |
| R2 | Rule ambiguity confuses the human | Effective matrix + win explanation + panic switch |
| R2 | `AUTO` silently widens a label | Matrix is computed by the server engine; rule changes are audited |
| R3 | Silent auto-approve when phone is pocketed | Screen/lock check denies unless the rule is `AUTO` (§7.2) |
| R3 | Notification permission silently off | Escalate; never approve invisibly |
| R4 | JSONL scan gets slow | 7d / 20MB cap; `AuditStore` interface for a Room swap |
| All | Log becomes a de-facto secret store (email bodies) | Deferred by request; `redacted` flag + export control land with it |
| R1 | Full disk takes the whole bridge offline (Q1 consequence) | Accepted by design; red banner + `audit_unavailable` makes cause obvious in one look |

---

## 10. Open decisions (need Mido)

**Q1 — Audit write fails (disk full).** ✅ **DECIDED 2026-10-17: deny** (`500 audit_unavailable`).
A gate you cannot audit is a gate you cannot trust. See §5.7 for the forced implementation rules.

**Q2 — NOTIFY, screen off or device locked.** ✅ **DECIDED 2026-10-17: deny, no escalation.**
`AUTO` still approves, because a rule already whitelisted it. See §7.2 for the full matrix.

**Q3 — NOTIFY and biometric.** ✅ **DECIDED 2026-10-17: biometric is `SECURE`-only.**
`NOTIFY` and `AUTO` never prompt. See §7.3 — this supersedes v0.3.1's "every request".

**Q4 — Origin in rules.** R2 rules are plugin × verb only; origin is recorded but not a matcher.
OK, or do you want `origin` in rules now even though it is just an IP?

**Q5 — Storage:** ✅ default accepted 2026-10-17: JSONL day-files, `AuditStore` interface for a
later Room swap. No Room in R1.

**Q6 — `✕` affordance.** ✅ default accepted 2026-10-17: one notification action labelled
`✕ Reject`. An overlay is a separate release only if it still feels too loud in practice.

**Q7 — Auto-approve cap.** ✅ **DECIDED 2026-10-17: no ceilings. No hidden rules — the system is
flexible.** Any rule may set any mode for any plugin/verb. The only guarantees are structural:
`SECURE` is the default, and the matrix must show the winning rule for every cell (§6.3).
Explicitly rejected: a hardcoded "delete can never be AUTO" guard, per-verb safety tiers, and any
special case that lives in the engine but not in the rule list.

Consequence to be honest about: `AUTO` on `delete` is one tap away from destroying data with no
human in the loop. That is the accepted design — the defence is visibility (matrix + audited rule
changes + panic switch), not a prohibition.

**Q8 — Timeout semantics.** Today 60s means "no tap". In NOTIFY that becomes 5s to *reject*. Do
you want NOTIFY timeouts to be genuinely 5s, or 5s plus a short grace (e.g. 8s total) so a
mistaken X is recoverable? *Recommend: 5s exactly, no grace. Simple, predictable.*

**Q9 — Log scope.** ✅ **DECIDED 2026-10-17: log everything, noise is a filter.** A derived
`noise` flag (stored per record) hides `UNROUTED` + `REJECTED_INVALID` by default, one tap to
reveal, long-press to override. See §5.12. Never drop at write time.

**Q10 — Retention.** ✅ default accepted 2026-10-17: 7 days / 20 MB.

---

## 11. Concerns worth stating out loud

**C1 — `AUTO` mode is a bigger security change than the log.** R1 is safe; R2 is the release that
can hurt. It should not ship until R1 has a week of real records proving the labels are right.

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

**C5 — `httpStatusFor` has no `not_found` entry**, so a bad-id pre-fetch answers 500
(documented in the API skill). R1 will make this visible on every such log entry. Fix it in R1
as a separate one-line commit (body code unchanged, status becomes 404) — or leave it and tag
those records `status_mismatch: true`. *Recommend: fix it, it is one line and the log is about
to make it embarrassing.*

**C6 — Logging full bodies is a privacy decision made today that is hard to walk back.** Day
files are deleted after 7 days, but they will contain email bodies and calendar descriptions.
The `redacted` flag exists so a later redaction pass is honest; the export path (R4) must ask
before sharing.

---

## 12. Invariants (apply to every release)

1. Every request produces exactly one audit record, written before the response is returned.
2. A route without a `verb` and `scope` does not compile.
3. No silent fallback: unreadable policy → server refuses to start; unreadable log line → counted
   and shown, never hidden.
4. Any auto-approving path is still recorded with its rule id and mode.
5. `SECURE` remains the default and the panic-switch target. Nothing can outrank it.
6. No side effect is ever reported as succeeded without an audit record. Append failure → 500,
   never a silent success (Q1, locked).
6. Gate decisions remain central. A plugin still cannot gate itself, and a plugin cannot opt out
   of the label either.
7. Every new error code lands in `httpStatusFor` in the same change.

---

## 13. Decision ledger

Every decision below is closed. Anything not listed is still open and named in §10.

| # | Decision | Date | Where |
|---|---|---|---|
| Q1 | Audit append fails → deny with `500 audit_unavailable` | 2026-10-17 | §5.7 |
| Q2 | Unattended (screen off/locked): `SECURE`/`NOTIFY` deny, `AUTO` approves. No escalation. | 2026-10-17 | §7.2 |
| Q3 | Biometric is `SECURE`-only; supersedes v0.3.1 "every request" | 2026-10-17 | §7.3 |
| Q7 | No hidden rules, no ceilings; engine is plugin × verb × mode only | 2026-10-17 | §6.3 |
| Q9 | Log everything; `noise` is a derived, stored, overridable flag | 2026-10-17 | §5.12 |
| — | `not_found` mapper fix (500 → 404), own commit in R1 | 2026-10-17 | §5.14 |
| Q5/Q6/Q10 | JSONL day-files · `✕ Reject` action · 7d / 20 MB | 2026-10-17 | §5.13 |

Still open, both R3-scoped and non-blocking for R1: **Q8** (NOTIFY window: 5s exactly vs 5s + grace
— recommended 5s exactly) and **Q4** (origin as a rule dimension — currently recorded, not matched).
