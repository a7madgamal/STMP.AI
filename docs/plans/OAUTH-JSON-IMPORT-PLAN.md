# Importing a Google OAuth client JSON into the OAuth screen

Status: **proposed, not built.** Awaiting a go.

## 1. What the file actually is

Google Cloud Console → Credentials → OAuth client → **Download JSON** produces
`client_secret_<clientid>.apps.googleusercontent.com.json`. Verified shape, from Google's own
client-library docs:

```json
{ "web": {
    "client_id": "…apps.googleusercontent.com",
    "client_secret": "GOCSPX-…",
    "redirect_uris": ["https://developers.google.com/oauthplayground"],
    "auth_uri":  "https://accounts.google.com/o/oauth2/auth",
    "token_uri": "https://oauth2.googleapis.com/token"
} }
```

The outer key is **`web`** for a Web-application client and **`installed`** for a Desktop client.
Google's own docs state that both sub-objects have five **mandatory** members — `client_id`,
`client_secret`, `redirect_uris`, `auth_uri`, `token_uri` — plus optional `client_email`,
`auth_provider_x509_cert_url` and `client_x509_cert_url`.

That mandatory list is why a missing `token_uri` is a REFUSAL and not a fallback: a file without
one is malformed, and inventing an endpoint for it would paper over a broken download with a value
nobody chose. The optional certificate-url members are ignored entirely — they describe how to
verify ID tokens, which this plugin never does.

## 2. What can be filled, and what cannot

| Dialog field | Source | Note |
|---|---|---|
| CLIENT ID | `client_id` | public, as designed |
| CLIENT SECRET | `client_secret` | goes to the vault on Save, never before |
| TOKEN URL | `token_uri` | mandatory in the format; https enforced |
| NAME | derived, editable | last DNS label of the client id before `.apps`, else `oauth-client` |
| SCOPE | **nothing** | the file has none; a refresh grant must not send scope anyway |
| REFRESH TOKEN | **nothing** | this file is the client half only; it is minted by an authorisation round trip |
| ALLOWED HOSTS | **nothing** | the file names no API host, and guessing one would defeat the allowlist |

So an import fills 4 of 7 fields. The remaining three are then the human's, and the dialog says
which and why rather than leaving blanks that look like a bug.

## 3. Where the file comes from, and why nothing is stored

`ACTION_OPEN_DOCUMENT` — **not** `ACTION_OPEN_DOCUMENT_TREE`.

This is the mirror image of the upload grant, and the asymmetry is the point:
`ACTION_OPEN_DOCUMENT` grants exactly ONE document, read-only, and needs **no persistable
permission at all** — which is precisely "read it and pre-fill, store nothing". A tree grant would
be wrong here: it would persist a permission the app does not need for a file it reads once.

Explicitly, so it cannot be added later by accident:
- **No** `takePersistableUriPermission`.
- **No** copy into app storage, cache dir, prefs, or the vault.
- **No** content in `Debug.log`, the audit, or any error message.
- The stream is read once into a String, parsed, and dropped. The String is not retained past the
  dialog's Save/Cancel.

## 4. Flow — import FIRST, then the form

```
[ADD CREDENTIAL]  →  empty form (today, unchanged)
[IMPORT JSON]     →  picker → parse → the same form, pre-filled
```

**Why the import is a separate button, not one inside the dialog.** An activity result that
returns while a `PaperDialog` is open has to find its way back into that dialog's `EditText`s
across a possible activity restart — a reference chain that breaks quietly. Doing the pick first
means the import produces plain data, `addDialog()` takes it as a parameter, and the dialog is
built once with the values already in place. Fewer moving parts, and the failure mode is a toast
instead of a half-filled dialog.

## 5. Parsing — a pure function, so it is testable

```kotlin
object OauthClientJson {
    data class Found(val clientId: String, val clientSecret: String, val tokenUri: String, val name: String)
    fun parse(raw: String): Found   // throws PluginError("oauth_import_failed", "<field>: <why>")
}
```

Rules:
- Try `web`, then `installed`. Neither present → refuse, naming what was found at the top level.
- `client_id`, `client_secret` and `token_uri` are **required**; missing → refuse naming that field.
  All three are mandatory members of the format, so an absence is a malformed file, not a variant.
- `token_uri` must be https even when it comes from the file. A file is untrusted input.
- Size cap (64 KiB) before parsing: a client-secret JSON is under 1 KiB, and an unbounded read is
  how a hostile file becomes an OOM.
- Error messages name the FIELD and never echo a VALUE — a parse error that quotes the client
  secret would put it in a toast, and toasts are screenshots.

## 6. Security review

- **The file is untrusted input.** It is parsed defensively, size-capped, and its `token_uri` is
  re-checked for https rather than trusted because it came from a Google download.
- **Importing is not saving.** Nothing reaches the vault until the dialog's Save, so a mis-picked
  file costs nothing but a cancelled sheet.
- **The client secret still never renders.** It arrives in a password-masked field, exactly as
  typed input would, and follows the same path into `SecretVault.put(owner = "oauth")`.
- **No route, no network.** The import is screen-only; it cannot be driven by an HTTP caller, so
  there is no new attack surface on the bridge.
- **`FLAG_SECURE` already set** on this activity, so the picker's result cannot be screenshotted
  into the form.

## 7. Contract

No route changes, no contract bump: the import is UI-only. `CONTRACT_VERSION` stays 8.

## 8. Tests

Pure `OauthClientJson.parse`:
- `web` object → all three values, name derived from the client id.
- `installed` object → same.
- Missing `client_secret` → refusal naming `client_secret`, and the message contains no value from
  the input.
- Unknown top-level key → refusal listing what was found.
- `http://` token_uri → refusal.
- Non-JSON → refusal, not a crash.
- Oversized input → refusal before parse.

Plus one source check that the dialog does not `takePersistableUriPermission` for the JSON picker.

## 9. Deliberately not in scope

- **No refresh-token import.** A client-secret JSON does not contain one, and inventing a flow that
  claims to is worse than saying so.
- **No "import a token dump".** If a tool exports refresh tokens in some other shape, that is a
  different feature and a different risk.
- **No auto-fetch of scope** from the token endpoint. Scope is what was GRANTED, and only the
  authorisation response knows it.
