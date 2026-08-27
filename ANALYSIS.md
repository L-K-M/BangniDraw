# ANALYSIS.md — the forward backlog

Findings from the full-project review of 2026-08-27 (originally written down
in `glm.md`), consolidated with the second-pass deep review of the same day
(`ds.md`) and the third pass (`qwen.md`, merged here and removed). Every
entry was verified in the source, not inferred from the docs; everything
marked [fix-now] or **[do]** has landed (see the cleared list); what remains
is shovel-ready work for a future LLM session, ordered small-to-large
within each section.

House rules for picking items up: read PLAN.md's relevant `docs/plan/` file
first, write the failing test before the fix, keep decision logic in
`engine/core`, and record what you learn in AGENTS.md.

## Implemented during this review session (2026-08-27)

Eight small, well-scoped PRs landed against `main` (merged PRs #55, #57,
#58/#54 supersession, #59, #60, #61, #63, #65, #66) — the first pass of the
`ds.md` [do] list. Each had CI green (`testDebugUnitTest lintDebug`),
strings in both locales (`values/` + `values-b+zh+Hans/`), no manifest
permission changes, and a pinned `engine/core` policy + test where
decision-shaped (`EraserTogglePolicyTest`). The parallel agents' PRs
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

The third pass (`qwen.md`) added five more, each through its own review
cycle to steady state:

- PR #67 — New Canvas orientation override resets with the preset
  (`rememberSaveable(selected)` re-keying) (`NewCanvasDialog.kt`). The
  override belongs to the preset it was chosen for; carrying it across
  applied a stale Landscape to a portrait row.
- PR #68 — Remember the last custom canvas size (`Prefs.lastCustomSize`,
  Custom-row pre-fill) + PR #73, its refinement: typed sizes win over the
  remembered pre-fill (`customEdited` latch; `RememberedCustomSizeContractTest`
  pins the user-edits-win shape). GLM round 1 asked the snap-vs-edits
  question; edits-win is the recorded answer.
- PR #74 — Name a palette when it is created (`PalettePolicy.createdName`,
  name dialog, unique-display-name check, `palette_name_taken` in both
  locales). GLM round-1 major caught a locale-frozen literal via the
  localized prefill; the field now starts empty with a placeholder so blank
  keeps the localizing token.
- PR #75 — Hover ring tinted with the brush colour (`HoverCursorSpec.ink`,
  explicit at every site; opaque hint ring inside the size ring)
  (`HoverCursorPolicy.kt`, `HoverCursor.kt`).
- PR #76 — Cache decoded shelf thumbnails (16 MiB `LruCache` keyed by
  `StudioThumbnailKey`, segment-boundary eviction on delete, put-then-
  verify against a racing delete) (`StudioViewModel.kt`, `StudioScreen.kt`).
  Clears the "no memory cache across scroll" note below.

#64 (pipette centering) was closed in favour of #66's better design — same
outcome, one landed.

These are cleared from the shovel-ready list; the remaining open items are
#6 (curve plot) and #8 (custom paper) — #7 landed via the parallel #68 —
plus the larger-idea backlog. The parallel agent's PR #62 (Studio delete/
rename/duplicate outcomes, `ProjectStore.delete` returning Boolean) landed
the honest-delete fix; my narrower #56 closed in its favour. No blocking
GLM feedback remains on any merged PR; #54 and #56 were closed as
superseded to avoid duplicate review load.

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

Only #8 (custom paper colour) from the original list is still untouched —
#6 (curve plot) is in flight as PR #77, #7 landed as PR #68 with #73 as its
typed-input refinement. Order: #8 (wiring the HSV picker through
`NewCanvasDialog`) after #77 lands, then the third-pass additions below.

8. **Custom paper colour at creation.** (`NewCanvasDialog` sixth swatch,
   `onCreate` passes the colour through)

Third-pass additions (from `qwen.md`, verified in source, none taken yet):

9. **No feedback when a stroke is refused because the document is busy.**
   `beginStrokeTool` returns null while `CanvasActionGate` is busy; the pen
   moves, nothing lands, nothing is said. The `strokeLayerNotice` toast
   path already exists for locked layers — one more reason string closes
   the loop. (`CanvasViewModel.beginStrokeTool`, `CanvasScreen`.)
10. **Keyboard shortcuts are undiscoverable.** `CanvasShortcuts` has a real
    table (Z/Y, brackets, B/E/S/G/I, Tab, L/C, Alt) and nothing in the UI
    mentions it; DeX/Chromebook users get zero hints. The table is data —
    a Shortcuts section in Settings or About is nearly free.
    (`ui/home/SettingsSheet.kt`, `engine/core/CanvasShortcut.kt`.)
11. **The mixing dish's slider position is not durable.** `DishState.t`
    resets to 0.5 whenever the panel recomposes from scratch, and `Prefs`
    stores only the wells. Persist `t` beside them — it is exactly the kind
    of durable setting §12 describes — or key the slider on the wells so a
    well change recentres it deliberately. (`ColorPanel.kt`, `Prefs.kt`.)
