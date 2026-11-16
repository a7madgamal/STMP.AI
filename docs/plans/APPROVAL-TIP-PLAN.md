# Approval tip plan

**Status: IN PROGRESS on branch `feat/approval-tip`. No push, no release.**

Date: 2026-11-16
Baseline: source at v0.51.34 / build 360.
Requested: Todoist "Approval modal: collapse to a tilted paper tip that slides in from the right
edge", refined in conversation.

## 1. What changes

The approval surface gets a resting state. Instead of a full-screen modal arriving on every
request, a narrow paper wedge appears against the right edge; touching it opens today's sheet.

Rest is not a second window implementation. It is **the same Activity, laid out as a strip**.

- **Rest**: window occupies only the right edge strip, `FLAG_NOT_FOCUSABLE` +
  `FLAG_NOT_TOUCH_MODAL`, dim 0, transparent background. Everything outside that strip belongs
  to the app underneath, which stays fully usable.
- **Expanded**: window becomes the whole screen again, focusable, with the existing dim, and the
  sheet keeps a small resting rotation so a sliver of the background stays visible.

Back-equals-deny, the stamp, the tear, the biometric flow, the password field, the misclick
guard and the 60s window are untouched.

## 2. Decisions

| Question | Decision |
|---|---|
| Does the tip count as "showing the modal" for the 60s window? | Yes. The clock already starts at `shownAt`, which is when the head request is presented; the tip is that presentation. A request must not sit unexpired behind an unseen tip. |
| Can the tip be denied without expanding? | No. Touch does exactly one thing — expand. No ✕, no swipe-to-deny. Removed deliberately to make a misfire impossible. |
| How is the queue shown? | Stacked wedges: up to 3 visible, later arrivals sit behind and slightly under the front one, faded by depth. Only the front wedge is a touch target. The 4th and beyond exist only in the gate until one becomes head. |
| What happens on collapse? | Tapping outside the expanded sheet folds it back to the wedge. It does **not** pass through to the app behind — a request in flight must not have stray taps landing on it. |
| Locked phone? | Skip rest, open expanded. A wedge on a keyguard screen cannot be read or tapped, and the window would burn while invisible. |
| Sound? | Unchanged: fires once on arrival, at `shownAt`, as today. |

## 3. Animation

Every frame is driven by `SystemClock.uptimeMillis` + `postOnAnimation`. **No
`ValueAnimator`, no `ViewPropertyAnimator`** — this project has already shipped three silent
animation failures from those (an invisible overlay card, a stalled fade-in, a countdown that
never moved), and `PaperEntranceView` is the working precedent.

| Beat | Duration | Shape |
|---|---|---|
| Tip arrives | 180 ms | decelerate, 24 dp in from the right, alpha 0 → 1 over the first 40 % |
| Ghost steps forward | 160 ms | decelerate |
| Expand | 220 ms | ease-out with a 2 % overshoot; the tip and the sheet are the same paper, so it rotates and translates rather than crossfading |
| Collapse | 140 ms | ease-in, flat, no overshoot |

Arriving is slower than leaving on purpose: that asymmetry is what reads as snappy rather than
soft. The 120 ms gate tick that refreshes queue depth stays separate from animation frames, so a
stack change can never stall the motion.

## 4. Geometry

The wedge's lean *is* its taper, so the shape is derived rather than hand-drawn:

- `TILT_DEGREES = 15°` — the angle of the long edge.
- `TOP_WIDTH_FRACTION = 0.34` of screen width (~3 cm on a 7 cm-wide phone), clamped to
  72–220 dp.
- The taper therefore ends at `height = topWidth / tan(tilt)` — for a typical phone that is
  around 570 dp, so the point lands on screen rather than off the bottom.
- Outline: from the top-right corner, a straight top edge leftwards, then the leaning edge down
  to the point, with a slight quadratic bow so the paper reads as flexed rather than cut.
- Touch target: the visual strip plus ~80 dp outward, because the right edge itself belongs to
  the system back gesture and the system wins that competition. The wedge must be easy to hit
  without stealing swipe-back.
- Ghost offsets: 10 dp inwards, 6 dp down, alpha 0.45 then 0.22.

## 5. Steps

1. **Geometry tokens + outline path** — pure module, unit-tested. No UI, no wiring. *(done)*
2. **`ui/ApprovalTipView.kt`** — the wedge drawn from step 1's path, paper fill via
   `Theme.sheet` so `GrainCoverageTest` stays green. Screenshot baseline, no behaviour.
3. **Window modes** — `applyWindowMode(REST | EXPANDED)` via `window.setAttributes()`; needs a
   translucent theme variant, since `windowIsTranslucent` cannot be added at runtime.
4. **Interaction** — tap expands, outside tap collapses, entrance and expand/collapse animation.
5. **Stack** — ghosts drawn from the gate's queue snapshots on the existing tick.
6. **Expiry honesty** — a request that expires while resting closes without flashing an empty
   sheet.
7. **Guards + records** — accessibility label on the wedge, screenshot baselines for 1/2/3,
   plan and changelog, single version bump.
8. **Device review** — real phone, before any push.

## 6. Open question

Steps 3–5 assume the stack lives in the current request's Activity. That holds while the head is
on screen, but when the head resolves the gate finishes that Activity and launches a *new* one
for the next request (`FLAG_ACTIVITY_CLEAR_TASK`), so ghost wedges cannot morph across the
handover — they vanish with the dying window and the new tip slides in behind them.

Two ways forward, and this is the one thing to settle before step 3:

- **A — keep per-request Activities (today's model).** Promotion means the stack re-forms: the
  sheet leaves, and 180 ms later a tip slides in carrying N-1 ghosts. Less continuity, no change
  to the gate's launch contract.
- **B — one long-lived Activity, next request delivered by `onNewIntent`.** The ghost can move
  into the front position as a real animation, because nothing is destroyed. Requires dropping
  `CLEAR_TASK`, teaching the Activity to swap its content for a new request, and re-establishing
  that a re-show can never reset the clock — `buildApprovalIntent` already carries the original
  `createdAt`, so the invariant is expressible, but it is a change to the safety surface.

## 7. What this plan deliberately does not do

- No dark theme, no Compose, no new animation dependency.
- No change to mode resolution, timeout, misclick guard, biometric rules, vault binding,
  sudo/password flow, queue order or exception writes.
- No new resolution path: the tip can only expand, so every decision still comes from the
  existing buttons.
