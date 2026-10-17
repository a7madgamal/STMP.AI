# PhoneBridge — End-to-End Security Review

Scope: the whole phone-bridge flow as built today — HTTP server, approval gate,
notification plugin, Google auth, Gmail plugin. Threat model: single user (Mido),
personal device, Tailscale as the network boundary, no app-layer auth (v0).

Status legend: **COVERED** · **GAP** (needs work) · **ACCEPTED** (known, deliberate)

---

## 1. What the system is

Remote caller --HTTP over Tailscale--> phone app
  - HTTP server (NanoHTTPD, bound to the tailnet IP only)
  - Approval gate: every request pops a full-screen approve/deny modal
  - Notifications plugin: read + fire actions
  - Gmail plugin: read / modify / send
  - Google auth: Play Services AuthorizationClient (Android client)
  - Tokens: Keystore-encrypted store

---

## 2. Findings

### S1. No app-layer authentication — ACCEPTED (v0)
Any device on the tailnet can call the API. Tailscale is the only wall.
Risk: **Medium.** A compromised tailnet device (or a leaked tailnet key) reaches
the server. Mitigation in place: the approval modal blocks every request, so an
attacker still cannot execute anything without a human tap on the phone.
Follow-up: add a bearer token + HMAC at v1 hardening.

### S2. Approval gate has no replay protection — GAP
The modal approves *a request*, not *a signed request*. A caller could re-send
the same payload and get a second approval prompt; if the human is on autopilot
they may approve twice.
Risk: **Low.** Still requires a human.
Fix: include a nonce + timestamp in the detail; reject reuse.

### S3. Only one approval at a time — ACCEPTED
Concurrent requests fail fast. This is deliberate (fail loud) but means a caller
can DoS the gate by holding a request open.
Risk: **Low.** Tailnet-only.
Fix (later): queue with a visible backlog, or rate-limit per source.

### S4. Approval detail can be truncated / spoofed — GAP
`ApprovalActivity` shows summary + detail JSON. For Gmail send, the summary is
built from the request body — but nothing prevents a crafted body from making
the summary read like a benign action.
Risk: **Medium** — the human is the last line of defence and could be misled.
Fix: show the *verbatim* target fields (recipient, subject, body preview) with
no reformatting, and colour-code destructive verbs.

### S5. Gmail access token in memory only — **GAP (being fixed now)**
Play Services flow returns an access token; no refresh token was stored. After
app restart or ~1h expiry the bridge is dead until manual re-login.
Risk: **Availability**, not confidentiality.
Fix: `requestOfflineAccess(serverClientId)` → server auth code → refresh token.

### S6. Refresh token storage — PARTIAL
`TokenStore` encrypts with an AES-GCM key in the Android Keystore (hardware-
backed where available). Good.
Gap: key is **not** yet biometric-gated (Phase 2). Anyone with the unlocked
device + app access can use the token.
Fix: Phase 2 — `setUserAuthenticationRequired(true)` on the key.

### S7. Backup / device transfer — COVERED
`data_extraction_rules.xml` + `backup_rules.xml` exclude the token prefs from
cloud backup and device transfer. Verified.

### S8. Server binds tailnet IP only — COVERED
`tailnetAddress()` picks the 100.64/10 address; never `0.0.0.0` unless no
tailnet address is found (in which case it falls back and logs it).
Gap: the fallback to `0.0.0.0` is silent-ish — it logs, but should **refuse to
start** instead, per fail-loud. -> **GAP (small)**

### S9. No TLS on the local server — ACCEPTED
Plain HTTP over Tailscale (WireGuard-encrypted). Acceptable; the transport is
already encrypted end to end by Tailscale.

### S10. Notification `extras{}` over-exposes data — ACCEPTED (by request)
The full-extras dump can include message bodies, senders, sometimes links.
Mido explicitly asked for "extract everything". Tailnet-only, own device.
Note: this widens the blast radius of a compromise; flagged for awareness.

### S11. `SYSTEM_ALERT_WINDOW` + Gmail tokens — ACCEPTED with mitigation
Draw-over-apps is powerful; combined with token access it is a high-value
target. Mitigation: Phase 2 biometric gate on the token key.
Risk: **Medium** until Phase 2.

### S12. Google client secret not embedded — COVERED
Android client type => no secret in the APK. Verified: no `client_secret` string
in the dex.

### S13. Debug log can leak secrets — GAP
`Debug.log` writes to logcat and the on-screen buffer. Token strings are not
logged today, but there is no redaction guard.
Fix: redact anything token-shaped before logging; never log request bodies for
auth endpoints.

### S14. Failure modes are loud — COVERED
Coded errors (`denied`, `auth_expired`, `unsupported_action`, ...) instead of
silent no-ops. No fallback flows. Matches the stated requirement.

---

## 3. Prioritised fix list

1. **S5** refresh token via `requestOfflineAccess` (in progress)
2. **S8** refuse to start if no tailnet address (no silent 0.0.0.0)
3. **S4** verbatim approval detail for destructive verbs (esp. Gmail send)
4. **S13** secret redaction in the debug logger
5. **S2** nonce/timestamp on approval requests
6. **S6/S11** Phase 2 biometric gate on the token key

---

## 4. Explicitly out of scope (v0)

- Multi-user / multi-account
- Google verification / production OAuth
- Remote (non-tailnet) exposure
- Server-side token custody (Design A keeps tokens on the phone)
