# Refined Paper cleanup plan

**Status: FINAL PLAN — implementation has not started. Build only on Mido's explicit `go`.**

Date: 2026-11-11
Baseline: source at v0.48.16 / build 292, with an unrelated uncommitted emulator-ABI option already in `app/build.gradle.kts`.
Source audit: `STMP Redesign Directions (Copy).md`, checked against the current source and existing STMP design/gate plans.

## 1. Product direction

Ship **Direction A: Refined Paper**, across the approval experience and the main app. STMP should feel like a deliberate, tactile paper instrument—not a generic Material app, a dark-luxury vault, or a collection of handmade decorations.

Keep the cream paper, warm ink, imperfect edges, grain and rubber stamp. Improve them through restraint and consistency:

- paper texture carries the identity on major surfaces and the approval moment;
- dense lists, body copy and functional controls become quieter and easier to scan;
- the stamp is the signature effect, with no confetti or unrelated theatrical motion;
- helpful, specific feedback replaces decorative or dead-end status language;
- existing behavior is preserved unless this document explicitly describes a presentation or navigation change.

### Locked boundaries

- **Existing Android Views + Kotlin architecture.** No Compose migration, XML-layout conversion, 3D engine or new animation dependency.
- **Light theme only.** Do not add a partial dark theme. Keep palette values semantic so a future dark-paper palette can be added deliberately.
- **No approval-policy rewrite.** Preserve the current mode resolution, approval/denial semantics, timeout behavior, misclick guard, biometric rules, vault binding, sudo/password flow, queue order and exception writes.
- **No new approval gestures.** Keep the existing Approve/Deny taps. No hold-to-approve, forced payload scrolling, number matching, new risk-based friction or mandatory biometric escalation.
- **Activity is read-only.** No request re-execution, rule creation, policy editing, caller blocking or approval controls in history.
- **No intermediate visual-review checkpoints.** Implement and verify the cleanup as one coherent epic; device checks still follow the standing one-case-at-a-time protocol.
- Preserve the pre-existing emulator-ABI edit. Do not overwrite, revert or include it accidentally in a design commit.

## 2. Information architecture: three tabs, three jobs

Replace **Home · Plugins · Settings** with **Home · Activity · Preferences**. A separate Plugins/Settings split falsely implies that plugin configuration is not settings.

Bottom navigation uses one consistent outline-icon family, text labels and a clear selected state using weight/shape as well as color. Preserve the paper bar and improve its restraint. Activity carries a badge when requests await a decision. Keep state across tab switches and use predictable system back behavior.

### Home — “Is it working, and what needs me?”

Order:

1. **Bridge health and server control**, sharing one surface.
2. **Actionable problems**, each with a direct remedy.
3. **Approval mode and fingerprint**, directly editable as they are today.
4. **Pending requests**, linking to Activity rather than becoming a second approval surface.
5. **Recent decisions**, a small read-only summary with a path to Activity.
6. **Get started**, visible only while onboarding/setup remains useful; no permanent promotional panel.

Move updates, diagnostics and developer tools out of Home. Keep global bridge-health facts on Home even when the remedy lives in Preferences or a plugin detail screen.

### Activity — “What is waiting, and what happened?”

- A live **Awaiting decision** section backed by read-only snapshots of both existing queues: the full-screen approval queue and the NOTIFY queue.
- Each pending row exposes known facts—caller, plugin/action, verb and waiting state—without offering a second place to resolve the request.
- Existing audit history remains below, with useful filters for outcome and plugin, the existing noise lens and readable empty states.
- Promote the audit from Settings → Tools into this tab; remove the duplicate destination.
- Keep storage, retention, filtering and record contents unchanged.
- Rows use structured summaries instead of long monospace strings. Every outcome has a word and icon, not color alone.
- A row opens **Request details**, not “replay”: recorded caller, action, exact request, query/body, timestamps, truncation, decision, errors and response where stored.
- The detail view may use the new visual language, but must not imply that it reconstructs the exact original screen or a later AI response that was not recorded.
- Preserve the existing raw-record inspection/copy capability, with clear labeling and no silent disclosure expansion.

