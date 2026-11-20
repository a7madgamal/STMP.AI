# JWT plugin

**Goal.** Let any caller reach an API that authenticates with a signed JWT, without the signing
secret ever leaving the phone and without the token ever reaching the caller.

The concrete first target is Mozilla AMO (`addons.mozilla.org/api/v5/...`), which is HS256 with a
short-lived token. The design must not be AMO-shaped, though: alg is a registry, not a branch.

## What AMO requires

Source: <https://addons-server.readthedocs.io/en/latest/topics/api/auth.html>

- Two values from the API credentials page: an **API key** (`user:543210:23`) and a **secret**.
- Sign `{iss, jti, iat, exp}` with **HS256**.
- `iss` = the API key exactly as shown; `iat`/`exp` in UTC seconds; `exp` no more than **300s**
  after `iat`; `jti` unique across recent requests.
- Send the result as `Authorization: JWT <token>`.
- "No JWT claims are made about the actual API request" — the token authenticates the *account*,
  so one token is reusable until it expires.

The static thing is the key pair. The token is minted.

## Shape

One plugin, id `jwt`, with its own screen (`JwtActivity`), one vault entry per credential.

| Piece | Where |
|---|---|
| Signing secret | `SecretVault`, name `jwt-<name>`, `kind = JWT` (never revealed) |
| Everything else (`iss`, alg, ttl, skew, header, kid, extra claims) | plaintext sidecar `jwt_config`, keyed by entry name |
| Allowed hosts | `SecretVault` meta for that entry — the same list the broker already enforces |

Splitting it this way means the vault index needs no migration, the secret is the only thing behind
the fingerprint, and `/secrets` keeps answering the metadata half while the vault is shut.

## Routes

| Route | Verb | Notes |
|---|---|---|
| `GET /jwt` | READ | Names, `iss`, alg, hosts. No secret, no token. `minMode = PARANOID` like `/secrets`. |
| `POST /jwt/{name}/call` | derived | Body is the broker's body; `***` becomes the token. `verbFor` maps the outbound method, so policy still keys on READ vs WRITE. |

There is deliberately **no route that returns a token**. A minted token is a live credential; handing
one back would make the vault decorative.

`POST /jwt/{name}/call` delegates the actual HTTP to `SecretBroker.call(ctx, token, hosts, body)` —
so the per-secret host allowlist, redirect refusal, timeout handling and response scrubbing are the
existing, tested ones rather than a second implementation.

## Seams for other algorithms

```kotlin
interface JwtSigner { val name: String; fun sign(keyMaterial: String, input: ByteArray): ByteArray }
```

`HS256` is the only implementation at launch. Adding `RS256`/`ES256` later is a new object plus one
entry in `JwtSigners`' map; the route, the screen and the store never mention an algorithm. The
`alg` field on the screen is a `Choice` built from `JwtSigners.names()`, so a new algorithm appears
in the UI by existing.

## Decisions and why

- **`iat = now - skew`** (default 30s). A phone clock a few seconds fast otherwise produces a token
  that is "not yet valid", which upstreams report as a bare 401.
- **ttl max 3600s, default 60s.** AMO's 300s ceiling is stated on the field rather than enforced
  globally, because it is an AMO rule and not a JWT rule.
- **`jti` from `SecureRandom`**, 16 bytes base64url. Mozilla's own example uses `Math.random()`;
  that is a nonce with a predictable sequence and there is no reason to copy it.
- **No per-request claim overrides.** A caller that could set `sub` or `iss` could mint itself as
  someone else. Claims come from the stored config only.
- **Reserved claims refused in the extra-claims field** (`iss`, `iat`, `exp`, `jti`, `nbf`), because
  a second source for any of them is a contradiction and not a merge.
- **Token cache** keyed by a fingerprint of secret + config, reused while `now < exp - 15`. It
  self-invalidates on rotation and on a config edit, and it is only consulted while the vault is
  open, which is the only time it can be.
- **Issuer validation catches the real typo.** A bare `[0-9:]+` value is the numeric account id, not
  the API key; it is refused at the field with a message that says so. Anything else is accepted, so
  non-Mozilla issuers (`https://...`, a client id) still work.
- **HS256 keys under 32 bytes are refused** at save time (RFC 7518 §3.2), with the actual byte count
  in the message. A short HMAC key is a security bug, not a preference.

## Edge cases

- Vault locked → `needsVault = true`, so the fingerprint prompt happens **before** the card.
- Wrong `iss` shape → refused in the dialog; a runtime 401 from upstream says only "unauthorized".
- Secret rotated or config edited → cache fingerprint changes, next call mints fresh.
- Backwards clock jump → cache entry looks expired, mints again. Never reuses a stale token.
- Token in a query string still works (some APIs want it), but the card marks the target WARN.
- Audit records the caller's body, which contains `***`, never the token. The broker scrubs the
  token out of the response too.
- Deleting an entry removes the vault secret and the sidecar record; the route then answers
  `unknown_secret` rather than minting from stale config.

## Contract

Two new routes → `CONTRACT_VERSION` 6 → 7, `PINNED_ROUTES` in `HelpContractTest`, and `/help` gains a
`jwtEntries` list (names, `iss`, alg, hosts — no values).
