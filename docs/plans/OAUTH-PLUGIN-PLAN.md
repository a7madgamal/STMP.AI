# `oauth` plugin — plan

**Status:** MVP design, not started. Written 2026-11-19.

**Goal.** A caller can use an API that authenticates with OAuth 2.0 + a refresh token,
without the credential or the access token ever leaving the phone.

First consumer: Chrome Web Store v2 (upload a package, poll, publish). Second: AMO's
source-file upload, which is multipart and therefore needs the same file-carrying route.

---

## 1. Why a separate plugin rather than extending `jwt`

`jwt` signs a token with a symmetric key the phone holds. OAuth exchanges a long-lived
`refresh_token` at a provider's token endpoint for a short-lived `access_token`. Different
inputs, different failure modes, different standards.

What they share is the *shape*: hold a credential, produce a bearer string, make the call.
That shape is already `SecretBroker`, and this plugin reuses it rather than inventing a
second outbound path. So the split is:

| | `jwt` | `oauth` |
|---|---|---|
| Credential | HMAC secret (or PEM) | refresh token + client secret |
| Minting | local signature | HTTP call to the provider |
| Token lifetime | chosen by us | dictated by provider |
| Reuse | cache until `exp − 15s` | cache until `expires_in − 60s` |

## 2. Standards this must satisfy (verified against RFC 6749, not memory)

**Refresh grant (§6).** `POST` to the token endpoint,
`application/x-www-form-urlencoded`, with `grant_type=refresh_token` and `refresh_token`
required, `scope` optional. The RFC's own example authenticates the client with HTTP Basic
(`Authorization: Basic …`).

**Client authentication (§2.3.1).** Two shapes, both legal:
- `Authorization: Basic base64(urlencode(client_id):urlencode(client_secret))` — the server
  "MUST support" this for clients issued a password.
- `client_id` + `client_secret` in the form body — "MAY support".

That "MUST support" is why Basic is the default and body is the opt-in. Getting it backwards
produces `invalid_client`, which is indistinguishable from a wrong secret.

**Success (§5.1).** `access_token` (required), `token_type` (required, case-insensitive),
`expires_in` (recommended, seconds), `refresh_token` (optional — **and if present it replaces
the old one**), `scope` (optional).

**Errors (§5.2).** HTTP 400 with an `error` code. The ones that matter operationally:

| `error` | meaning | what the plugin does |
|---|---|---|
| `invalid_grant` | refresh token expired, revoked, or issued to another client | own error code; only a human re-authorising fixes it |
| `invalid_client` | client auth failed — wrong secret, or wrong auth style | own error code that NAMES the style used |
| `invalid_request` | malformed | 400, caller's fault |
| `unsupported_grant_type` | not a refresh endpoint | 400 |

**MVP decision: no rotation write-back.** §6 says a new `refresh_token` MUST replace the old
one. Writing it back means mutating the vault from inside a request, with a fingerprint
prompt in the middle of an API call. MVP refuses instead: if a response carries a
`refresh_token` that differs from the stored one, the call fails with
`refresh_token_rotated` and tells the human to update the entry. Safer than a silent write,
and it makes rotation visible.

## 3. Storage

**Vault entry** (`Kind.OAUTH`), value is a JSON object — not a flat string:

```json
{"refresh_token": "…", "client_secret": "…"}
```

Why JSON in one entry: `SecretVault` stores arbitrary text already, so this needs no schema
change, and it keeps both halves behind one fingerprint. The alternative — two entries —
means two unlock prompts or a half-configured credential.

**Sidecar config** (plaintext, keyed by name), everything non-secret:

- `client_id` — public by design
- `token_url` — https only
- `scope` — optional; omitted means "as originally granted" per §6
- `auth_style` — `basic` (default) | `body`
- `hosts` — the per-credential allowlist, same matcher as `jwt` (`HostAllowlist`)

`token_url` and the allowed hosts are separate: the token endpoint is where the secret goes,
the hosts list is where the resulting *token* may go. A credential whose token_url host is
not in `hosts` would mint a token it cannot use — the screen should warn, not refuse, because
a token may legitimately be minted for use elsewhere later.

## 4. Routes (MVP: three)

### `GET /oauth`
Names, `client_id`, `auth_style`, hosts. No values, no tokens. `minMode = PARANOID`, same
reasoning as `/jwt` and `/secrets`.

### `POST /oauth/{name}/call`
The `jwt` call shape, unchanged: `url`, `method`, `headers`, `query`, `body`, `***` where the
token goes. Verb from the outbound method (`OutboundVerb`). Delegates to `SecretBroker` with
the access token as the injected value.

Covers Chrome's `:fetchStatus` and `:publish`, AMO's `versions/` create and metadata patches.

### `POST /oauth/{name}/upload`
Streams a file from the SAF-granted folder as the outbound body.

