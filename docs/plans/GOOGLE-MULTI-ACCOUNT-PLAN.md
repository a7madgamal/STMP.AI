# Multiple Google accounts — one per request, named out loud

Today every Google plugin shares exactly one identity: one refresh token, one email, one
in-memory access token. "The account" means whoever signed in last. This plan makes the
account an explicit, per-request fact, so an action can never touch a mailbox the human did
not see named before approving it.

The governing rule, from Mido (2026-11-20): **one account works as it is; anything more must
be stated explicitly.** And where a rule could be either enforced or softened — **fail hard**.

---

## 1. The two lies this plan removes

Both are live in the shipped build and both are silent.

**1.1 The modal prints a global account.** `BridgeService.kt:1459` reads
`AuthManager.accountEmail` — the one global field — and hands it to the approval modal. It is
correct only while exactly one account exists. Add a second and the modal names whichever
account signed in last, regardless of which token the request will actually use.

**1.2 The audit log cannot answer "whose mailbox".** `AuditRecord` has no account field at
all. Nothing in the permanent record says which identity an action touched, so the one
question that matters after a wrong send is unanswerable.

There is a third, subtler problem: `AuthManager.grantedScopes` is a single global set, and
`PlayAuth` passes `null`, so it is empty (meaning "unknown") after every refresh. Drive
already works around this. Per-account scopes make that workaround unnecessary — see §7.

---

## 2. Identity: the full email address, and nothing else

`?account=eng.a7mad.gamal@gmail.com`

- Case-insensitive on lookup, echoed back exactly as stored.
- The **full address**, not the local part and not a label. One spelling per account, so
  `/help` can document exactly one form and an agent never has to try a second.
- **A label is never an identifier.** Labels exist only as a convenience in the picker UI. A
  label rename must never change a request, which means it must never be accepted in one.
- **The Google `sub` is the vault key, never the API value.** It survives a Workspace domain
  rename, so the token keeps working when the address changes (§9).

Rejected, with reasons:

| Candidate | Why not |
|---|---|
| Google `sub` (`118013990048721075775`) | Unreadable in an approval modal; every skill would carry an opaque number |
| Local part only (`eng.a7mad.gamal`) | Ambiguous the moment a Workspace account shares the local part; adds a collision branch for a handful of keystrokes |
| A label (`work`) | A private mapping: the agent would have to resolve it before writing a request, and a rename silently breaks saved skills |
| Path segment (`/gmail/accounts/{email}/messages`) | Restructures three plugins' routing for no gain; an email in a path is an encoding problem |

---

## 3. The rule, stated exactly

| Accounts | Behaviour |
|---|---|
| 0 | Every Google route refuses `no_accounts`. |
| 1 | No `account` parameter is accepted or required. **The interface works exactly as it does today.** |
| 2+ | Every request on every Google route **must** carry `account`. Omitted → `account_required` (400, pre-gate, no modal). Unknown → `account_not_found`, naming the value and the accounts that exist. |

There is no "current account", no default account, and no per-plugin binding. A plugin
following an implicit default is precisely the behaviour this plan exists to delete.

**Fail hard, three specific ways:**

1. **No fallback when the parameter is missing.** 2+ accounts and no `account` is a 400. It is
   never "use the first one".
2. **No cache fallback.** A cache miss for a key is a refresh for that key or an error. Never
   "return the token I have".
3. **No silent alias.** A local-part value, a label, or a `sub` in `account` is refused with
   the accepted form named. Accepting a second spelling is how a `/help` page becomes wrong.

---

## 4. Where the account resolves — before the gate

The account must be resolved **before** the approval view is built, not inside the handler.
A handler runs after the human has already decided, so an account resolved there can only be
reported after the fact — the exact shape of bug 1.1.

```
checkParams  →  resolveAccount  →  policy  →  gate (modal shows the account)  →  handler
```

- `resolveAccount` is a **plugin hook**, called by the dispatcher next to `checkParams`. The
  dispatcher stays generic: it does not learn what a Google account is.
- The resolved account is passed to the handler as an argument, so a handler **cannot** obtain
  a token without naming one. The compile error is the enforcement.

