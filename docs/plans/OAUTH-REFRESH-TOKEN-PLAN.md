# Getting a refresh token without being blocked — plan

Status: **proposed, not built.** Awaiting a go.

## 1. The complaint, stated precisely

Mido, 2026-11-20: *"i don't like being unblocked by a required field that blocks the json import
until I do many steps. we should accept the json data and have a follow up to help the user get the
refresh token as easily as possible."*

Two separate defects, and only one of them is the required field:

1. **The import refuses to SAVE without a refresh token.** You supplied a real file and the app
   discarded it because a field it knows you cannot fill from that file was empty. That is backwards
   — the file was good, the import worked, and the save threw it away.
2. **Getting the refresh token is "many steps" entirely outside the app.** Playground, gear icon,
   paste two values, tick a scope, authorise, sign in, exchange, copy. Nine interactions across two
   apps, and the user has to already know the Playground exists.

Fixing (1) alone makes the import succeed; fixing (2) is what makes it *easy*.

## 2. What is actually missing, and what is not

| Field | Source | Blocking? |
|---|---|---|
| name, client id, client secret, token url | the file | **no** — import fills them |
| scope | nowhere in the file | **no** — display-only, optional |
| allowed hosts | nowhere in the file | **no** — empty is a valid, refusing state |
| **refresh token** | an authorisation round trip | **yes, today** |

So exactly one value is missing, and it is the only thing that requires leaving the app.

## 3. Phase A — accept the file, and let the credential be incomplete

**A credential may be saved without a refresh token.** Stored state becomes an explicit, named
state — not a silent one:

- `OauthSecret.fromVault` stops being the only reader. A new `OauthSecretStatus` answers
  `READY` / `NEEDS_REFRESH_TOKEN`.
- An incomplete credential is a real thing the screen shows — a card saying **"needs a refresh
  token"** with the follow-up action on it — rather than an entry that silently fails at call time.
- `POST /oauth/{name}/call|upload` on an incomplete credential refuses **pre-gate** with a new
  `oauth_needs_refresh_token`, naming the screen. A call that cannot work must not cost a tap;
  this is the same rule `no_folder_granted` already follows.
- `GET /oauth` reports `status: "needs_refresh_token"` per credential, so a caller can see why
  before trying.
- The vault entry is written at import time holding what was supplied, with the refresh token
  absent — not a placeholder, because a placeholder is a lie that fails later and further away.

This is the phase that directly answers the complaint: **the import saves.**

## 4. Phase B — make the follow-up the easiest thing available

The follow-up must be a guided path, not a paragraph of instructions. Three tiers, cheapest first.

### Tier 1 — in-app, generated per credential (no network work, big win)

From the JSON we already parse, the app can construct the exact authorisation URL:

```
https://accounts.google.com/o/oauth2/auth
  ?client_id=<from the file>
  &redirect_uri=<from the file>
  &response_type=code
  &scope=<chosen>
  &access_type=offline
  &prompt=consent
```

and open it with an `ACTION_VIEW` intent, so the user lands on Google's own consent screen with
everything pre-filled instead of typing into the Playground's boxes. `access_type=offline` and
`prompt=consent` are what actually produce a refresh token — omitting them is the single most
common reason people get no `refresh_token` back, and the Playground hides that setting.

This removes: opening the Playground, the gear icon, pasting the client id, pasting the secret,
finding the scope in a long list. What remains is: sign in, approve, and get a code back.

**It still needs the code pasted back**, because Tier 1 cannot listen for the redirect. That is
what Tier 2 buys.

### Tier 2 — the app receives the redirect itself (loopback)

Google supports `http://127.0.0.1:PORT` (and `localhost`) as a redirect host, and that is the one
shape an app can serve itself. The round trip then completes in one tap: open the consent screen,
sign in, and the app catches the code and exchanges it for a refresh token — no copy-paste, no
Playground.

**VERIFIED, and the detail decides viability.** Google's loopback-migration guide states: *"The
loopback IP address flow is being deprecated for iOS, Android, and Chrome OAuth application types,
but will continue to be supported on desktop apps."* So loopback was blocked on **2022-10-21** for
Android/Chrome/iOS *client types* — but a **Desktop app** client is explicitly still supported.

The rule is about the CLIENT TYPE, not the device the code runs on. This matters directly here:
STMP's importer already parses the `installed` wrapper, which **is** the Desktop app client type.
So the two halves meet without inventing anything:

- `"installed"` file → loopback flow is available → Tier 2 can work.
- `"web"` file → loopback is not an option for that client → Tier 3 (Playground) is the only path.

**The costs are real, stated plainly:**

- **A second HTTP listener on loopback.** `HttpServer` currently binds one chosen address, so
  `127.0.0.1:8787` connects to nothing. Serving a redirect needs a loopback listener — and a
  loopback port is reachable by **every other app on the phone**, which is precisely the
  man-in-the-middle risk Google's own deprecation guide describes. It must be scoped hard:
  loopback only, one callback path, single-use `state` nonce checked before the code is used, a
  lifetime of one authorisation, and the socket closed the instant the code arrives.
- **A Desktop client, not an Android one.** The user must create a **Desktop app** client in the
  console. An Android client would be refused by Google for loopback regardless of what this app
  does, so the screen must say which type to make — and detect the type from the file rather than
  letting the redirect fail later with an opaque error.
- **PKCE is not optional here.** A loopback redirect with a public client is exactly the case
  RFC 7636 exists for, and it also removes the need for the `client_secret` in the exchange.

### Tier 3 — the Web client path (what exists today, improved)

For a Web client the redirect is `https://developers.google.com/oauthplayground`, and only the
Playground can catch it. So Tier 3 is the current flow with the friction removed:

- A **Copy** button for the client id and one for the secret.
- A **Open Playground** button that opens the page with the scope pre-selected.
- The redirect-URI check that already exists, but now *before* the user leaves.
- A single paste field with a **Parse** action that accepts the whole `refresh_token` line, so a
  pasted fragment cannot be stored half-right.

## 5. Recommendation

**Build A + Tier 1 + Tier 3 now. Tier 2 is supported by Google for Desktop clients and is the real
answer to "as easily as possible" — but it adds a listening socket to a security app, so it should
be its own yes.**

A + Tier 1 turns "blocked, go read docs" into "saved, now tap this to finish", and neither adds a
listener. Tier 2 removes the paste entirely, and the docs confirm it is available whenever the
credential is a Desktop (`installed`) client.

Because the importer already knows which type the file is, the screen can promise the right thing
from the start: `installed` → "finish in one tap" (Tier 2 if built, Tier 1 until then); `web` →
"finish in the Playground" (Tier 3).

## 6. What changes where

- `OauthSecret.kt` — parse with the refresh token optional; add the status type.
- `OauthActivity.kt` — save without it; per-card "needs a refresh token" state and the follow-up
  actions; the generated authorisation URL; copy/open buttons; the paste-and-parse field.
- `OauthPlugin.kt` — pre-gate `oauth_needs_refresh_token` on `call`/`upload`; `status` in `GET /oauth`.
- `ErrorCodes.kt` — `oauth_needs_refresh_token` (409, a configuration disagreement, not a fault).
- `/help` — says a credential can exist incomplete, and what that refuses.
- Contract version: **no bump** — no route is added or removed; only a new refusal code and a new
  field in an existing response.

## 7. Deliberately not in scope

- **No scope inference from the file** — it has none, and the granted scope is only knowable from
  the authorisation response.
- **No storing the authorisation code** beyond the single exchange.
- **No "refresh token" route over HTTP** — a token handed to a caller works outside the gate, which
  is the whole reason this plugin exists.
