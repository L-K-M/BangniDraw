# ANALYSIS.md — the forward backlog

Findings from the full-project review of 2026-08-27 (originally written down
in `glm.md`), consolidated with the second-pass deep review of the same day
(`ds.md`). Every entry was verified in the source, not inferred from the
docs; everything marked [fix-now] or **[do]** has landed (see the cleared
list); what remains is shovel-ready work for a future LLM session, ordered
small-to-large within each section.

House rules for picking items up: read PLAN.md's relevant `docs/plan/` file
first, write the failing test before the fix, keep decision logic in
`engine/core`, and record what you learn in AGENTS.md.

## Implemented during this review session (2026-08-27)

Eight small, well-scoped PRs landed against `main` (merged PRs #55, #57,
#58/#54 supersession, #59, #60, #61, #63, #65, #66) — the first pass of the
`ds.md` [do] list. Each had CI green (`testDebugUnitTest lintDebug`),
strings in both locales (`values/` + `values-b+zh+Hans/`), no manifest
permission changes, and a pinned `engine/core` policy + test where
decision-shaped (`EraserTogglePolicyTest`). The parallel agent's PRs
(#58 share chooser, #62 studio outcomes) landed independently and cover
the same underlying items (share chooser title + Studio delete outcome).
Response notes from GLM 5.3 reviews are recorded below per PR (no blocking
feedback remains; only minor suggestions responded to with code changes or
refuted with evidence).

- PR #55 — Top-strip layer badge inset (`BADGE_INSET` 2 dp, `BADGE_RING` 1 dp,
  shared `stripBackground` token) (`TopStrip.kt`).
- PR #57 — Eraser slot long-press toggle (`EraserTogglePolicy` + `ToolRail`
  `combinedClickable` + provider-based haptic silencing) (`ToolRail.kt`,
  `CanvasViewModel`, `engine/core/EraserTogglePolicy.kt`). GLM minor: double
  long-press haptic → dropped the explicit one; the built-in (provider-
  gated) haptic remains.
- PR #58 (parallel, supersedes #54) — Canvas share chooser title (plus Studio
  `Untitled` fallback).
- PR #59 — Fill progress card dock clearance (`fillCardBottomPadding` per
  `railMode`) (`CanvasScreen.kt`).
- PR #60 — Layer menu reorder items (`ReorderItem`, `reorderActions` guard)
  (`LayerPanel.kt`). GLM minor: section rendered with no moves available →
  guarded with `reorderActions.isNotEmpty()`.
- PR #61 — Snap-right-angles preference (`Prefs.snapRightAngles`, Settings
  switch, `CanvasScreen` handler wiring) (`Prefs.kt`, `CanvasViewModel.kt`,
  `CanvasScreen.kt`). GLM flagged a missing collector; applied.
- PR #63 — Eraser-end preset preference (`Prefs.eraserEndPreset`, Settings
  choice) (`Prefs.kt`, `SettingsSheet.kt`, `StudioScreen.kt`). GLM findings
  refuted: the "duplicated default" is one `BrushPresets.HARD_ERASER_ID`
  constant referenced twice; the "untyped String" is the open preset-id
  contract (PLAN decision 9) with `resolveEraserPreset`'s graceful fallback.
- PR #65 — Color panel hex/RGB fields preserve edits (`lastReflected`
  + `syncSiblings` + `emit`) (`ColorPanel.kt`). GLM major: stale sibling
  drafts → applied the exact `syncSiblings` fix the reviewer proposed.
- PR #66 — Hover-cursor centering (pipette ring with clear center) (`HoverCursor.kt`).
  GLM flagged crosshair covering the sampled pixel; removed `drawCrosshair`
  from the pipette path (retained for the ring-cursor brush path).

These are cleared from the shovel-ready list; the remaining open items are
#6 (curve plot), #7 (custom size persistence), and #8 (custom paper), plus
the larger-idea backlog. The parallel agent's PR #62 (Studio delete/rename/
duplicate outcomes, `ProjectStore.delete` returning Boolean) landed the
honest-delete fix; my narrower #56 closed in its favour. No blocking GLM
feedback remains on any merged PR; #54 and #56 were closed as superseded
to avoid duplicate review load.

## Cleared (do not re-open)

- Tool-rail icon collisions + divider indices — PR #22.
- Zero per-frame allocation in `CanvasRenderer.visibleCanvasRect` — PR #23.
- Eyedropper read throttling (`EyedropperSampleGate`) — PR #30.
- Live zoom/angle readout during navigation (`onNavigateActive`) — PR #35.
- Studio empty state on every width — PR #33 (parallel work).
- RGB fields stack under font scaling — PR #39 (parallel work). The
  *cursor-reset* problem below is separate — FIXED by PR #65 (`ColorPanel`'s
  `lastReflected` + `syncSiblings` pattern). See `ds.md` §3.1.

## Shovel-ready (remaining)

Only #6 (curve plot), #7 (custom size memory), #8 (custom paper colour)
from the original list are still open; everything else from `ds.md` §3.1
landed in the eight PRs above. Order: #6 < #7 < #8 (the curve editor needs no
new APIs; the size preference is a one-file DataStore addition; the custom
paper requires wiring the HSV picker through `NewCanvasDialog`).

6. **Draw the pressure curve.** (`ui/canvas/BrushSettingsSheet.kt`,
   `engine/core/Curve.kt`)
7. **Remember the last custom size.** (`Prefs` + `NewCanvasDialog` + `Prefs` key)
8. **Custom paper colour at creation.** (`NewCanvasDialog` sixth swatch,
   `onCreate` passes the colour through)

## Larger ideas (need product judgment or a proposal doc)