- Query: `url` (required), `file` (required), `field` (optional), `contentType` (optional)
- **No `field`** → raw bytes, `Content-Length` from the file. This is exactly Chrome's
  `-T package.zip` upload.
- **With `field`** → `multipart/form-data` with one part named `field`. This is exactly AMO's
  source upload.
- Verb: `WRITE` always.
- `streamsRequest = false` (the request carries no body), `auditRequestBody = false`,
  `auditResponseBody = false` — the payload is a file, and neither direction may be logged.

One route, not two, because the only difference is a boundary and a header line.

## 5. Token cache

Keyed by credential name; invalidated when:

- `expires_in` elapses minus a 60s margin (default 3600 when the provider omits `expires_in`)
- the vault locks (`clear()`, same as `jwt`)
- the sidecar config is edited
- the secret is rotated
- a token response arrives with a *different* `access_token` than the cached one — a
  provider may invalidate early

**Never persisted.** A token on disk is a credential at rest; a token in memory is not. Losing
the cache on lock costs one extra HTTP call to the token endpoint.

## 6. Security review

**The credential never leaves the phone.** Both halves sit in the vault; the exchange runs in
the plugin. The caller supplies a URL and a body only.

**The token never leaves the phone either.** No route returns one. Same rule as `jwt`, and
for a stronger reason: an access token is directly usable upstream, so handing one out makes
the gate advisory.

**Host allowlist is enforced twice** — once for the call target (broker), and the token
endpoint is checked separately against `token_url` being https. A credential cannot be
pointed at a plaintext token endpoint.

**Token endpoint host is NOT the allowlist.** Deliberate: the allowlist describes where a
token may *go*, not where it may be *minted*. Conflating them would force adding
`oauth2.googleapis.com` to a list whose purpose is egress control, widening it for the wrong
reason.

**Scrubbing.** The broker already replaces the injected value everywhere in the response. The
access token is the injected value, so it is scrubbed the same way. The refresh token and
client secret must ALSO be scrubbed from any response body — a provider echoing a request in
an error message would otherwise leak them into the audit and to the caller. MVP: scan the
response for both strings and replace with `***`, in addition to the broker's pass.

**Redirects.** Inherited: the broker never follows one. A 3xx from the token endpoint is a
refused call, not a redirect to follow with credentials attached.

**Upload route specifics.**
- File access requires the **oauth plugin's own** SAF grant (§10). No grant → refuse with
  `no_folder_granted` naming the OAuth screen, *before* the card (a request that can never
  succeed must not cost a tap).
- Only files the granted folder lists may be read — name validated by `FilesStore.requireName`,
  which already refuses path separators.
- The card shows file name, size, destination host, method and the multipart field. A card
  that says "upload" without the filename is unjudgeable.
- `Content-Length` comes from the file's real size; a wrong length must fail the upload rather
  than truncate it.

**Audit.** The upload records outcome, status and the file name — never bytes, never headers
that carry the token (the broker's scrub covers the response; the request headers are
generated inside the plugin and never enter the audit).

**Failure that must not be silent:** a token response missing `access_token` or with a non-2xx
status. Both become coded errors naming the RFC error code, never a generic `upstream_failed`.

## 7. Compatibility with other OAuth providers

MVP covers exactly the refresh grant with a client secret. What that reaches without change:

- **Google** (Chrome Web Store, and everything else) — Basic auth at
  `https://oauth2.googleapis.com/token`
- **Slack** — body or Basic, `https://slack.com/api/oauth.v2.access`
- **Dropbox** — Basic, `https://api.dropboxapi.com/oauth2/token`
- **Spotify** — Basic, `https://accounts.spotify.com/api/token`
- **GitHub Apps** — Basic, `https://github.com/login/oauth/access_token`, JSON response
- **Salesforce** — body, `https://login.salesforce.com/services/oauth2/token`
- **Microsoft Entra** — body, tenant-scoped token URL

What MVP deliberately does NOT do, and why each is a separate decision later:

| Not in MVP | Why | Cost later |
|---|---|---|
| PKCE / authorization-code flow | needs a browser and a redirect URI | a different plugin or a hosted callback |
| Service accounts / JWT-bearer grant (Google SA) | signs a JWT with an RSA key — that's `jwt`'s territory | a grant-type field and a signer |
| Client-credentials grant (no refresh token) | no user context, different entry shape | one more `grant_type` value |
| Token rotation write-back | mutating the vault mid-request | a second device prompt |
| `token_type` other than Bearer (e.g. MAC, DPoP) | rare, and DPoP needs a key pair | header strategy per credential |
| Per-provider quirks (`access_token` in a query, form-encoded responses) | GitHub returns form-encoded by default; MVP sends `Accept: application/json` and lets it fail loudly | a response-format field |

