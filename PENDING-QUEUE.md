# PhoneBridge — pending fix queue (from device testing)

Mido's workflow (2026-10-19): *"when I give you fixes while testing just queue them into our
todoist and focus on testing and collecting feedback instead of fixing on the spot."*

**Why this file exists:** the Todoist write path goes through PhoneBridge's own gate, and the
gate needs the AI — which is broken (see below). So the queue could not be written to Todoist
while testing. These are recorded here and must be pushed to Todoist's **PhoneBridge** parent
(`6hp83cg6Q5764fwM`) once the bridge is healthy again.

## Queued (not yet implemented)

- [ ] **UI: vertical margin between list items.** *"add margin around list items they are
      touching"* — rows sit flush against each other. Add spacing in `Theme.statusRow` /
      the rule rows in `SettingsActivity` / the log list in `LogActivity`.
- [ ] **Auto-start the server on app load.** *"start server on app load automatically"* —
      `MainActivity.onCreate` should start `BridgeService` if it is not running, instead of
      requiring the START SERVER tap. Consider the interaction with the manual STOP control:
      auto-start on launch must not make STOP impossible for the session.

## Already shipped while testing (before the queue rule landed)

- [x] **0.20.1 / build 97** — lying "Downloading…" toast removed (the provisioner is
      idempotent, so the toast fired on every no-op tap); the model loader is now a
      **button-scoped progress fill** (`Theme.progressButton`), a `LayerDrawable` of a dimmed
      track plus a `ClipDrawable` fill driven by the real byte count; a ready model confirms
      before re-downloading. Pushed, exit 0, md5 `ab5fa33709476dfc0a2b179031321a57`.

## Blocking finding — the model cannot summarise (root cause: chat template)

**Not memory, not GPU, not speed.** The engine loads fine on the phone:
`loading qwen3_0_6b_mixed_int4.litertlm (474 MB) on Backend$GPU` → `engine on GPU` in ~6 s,
on the **Samsung Xclipse 920** via WebGPU/Vulkan. Then every inference fails:

```
W/LiteRtLm: inference failed: Failed to call nativeSendMessage: INTERNAL:
  Failed to apply template: invalid operation: tried to use + operator on
  unsupported types string and sequence (in template:14)
W/SummaryProvider: qwen3-0.6b-int4 produced no summary; the caller fails the request
```

Reproduced identically on the host with the **official Python binding**, so it is not the
phone and not my Kotlin usage: the `.litertlm`'s embedded **Qwen3 chat template** concatenates
`'\n' + message['content']` (template line 14), which is valid for a **string** content but
blows up when content arrives as a **sequence of parts** — which is what `Contents` serialises
to. A plain-string message path avoids the crash but then produces **unrelated output**
(Chinese filler), i.e. the template is not applied at all.

**Consequence: the bridge is down for every AI route, including the Todoist queue path.**
`/debug` and `/logs` still answer (they declare `aiSummary = false`), which is how this was
diagnosed at all.

**Candidate fixes, in preference order — decide before building:**
1. **Swap the model artifact.** `Qwen3-0.6B.litertlm` (the dynamic-INT8 sibling) is documented
   as carrying "LiteRT-LM's official qwen3 configuration" and needing 0.18.0+. One constant in
   `ModelRegistry`. Cheapest if it works.
2. **Try Gemma with an HF token.** All small Gemma are gated; a token makes them fetchable and
   Gemma's template is the well-trodden path on this runtime.
3. **Pin a different LiteRT-LM version** (0.17.1) if the template handling regressed in 0.18.0.
4. **Drop the system instruction** and put everything in one string, if a string-only path can
   be made to apply the template properly (unproven — the string path produced garbage).

Each of 1–3 is one constant / one gradle line, because the `SummaryModel` seam exists for
exactly this. Do not tune timeouts or budgets — the failure is not latency.