Rejected: resolving in the handler (lies in the modal), and hardcoding Google into the
dispatcher (the bridge would learn one vendor's concept).

### 4.1 One authority for "is it required"

Whether `account` is mandatory depends on how many accounts exist, which lives on disk — so it
cannot be a constant in `ParamSpec`. Rather than teach two places the same condition, there is
**one function**:

```
accountRequirement(ctx) → NONE | REQUIRED
```

- `resolveAccount` calls it to decide whether to throw `account_required`.
- `/help` calls **the same function** per plugin and prints `account (required — 2 accounts)`.

`ParamSpec("account", …)` stays declared with `required = false` and means only *this route
accepts an account*. Its doc comment gains one line: presence is decided by
`accountRequirement`, not by this flag. `HelpPlugin` already holds a `Context`
(`HelpPlugin(private val ctx: Context)`), so the render path can call it.

Rejected: duplicating the rule into `/help`. Two computations of one condition, one of them on
a render path nobody tests, is exactly how `/help` starts lying about the API — the failure the
`ParamSpec` machinery was built to prevent.

---

## 5. Storage

`TokenStore` becomes a map keyed by `sub`:

```
sub → { refreshToken (AES-GCM), email, label?, grantedScopes: Set<String>, addedAt }
```

- Same Keystore key, same AES-GCM construction, same file. No new crypto.
- `email` is the lookup key for the API, stored alongside. `sub` is the durable key.
- Removal deletes the entry; nothing is tombstoned.

**Migration must not cost a re-login.** The existing single `refresh_token` field is adopted as
the first account on read: the token is preserved, and the entry gains its `sub`/`email` on the
next userinfo call. Until that upgrade completes the account has no `sub`, so it is addressable
only when it is the sole account — which is the only state it can be in, since a second account
cannot be added without a working userinfo call. Any failure to upgrade surfaces loudly rather
than inventing an id.

---

## 6. The token cache

A `Map<sub, {token, expiresAt}>`, and the key is taken from **the same variable that builds the
request**. There is no code path that reads the cache without a key.

A test asserts the failure directly: an unknown key throws, and no account's token is ever
returned for another account's request. A single-slot cache would have made account A's token
answer account B's request, silently, with the modal naming B — the worst possible pairing.

Rejected: no cache at all (a token POST on every request, ~200ms each, needless quota), and a
single-slot cache with a "recently used" heuristic (a heuristic is a fallback with a nicer
name).

---

## 7. Scopes become per-account facts

`AuthManager.grantedScopes` is one global set, fed by `setAccessToken(...)`, and `PlayAuth`
passes `null` — so after any refresh it is empty and means "unknown", not "missing". Drive
currently carries a comment explaining that distinction.

With accounts stored as records, the granted set is captured from **both** the exchange and the
refresh response (`scope` is returned by Google on both) and stored per account. Then:

- Drive's warning becomes a real fact rather than a guess.
- The accounts screen can show what each account actually holds.
- A route needing a scope the account lacks is refused by name, before the API call.

**Union consent is kept.** Adding an account consents to `PluginRegistry.allScopes()`, exactly
as today. Minimal scopes per account is a v2 question, and the cost is stated where the choice
is made: the add screen says what the account will be granted and which plugins can then act as
it.

---

## 8. UI surfaces — every one names the account

| Surface | Today | After |
|---|---|---|
| Plugin row (`Gmail`) | "ready" / email only on the Google card | names the account it will act as, or "not bound" |
| Google plugin card | one email, sign in / log out | one row per account: email, scopes, re-authorise, remove |
| Approval modal | global `accountEmail` | **the resolved account for this request** |
| NOTIFY card | no account | **the resolved account**, since silence approves |
| Home banner | "signed in as X" | "2 Google accounts", tap for the list |
| Audit record | no account field | `account` field on every Google record |
| `/help` | no account | the account list, with the exact parameter form |

**The NOTIFY card is the one that matters most.** `NotifyGate.await(...)` has no account
parameter and `NotifyOverlay.show(...)` has no slot for one, so under a `NOTIFY` rule a Gmail
send is approved by five seconds of silence with nothing on screen naming the mailbox. The
account must be on that card. This is a real change to both files, not free plumbing.

**Home names no account.** With two accounts, "signed in as X" is false no matter which email it
picks. It states the count and offers the list.

---

## 9. Failure states — coded, each naming itself

| Code | When | What it says |
|---|---|---|
| `no_accounts` | 0 accounts | no Google account is connected |
| `account_required` | 2+ accounts, param absent | lists the accounts **and** shows a correct example URL |
| `account_not_found` | param names nothing | the value sent, and the accounts that exist |
| `account_address_changed` | `sub` exists, email does not | the account is now X, offer the one-tap repair |
| `auth_expired` | `invalid_grant` | that account must be re-authorised |
| `scope_missing` | known to lack the scope | which scope, which account, how to grant it |

`account_required` listing the accounts is deliberate: an error that says only "required" costs
a round trip and invites a guessing loop, which is how an agent ends up trying a label.

`account_address_changed` exists because a Workspace rename is otherwise indistinguishable from
a deleted account: the vault is keyed by `sub`, so the token is fine and only the lookup
address moved. Reporting `account_not_found` there would send someone to re-add an account they
still have.

---

## 10. Adding and removing accounts

**Add** uses the Play Services picker with `Prompt.SELECT_ACCOUNT` and offline access — verified
present in the pinned `play-services-auth:22.0.0`. Today the code always sends
`Prompt.CONSENT`, which forces the full scope screen even when nothing changed.

**Re-authorise a known account** uses `setAccount(Account(email, "com.google"))` plus
`Prompt.CONSENT`: no picker, so it cannot land on the wrong account. The `Account` is
reconstructed from the stored email, so **no `GET_ACCOUNTS` permission** is needed — the
manifest does not have it and does not need to.

**Remove** revokes at `https://oauth2.googleapis.com/revoke` first, then wipes locally. Today
`AuthManager.clear` wipes only locally, so a removed account's refresh token stays valid at
Google forever — acceptable with one account and one "log out", not acceptable with several.
A revoke that fails is reported, not swallowed: the token is still live at Google.

Removing the last account returns the app to the single-account behaviour, which requires
nothing stored.

---

## 11. Why there is no per-plugin binding

An earlier draft gave each plugin a bound account. It was rejected: it makes "Gmail is on
account A" a fact that persists and must be audited, when the simpler truth is that every
request already names its account. Fewer stored facts, one rule, and no screen to keep in sync.

The consequence is deliberate and stated plainly: **when the second account is added, existing
callers start failing** with `account_required` until they pass one. That is the correct
failure — the alternative is continuing to act as account A without anyone saying so.

---

## 12. Agent and skill impact

Every existing Google call sends no `account`, so it works at one account and fails loudly at
two. That is by design. Two things make it survivable:

1. **`/help` carries the account list**, so discovery is one call and the parameter form is
   documented from the running build rather than from memory.
2. **The error teaches the fix** (`account_required` lists the accounts and an example URL), so
   a skill can recover within the same request loop rather than needing an edit.

`stmp-api` gains one discovery line: read `/help`, and if more than one account exists, every
Google route needs `account`. `cold-outreach-email` inherits it by reference rather than
repeating it.

---

## 13. Build order

1. `TokenStore` map + migration from the single token (no behaviour change yet).
2. `AuthManager` per-account: keyed cache, per-account scopes, `accessToken(ctx, sub)`.
3. Plugin hook `resolveAccount`, called pre-gate; declare `account` as an optional `ParamSpec`
   on every Google route so `/help` documents it and unknown-parameter refusal already works.
4. `accountRequirement(ctx)` — the single authority — then presence enforcement and the coded
   errors. `/help` reads the same function; it does not re-derive the rule.
5. Surfaces: approval modal, **NOTIFY card**, audit field, `/help`, plugin rows, Home.
6. Google plugin: per-account rows, add / re-authorise / remove with revoke.

Step 3's declaration is deliberately *optional* in `ParamSpec` even though it becomes required:
`Route` carries no `Context` and should not grow one, and presence depends on stored state.
Optional-in-the-spec, required-in-`accountRequirement`, and `/help` asks the authority rather
than the flag — see §4.1.

---

## 14. What this plan does not do

- **No minimal-scope-per-account.** Consent stays a union. Stated as v2, with the cost named at
  add time.
- **No per-plugin binding.** See §11.
- **No change to `NOTIFY` as a mode.** If a rule says a Gmail send may be approved by silence,
  it still may — but the card now names the mailbox, so silence is an informed silence.
- **No browser-identity unification.** The host Chrome profile has two Google accounts signed in
  and browser-driven Google actions use Chrome's active account, which is not the token's
  account. Identity-bearing work goes through OAuth routes; browser automation only where
  nothing else can, and it must say so.