That last one is the honest gap: GitHub Apps return `application/x-www-form-urlencoded` unless
asked otherwise. MVP sends `Accept: application/json`, which GitHub honours — but if a provider
refuses, the failure is `unsupported_content_type` from `ResponseBody`, which is a clear message
rather than a parse error.

## 8. Contract and UI

- `CONTRACT_VERSION` 8 (three new routes).
- `PINNED_ROUTES` gains `GET /oauth`, `POST /oauth/{name}/call`, `POST /oauth/{name}/upload`.
- `/help` gains an `oauthCredentials` list and an `oauth` how-to object, mirroring `jwt`.
- Its own screen (`OauthActivity`), own inventory section, `Kind.OAUTH` entries hidden from the
  Secrets screen — exactly the `jwt` wiring.
- Fields in the add dialog: name, client ID, token URL, refresh token, client secret, scope,
  auth style, hosts. **Required first**, per the dialog rule already in place.

## 9. Decided (previously open)

**Upload is in v1.** Mido, 2026-11-19: *"yes upload is important"*. Without it the plugin
cannot submit to either store, which is the whole reason it exists. So the file-carrying route
ships with the first version, not after it.

**Token-endpoint timeout: 15s, not the broker's 60s.** The broker's 60s is tuned for a
third-party API that may legitimately be slow. A token endpoint is a single small POST and a
request that already cost a human a tap should not then hang for a minute. A timeout here is
`oauth_token_timeout`, distinct from `upstream_timeout`, because "the provider took too long to
mint" and "the provider took too long to answer" are different facts.

**`client_id` stays plaintext, out of the vault.** It is genuinely public — it ships in every
browser and mobile client. Putting it in the vault would mean the entry cannot be inspected or
copied without a fingerprint, for a value that is safe to print. The screen shows it, `/oauth`
returns it.

## 10. CORRECTED: the plugin owns its own file grant

**Mido, 2026-11-19: *"Plugins should be self-contained"*.** The earlier decision to share the
`files` plugin's SAF tree was wrong, and this section replaces it.

What "self-contained" means concretely: turning `files` off must not affect `oauth`, and
configuring `oauth` must not require configuring another plugin first. Sharing a runtime grant
breaks both — I had even written "turning off `files` will look like it breaks uploads", which
was a symptom described as a caveat instead of a defect.

**Also: my justification was unverified.** I claimed a second grant "counts twice against the
per-app cap". The pixel-verified facts are only that `FilesStore.accept` releases the previous
tree with a comment saying Android caps the number — no figure was confirmed. A limit that exists
and a limit that matters at N=1 are different claims, and I used the second without checking.

So:

- `oauth` stores **its own** tree URI in its own prefs, releases its own previous grant, takes its
  own persistable permission.
- It may **reuse the code** that does that — a shared pure/helper class is fine, and duplicating
  SAF arithmetic is how two implementations drift. What it must not reuse is another plugin's
  *stored state*, *enabled flag*, or *settings screen*.
- No grant → refuse with `no_folder_granted` naming the OAuth screen, before any card.
- The Files plugin and this one can point at the same folder or different ones. Their choice;
  neither reads the other's record.

### Shared value, not shared state

Mido, 2026-11-19: *"use the file plugin folder if it's hardcoded and safe"*.

The folder name `Downloads/STMP` is a fact about the app, not about one plugin. It moves from
`FilesPlugin.FOLDER` (a companion constant today, used for messages) to a core object
(`StmpFolder`) that both plugins read. Each plugin still takes **its own** persistable grant over
that folder — two tokens, one directory, chosen by the human in each case.

Why a constant and not the URI: delete `files` from the build entirely and `oauth` still works,
because the constant lives in core, not in a plugin. Sharing the stored URI is exactly the defect
this section exists to remove.

The safety property is unchanged: the folder is hardcoded, so neither plugin can be pointed at
`Downloads` at large or at `DCIM`.

## 11. Decided earlier, still standing

- Upload is in v1.
- Token-endpoint timeout 15s, own error code `oauth_token_timeout`.
- `client_id` stays plaintext, out of the vault.

## 12. Flow, start to finish

### Chrome Web Store (the first consumer)

**Before STMP is touched at all** — this is browser work, and the order matters because each step
produces the input for the next:

1. **Google Cloud project** with the Chrome Web Store API enabled.
2. **2-step verification** on the Google account that owns the extension. Google refuses
   publish/update without it.
3. **OAuth client** (Web application) with `https://developers.google.com/oauthplayground` as an
   authorised redirect URI. → *produces client ID + client secret*
4. **Refresh token** from the Playground with scope
   `https://www.googleapis.com/auth/chromewebstore`, authorised as the account that owns the item.
   → *produces the refresh token*

**Then on the phone:**

