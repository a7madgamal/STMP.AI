# OAuth credentials without a console — the corrected design

Status: **design settled; implementation not started.** Supersedes the loopback proposal in
`OAUTH-TIER2-LOOPBACK-PLAN.md`, which was wrong on one decisive point (see §2).

---

## 1. The rule that decides everything

**Nothing in this app may require the user to open a Cloud Console.**

That is the owner's rule, stated plainly: *"nothing in this app should be for me alone! except
config but features should be standalone plugins."* Configuring a client id is maintainer work,
done once per build and shipped in the APK. Everything a user touches must be a button.

The OAuth plugin as it stands breaks that rule. Its add-credential screen asks for a client id, a
client secret, a refresh token and a host allowlist. That is not a feature; it is a build
instruction with a form around it. It works for exactly one person — the one who already knows
what a token endpoint is.

---

## 2. What the loopback proposal got wrong

`OAUTH-TIER2-LOOPBACK-PLAN.md` proposed a listener on `http://127.0.0.1:PORT` to catch the
redirect. Two corrections, both verified against Google's own documentation:

**2.1 Loopback is blocked for Android client types, and has been since October 2022.** Google's
loopback migration guide states the flow "is being deprecated for iOS, Android, and Chrome OAuth
application types" and lists the compliance dates down to *"October 21, 2022 - all existing
clients are blocked (including exempted clients)."* The reason given is man-in-the-middle: another
app on the same device can open that loopback port and take the authorisation code.

**2.2 Using a Desktop-app client to reach that exemption is a hack, and was rejected.** Desktop is
the one type still allowed to redirect to loopback, so it is tempting — but an APK is not software
installed on a computer. Claiming that category re-opens, on Android, precisely the hole Google
closed for Android. Some tooling recommends it for **build machines** minting publisher tokens;
that guidance does not describe an app shipped to users.

**Conclusion: no loopback listener in this app.** The correct mechanism is the one already in the
codebase.

---

## 3. The mechanism: Google Identity Services

`PlayAuth` already does exactly what Google's migration guide tells Android apps to do:

- `Identity.getAuthorizationClient(activity)`
- `AuthorizationRequest.builder().setRequestedScopes(...)`
- `.requestOfflineAccess(webClientId)` for a one-time server auth code
- `Prompt.SELECT_ACCOUNT` for the account picker

There is no browser, no redirect, no loopback, and no client-type fiction. The consent UI is
rendered by Play Services, which is also why it cannot be spoofed by another app.

**The gap is scopes, not transport.** `PluginRegistry.allScopes()` covers Gmail, Calendar and
Drive. `https://www.googleapis.com/auth/chromewebstore` is not in that list, so the existing
consent never asks for it.

---

## 4. The decision: scopes on demand

Two ways to add a publishing scope, and the answer is the second one.

| Option | What the user sees | Verdict |
|---|---|---|
| **A — always ask** | Every account consent includes "manage your Chrome Web Store items" | Rejected. Almost nobody publishes; the prompt would be permanent noise on a feature they never use, and a publishing scope in a personal-data consent teaches people to approve publisher access reflexively |
| **B — ask on demand** | Data scopes at connect; `chromewebstore` requested the first time a publish is attempted | **Chosen.** The prompt appears exactly when it means something, and it is the same `authorize()` call with a different scope list |

This is why the OAuth plugin's credential stops being something a user *creates* and becomes
something the user *connects*: the scope is a property of the action, not of a form they fill in.

---

## 5. What the plugin becomes

**Kept, unchanged:** the whole engine. `POST /oauth/{name}/call`, `POST /oauth/{name}/upload`, the
per-credential host allowlist, `OauthMinter` (refresh-token grant, no redirects followed, 15s
timeout), `OauthTokenCache`, the vault split where the client id is plaintext and the secret lives
behind the fingerprint. None of that is affected.

**Replaced:** credential acquisition.

```
BEFORE                                  AFTER
────────────────────────────────────    ──────────────────────────────────────
+ ADD OAUTH CREDENTIAL                  + CONNECT ACCOUNT
  paste client JSON                       (nothing pasted)
  open Playground in a browser            Play Services consent, in-app
  copy refresh_token                      refresh token captured and stored
  paste refresh_token                     
  type a host allowlist                   host allowlist still typed — it is a
                                          policy decision, not a credential
```

The host allowlist stays manual on purpose. It is the one field that is genuinely the user's
judgement about where their token may be sent, and guessing it would be worse than asking.

---

## 6. What ships baked in

One client per provider, in `local.properties` → `BuildConfig`, exactly like the Google account
clients:

| Provider | Client | Type | Why |
|---|---|---|---|
| Google (data) | already shipped | Android + Web | account sign-in, via GIS |
| Google (store) | **new** | Android + Web | same GIS path, different scope list |
| Mozilla AMO | **new** | its own OAuth client | not Google; separate provider, separate consent |

**No client secret in the APK for the mobile path.** GIS returns a one-time server auth code,
redeemed by the Web client — the secret stays in `BuildConfig` for that redemption, which is the
existing, accepted trade-off, and is not a *new* exposure.

---

## 7. Failure states

- `oauth_not_connected` — the credential exists but has no refresh token; the card says CONNECT,
  never a paste field.
- `scope_missing` — a publish was attempted and `chromewebstore` was not granted; the response
  names the scope and the one tap that grants it.
- `invalid_grant` — the credential was revoked at Google. Report it, do not retry, do not
  silently fall back to another credential.
- `host_not_allowed` — unchanged, and still refused pre-gate so it costs no tap.

---

## 8. Build order

1. `PlayAuth.authorize` accepts an explicit scope list (today it reads `PluginRegistry.allScopes()`).
2. `GoogleLoginActivity` accepts extra scopes + the name of the OAuth credential they belong to.
3. On success, store the refresh token against that credential instead of adding an account.
4. `OauthActivity`: replace the two-step Playground dialog with a single CONNECT that launches
   that activity. Delete `RefreshTokenPaste` usage from the connect path — the field survives only
   for the Paste-an-existing-token case, which stays as an escape hatch.
5. Request `chromewebstore` on the first publish that fails with `scope_missing`, then retry once.

---

## 9. What this plan does not do

- **No loopback listener.** Blocked for Android client types; the Desktop-client workaround is a
  hack and is rejected in §2.2.
- **No service account for user data.** A service account is not a person; `chromewebstore` acts
  as the account that owns the extension. Domain-wide delegation needs Workspace and does not
  apply to consumer accounts. A key shipped in the APK would be worse than a refresh token.
- **No per-plugin Google clients.** One client, scopes name the capability — see
  `GOOGLE-MULTI-ACCOUNT-PLAN.md` §2 for the same reasoning applied to accounts.