- **GL band flatten** (`03-canvas-engine.md` §10.4): supersedes both CPU
  flatten call sites (gallery sync ~every 30 s, thumbnail at checkpoints,
  share/export) and removes the ~128 MiB peak ISSUES.md records. This is the
  single biggest performance item left in v1.
- **Brush "save as new preset."** Users can mutate the seven built-ins but
  cannot fork one into a new rail slot; `BrushPresetStore` already supports
  arbitrary ids and `railOrder` sorts unknown ids after built-ins. Needs
  rail-overflow UX (the FULL rail budget is exactly 10 slots) and a name
  dialog.
- **History list UI.** The journal is capped and persisted but reachable
  only as linear undo/redo; PR #31 (open at the time of writing) adds an
  undo-depth readout on long-press. A sheet listing recent entries (kind,
  layer, step count against the cap — `readyState` already computes the
  numbers) is the natural next step.
- **Pressure "scratchpad" in Settings.** The pressure preference is three
  radio buttons; a small scratch canvas drawing a test stroke under the
  current curve (pure Compose Canvas replaying a few saved samples) would
  make the choice feel real.
- **Mixing-dish drag-to-mix.** Let a drag on the dish strip smear the mix
  continuously (`MixingDish.gradient` already exists) — a smear gesture
  instead of a form-control slider.
- **Canvas edge resistance (rubber-band overshoot on pan).** Pure
  `ViewTransform` policy; makes the canvas feel physical. Keep Meltorama
  math in sync if either side changes (AGENTS.md rule).
- **Hue-milestone haptic on eyedropper drag.** The `HueMilestone` helper
  exists for the picker ring; tick when a dragged pick crosses a hue band.
- **Ambient Studio shelf.** The newest painting's `thumb.png` rendered
  large and dim behind the app name. One image, big polish.
- **Session playback from the undo journal.** The journal already holds
  every tile delta; replaying entries at intervals is a cheap precursor to
  the planned time-lapse feature.
- **Composition guides (rule of thirds + center).** A toggleable, purely
  visual overlay from the canvas overflow menu; no document change, no
  journal entry, ~40 lines. The cheapest high-value drawing aid not yet
  shipped.
- **Actual-size (100 %) zoom action.** Reset-to-fit exists; the other anchor
  for pixel work is scale = 1. A long-press on the reset pill (or a second
  pill state) jumping to 100 % at the view center is pure `ViewTransform`
  math.
- **Mouse wheel zoom at the pointer.** `CanvasTouchHandler` returns false
  for `ACTION_SCROLL`; a wheel → `NavigationStep`-style zoom anchored at
  the pointer serves trackpad/keyboard-cover users.
- **Layer "solo" via eye long-press.** Long-press the visibility eye hides
  every other layer (view filter, journaled per-layer as ordinary
  visibility entries or grouped — needs a small design call).
- **Export current layer as PNG.** `CpuFlatten` restricted to one layer plus
  `ImageEncode`'s alpha-preserving PNG; asset makers' favourite.
- **Clear painting (journaled).** A blank restart today means delete +
  create; a whole-document clear as one journal entry is honest and
  undoable.

## New observations from the second pass (durable notes)

- **Two prefs shipped unreachable and are now wired**: `snapRightAngles`
  (property + KDoc existed, no key/UI — #61) and `eraserEndPreset` (key +
  collector existed, no UI — #63). Pattern to watch: a pref whose only
  writer is a settings row that never shipped reads as dead code in the
  canvas layer.
- **Mid-session gallery sync cost**: `GallerySyncDecision`'s 30 s floor is
  the only throttle on full-canvas flatten + `Bitmap.compress(PNG)` during
  active painting (ceiling checkpoints every 90 s). Acceptable per plan
  §9.3; revisit with the GL band flatten.
- **`MixingDish.gradient`** recomputes 9 Mixbox mixes per color-panel
  recomposition — wrap in `remember` when the dish section is next touched.
- **`BrushSettingsSheet` preview** allocates a bitmap per 50 ms debounce
  tick while sliders drag — bounded, but reusable bitmaps would remove the
  GC churn.
- **Studio thumbnails** decode at 512 px with no display-size sampling or
  memory cache across scroll — bounded by LazyVerticalGrid, fine at shelf
  sizes.
- **Reset pill vs fill card** shared the bottom-centre lane (fixed by #59's
  per-mode clearance); any new bottom-centre transient must take a mode
  clearance too.
- **AAPT2 daemon startup failures** (`processDebugResources`) were
  transient in this sandbox; `--stop` + retry clears them. CI is the
  source of truth.

## Verified clean (do not re-litigate)

Checked during the original review and found correct: the front-buffered drain
and tail-rect folding (§8.1); nav-slot compaction on `ACTION_POINTER_UP`;
per-pointer axis reads (no `actionIndex` bug); the stabilizer's bounded
catch-up; palm rejection's `stylusNear` feed and cancel-path stylus
clearing; the `MotionEventPredictor` ownership decision (AGENTS.md); zoom
readout liveness (`view` is snapshot state — see REVIEW.md R-097); and the
whole ISSUES.md review-2 list.

The second pass additionally verified: the touch path stays allocation-free
(scratch arrays, `DabRing`, no per-sample lambdas — PR #51's work holds);
the layer-panel thumbnail poll is policy-gated and stops when the panel
closes; the quick-palette popover's auto-dismiss honors screen-reader state
and scales by the recommended a11y timeout; the closing scrim's focus and
a11y gating lives outside the gated chrome box; and the theme (warm paper
light / slate dark, saffron accent) is coherent — no aesthetic changes
recommended.