### Preferences — “How does STMP behave?”

One organized configuration map, grouped by user intent rather than implementation ownership:

1. **Phone** — notifications, contacts, audio and speech capabilities.
2. **Accounts & services** — Google/Gmail/Calendar/Drive and other account-backed capabilities.
3. **Secrets & connections** — secrets and SSH, retaining their separate add flows.
4. **AI** — the existing master switch and selected-backend configuration.
5. **Approval & exceptions** — a shortcut to the same Home approval controls, plus the existing exceptions editor; never a second source of policy state.
6. **System** — bridge startup, sound, lock-screen popup, overlay, battery and vault access.
7. **About & updates** — the existing update channel/check/install/skip behavior and release information.
8. **Developer** — diagnostics and the existing debug log.

Group rows with clear headings, spacing and hairlines; do not wrap every row in its own heavy card. Retain all existing destinations and setting write paths. Plugin-owned settings—including SSH connection setup and speech settings—remain on the owning detail screen.

#### Clickable-row hierarchy

A capability row's primary action is **open its detail/configuration screen**, not merely operate its switch.

- Retain the small **top-level help icon on the left**. Replace the repeated loud 💡 treatment with a restrained monochrome outline icon in muted ink. It remains visually secondary, has a 48dp touch target and a specific accessibility label.
- Name and plain-language purpose lead; status is specific and secondary.
- A visible chevron plus pressed, focused and hovered states makes the row's navigation obvious.
- The whole row opens details. The help icon and switch consume their own gestures and never navigate accidentally.
- When setup is incomplete, show **Set up** or **Grant** beside the exact problem instead of a switch that cannot make the capability work. Do not change the saved preference merely because a permission or prerequisite is missing.
- Keep route tables, raw implementation detail and advanced configuration on detail screens, without hiding information needed to understand current state.

## 3. Visual system

### Typography

Bundle and license the selected open-source fonts locally; do not depend on a runtime font download.

| Role | Typeface | Use |
|---|---|---|
| Expressive display | **Handlee** | Major headings and stamp treatment only, normally **20–24sp or larger**; never tiny handwritten labels |
| UI/body | **Atkinson Hyperlegible Next** | Body copy, controls, settings, statuses and navigation; real weights rather than synthesized Handlee bold |
| Exact payload | **JetBrains Mono NL** | Commands, code, JSON, IDs and paths; no ligatures |

Update third-party notices and include the actual font license files.

Starting type scale, adjusted only after device inspection:

- caption/supporting metadata: **13sp**;
- secondary text: **14sp**;
- body and ordinary control labels: **16sp**;
- row titles and emphasized controls: **17–18sp**;
- section/page headings: **20–24sp**;
- approval headline: clear display tier, without allowing the AI summary to dominate;
- exact approval payload: **15–16sp mono**, selectable and never ellipsized.

Respect Android font scaling. Test long labels, 1.3× font scale, Arabic/mixed-script content and unsupported glyph fallback; use the platform fallback rather than rendering boxes or silently dropping characters. Do not solve dense layouts by shrinking text below the agreed floor.

### Color, texture and surfaces

- Keep warm cream backgrounds and near-black brown ink.
- Separate the **brand/stamp accent** from **danger** with visibly distinct tokens; do not leave `ACCENT == DANGER`.
- Darken amber and secondary text where needed. Automated contrast checks must cover actual foreground/background pairs, including raised, warning, danger, disabled and selected surfaces—not just constants against the page background.
- Target **WCAG 4.5:1 or better for ordinary text**, and at least 3:1 for qualifying large text and meaningful control boundaries/icons. Do not communicate risk by hue alone.
- Use explicit risk icon + word + reason. Preserve the current risk semantics and the rule that an absent/failed assessment is not “safe.”
- Preserve semantic state color: grey = idle/stopped, green = verified running, never red for ordinary stopped state.
- Reduce squiggle amplitude, border contrast and grain on dense rows, long text and controls. Keep stronger paper character on major cards, empty states and the approval sheet.
- Use one spacing/radius/elevation system and a single shared component factory per control role. Do not add local one-off button or switch styles.

### Paper switches

