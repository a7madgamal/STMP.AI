# Approval tip plan

**Status: IMPLEMENTED LOCALLY on branch `feat/approval-tip` — awaiting device review. No push,
no release.**

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

## 6. Resolved: one long-lived Activity

Option B was chosen. `CLEAR_TASK` is gone and the launch carries `SINGLE_TOP`, so the next request
is delivered to the window already on screen. The stack therefore promotes rather than re-forms.

The clock invariant survives because nothing time-related is stamped in the Activity: the deadline
is the gate's arm time, and the intent carries the original `createdAt`. A test pins that a
request which waited 30 seconds arrives with 30 seconds already spent.

What a swap must drop is dropped in one named act (`releaseRequestScopedState`) rather than
scattered per field, because the failure mode — a forgotten field — is invisible until the previous
request's countdown runs behind the next request's card.

## 6a. Implementation notes

- **Rest is a strip window, not a full-screen transparent one.** A full-screen translucent window
  sits on top of the app behind it, and Android 12+ blocks touches that would pass through a window
  opaque enough to hide what it covers. A strip wider than the paper never covers the screen, so
  the untrusted-touch rule never applies.
- **The theme is always translucent.** `windowIsTranslucent` is read when the window is created,
  before `onCreate`, so a per-shape theme is impossible without two Activities — and two launch
  paths into one decision surface is the class of bug this repository has already paid for. The
  vault prompt keeps the old opaque theme, since it is always a full sheet.
- **0.5° rest tilt, chosen by eye.** Rendered at 5°, 3°, 2°, 1° and 0.5° at true phone size; 5°
  wrapped a command over three lines and clipped the card's corners flush against the edge.
- **Motion is clock-driven, never animator-driven**, and every loop carries a frame backstop: a
  motion that cannot finish must snap, because the alternative is a sheet stuck half-open while the
  request expires underneath it.
- **The wedge activates through `performClick`**, so a screen reader can open the card. It also
  carries a content description set in the view's `init`, where no call site can forget it.

## 6b. Verification

`./gradlew :app:testDebugUnitTest` — 899 tests, 0 failures.
`./gradlew :app:lintDebug` — clean.
`./gradlew :app:verifyRoborazziDebug` — all screenshot states match.
`changelog-check`, `version-check`, `version-guard` — ok.

Compilation, Robolectric and source guards do **not** establish that the motion feels right on a
device, nor that a strip window behaves as described on a given OEM. Neither is verified until it
has been seen on the phone.

## 7. What this plan deliberately does not do

- No dark theme, no Compose, no new animation dependency.
- No change to mode resolution, timeout, misclick guard, biometric rules, vault binding,
  sudo/password flow, queue order or exception writes.
- No new resolution path: the tip can only expand, so every decision still comes from the
  existing buttons.