12. **Clear layer is destructive without confirmation while merge/flatten
    have dialogs.** Undo covers it, which is why this is a deliberate-call
    item rather than a defect: "Clear" sits one menu tap from a fat finger
    and the app already pays for confirmation dialogs on the two
    scarier-but-recoverable actions. Either confirm or record the asymmetry
    as deliberate in AGENTS.md. (`LayerPanel`'s `LayerMenu`.)

## Larger ideas (need product judgment or a proposal doc)

Third-pass ordering take: of the post-v1 backlog in `12-roadmap.md` §5,
**symmetry** is the highest delight-per-effort (size S, every engine seam
exists, one journal entry) — it changes what people draw, not just how.
Then image import as layer/reference, then gradient fill.

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
- **Brush-size by pen drag (two-finger hold).** Hold two fingers, drag the
  pen vertically, watch the hover ring grow — the arbiter already
  distinguishes nav from stroke; a hold-without-move state is the new
  piece. Risky enough to need a proposal doc before building
  (`GestureArbiter`, `HoverCursor`).
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
- **"Continue" launcher shortcut.** `ShortcutManager` dynamic shortcut to
  the most recently updated painting — long-press the launcher icon, jump
  straight back in. No permissions; the Studio already knows the newest id.
  Pairs with the one-tap promise. (`StudioViewModel`, `BangniApp`.)
- **HSV picker sized to its panel.** `PICKER_SIZE` is a fixed 220 dp in a
  panel that reaches 320 dp on tablets; a width-scaled picker (with a cap)
  is a quiet aesthetic win. (`ColorPanel.kt`.)
- **Panel close affordance.** Dismissal is scrim-tap or Back; a side sheet
  on a tablet reads as permanent furniture to a first-time user. A close
  icon in the panel header costs ~12 lines. (`ColorPanel`, `LayerPanel`,
  `BrushSettingsSheet` headers.)
- **Checkerboard that scales with zoom.** The transparent-paper checker is
  a fixed 8 dp; at 64× it becomes a shout. Banding `checkerPx` by
  `view.scale` (8/16/32) keeps it a texture — `setCanvasAppearance` already
  takes it as a parameter. (`EngineSession`, `CanvasScreen`.)
- **Dock rail rounded.** Side rails are rounded rectangles; the DOCK variant
  is a full-width slab. Rounding its top corners makes the dock read as the
  same object in a different posture. (`ToolRail.Dock`.)
- **A display face for the Studio's name and panel headers.** The theme is
  default Material; one CC0/OFL display font would give the app a voice
  without touching the canvas. Needs provenance recorded in AGENTS.md
  (third-party assets rule). (`ui/theme/Type.kt`.)
- **Shelf cards with a whisper of elevation.** Thumbnail cells are bordered
  and flat; 1 dp elevation + the existing radius gives the paintings
  physicality, especially in dark mode. The "New painting" cell could take
  a `primaryContainer`-tinted fill for the same reason.
  (`StudioScreen.PaintingCell`, `NewPaintingCell`.)

## New observations from the second pass (durable notes)

- **Two prefs shipped unreachable and are now wired**: `snapRightAngles`
  (property + KDoc existed, no key/UI — #61) and `eraserEndPreset` (key +
  collector existed, no UI — #63). Pattern to watch: a pref whose only
  writer is a settings row that never shipped reads as dead code in the
  canvas layer.
- **Mid-session gallery sync cost**: `GallerySyncDecision`'s 30 s floor is
  the only throttle on full-canvas flatten + `Bitmap.compress(PNG)` during
  active painting (ceiling checkpoints every 90 s). Acceptable per plan
  §9.3; revisit with the GL band flatten. Related: `syncStale` fires from
  `refresh()` — the painting just edited is stale by construction, so the
  flatten runs while the user is still deciding what to open. It is on IO
  and guarded now (a corrupt painting skips, no crash); delaying the sweep
  a few seconds after the listing lands would move it off the user's
  immediate attention. Superseded by the GL band flatten either way.
- **`MixingDish.gradient`** recomputes 9 Mixbox mixes per color-panel
  recomposition — wrap in `remember` when the dish section is next touched.
- **`BrushSettingsSheet` preview** allocates a bitmap per 50 ms debounce
  tick while sliders drag — bounded, but reusable bitmaps would remove the
  GC churn.
- **Studio thumbnails** decode at 512 px with no display-size sampling;
  the memory cache across scroll landed with PR #76, so what remains is
  optional `inSampleSize` decoding to the cell size (halves the cache's
  byte footprint for the same hit rate).
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

The third pass additionally verified: the front-buffered drain/release
protocol and the `pendingStrokeFallback` CAS chain in `EngineSession`;
`SandwichCache`'s ping-pong allocation and availability flags;
`CompositePass` page-run batching with VBO orphaning; the `HalfTurn`
neutralization symmetry between `CanvasRenderer` and `EngineSession`;
history load/recovery/replay and the `nextName` floor (3a/3b); string
completeness between `values/` and `values-b+zh+Hans/` (the six keys absent
from zh are `translatable="false"` by design); the RMW capture/restore
ordering through `ResolveCurrent`; and that `CanvasContent`'s
recomposition-per-navigation-sample is a measured trade (no stroke runs
during navigation), not a defect — see REVIEW.md R-100's refutation class.