Keep the custom paper-styled switch instead of replacing it with a generic Material switch.

- Give it a real switch accessibility role, checked-state announcements, keyboard/focus behavior and state-change events.
- Correct the low-contrast off state and distinguish **off**, **disabled**, **pending** and **cannot work until setup**.
- Keep at least 48dp touch targets and current truthful rendering from stored/live state.
- Do not optimistically move the knob before an operation succeeds.
- Verify TalkBack on both server and plugin rows, including the separate row/help/switch gesture owners.

## 4. Helpful feedback is a shared component, not copy patching

Replace bare “needs attention,” “not set up” and unexplained errors with a reusable status/resolution model and renderer:

1. **What is wrong** — “Notification access is off.”
2. **What it affects** — “Notification requests will fail; other plugins can still run.”
3. **What to do next** — a same-surface **Grant access**, **Set up**, **Reconnect**, **Retry** or **Open details** action.

Requirements:

- Resolve specific causes where the facts exist; never invent a more precise diagnosis than the source provides.
- Keep measured bridge liveness separate from listener/permission health.
- Preserve the original technical error verbatim behind **Details**; lead with useful language, not an internal exception string.
- Use the same pattern on Home, Preferences, capability details, setup, downloads and failures.
- An unavailable action is absent when it could never apply; a temporarily unavailable action may remain disabled only with a visible reason.
- Do not convert a missing prerequisite into a preference write, silently enable a plugin or silently change approval policy.

## 5. Approval card: facts first, model second

Keep the current full-screen approval Activity and its decision wiring. Rebuild its layout in Views around the order needed to judge a request:

1. **Request header** — plugin/capability, action verb with icon and word, existing queue information and current countdown treatment.
2. **Who is asking** — resolved caller identity and available trust/origin facts. Do not fabricate a request count or “known” trust claim not present in current data.
3. **Deterministic action headline** — the route's resolved human description, never an AI-generated substitute.
4. **Exact request** — the dominant decision content: structured target rows, full command/body/path and important query values in readable mono. No truncation, rewriting, invented fallback headline or hiding required facts only under raw JSON.
5. **Additional details** — secondary metadata and raw JSON behind a disclosure.
6. **Optional model reading** — below the facts, in regular UI type, with the current loading/failure/risk behavior. The card remains complete when AI is off or fails.
7. **Pinned action dock** — existing Deny/Approve behavior, current misclick guard and existing biometric flow. Deny stays immediately available.

Preserve required target resolution before display, full sudo/password behavior, runtime-permission continuation, exception behavior and all `ApprovalView` field wiring. Any refactor must keep the Activity plugin-blind: plugins continue to supply structured sections/rows rather than adding per-plugin UI branches.

### Scrolling and reachability

- Body/payload content scrolls; decision controls stay pinned and reachable.
- Do not require scrolling to the end before Approve can arm.
- Every scrollable area receives a consistent overflow treatment: visible next-content peek or edge fade, a **More below ↓** cue while more content exists, and a scrollbar where useful.
- Approval overflow uses explicit **Scroll for full request** guidance while off-bottom.
- Cues disappear at the end, do not cover text or controls, and have accessibility equivalents.
- Apply the same behavior to Home, Activity, Preferences, details and dialogs—not only the approval card.
- Check system-bar insets, gesture/three-button navigation, large fonts, landscape and long Arabic/multiline content.

## 6. Paper motion and effects

Implement with the existing Views, `Camera`, `Canvas`, drawables and lightweight snapshot rendering. No 3D engine. Motion must never decide a request, hold its latch open, change timestamps or become the only teardown path.

### A. Entrance — Present → Land

Target: **280–320ms**, on the first presentation only.

1. Start at zero opacity, slightly below the final position, with subtle sideways tilt.
2. Perspective makes the **top edge about 8–10% wider than the bottom**. Use actual camera/perspective geometry, not a flat trapezoid that merely looks skewed.
3. Fade to visible quickly over **60–80ms** while rotating/translating the sheet toward the screen.
4. A broad, soft shadow contracts as the paper approaches the “desktop.”
5. Land flat with soft deceleration and a tiny **2dp recoil**; no rubbery bounce and no slow content stagger.

