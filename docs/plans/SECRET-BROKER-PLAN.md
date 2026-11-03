# SECRET-BROKER-PLAN.md

**Status: DECIDED — close-out (2026-10-18).** Every open question has an answer. Nothing
built yet; this is the spec to build against.

## Goal

Secure access to third-party APIs from PhoneBridge (starting: **Todoist**), so a secret
lives on the phone (Keystore, biometric-gated) and never on the host — **without** writing a
Kotlin plugin per service. Mido will keep adding services as he needs them, so per-service
plugins are not the primary path.

## Decisions

- **D1 — Generic mechanism, not N plugins.** One broker; adding a service is not a release.
- **D2 — Loose coupling.** We do NOT model upstream structures or track their shapes. We
  still fail fast; we just don't pretend to understand the payload.
- **D3 — The security boundary is the DOMAIN.** A request's host must be one already allowed.
  A new host triggers a **secure whitelist prompt**, and the decision is **saved**.
- **D4 — Caller supplies:** the URL, the HTTP method, a *stripped-down safe* curl-params set
  (headers / body / query), and a **placeholder** for the secret.
- **D5 — Bridge does:** inject the secret, make the call, return our standard envelope:
  upstream **HTTP status**, **non-secret headers**, and the **body**. Any header value that
  carries the secret is **redacted entirely**, and the secret is scrubbed from the body too
  (R4). Loose coupling: we do not model or track upstream structures — we return what came
  back, as-is, minus the secret.
- **D6 — Card shows URL + method.** Caller is a developer; naming the *target* is not our job
  for MVP (that was the expensive part of the manifest idea — dropped).
- **D7 — Plugin per secret; the secret occupies the `plugin` slot.** Each registered secret is
  its own `Plugin`, `id` = the secret name, so policy reads `todoist × EXECUTE` as a normal
  rule — no change to `Rule`, `parse`, `resolve` or the rules screen.
- **D8 — One route per secret, FIXED verb `EXECUTE`; the consumer never mentions a verb.** The
  broker route is `POST /secrets/{secret}/call`; the outbound method rides in the body as data.
  No method→verb map, no verb vocabulary exposed to the caller, and policy granularity is
  **per secret**. `EXECUTE` is the honest classification — the route runs a caller-specified
  call, which is exactly what `EXECUTE` means here. Per-method granularity is a later ADDITIVE
  change (register more routes), not a redesign.
- **D9 — Vault is per-secret Keystore keys, unlocked as a SESSION.** See "Unlock model".

## Injection — resolved by the placeholder (no taxonomy needed)

The caller writes the placeholder **where the secret goes**:

```json
{ "url": "https://api.todoist.com/api/v1/tasks",
  "method": "GET",
  "headers": { "Authorization": "Bearer ***" } }
```

That single rule covers static header, templated header (prefix is just literal text), query
param, and body field. **The five-style classification I proposed is unnecessary — this is
better.** Out of scope: signature auth (HMAC / AWS SigV4) — there the secret is *derived*, not
placed, so a placeholder cannot express it.

## Unlock model (D9) — decided in chat, 2026-10-18

The vault holds the secrets; the app is protected by the same gesture.

- **App lock on load IS the vault unlock — ONE scan.** The load biometric carries a
  `CryptoObject` bound to the vault key, so the same finger authenticates the app AND decrypts
  the vault. **Fingerprint only** (`BIOMETRIC_STRONG`): a crypto-bound prompt cannot accept
  `DEVICE_CREDENTIAL`, so there is no PIN/pattern escape hatch. This is a deliberate trade, not
  an oversight.
- **No separate "Unlock vault" button.** The load prompt is the only unlock; there is nothing to
  re-unlock mid-session because the vault lives until the process dies.
- **Key shape:** auth-per-use Keystore key (`setUserAuthenticationParameters(0,
  AUTH_BIOMETRIC_STRONG)`), per-secret alias, `setIsStrongBoxBacked(true)` wrapped with a TEE
  fallback (`StrongBoxUnavailableException`), `setUnlockedDeviceRequired(true)`, keep
  `setInvalidatedByBiometricEnrollment(true)` (default).
- **Plaintext lives in RAM for the session** (accepted): decrypt once on unlock, hold in memory,
  drop on process death. The earlier "never cache plaintext" recommendation is **superseded** —
  session unlock is the point. Named cost: root or a compromised process can read app memory.
  One scan unlocks all secrets (read as one vault, not per-token).