5. **Create the credential** on the OAuth screen: name (`chromewebstore`), client ID, token URL
   (`https://oauth2.googleapis.com/token`), refresh token, client secret, allowed host
   (`chromewebstore.googleapis.com`).
6. **Grant the folder** that will hold the packages, on the Files screen — shared with `files`.
7. **Smoke test** with a read-only call: `fetchStatus` against the item. Cheapest possible proof
   that the credential mints and the host allowlist is right.

**Then per release:**

8. Build the package here.
9. `POST /files/upload?path=…` → lands in `Downloads/STMP`. **Gated.**
10. `POST /oauth/chromewebstore/upload?url=…:upload&file=…` → raw zip body. **Gated.**
11. If `uploadState` is `IN_PROGRESS`, poll `:fetchStatus` via `/oauth/…/call`. **Gated each time.**
12. `POST /oauth/chromewebstore/call` with `url=…:publish`. **Gated.**
13. `:fetchStatus` to read the review state. **Gated.**

### AMO (the second consumer, same plugin)

Steps 5–7 are identical except the token URL is the AMO provider's and the host is
`addons.mozilla.org`. Per release: build → `/files/upload` → `/oauth/{name}/upload` with
`field=upload` and `channel=listed` → poll `upload/<uuid>/` → `versions/` create → source upload
with `field=source`.

## 13. What the user provides, and in what order

**The first thing you provide is the OAuth client ID and client secret.** Everything else is
derived from that pair — the refresh token cannot exist without them, and the plugin entry cannot
be created without them.

| # | Value | Where it comes from | Secret? |
|---|---|---|---|
| 1 | client ID | Cloud Console → Credentials | No — public by design |
| 2 | client secret | same page, shown once | **Yes** |
| 3 | refresh token | OAuth Playground, after authorising | **Yes** — this is the account |
| 4 | token URL | provider docs | No |
| 5 | allowed host | the API you will call | No |
| 6 | item / publisher ID | store dashboard | No |

Everything above except the two secrets is typed once on the phone and shown in `/oauth`.
The two secrets go into the vault and are never returned.

## 14. Gaps found in this end-to-end pass

These were not in the earlier draft. All are MVP-relevant.

**A. The upload route does not go through `SecretBroker`, so it loses every guarantee for free.**
`call` delegates and inherits https-only, the host allowlist, no-redirect-following, timeouts,
bounded responses and the secret scrub. `upload` uses a different code path (streamed body, no
JSON), so it must re-establish all of them deliberately. That is the single biggest thing this
pass found: the plan said "the broker already does it" for `call` and then reused the route for
`upload` as though that carried over.

**B. Multipart needs more than a file.** AMO's upload create wants `upload` **and** `channel`;
the source upload wants `source`, `upload` and `license`. One `field` parameter cannot express
that. MVP: `field` names the file part, plus `fields` as a JSON-encoded query value holding text
parts. Raw mode (no `field`) keeps `fields` forbidden, because a raw body has no parts.

**C. `expires_in` may be absent, and 3600 is a guess.** Caching a token for an hour when the
provider never said so risks handing out a dead token. MVP: if `expires_in` is missing, cache for
60s only — the token is still reused across a burst of calls, and a wrong guess costs one extra
mint rather than a failed call.

**D. Sending `scope` on a refresh can fail.** RFC 6749 §6 allows it only as a subset of the
granted scope; a provider that disagrees answers `invalid_scope`. Omitting it is defined as
"same as originally granted". MVP therefore **does not send scope on refresh** — the field stays
in the entry for documentation and display only.

**E. CORRECTED — this gap does not exist.** An earlier draft claimed `/files/upload` writes
through MediaStore while `files` reads a SAF tree, and that the two might not see the same files.
**That was wrong.** `FilesPlugin.upload()` calls `DocumentsContract.createDocument` on the granted
tree URI, then `openOutputStream` on the result — SAF end to end. MediaStore belongs to the Drive
plugin's save-to-Downloads path (`PhoneSave.kt`), and the two got conflated. So `/files/upload`
writes into exactly the tree the human granted, and the upload route reads from that same tree.

The picker still grants a **tree**, not a single file, and that is not a design choice: the two
grant shapes are `ACTION_OPEN_DOCUMENT` (one document, read-only by default) and
`ACTION_OPEN_DOCUMENT_TREE` (a folder). Only the tree supports **create**, which is what an
upload needs. A single-file grant cannot receive an upload.

**F. No way to test a credential without a real call.** Accepted: the smoke test is a read-only
endpoint (`fetchStatus` / `accounts/profile`). A dedicated token-check route is not worth a route
for MVP.

## 15. Implementation risk

Nothing in the design is undecided. What remains is code: the minter, the cache, the upload
streaming (with A and B above), the screen, and the contract bump.