The live decision surface remains present and Deny remains usable. Entrance motion does not postpone the existing misclick guard, countdown or AI fetch. Do not replay it after biometric cancellation, rotation/recreation or re-showing the same pending request.

### B. Approval — stamp the desktop

Target: approximately **650ms** total, while preserving existing request resolution timing and teardown backstops.

- Sharpen the stamp lift/perspective and impact rather than adding more movement.
- At the impact frame, synchronize one firm sound and haptic with the visual contact.
- Compress the sheet **1–2%**, then allow one subtle recoil.
- Render real masked ink bleed around the supplied stamp artwork. The existing `bleed` animation value is currently not used in drawing; this cleanup must make the effect real or remove the dead mechanism.
- Preserve the supplied approval artwork, slight hand-placement rotation, transparency/grain and brief readable hold.
- Exit cleanly with no flash, double attach or animation-dependent stranded card.

### C. Rejection — stamp → crease → tear

After the existing DENIED decision is recorded and the red mark lands:

1. A short compression/crease travels through the paper.
2. Tear a **snapshot** of the sheet along a deterministic irregular paper edge; never mutate the live content or audit record.
3. Separate the halves with **3–4°** opposing tilt and **20–28dp** travel, then fade within roughly **300ms**.
4. Keep it paper-like and quick: no shards, particles, confetti or prolonged full-screen scrim.

### D. Reduced motion and failure handling

- When system animations are disabled/reduced, use a **100–120ms fade** and static verdict instead of perspective, recoil, slam or tear.
- Keep the existing sound preference and accessibility semantics; haptic/sound must not be required to understand the result.
- Snapshots and animation allocations must be bounded, released on detach and safe on low-memory devices.
- Preserve idempotent resolution/removal and the existing forced-teardown deadline. Any skipped/cancelled/failed animation converges on the same cleanup.
- Apply reduced-motion handling to the NOTIFY overlay too. Its approval semantics and queue remain unchanged.

## 7. Platform navigation and consistency

- Audit all roots for edge-to-edge system-bar insets; keep controls above navigation bars and preserve existing padding.
- Use the platform back APIs consistently: detail → owning tab, tab → Home where appropriate, Home → system default.
- Do not trap the user in a tab or replay tab entrances on every resume.
- Approval back/dismiss must follow the existing pending-request behavior; predictive-back previews must not approve, deny, cancel biometric continuation or destroy a required pending surface.
- Preserve task affinity, lock-screen behavior and the existing re-show-on-return contract.
- Keep bottom navigation stable, with Activity badge updates driven by real pending snapshots rather than decorative counts.

## 8. Implementation sequence

Work on a design branch. Keep commits buildable and separate behavior-preserving refactors from visual changes. Do not make a big-bang rewrite of the approximately 35k-line View codebase.

### Stage 1 — Foundation and guards

- Add the two font families, licenses and semantic type roles to `ui/Theme.kt`.
- Split brand/danger tokens, establish tested contrast pairs and introduce the quieter surface/spacing system.
- Add reusable row, status/remedy, overflow-affordance, icon and accessible-switch components.
- Add source/Robolectric guards for typography roles, contrast, accessibility metadata and component usage before sweeping screens.
- Keep existing grain/squiggle guard tests meaningful; update allowlists only for justified geometry changes.

### Stage 2 — Approval structure and motion

- Extract approval presentation sections from the 1,409-line Activity without changing gate inputs or result wiring.
- Reorder facts/model content and add the shared overflow treatment and pinned dock.
- Implement the perspective entrance, synchronized approval stamp and snapshot-based denial tear.
- Keep animation policy values isolated and testable; preserve existing deadlines and lifecycle handling.
- Extend `ApprovalViewWiringTest` and relevant approval tests for all fields, queue states, biometric/password paths and motion fallbacks.

### Stage 3 — Home and actionable status

- Split the 1,817-line `HomeFragment` into health, approval, pending/recent and update-extraction components.
- Introduce the structured status/remedy model; preserve verified-liveness inputs and warning precedence.
- Move the entire existing update flow to Preferences, including in-progress install continuation and return-from-settings behavior.
- Keep periodic repaints from rebuilding controls underneath a finger; update in place or in isolated posted hosts.

