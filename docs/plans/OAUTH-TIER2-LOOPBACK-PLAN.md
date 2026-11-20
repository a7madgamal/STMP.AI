# Finishing an OAuth credential without the Playground — Tier 2 plan

Status: **SUPERSEDED — do not build.** The loopback listener it proposes is blocked for Android
OAuth client types (Google's own migration guide, October 2022), and the Desktop-client workaround
is a classification hack. Corrected design: `OAUTH-NO-CONSOLE-PLAN.md`.

Supersedes §4 Tier 2 of `OAUTH-REFRESH-TOKEN-PLAN.md`, which guessed at the rules. Every claim
below was checked against Google's own documentation this session, and two of them corrected an
earlier assumption.

---

## 1. What this replaces, and why the Playground has to go

The Playground is not merely extra steps. It is **the wrong client**:

- It exchanges the code with **Google's** credentials unless you tick *"Use your own OAuth
  credentials"*, which produces `unauthorized_client` when the code was minted for **your** client.
  That is the 401 hit on 2026-11-20.
- Its own footer says: *"The OAuth Playground will automatically revoke refresh tokens after 24h."*
  So even a working exchange there produces a credential that dies tomorrow.

Anything that depends on it is a dead end, not a convenience.

## 2. Verified facts

| Claim | Source | Matters because |
|---|---|---|
| Localhost redirect URIs are **exempt** from the HTTPS requirement | Google web-server docs, redirect validation rules table | A `http://127.0.0.1:PORT` redirect is legal for a **Web** client — the client type already in use |
| Google's own web sample uses `http://localhost:8080` | same doc, prerequisites | This is a supported path, not a workaround |
| Loopback is **DEPRECATED for Android, Chrome and iOS client types**, still supported for Desktop | Google loopback-migration guide | The rule is about the CLIENT TYPE, not the device. **A Web client is fine.** Corrects an earlier assumption |
| `code_challenge` with S256, and `code_verifier` | Google native-app docs, authorisation params | PKCE is supported and recommended, so the exchange needs no client secret |
| Port matching | web-server docs: redirect URIs are registered and compared | A **Web** client needs the EXACT URI registered, so the port must be **fixed**, not random |

The last row is the one that shapes the design: the desktop guidance says "start a listener on a
random available port", and that works for a **Desktop** client because Google allows any port on
loopback there. It does **not** work for a Web client, where the URI must match a registered value.

## 3. The flow

```
[CONNECT]  →  activity launches the browser with the authorisation URL
           →  user picks the account and taps Allow
           →  Google redirects the browser to http://127.0.0.1:8099/oauth/callback?code=…&state=…
           →  the in-app listener validates state, takes code, replies with a plain HTML page
           →  listener closes
           →  POST code + code_verifier to the token endpoint (no client secret)
           →  refresh token goes into the vault; the credential becomes READY
```

Nothing is copied. Nothing is pasted. The Playground is not involved.

## 4. Security review — the part that decides whether this ships

**4.1 The trust boundary is the phone, not the network.** Any app on the device can connect to
`127.0.0.1:8099` while the listener is alive. Google's own deprecation guide names this as the
reason Android client types lost loopback. So the listener must assume an attacker is already
connected.

**4.2 What defends it, in order:**

1. **PKCE S256, mandatory.** `code_verifier` is 32 random bytes, base64url, generated per attempt
   and held in memory only. `code_challenge = BASE64URL(SHA256(verifier))`. A stolen code is
   worthless without the verifier, which never leaves the process. This is the primary defence and
   it is why the exchange can also drop the client secret entirely (RFC 7636 §4.5, §5).
2. **Single-use `state`.** 32 random bytes, compared with a constant-time equals **before** the
   `code` parameter is read — so a request without the right state does not even cause the code to
   be inspected, let alone used.
3. **Exact path.** Only `/oauth/callback` is served. Everything else gets `404` and does not touch
   the state.
4. **Loopback bind only.** `ServerSocket(8099, 0, InetAddress.getByName("127.0.0.1"))`. Binding
   `0.0.0.0` would put the callback on the tailnet, where another device could reach it.
5. **One code, then die.** The socket closes after the first successful callback, after a failure,
   on `onStop`, and on a **120 s** timeout. No lingering port.
6. **Nothing is logged.** The `code`, the `verifier` and the refresh token never enter `Debug.log`,
   the audit or a Toast. A `code` in logcat is a credential in logcat.
7. **The reply page is static.** It is written as a fixed string with no interpolation of request
   data — the callback path is trivially XSS-able otherwise, and an attacker who can inject into
   that page can exfiltrate the next attempt's state.

**4.3 What is deliberately NOT trusted:** the `state` value is not used as a cache key, the request
is not parsed beyond three exact query params, and the response does not echo anything from the
request.

**4.4 One residual risk, stated plainly.** A local attacker who can read another process's memory
or intercept loopback on a rooted device can defeat PKCE. That is unavoidable for any app doing
this on Android, and it is the same trust the vault already places in the OS.

## 5. Usability — what the user actually does

**Once, in the console:** add `http://127.0.0.1:8099` to the client's **Authorized redirect URIs**.
The screen must say this before the first attempt, with a copy button, because a mismatch produces
`redirect_uri_mismatch` — an error that reads as a bug in the app.

**Every time after:** tap CONNECT → pick account → Allow → the sheet closes and the card goes
green. No paste, no other app.

**Failure must be specific**, because this flow has four distinct ways to fail and they look alike:
`state_mismatch` (an attacker or a stale tab), `redirect_uri_mismatch` (the console step was
skipped), `access_denied` (the user pressed Cancel — not an error, say so), `oauth_token_timeout`.

## 6. Design decisions worth arguing about

**6.1 Fixed port, 8099, not random.** Required for a Web client (see §2). A busy port is a real
failure, so the screen should offer a **second** registered fallback (`8100`) rather than silently
retrying something that cannot work.

**6.2 The listener lives in `OauthActivity`, not `BridgeService`.** Same process and same lifecycle
as the thing being authorised, which is the sensible reading of "reuse our server": shared code,
not shared socket. The bridge binds an externally reachable address on purpose
(`BindAddress.isUsable` rejects `127.x`), and every bridge route is gated — a callback arriving from
a browser cannot answer an approval card, so putting it there would need a gate exemption on the
main server. That is the one exemption the tests exist to prevent.

**6.3 Keep the Playground path as a labelled fallback.** Some clients cannot add a loopback URI
(an organisation may restrict it). The fallback stays, but it must carry the two warnings it taught
us: tick *Use your own OAuth credentials* **before** Authorize, and its tokens die in 24 h.

**6.4 No client secret in this flow.** PKCE replaces it. Storing a secret that the flow does not
use would be keeping a credential for no reason — the vault entry keeps it only because the *call*
path and the fallback still need it.

## 7. Changes by file

- `plugins/oauth/OauthCallback.kt` — **new**, pure-ish: state and verifier generation, PKCE
  challenge, the exact-URI match, and the reply page as a constant string. Testable on the JVM.
- `OauthActivity.kt` — start the listener, launch the browser, close on every exit path; the
  console instruction with a copy button; the fallback warnings.
- `plugins/oauth/OauthMinter.kt` — a second entry point for the code exchange (form POST with
  `grant_type=authorization_code`, `code_verifier`, no secret). Its 15 s timeout and https-only
  rules already apply.
- `ErrorCodes.kt` — `oauth_state_mismatch`, `oauth_redirect_mismatch`, `oauth_access_denied`,
  `oauth_callback_timeout`, each mapped to its real status.
- `OauthSecret.kt` — unchanged; a successful exchange produces a normal refresh token.
- Contract version: **no bump** — no route changes. This is screen-only, which also means it adds
  no attack surface to the bridge.

## 8. Tests

Pure, on the JVM:
- `code_challenge` is exactly `BASE64URL(SHA256(verifier))` for a known pair — the RFC's own
  example, so the implementation is checked against the spec rather than against itself.
- `state` comparison is constant-time and rejects a prefix, an empty value and a `null`.
- The callback path matcher accepts `/oauth/callback` and rejects `/oauth/callback/`, `/callback`,
  a trailing `?` variant and everything else.
- The reply page contains no interpolated input.
- A busy port surfaces as its own named error rather than an exception.

Source-guard tests (the pattern this repo already uses):
- The listener binds `127.0.0.1` literally — never `0.0.0.0`, never the tailnet address.
- `ServerSocket` is closed in every failure path (a `use { }` or an explicit `finally`).
- No `Debug.log` call in the new file takes the `code`, `verifier` or token.

## 9. Out of scope

- **No refresh-token route over HTTP.** Unchanged rule: a token handed to a caller works outside the
  gate.
- **No multi-account picking in-app** — Google's own account chooser is better and already trusted.
- **No client-secret-less migration of the call path.** The mint still uses the stored secret; only
  the exchange uses PKCE.
