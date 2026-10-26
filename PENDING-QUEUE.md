# PhoneBridge — pending fix queue (from device testing)

Mido's workflow (2026-10-19): *"when I give you fixes while testing just queue them into our
todoist and focus on testing and collecting feedback instead of fixing on the spot."*

**The Todoist path is healthy again** (verified 2026-10-19 20:03: a broker call returned real
project data), so new items go straight to the **PhoneBridge** parent todo
(`6hp83cg6Q5764fwM`) — this file is now only the local mirror for anything found while the
bridge is down.

## Shipped (all verified pushed)

- [x] **0.20.1 / 97** — lying "Downloading…" toast removed; button-scoped progress fill
  (`Theme.progressButton`); a ready model confirms before re-downloading.
- [x] **0.20.2 / 98** — **model artifact swapped.** The old `mixed_int4` file's chat template
  crashed every inference (`tried to use + operator on unsupported types string and sequence`);
  now `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`. Constrained decoding removed (it truncated
  mid-object); the system prompt is longer and is the guide.
- [x] **0.21.0 / 99** — **both queued fixes:** `Theme.ROW_GAP_DP` spacing applied inside
  `Theme.statusRow` (and reused by Settings/Log rows); **auto-start the server on app load**
  (`maybeAutoStartServer`, once per process so a manual STOP sticks).
- [x] **0.21.1 / 100** — **GPU fixed** (`<uses-native-library libOpenCL.so / libvndksupport.so`)
  and **CPU fallback deleted** (fail hard).
- [x] **0.21.2 / 101** — SECURE budget 3s → 10s (the model was 0.2s over, not broken).
- [x] **0.21.3 / 102** — **GPU sampler bundled** (`libLiteRtTopKOpenClSampler.so`, v0.18.0,
  sha256 `0f77c888…`). Inference **3.2s → 1.23s**; `OpenCL sampler not available` is gone.
- [x] **0.22.0 / 103** — **modal arms on the fetch, not a timer.** `DECISION_DELAY_MS` deleted;
  loading words + looping dots; 60s clock starts at enable; AI failure unlocks the modal and
  names the cause.

## Before launch — the one parked task

- [ ] **Rename the application id `com.mido.phonebridge` → `com.mido.stmp`.** Deliberately NOT done
  during the rebrand (Mido, 2026-10-26: *"skip the package rename for now, keep it as a todo before
  launch"*). Every user-visible name is already STMP; what is left is identifiers only — the
  application id, the Kotlin package, and the `Theme.PhoneBridge*` style names. This cannot be a
  copy change: a new application id is a **different app to Android**, so it resets every runtime
  permission grant (`POST_NOTIFICATIONS`, full-screen intent, draw-over-apps, battery exemption)
  and the notification-listener binding. The vault also cannot follow it — Keystore entries are
  per-app and are deleted on uninstall — so the stored secrets must be re-added by hand. Do it once,
  before the first real install, and budget the re-granting pass.

## New findings from the 2026-10-19 sweep (queued, not yet built)

- [ ] **`/debug` does not expose the policy RULES — only `global` + `readable`.** So the ANY
  rule (and any per-pair rule) cannot be verified remotely: there is no way to see what the
  gate will actually do without reading the phone screen. `policyInfo()` in `DebugPlugin.kt`
  should report the rule list (`plugin`, `verb` or `ANY`, `mode`). Cheap, and it is the only
  remote window into the switch that decides whether a human is asked at all.
- [ ] **`audit.unreadableLines: 7`** — `/debug` reports 7 lines the audit store could not parse
  (of ~290). Not yet investigated: they may be pre-0.13.1 `OFF_APPROVED` records (expected, by
  the no-backward-compat rule) or genuine corruption. Worth reading one before dismissing them.
- [ ] **UI: app content renders under the status bar.** Mido: *"notification leak is a ui issue /
  no need for app ui to render under the notification bar"* — screens draw beneath the system
  bars instead of respecting the insets. No `fitsSystemWindows` / `WindowInsets` handling exists
  anywhere in the app.

## New backlog (2026-10-20) — written here because the bridge was down

The Todoist path was unavailable when these were requested (bridge `000` / port 8787 refused),
so they are recorded locally rather than claiming they were filed. Move to Todoist sub-todos
under the PhoneBridge parent (`6hp83cg6Q5764fwM`) once the bridge is back.

- [ ] **Revert the model to Qwen3-0.6B.** Mido: *"too slow and I think it crashed. new plan:
  recert to the old small one."* The 1.7B idles ~2 GiB and peaked ~5.7 GiB; the bridge died
  twice in one morning (both times around a request). Four constants in `ModelRegistry`; the app
  re-downloads because `MODEL_ASSET` names a different file. Description quality is no longer
  riding on the model — `BodyDetail` guarantees it — so only the RISK label is lost, and a 0.6B
  produces a single label far more reliably than a sentence.
- [ ] **Options screen.** A new screen (not the Exceptions screen) holding device-level settings.
  First two occupants below; the screen exists so the next setting has a home rather than
  becoming another row on the home checklist.
- [ ] **Options: enable/disable fingerprint.** When disabled, `SECURE` is just the modal — a tap,
  no biometric. Must respect the crypto-bound rule: once a vault exists the prompt *is* the vault
  unlock, so disabling fingerprints has to either (a) keep the vault prompt for secret calls only,
  or (b) refuse to disable while a vault exists. Decide before building.
- [ ] **Options: AI backend — off / local / external.** Off = no summary and no risk, deterministic
  rows only (must not 503 the bridge). Local = the on-device `.litertlm` (current). External =
  Ollama or unsloth server over the tailnet, so the phone can ask a bigger model on the LAN/box.
  Needs: a reachability check, a timeout, a fail-closed behaviour when the endpoint is down, and
  the prompt sent over the wire must not leak a secret (the broker scrubs its own response, not
  our request to a model server).