### Stage 4 — Activity

- Add minimal read-only pending snapshot APIs to both queue owners. Do not expose mutable `Pending`/overlay entries or request-resolution methods.
- Promote the existing log list/detail into the Activity tab, restyle and preserve filters/noise/raw inspection.
- Add recent-decision and badge consumers to Home/navigation.
- Test queue snapshot isolation, ordering, disappearing pending entries, missing audit records, filtering and large histories.

### Stage 5 — Preferences consolidation

- Merge Plugins and Options into one Preferences surface using the intent-based groups above.
- Preserve all existing setting stores, write methods, plugin detail destinations, help actions and permission flows.
- Make row navigation explicit with chevrons/feedback; retain subtle left help icons and independent paper switches.
- Update the bottom-nav enum/shell, deep links/intents, back handling and old `LogActivity`/tool entry points without leaving unreachable screens or duplicate sources of truth.
- Update remaining Secrets, SSH, onboarding and detail surfaces to use the shared visual/status components as touched.

### Stage 6 — Sweep, verification and ship

- Sweep every main/detail surface for old fonts, tiny Handlee labels, ambiguous statuses, invisible overflow, raw route clutter, duplicate settings and card-in-card remnants.
- Run focused tests during implementation, then the repository's existing `:app:check`/test-summary and scoped tool checks—not an unbounded workstation-wide typecheck.
- Build, sign and deliver using the established STMP release process. Bump `versionCode`/`versionName` and update `CHANGELOG.md` together.
- Update current verified-status documentation without converting unit-test success into device-verified visual claims.
- No push, tag or release occurs unless separately authorized by the applicable repo workflow; when a release push is authorized, report the deterministic release URL in the same response.

## 9. Acceptance matrix

Automated checks:

- Existing approval/gate/audit/queue/policy tests remain green; no stored schema or policy-value migration is introduced.
- Approval rows still render every structured field; commands and payloads remain complete, selectable and visually subordinate only to the deterministic decision hierarchy.
- Contrast tests cover real semantic surface combinations and both enabled/disabled states.
- Switch role/state, row/help/switch gesture ownership, pending snapshots, overflow visibility and reduced-motion branches have focused tests.
- Lifecycle tests cover re-show, rotation/recreation, biometric cancellation, animation cancellation and forced teardown.
- Update and plugin-setting tests confirm the moves did not drop fields, callbacks, pending installs or preference values.

Device verification, one case at a time with Mido's feedback:

- Approval entrance perspective and shadow; approve stamp impact/bleed; denial crease/tear; reduced-motion static path.
- Long command, long JSON, multiline Arabic, large-font and landscape approval layouts with visible overflow and pinned controls.
- Existing approve, deny, timeout, biometric, sudo/password, exception and permission-continuation outcomes remain correct.
- Home warning names its cause and the fix lands on the correct destination.
- Activity pending count reflects both queues and disappears after resolution; history/detail remains read-only.
- Preferences rows are visibly clickable; help and switch taps never trigger accidental navigation.
- TalkBack announces switch roles/states and row actions coherently; font fallback remains legible.
- Insets and back behavior work with gesture and three-button navigation on the actual test phone.

**Done means:** the three-tab structure is complete, old Plugins/Settings duplication is gone, the refined-paper system is applied consistently, approval facts outrank AI, helpful remedies replace dead-end states, motion matches this specification, and no existing approval/security behavior regressed. Visual and motion claims remain **shipped, not device-verified** until observed on the phone.

## 10. Explicitly deferred

- Dark paper/DayNight and dynamic color.
- Compose or Material 3 Expressive migration.
- Risk-tier friction, slide/hold approval, number matching and mandatory biometric step-up.
- Changing silent-approval/unattended behavior, timeout duration, queue capacity, burst merging or rate limiting.
- Request re-execution, replay claims, rule creation from history and “deny and block” actions.
- Baseline profiles, splash redesign, launcher redesign and unrelated performance work.
- New plugins, routes, credentials or backend changes.
