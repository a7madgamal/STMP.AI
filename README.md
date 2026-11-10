# STMP

**Your phone answers to an API. Nothing happens without a stamp.**

STMP is an Android app that runs a small HTTP server on your phone, so a remote caller on your
tailnet can read notifications, use Gmail and Calendar, drive Google Drive, run shell commands over
SSH, and reach secrets that never leave the device. Every request — reads included — passes a gate
on the phone that shows a human exactly what is being asked and waits for a decision.

It is called STMP because the decision is a rubber stamp. Literally: approve and a green mark slams
down over your screen with a thud and a crease of paper. Deny and it comes down red.

```
        ┌─────────────────────┐
        │   caller (you)      │
        │   curl / agent / CI │
        └──────────┬──────────┘
                   │ tailnet
        ┌──────────▼──────────┐
        │   STMP on the phone │
        │  ┌───────────────┐  │
        │  │  the gate     │◄─┼── a human taps
        │  └───────┬───────┘  │
        │  ┌───────▼───────┐  │
        │  │   plugins     │  │
        │  └───────────────┘  │
        └─────────────────────┘
```

## Why this exists

Giving a language model a shell is easy. Giving it a shell you can *watch* is the hard part.

Most "agent runs commands on my machine" setups have two states: full trust, or nothing. STMP is an
attempt at the missing middle — the caller gets real capability, and the human gets a card that
names the exact string about to run, on a device they are already holding.

The design rule throughout: **the gate shows what will happen, not what it hopes will happen.** A
command is displayed verbatim, never prettified. A secret's value never leaves the vault. A file's
bytes never enter the audit log.

## What it does

| Plugin | What it gives a caller |
|---|---|
| **notifications** | List active notifications, fire their actions, reply, dismiss |
| **gmail** | Read, search, send, trash |
| **calendar** | List and create events |
| **drive** | Upload, download, save straight to the phone's Downloads |
| **secrets** | A credential broker: the token lives in the phone's vault, the caller sends a placeholder |
| **ssh** | Run a command on a host, with a key generated *on the phone* |
| **todoist** | Tasks, through the same vault broker |
| **logs / debug** | The app's own logcat and state, so a broken bridge can explain itself |
| **audio** | Play one of three short attention cues on the phone |

## The gate

Every route declares a `Verb` (`READ`, `WRITE`, `EDIT`, `DELETE`, `EXECUTE`) and every request goes
through `ApprovalGate` before a handler runs. A plugin cannot opt out — with **one** deliberate
exception, `POST /audio/{cue}`, because a cue whose job is to make someone look at the phone cannot
require them to look first. That route can only make a short noise, and it still writes a normal
audit record. See `Route.gateExempt` for why the flag must not spread.

Three modes, set per plugin/verb pair:

- **`SECURE`** — full-screen modal, 60s window, needs a tap. Biometric when the vault exists.
- **`NOTIFY`** — a small card in the corner, 5s window. **Silence approves**, which is why the window
  is short and why the card can only be drawn when someone is looking.
- **`AUTOMATIC`** — per-request rule resolution: `SECURE`, `NOTIFY`, or one of the above per pair.

The card shows the request in plain words, generated on-device. A password typed at the modal goes
to sudo's stdin and nowhere else — not the audit log, not a temp file, not an argv.

## Build it

You need JDK 21, the Android SDK (compileSdk 35), and an arm64 phone.

```bash
git clone git@github.com:a7madgamal/STMP.AI.git
cd STMP.AI
printf 'sdk.dir=%s/android-sdk\n' "$HOME" > local.properties
./gradlew assembleDebug
```

That builds. The APK lands in `app/build/outputs/apk/debug/`. Sideload it; there is no Play release
and no intention of one — the whole design assumes you control the device.

Everything except Google sign-in works with no further setup.

## Google sign-in (optional)

Gmail, Calendar and Drive need OAuth, and **the credentials are not in this repo** — each developer
registers their own Google Cloud project. Not a formality: the Android client authenticates by
package name + signing SHA-1, so a build signed with your key can never match someone else's
registration. Everyone needs their own regardless.

