# SECRET-BROKER-PLAN.md

**Status: DRAFT — plan mode, decisions accumulating (2026-10-18).** Not a spec yet; nothing built.

## Goal

Secure access to third-party APIs from PhoneBridge (starting: **Todoist**), so a secret
lives on the phone (Keystore, biometric-gated) and never on the host — **without** writing a
Kotlin plugin per service. Mido will keep adding services as he needs them, so per-service
plugins are not the primary path.

## Decisions so far

- **D1 — Generic mechanism, not N plugins.** One broker; adding a service is not a release.
- **D2 — Loose coupling.** We do NOT model upstream structures or track their shapes. We
  still fail fast; we just don't pretend to understand the payload.
- **D3 — The security boundary is the DOMAIN.** A request's host must be one already allowed.
  A new host triggers a **secure whitelist prompt**, and the decision is **saved**.
- **D4 — Caller supplies:** the URL, the HTTP method, a *stripped-down safe* curl-params set
  (headers / body / query), and a **placeholder** for the secret.
- **D5 — Bridge does:** inject the secret, make the call, return our standard envelope:
  upstream **HTTP status**, **non-secret headers**, and the **body** (a clear example of the
  data returned). Any header value that carries the secret is **redacted entirely**, and the
  secret is scrubbed from the body too (R4). Loose coupling: we do not model or track upstream
  structures — we return what came back, as-is, minus the secret.
- **D6 — Card shows URL + method.** Caller is a developer; naming the *target* is not our job
  for MVP (that was the expensive part of the manifest idea — dropped).

## Injection — resolved by the placeholder (no taxonomy needed)

The caller writes the placeholder **where the secret goes**:

```json
{ "url": "https://api.todoist.com/api/v1/tasks",
  "method": "GET",
  "headers": { "Authorization": "Bearer {{todoist}}" } }
```

That single rule covers static header, templated header (prefix is just literal text), query
param, and body field. **The five-style classification I proposed is unnecessary — this is
better.** Out of scope: signature auth (HMAC / AWS SigV4) — there the secret is *derived*, not
placed, so a placeholder cannot express it.

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

## Open questions

- **Q5 — How is a generic call governed by policy?** The app's identity is "policy governs what
  runs", keyed on `plugin × verb`. A generic call has neither. Options: (A) the **secret** is
  the policy unit; (B) secret × a caller-declared verb; (C) one global bucket for all generic
  calls. Lean: **A** — you govern the secret, not the call.

## Known edges (not yet decided)

- **Binary responses** (PDF, images, attachments) are not JSON; the envelope needs a rule.
- **OAuth refresh flows** (Google calendar/gmail) stay as real plugins — different problem.
- **Timeout** on the outbound call, after the human tap.
