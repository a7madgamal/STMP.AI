# Google Gateway — FULL PLAN (MVP: Gmail)

Status: PLAN ONLY. No code yet. Owner: Mido. Author: Crabby.
Supersedes the earlier draft in this file.

Goal: gate access to Google accounts (Gmail first; Drive/Docs/Calendar/… later) behind the
SAME security model as the notification bridge: per-request approval popup, and later a
mandatory fingerprint on every request. Single user, dev mode, no distribution concerns.

---

## 1. Locked decisions

| # | Decision | Value |
|---|----------|-------|
| 1 | Token location | **On the phone** (Design A). Token never leaves the device. |
| 2 | Distribution | None. Dev mode, single user. Ignore Google verification/assessment. |
| 3 | Approval | **Every** request opens the approval modal. No tiers. |
| 4 | Fingerprint | **Mandatory on every request**, applied in **Phase 2** (after Gmail works). |
| 5 | Refresh token | Accept the **~7-day re-login** (Testing-mode limitation). `auth_expired` + re-login button. |
| 6 | Extensibility | **Plugin architecture**: new Google service = new plugin module, no auth rework. |
| 7 | UI | Clean-up: buttons reflect state via colour (server up = green, permission granted = green, etc.). No more all-grey. |

---

## 2. Architecture

    [remote caller] --HTTP over Tailscale--> [phone: PhoneBridge app]
                                               |
                                               +-- PluginRegistry
                                               |     +-- GmailPlugin  (routes / scopes / UI)
                                               |     +-- DrivePlugin  (later)
                                               |     '-- CalendarPlugin (later)
                                               |
                                               +-- AuthManager
                                               |     +-- OAuth (Device Grant)
                                               |     +-- token store (Keystore-encrypted)
                                               |     '-- refresh + re-login
                                               |
                                               +-- ApprovalGate
                                               |     +-- approval modal (exists)
                                               |     '-- BiometricPrompt (Phase 2)
                                               |
                                               +-- HttpServer (NanoHTTPD, tailnet-bound)
                                               |
                                               '-- (plugins call Google APIs over HTTPS using AuthManager token)

### 2.1 Plugin contract

Each plugin implements one interface and is auto-registered. A plugin declares:
- `id` — e.g. "gmail"
- `displayName`
- `scopes: List<String>` — the OAuth scopes this plugin needs
- `statusCard(): UI model` — what the app's home screen shows for it
- `routes: List<Route>` — HTTP endpoints, each with: method, path, gated?, handler
- `enabled` — whether its scopes are currently granted

`Route` handler signature: `suspend (Request) -> Response`. Every handler receives the
request only AFTER the approval gate passed (the server enforces gating centrally; a plugin
cannot opt out — `gated` is not a plugin choice, it is default-on).

Adding a service later = add one plugin module + its scope list. Core is untouched.

### 2.2 Incremental authorization

AuthManager stores a single Google credential (refresh + access token) plus the set of
granted scopes. When a request needs a scope not yet granted:
- returns `scope_required` with the missing scopes, and the app shows a "Enable X" action.
Enabling a new plugin triggers an incremental consent that ADDS scopes without losing the
existing ones. Gmail keeps working when Drive is added.

---

## 3. OAuth design (Device Authorization Grant, RFC 8628)

Chosen because it needs NO redirect URI, no custom URI scheme (disabled on Android), and no
Play-signed app identity — perfect for a sideloaded personal app.

Flow:
1. App requests device+user code from Google's device endpoint with the client ID.
2. App shows: `Go to google.com/device` + the user code (and a copy button).
3. Mido opens the URL (any browser), enters the code, consents to the scopes.
4. App polls the token endpoint until it returns the tokens (or expires).
5. App stores the refresh token (Keystore-encrypted) + caches the access token.

Google Cloud side (one-time, done by Mido in the console):
- Create a project, enable the Gmail API.
- OAuth consent screen: **External**, add Mido's Google account as a **Test user**, keep the
  project in **Testing**.
- Create an OAuth client of type **TVs and Limited Input devices** -> get client ID.
- Client secret: for device clients a secret is issued; treat as non-secret (embedded), as
  Google documents for installed apps.

Refresh:
- Access token expires (~1h) -> refresh with the refresh token silently.
- Refresh token expires (~7 days, Testing mode) -> refresh fails with `invalid_grant` ->
  AuthManager surfaces a single **`auth_expired`** state -> app shows "Re-login with Google"
  -> Device Grant again. Callers get a loud `auth_expired` error, never a silent retry loop.

Token storage:
- Refresh token encrypted with an Android Keystore-backed AES key (hardware-backed).
- Phase 2 ties that key to the biometric (CryptoObject) so even the token can't be used
  without a fingerprint.

---

## 4. HTTP + approval model (same as notifications)