- **Service runs on its own (`START_STICKY` stays).** The vault starts **locked on every new
  process**. So `running` does NOT imply `unlocked` only when Android auto-restarts the service
  (system kill / Doze) without the UI. A cold reboot is already "down until the app is opened"
  (no boot receiver).
- **Only the SECURE path can raise an unlock prompt.** A plugin rule can never ask for a modal —
  NOTIFY is silent by definition.
- **Locked vault + NOTIFY call → `secret_locked`** (no prompt, no card, logged). Distinct from
  `DENIED_UNATTENDED`: "app not unlocked" is a different fact from "nobody was there".
- **Locked vault + SECURE call → ONE modal.** The approval card carries the vault `CryptoObject`
  and reads "scan to unlock and approve"; one finger unlocks and approves. Not two modals.

Net: `secret_locked` is reachable only via NOTIFY (or a non-UI caller) on a process whose vault
was never opened. Every SECURE secret call either has a live vault or shows one modal that
unlocks it.

## Hard rules (correctness, not preferences)

- **R1 — Redirects.** Never follow a redirect with the secret attached, or re-validate the host
  on every hop. Otherwise the allowlist is trivially bypassable.
- **R2 — Host match.** Exact host, or a boundary-correct `*.domain` — never a naive
  `endsWith` (which `evil-api.todoist.com` satisfies). Scheme pinned to `https`.
- **R3 — Loopback + private ranges blocked unconditionally.** The bridge itself listens on
  `127.0.0.1:8787`; a "generic call" must not be able to reach it or the LAN.
- **R4 — Scrub the secret from the ENTIRE response** (headers *and* body) before it is returned
  **or logged**. Not "secret headers" — any occurrence. Some APIs echo the token on error, and
  the body goes into the audit log.

## Resolved edges

- **Non-JSON upstream response → REFUSE.** A non-JSON content-type returns coded
  `unsupported_content_type`, nothing returned. Base64-into-the-envelope is additive later if a
  binary fetch is ever actually needed.
- **Outbound call timeout → FIXED 60s.** After the human tap, the broker waits up to 60s for the
  upstream; a hang → coded `upstream_timeout`. (Generous over the 15s lean, chosen deliberately.)
  Note: a 60s hold pins a request thread; the gate's own decision window is a separate clock.
- **`EXECUTE` has no HTTP method → ACCEPT (for now).** One secret = one rule; policy cannot
  separate read from delete. If deletes must always ask, the whole secret goes SECURE and silence
  is lost. **Accepted risk, explicit:** a NOTIFY secret can fire a destructive call silently.
  Raise-only override (caller flags destructive, policy may only raise toward SECURE) stays
  additive if it ever bites.
- **The card must show the URL even for GET reads.** The app's convention (`Plugin.rowsFor`) is
  that GET renders **no rows** — "a read carries no target to approve". For this plugin the URL
  *is* the card, so the broker must implement `describeRows` **directly** (it is an interface
  method, not `rowsFor`) and return the URL+method row for every call including reads. Do not
  route the broker through the read/write helper, or reads approve silently with an empty card.
- **OAuth refresh flows** (Google calendar/gmail) stay as real plugins — different problem.

## New error codes (register each in `httpStatusFor` — see the two-step rule)

`secret_locked`, `unsupported_content_type`, `upstream_timeout`. Adding a coded error is a
TWO-step change: throw the code AND register its status. An unlisted code hits
`else -> INTERNAL_ERROR` and reports a correct payload under a wrong `500`.

## Q&A

- **Q6 — verb granularity for MVP: one rule per secret.** `todoist × EXECUTE`. Loosest, zero verb
  coupling, and faithful to the app's own rule that *the verb comes from the route* — here the
  route declares `EXECUTE`, and the outbound HTTP method is just data in the body. No
  method-inference anywhere. Per-method routes are rejected for MVP (they reintroduce
  method-inference and would need DELETE/PATCH body support in NanoHTTPD 2.3.1); the additive
  path is registering a second route with a different fixed verb.
- **Q5 — governance.** The secret is the policy unit, expressed as the plugin id (D7/D8).

## Superseded

- The per-method route idea (`/secrets/todoist/read`, `/secrets/todoist/delete`, verb from the
  path or the method) was dropped — it exposed our verb vocabulary to the consumer, which is the
  coupling Mido flagged. Replaced by D8 (one route, fixed `EXECUTE`).
- "Never cache plaintext; decrypt per call" — superseded by session unlock (D9), which holds
  plaintext in RAM by design.
- The separate explicit "Unlock vault" button — superseded by the load-prompt unlock.
