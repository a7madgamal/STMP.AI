# Android Phone Bridge — v0 spec

Local-only Android app exposing a small HTTP server so a remote caller (over Tailscale)
can read notifications and trigger notification actions. Security is the critical feature.

## Decisions (locked)
- **Action mechanism:** NotificationListenerService + direct `Notification.Action` PendingIntent
  invocation ONLY. No accessibility fallback. If an app doesn't expose the action → fail loud
  (`unsupported_action`).
- **Auth:** none for MVP. Tailscale is the network boundary. Harden later.
- **Approval:** EVERY request (including GET) opens a full-screen approval popup showing all
  request details. Always per-request, no "remember". Timeout -> auto-deny.
- **API:** 3 endpoints, REST-ish.
  - `GET  /notifications`              -> list active notifications
  - `POST /notifications/{id}/action`  -> `{action: index|label, replyText?}`
  - `POST /notifications/{id}/dismiss` -> cancelNotification
- **id:** raw `StatusBarNotification.getKey()`.
- **Build:** debug APKs built on the KVM box, sideloaded by Mido. iterate fast.

## Response envelope
- approve + ok   -> 200 `{ok:true, ...}`
- deny           -> 403 `{ok:false, error:"denied"}`
- timeout        -> 408 `{ok:false, error:"approval_timeout"}`
- bad action     -> 409/422 `{ok:false, error:"unsupported_action", detail:...}`
- exec failure   -> 500 `{ok:false, error:"action_failed", detail:...}`

## Stack
Kotlin, minSdk 26, targetSdk 35, NanoHTTPD embedded server, NotificationListenerService,
approval = full-screen Activity launched from a high-priority notification.

## Security model (v0)
Tailscale = network boundary. Approval popup = the gate. Nothing else.
Every request path MUST pass through the approval popup before any read or action.