- Server bound to the **tailnet IP only** (fix the existing `0.0.0.0` bind).
- Every route passes through `ApprovalGate.await()` before any Google call.
- Approval modal shows: source IP/node, method+path, target account, the concrete Google call
  (e.g. "Gmail: list 20 messages", "Gmail: SEND to x@y — subject …"), and (for writes) the
  body preview.
- Deny -> `403 denied`; timeout -> `408 approval_timeout`; needs scope -> `409 scope_required`;
  token dead -> `401 auth_expired`. Loud, never a silent no-op.

### Endpoint envelope (uniform)
Success: `{ok:true, ...}`. Error: `{ok:false, error:"<code>", detail:"..."}`.

Error codes: `denied`, `approval_timeout`, `scope_required`, `auth_expired`,
`bad_request`, `not_found`, `google_error`, `rate_limited`.

---

## 5. Phase 1 — Gmail MVP endpoints

| Method | Path | Body | Approval | Notes |
|--------|------|------|----------|-------|
| GET | `/gmail/profile` | – | yes | logged-in address + granted scopes |
| GET | `/gmail/messages` | query `?q=&max=&pageToken=` | yes | list; metadata id/from/subject/date/snippet |
| GET | `/gmail/messages/{id}` | – | yes | full message (headers + text/plain body) |
| POST | `/gmail/messages/{id}/modify` | `{addLabels:[],removeLabels:[]}` | yes | mark read/unread = toggle `UNREAD` |
| POST | `/gmail/messages/{id}/trash` | – | yes | move to trash |
| POST | `/gmail/messages/send` | `{to:[],cc:[],bcc:[],subject,body,threadId?}` | yes | send as Mido |

Scopes for Phase 1 (choose least-privilege that still covers the endpoints):
- `gmail.readonly` — profile, list, get
- `gmail.modify` — modify, trash
- `gmail.send` — send
(All three at once = simplest for a single-user dev app. Incremental later if desired.)

MIME: a plugin-local helper (base64url decode) turns Gmail's payload into plain text. No
email-writing HTML rendering in v1 — text/plain only, fail loud on exotic structures.

---

## 6. Phase 2 — mandatory fingerprint

- Add `androidx.biometric` + `BiometricPrompt`.
- Create a Keystore AES key with `setUserAuthenticationRequired(true)` and
  `setInvalidatedByBiometricEnrollment(true)` (StrongBox where available).
- Gate order per request: **approval modal -> BiometricPrompt (CryptoObject) -> execute**.
- The CryptoObject is also the key that decrypts the stored refresh token -> a request
  cannot reach Google without a successful biometric.
- Applied to EVERY route, read or write (per decision #4). Not configurable.
- Fail-loud: biometric cancelled/failed/lockout -> `denied` (or a distinct `biometric_failed`).

---

## 7. UI clean-up

Home screen redesign, state-driven colours:
- Start/Stop server button: green when running, red/neutral when stopped.
- Permission rows (Notification access, Full-screen popup, Draw-over-apps, Google login):
  green check when granted, amber when missing/needing action.
- Google row: shows account email + "token valid" / "re-login needed" (amber/red) + scopes.
- Debug log stays (collapsed by default now), because it saved us repeatedly.
- Plugin cards: one per enabled plugin, each showing its state.

---

## 8. Repo / module layout (target)

    app/
      core/        auth, gate, server, plugin registry, ui kit
      plugins/
        gmail/     GmailPlugin (scopes, routes, mime, ui card)
        drive/     (later)
      ui/          activities (Main, Approval), state-driven widgets

(Interim: keep single-module if simpler to ship Phase 1 fast; the plugin INTERFACE is the
important part — physical module split can follow once a 2nd plugin exists.)

---

## 9. Test plan

Phase 1:
1. Device-Grant login succeeds; profile returns Mido's address.
2. `/gmail/messages` lists recent mail; approve/deny both behave correctly.
3. `/gmail/messages/{id}` returns a readable body.
4. `/gmail/messages/{id}/modify` marks read (verify in Gmail).
5. `/gmail/messages/send` sends to a test address; verify arrival.
6. Kill token (revoke in Google account) -> endpoint returns `auth_expired` -> re-login works.

Phase 2:
7. Every request prompts fingerprint; wrong/cancelled finger -> no execution.
8. Removing enrolled biometric invalidates the key -> re-login required (expected).

---

## 10. Risks / honest notes

- **7-day re-login** is real and unavoidable in Testing mode. Accepted.
- `gmail.readonly` + `gmail.modify` are restricted scopes; fine for a single test user, but
  Google may email "unverified app" warnings — expected, ignore.
- Device Grant uses a polling loop; must respect `interval` and handle `slow_down`.
- Sending mail is irreversible — the approval modal must show the full recipient + subject +
  body preview before approving. This is the highest-risk endpoint.
- `SYSTEM_ALERT_WINDOW` (already in the app) is powerful; combined with Gmail tokens it is a
  high-value target. That is exactly why Phase 2 makes the token unreadable without a
  biometric.