1. Create a project in the [Google Cloud console](https://console.cloud.google.com/) and enable the
   Gmail, Calendar and Drive APIs.
2. **Credentials → Create credentials → OAuth client ID → Android.** Package name
   `com.mido.phonebridge`; for the SHA-1 run
   `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
   (or your project keystore if you have one).
3. **Create a second OAuth client ID → Web application.** You need this one's id *and* secret,
   because the app exchanges a server auth code for a refresh token.
4. On the OAuth consent screen, add your own Google account as a **test user**. The app requests
   scopes Google classifies as sensitive, so it will not pass verification for public use without a
   review — for personal use, test-user is enough.
5. Put all three values in `local.properties` (gitignored):

```properties
sdk.dir=/path/to/android-sdk

google.clientId=<the Android client id>.apps.googleusercontent.com
google.serverClientId=<the Web client id>.apps.googleusercontent.com
google.clientSecret=<the Web client secret>
```

Rebuild. Without these the app still compiles, starts, and serves every non-Google route — login
just reports `build not configured` instead of quietly authenticating against someone else's
project.

**Never commit `local.properties`.** It is gitignored, and that is the only thing keeping your
secret out of the history.

**Signing.** The project keystore is not in the repo (it is the app's identity; committing it would
let anyone publish an update as this app). `assembleDebug` falls back to your SDK's debug keystore so
a fresh clone builds immediately. An APK signed with that fallback will **not** install over one
signed with the project key — different key, different app to Android.

## Run it

1. Install, grant notification access, notification permission, and draw-over-apps.
2. Start the server from the home screen. It binds to your tailnet address on port 8787.
3. `curl http://<tailnet-ip>:8787/debug` — if you get JSON, the bridge is up.

That first `curl` will pop a card. That is the point.

```bash
# read something (NOTIFY card, 5s, silence approves)
curl http://<tailnet-ip>:8787/notifications

# run a command (SECURE modal, needs a tap)
curl -X POST http://<tailnet-ip>:8787/ssh/myhost \
  -H 'Content-Type: application/json' \
  -d '{"command":"hostname"}'
```

## Getting root on the host the bridge runs on

The SSH plugin executes on whatever host the connection points at. Point a connection at the
bridge's **own** host and `/ssh/<that-connection>` with `sudo -S` is root there — with the
password typed into the approval modal, never stored, and no sudoers change at all.

This is the answer to "give the bridge sudo" that needs no `NOPASSWD` grant and no carved-out
identity. It works because `sudo -S` reads one line from stdin and the route now supplies exactly
that: the modal password, wiped after the request.

One-time setup, on the phone:

1. SSH → ADD CONNECTION → host = the bridge host's tailnet address, user = the account that owns
the bridge process.
2. Paste the generated public key into that account's `~/.ssh/authorized_keys`.
3. Confirm the host key. The first call answers `409 ssh_host_key_unknown` with a fingerprint and
   runs nothing; compare it against `ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub` on the target and
   repeat the call with `"trustHostKey"`. Never trust it blind — the handshake exists to make that
   a decision, not a formality.

```bash
curl -X POST http://<tailnet-ip>:8787/ssh/self \
  -H 'Content-Type: application/json' \
  -d '{"command":"sudo -S -p \"\" id"}'
```

The card asks for the password because `SudoCommand` recognises `sudo` in the command and forces
the mode to `PARANOID` before the modal opens. The password goes to the command's stdin and
nowhere else — not argv, not the environment, not the body, not the audit log. A `sudo` command
without `-S` is refused pre-gate with `400 sudo_needs_stdin`, so a guaranteed failure never costs
a tap.

**Do not chain it through a second host.** `ssh otherhost 'sudo -S id'` hangs to its deadline: the
intermediate `ssh` consumes the password from stdin, so the inner `sudo` waits for a line that
never arrives. The password is fed to the outermost process only. If the target is not reachable
directly, add a connection to it rather than nesting.

Limits worth knowing before relying on it:

- `POST /ssh/{name}/detach` refuses `sudo` outright — the password is wiped when the request ends
  and the job outlives it. Run privileged work in the foreground.
- Exactly one line reaches stdin, so a second prompt gets EOF. Pass `-y` yourself when you need it;
  the route will not rewrite the command the human approved.

## Layout

```
app/src/main/java/com/mido/phonebridge/
├── core/          the gate, the vault, the dispatcher, error codes
├── plugins/       one package per capability; each owns its routes and its modal rows
├── notify/        the NOTIFY card and its queue
├── audit/         append-only request log, with bodies withheld where they could leak
├── ui/            the whole visual language: paper, grain, squiggles, the stamp
└── alert/         sounds and haptics
```

`ui/Theme.kt` is the single source of design truth. `ui/PaperGrain.kt` is the texture.
`ui/StampOverlay.kt` is the moment.

## Two ideas worth stealing

**Error codes are total.** `ErrorCodes.kt` maps every code the app can throw to an HTTP status, and
`ErrorCodesTest` scans the source for `PluginError("…")` literals and fails the build on one that is
missing. A `when` with `else -> 500` was the bug that motivated it: a caller's bad query parameter
was recorded as a server fault, and nobody noticed for weeks.

**Coverage is enforced, not remembered.** `GrainCoverageTest` and `SquiggleCoverageTest` scan for
`setBackgroundColor`, `backgroundTintList`, `MaterialButton(` and friends, and fail the build on any
use not explicitly allowed with a reason. Both exist because the texture shipped twice without
reaching the screen while the code read correctly.

## Status

Pre-1.0, moving fast, one user. The gate, the vault, the audit log and the SSH path are
device-verified. The app id is still `com.mido.phonebridge` — a rename is parked until before the
first real install, because a new application id is a different app to Android and resets every
runtime permission.

`CHANGELOG.md` is the real history, and it records *why* each change happened. It is long on
purpose: several entries exist to stop a future change undoing a fix that was paid for.

## Credits

Handlee by Joe Prince — SIL Open Font License, see `OFL-Handlee.txt`.

The attention cues (`res/raw/cue_*.ogg`) are from Kenney's "Interface Sounds" 1.0 pack, released
under CC0 (public domain). Provenance per file is recorded in `assets/cue-attribution.txt`.
Stamp and paper-crease audio are from Freesound and are used as permitted by the terms under which
they were obtained.

## Licence

Apache License 2.0 — see `LICENSE`. Third-party components and their licences are listed in
`THIRD-PARTY-NOTICES.md`, and the project's own copyright and attribution in `NOTICE`.

The work is also released as a waqf and as sadaqah jariyah. That is an intention, not a
restriction: Apache-2.0 grants every right it grants, unconditionally and to everyone.
