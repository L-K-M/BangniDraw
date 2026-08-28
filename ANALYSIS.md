# ANALYSIS.md — the forward backlog

Findings from the full-project review of 2026-08-27 (originally written down
in `glm.md`), consolidated with the second-pass deep review of the same day
(`ds.md`) and the third pass (`qwen.md`, merged here and removed), plus the
fourth deep review of 2026-08-28 (`muse.md`, 8d638d3, 217 Kt files, 14-year-old
lens), the `hy3.md` review, and the fifth pass (`glm.md`, 2026-08-28,
v1.1.0 @ `8d638d3`, focused on the post-#58 features: watercolor, Water tool,
tracing references, Chinese ink, spray can, rail budget, composition guides),
and the sixth pass (`k3.md`, 2026-08-28, v1.1.0).
Every entry was verified in the source, not inferred from the docs;
everything marked [fix-now] or **[do]** has landed (see the cleared list);
what remains is shovel-ready work for a future LLM session, ordered
small-to-large within each section.

House rules for picking items up: read PLAN.md's relevant `docs/plan/` file
first, write the failing test before the fix, keep decision logic in
`engine/core`, and record what you learn in AGENTS.md. **Before picking any
item, re-check the open-PR list — several backlog items below are currently
in flight and must not be re-picked.**

## Implemented during this review session (2026-08-28) — in review

Two shovel-ready fixes from `muse.md` landed as separate PRs, each with CI
green (`testDebugUnitTest lintDebug`), strings in both locales, no manifest
permission change, and a pure `engine/core` test where decision-shaped. Both
are currently **open** (hybrid review bootstrap), so they are not yet in the
cleared list below; they are recorded here so a future session does not
re-open them.

- PR #104 — **Persist mixing dish slider position** (`DishState.t` durable)
  (`data/Prefs.kt:173` new `KEY_DISH_T`, `dish` Flow, `setDishT` debounced;
  `ui/canvas/CanvasViewModel.kt:1522` `setDishT` + `dishTJob` 200 ms trailing
  debounce flushed on `onCleared` via `appScope`; `ui/canvas/ColorPanel.kt:88`
  `onDishTChanged` through `MixingDishControls:718` using `state.dish.t`
  directly; `ui/canvas/CanvasScreen.kt:1602` wiring; `engine/core/DishStateTest.kt`
  pins default, rejection, copy preservation). Fixes `ANALYSIS#11` /
  `muse.md#1.1`. GLM round-1 major caught per-frame DataStore writes →
  debounced; minor caught dead `setDish` helper and misnamed test → fixed.
  Round-2 flagged redundant NaN double-guard → tidied.

- PR #105 — **Confirm destructive clear-layer action** (`CanvasDialog.ClearLayer`)
  (`engine/core/CanvasUiPolicy.kt:13`; `ui/canvas/CanvasScreen.kt:1792`
  `CanvasDialogHost` confirmation with `layer_clear_title/body`;
  `ui/canvas/LayerPanel.kt:269` overflow Clear now requests dialog, `hint`
  LAST_LAYER click does too, dead `onClear` param removed;
  `res/values/strings.xml:283` + `values-b+zh+Hans:255` new strings;
  `engine/core/CanvasUiStateTest.kt:90` pins mid-stroke parking). Fixes
  `ANALYSIS#12` / `muse.md#1.2`. GLM round-1 flagged dead `onClear` param
  and noted no additional `values-*` locales exist to add strings to →
  applied.

These are cleared from the shovel-ready list in this document (see remaining
below); the PRs stay open until steady-state per `CLAUDE.md` (no blocking
feedback in review, or two consecutive hybrid audits with no relevant findings,
or two consecutive GLM timeouts). Do not re-implement #11 or #12 while #104
and #105 are open — review them instead.

## Implemented during the fifth pass (2026-08-28, glm.md) — in review

Five more shovel-ready items, each its own branch and PR, each with CI green
(`testDebugUnitTest lintDebug`), strings in both locales where user-visible,
and a JVM test where anything pure is pinned. All were verified red-first
where a test could express the failure. Do not re-pick these while their PRs
are open.

- PR #119 — **Redo long-press history readout** (`HistoryJournal.redoDepth`
  + `UiState.redoSteps` + the undo button's `combinedClickable` shape mirrored
  on redo; `canvas_redo_readout` plural, both locales). GLM round 1: round
  ripple + `onLongClickLabel` applied to both buttons (undo shipped with the
  same square ripple); the missing-other-locales finding refuted — the app
  ships exactly two locale directories. glm.md §B.1.1.
- PR #122 — **Notify when a stroke is refused because the document is busy**
  (`noteStrokeRefused()` rides the `strokeLayerNotice` toast channel;
  `canvas_busy` string; `StrokeRefusalContractTest`). GLM round 1 asked for a
  behavioral test — declined with evidence (Hilt/`Context` constructor, JVM-
  only suite rule, and the established source-contract pattern), but the
  fragility half was applied: assertions scope to whole function bodies, not
  fixed character windows. ANALYSIS #9 / glm.md §A.9.
- PR #125 — **Keyboard shortcuts listed in Settings** (`CanvasShortcutCatalog`
  in `engine/core`, cross-checked against `CanvasShortcuts.resolve` by
  `CanvasShortcutCatalogTest` so the help cannot drift from behaviour;
  Settings section with chord chips; nine strings, both locales). GLM round 1:
  set-comparison test hardening applied. ANALYSIS #10 / glm.md §A.10.
- PR #130 — **Brush preview buffer reuse** (`BrushPreview.RenderBuffer` +
  ping-ponged `PreviewTarget`s: render mutates only the back bitmap, publish
  is a main-thread swap, a `Mutex` serializes cancelled predecessors;
  `BrushPreviewTest` pins reused-buffer ≡ fresh-render). GLM round 1 critical
  (draw/render races) applied and strengthened beyond the suggestion. Clears
  the second-pass preview-churn note. glm.md §B.2.5.
- PR #131 — **Custom paper colour at creation** (sixth swatch + nested HSV
  picker over `HsvChannel`, committing through the same
  `onCreate(size, paperColor)` seam; `PaperSwatchCustomDefault` theme color;
  `CustomPaperContractTest`). The stale "waits for step 7" comment is gone
  with it. GLM round 1: tolerant contract + distinct preview label applied;
  swatch-geometry finding refuted (`FlowRow` wraps; no count math exists).
  ANALYSIS #8 / glm.md §A.8 + §B.1.3.

Parallel-agent PRs that touch the same backlog (recorded so this list stays
honest): #117 transparent-paper hint (muse #15 adjacent). (#111, #120,
#132, #135/#137 below belong to the sixth pass, k3.md.)

## Implemented during the sixth pass (2026-08-28, k3.md) — in review

Four PRs, each with CI green (`testDebugUnitTest lintDebug`), strings in both
locales where user-visible, and pure tests where decision-shaped. All reached
a GLM round with **0 actionable suggestions** after the recorded follow-ups.
Do not re-pick while open.

- PR #111 — **Pigment wash rail glyph** (k3 §1.1; `BrushToolGlyph.PIGMENT_WASH`
  had shipped mapped to the Water tool's `Icons.Filled.WaterDrop`, two
  droplets in one FULL rail). New repo-owned `ToolGlyphs.PigmentWash` swash;
  `ToolIconContractTest` now pins per-glyph distinctness, a droplet ban for
  every brush mapping, and non-vacuous regex capture. GLM rounds 1–4 were all
  test-hardening and were all applied. AGENTS.md's glyph rule records the
  droplet as the Water tool's.
- PR #120 — **Panel close affordance** (shovel-ready #20 / larger idea): a
  shared `PanelHeader`/`PanelCloseButton` for the colour, brush, fill and RMW
  sheets plus the layer panel's header; `panel_close` in both locales;
  `PanelCloseContractTest` walks each sheet call site's own argument region.
  GLM rounds applied: shared button, per-call-site check, window bound at the
  enclosing function.
- PR #132 — **Actual-size (100 %) zoom** on the reset pill's long-press
  (larger idea): `ActualSizePolicy` (pure, tested) computes `1 / fit.scale`
  clamped to the pinch limits, anchored at the view centre, rotation zeroed;
  the pill is a `Surface` with `combinedClickable` (a nested button would fire
  on the long-press release). GLM round 1 applied (ripple clip, haptic
  double-fire gate, boundary test, KDoc clamp note); round 2 was clean.
- PR #143 — **HSV picker sized to its panel** (larger idea): `pickerSizeFor`
  caps at 280 dp instead of the fixed 220 dp, with behavioral tests including
  the height-constrained arm.

Closed as duplicates of earlier in-flight PRs (recorded so the reasoning is
not lost; do not reopen): **#135** (custom paper colour — duplicate of #131,
whose `paperIsCustom` flag and float-channel saver are the cleaner reads of
the same item) and **#137** (shortcut legend — duplicate of #125, same
single-source-verified-against-`resolve` design; #125's row-per-binding
layout moots this one's merged-row test). Lesson recorded below under
sixth-pass notes: the open-PR list moved while the pass ran.


## Implemented during review session (2026-08-27)

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
- Mixing dish slider position not durable — PR #104 (open, in review).
- Clear layer destructive without confirmation — PR #105 (open, in review).
- Pressure curve editor (#6) — landed in v1.1.0: `CurveEditor` ships in
  `BrushSettingsSheet` for every pressure curve ("Draw pressure curves",
  CHANGELOG 1.1.0).
- Composition guides — landed via PR #98 (top-strip overflow toggle +
  `Prefs.compositionGuideVisibility`).
- Redo long-press readout — PR #119 (open, in review).
- Stroke-refused feedback (#9) — PR #122 (open, in review).
- Shortcuts discoverability (#10) — PR #125 (open, in review).
- Brush preview buffer churn — PR #130 (open, in review).
- Custom paper colour at creation (#8) — PR #131 (open, in review).

## Shovel-ready (remaining)

**Every numbered original is now landed or in review** (#6 via v1.1.0's
`CurveEditor` — the old "in flight as PR #77" line was stale; #7 via #68/#73;
#8 → #131, #9 → #122, #10 → #125, #11 → #104, #12 → #105). What remains
below is the third-pass list (annotated where in review), the fourth-pass
(`muse.md`) additions, and the fifth-pass (`glm.md`) additions. Order is
small-to-large within each block.

Third-pass additions (from `qwen.md`, verified in source; #11/#12 are in
review as #104/#105 — do not re-pick):

9. **No feedback when a stroke is refused because the document is busy.**
   `beginStrokeTool` returns null while `CanvasActionGate` is busy; the pen
   moves, nothing lands, nothing is said. The `strokeLayerNotice` toast
   path already exists for locked layers — one more reason string closes
   the loop. (`CanvasViewModel.beginStrokeTool`, `CanvasScreen`.)
   **→ in review as PR #122; do not re-pick.**
10. **Keyboard shortcuts are undiscoverable.** `CanvasShortcuts` has a real
    table (Z/Y, brackets, B/E/S/G/I, Tab, L/C, Alt) and nothing in the UI
    mentions it; DeX/Chromebook users get zero hints. The table is data —
    a Shortcuts section in Settings or About is nearly free.
    (`ui/home/SettingsSheet.kt`, `engine/core/CanvasShortcut.kt`.)
    **→ in review as PR #125; do not re-pick.**
<!-- 11 and 12 are now PR #104 and #105 — see Implemented 2026-08-28 above. -->

Fourth-pass additions (from `muse.md:1` + `muse.md:5`/`muse.md:6`, verified
in source, ordered small-to-large, no duplicates with above):

13. **Left-handed ledge toggle text clips at 200 % font scale.** `SliderLedge.kt:142`
    `Text(maxLines=1)` in the `88.dp` toggle truncates zh-Hans
    "不透明度" at large scale instead of falling back to icon+description as
    `ToolRail` does. (`ui/canvas/SliderLedge.kt`, `R.string.brush_opacity`.)
14. **Layer opacity inline slider can be stranded when document goes busy.**
    `LayerPanel.kt:146` `LaunchedEffect(documentBusy)` clears `draggedId` but
    not `opacityLayer`; a preview that enqueues a `CanvasDocumentAction` can
    leave the row in editing mode with no commit path. Clear `opacityLayer`
    and call `onOpacityFinished()`. (`ui/canvas/LayerPanel.kt`.)
15. **Transparent-paper swatch reads as gray, not see-through.** `NewCanvasDialog.kt:386`
    and `LayerPanel.kt:814` render `surfaceVariant` with "∅". A child reads
    gray paper. Replace with a 2×2 checker thumbnail + label "See-through" /
    "透明". (`ui/home/NewCanvasDialog.kt`, `ui/theme/Color.kt`.)
16. **Colour panel hex field commits mid-typing "FFF" → white.** `ColorPanel.kt:536`
    `ColorFields` now preserves cursor (`lastReflected`) but typing "F" → "FF" →
    "FFF" parses as `#FFFFFF` on the second char and commits. Gate commit on
    `onFocusChanged` blur or add 300 ms debounce per field. (`ui/canvas/ColorPanel.kt`.)
17. **Rename painting allows duplicate titles.** `StudioScreen.kt:599` accepts any
    non-blank, `StudioViewModel.rename` writes verbatim; shelf shows two "Cat"s
    with only relative time to disambiguate. Add uniqueness guard or visual
    suffix, or show subtitle with id. (`ui/home/StudioScreen.kt`.)
18. **BrushSettingsSheet knot sliders recompose whole sheet per pixel.** `BrushSettingsSheet.kt:612`
    four `SettingSlider` for `p0..p3` call `onPresetChanged` per frame, no
    debounce. Throttle to `onValueChangeFinished` for the model or debounce
    the sheet recomposition. (`ui/canvas/BrushSettingsSheet.kt`.)
19. **Studio shelf cards flat; dock is a slab.** `StudioScreen.kt:355` bordered
    flat cards; `ToolRail.Dock` full-width slab unrounded. Add 1 dp elevation +
    radius 12 and round dock's top corners so it reads as one object.
    (`ui/home/StudioScreen.kt`, `ui/canvas/ToolRail.kt`.)
20. **Panel close affordance absent on tablet.** Dismiss is scrim-tap or Back;
    side sheet reads as permanent furniture. Add X in header (~12 lines each:
    `ColorPanel`, `LayerPanel`, `BrushSettingsSheet`). (`ui/canvas/PanelHost.kt`.)
    **→ in review as PR #120 (parallel agent); do not re-pick.**
21. **Focus handle tiny and faint.** `FocusHandle.kt` 6×48 dp at 35 % opacity.
    Bump to 8×48, 55 % and add `semantics { onClick }` "Show controls".
    The first-run hint never mentions focus mode either (k3 §4.8) — a
    one-time focus hint is the other half of the discoverability fix.
22. **Slider ledge mirrors visually but not for touch when left-handed.**
    `SliderLedge.kt:222` `mirrored()` does `scaleX = -1f` on `ThinSlider`
    for `Hand.LEFT`; that flips paint, not `pointerInput` coords, so drag
    direction inverts. Remove the graphics-layer hack (keep sliders LTR) or
    mirror via value inversion (`1 - value`). (`ui/canvas/SliderLedge.kt`,
    `ui/canvas/ThinSlider.kt`.) Needs device verification before merging.

Fifth-pass additions (from `glm.md`, verified in source, ordered small-to-large):

23. **No blur-tool shortcut.** `CanvasShortcuts` maps S→smudge, W→water,
    B/E/G/I… but blur has no key. Add one or record the omission as
    deliberate beside the table — bundle with future shortcut work rather
    than alone. (`engine/core/CanvasShortcut.kt`, and `CanvasShortcutCatalog`
    once #125 lands.)
24. **Water tool explains itself nowhere.** It re-wets and carries existing
    paint — the most magical tool for a young painter — and its sheet is
    three bare sliders. One `bodySmall` hint line under the title, the
    pattern `TracingReferencePanel` and the eyedropper sheet already use.
    (`ui/canvas/RmwSettingsSheet.kt` `WaterSettingsSheet`.)
25. **`BrushToolGlyph.PAINTBRUSH` is unreachable.** Since 1.1.0 the
    `builtin.paintbrush` preset carries `icon: "watercolor"` (it became the
    Watercolor brush) and no shipped preset or code path sets
    `icon: "paintbrush"` — verified across all `assets/brushes/*.json`. The
    enum entry plus its Material `Brush` icon mapping in `ToolRail.kt:845`
    is dead code reachable only from hand-authored user JSON. Delete it
    (update the glyph policy test) or document it as user-JSON-only;
    coordinate with #111's glyph work. XS.
26. **Studio thumbnails `inSampleSize`** (carried second-pass note, still
    open): decode to the cell size to halve the cache's byte footprint for
    the same hit rate. (`ui/home/StudioViewModel.kt`.)
27. **Watercolor CPU preview in BrushSettingsSheet.** The Watercolor brush —
    one of the five core rail brushes — shows a hint string instead of a
    preview (`BrushSettingsSheet` skips rendering for every
    `watercolor != null` preset). A CPU approximation is feasible without
    GL: a soft horizontal wash band through the existing `DabStamp`/
    `Composite` CPU path with the behavior's falloff, a speckle pass for
    granulation, a darker rim. M size, high kid-value, JVM-testable.
    (`engine/core/BrushPreview.kt`, `ui/canvas/BrushSettingsSheet.kt`.)
    (Also k3 §3.4.)
28. **`brushPresetName` falls through to the raw `@string/…` token** (k3 §1.2).
    Every built-in id is registered in the `when` today, but the
    `else -> preset.name` arm displays the stored name verbatim — and
    built-ins store `@string/preset_*` — so the first future preset that
    forgets to register shows users a literal token. Pin it: a test that
    every id in `assets/brushes/*.json` resolves (the assets are already
    read by `BrushPresetStoreTest`), or a resource-key field on the preset.
    XS. (`ui/canvas/BrushPresetName.kt`.)

## Larger ideas (need product judgment or a proposal doc)

Third-pass ordering take: of the post-v1 backlog in `12-roadmap.md` §5,
**symmetry** is the highest delight-per-effort (size S, every engine seam
exists, one journal entry) — it changes what people draw, not just how.
Then image import as layer/reference, then gradient fill. Fourth-pass
(14-year-old lens) lifts **brush "save as new preset"** and **pressure
scratchpad** higher — they make the tools feel owned and legible to a child.

- **GL band flatten** (`03-canvas-engine.md` §10.4): supersedes both CPU
  flatten call sites (gallery sync ~every 30 s, thumbnail at checkpoints,
  share/export) and removes the ~128 MiB peak ISSUES.md records. This is the
  single biggest performance item left in v1. Peak is steeper after watercolor
  wet state → raise priority.
- **Brush "save as new preset."** Users can mutate the seven built-ins but
  cannot fork one into a new rail slot; `BrushPresetStore` already supports
  arbitrary ids and `railOrder` sorts unknown ids after built-ins. Needs
  rail-overflow UX (the FULL rail budget is exactly 10 slots) and a name
  dialog. **Raised: child pressure — kid wants a second pencil.**
- **History list UI.** The journal is capped and persisted but reachable
  only as linear undo/redo; PR #31 (open at the time of writing) adds an
  undo-depth readout on long-press. A sheet listing recent entries (kind,
  layer, step count against the cap — `readyState` already computes the
  numbers) is the natural next step. Child undo-anxiety makes this higher.
- **Pressure "scratchpad" in Settings.** The pressure preference is three
  radio buttons; a small scratch canvas drawing a test stroke under the
  current curve (pure Compose Canvas replaying a few saved samples) would
  make the choice feel real. **Raised: child cannot parse "softer/linear/harder"
  without a doodle.**
- **Mixing-dish drag-to-mix.** Let a drag on the dish strip smear the mix
  continuously (`MixingDish.gradient` already exists) — a smear gesture
  instead of a form-control slider.
- **Canvas edge resistance (rubber-band overshoot on pan).** Pure
  `ViewTransform` policy; makes the canvas feel physical. Keep Meltorama
  math in sync if either side changes (AGENTS.md rule).
- **Flip-view mirror check (M, proposal first).** The classic artist's
  mistake-checker: mirror the *view* horizontally to instantly see skewed
  faces and proportions (not equivalent to the existing rotate). Sketch: a
  `mirrored` flag on `ViewTransform`, composed into the canvas matrix with
  the X mapping flipped on input, a top-strip overflow toggle; purely a view
  change — no document or journal impact, undo-free by construction. The
  transform math must keep Meltorama parity rules in mind (AGENTS.md). Also
  listed in the hy3 delight section below as "Mirror-check toggle" — same
  idea, one entry. (glm.md §B.4.11.)
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
  the planned time-lapse feature. Share value for a child.
- **Composition guides (rule of thirds + center).** A toggleable, purely
  visual overlay from the canvas overflow menu; no document change, no
  journal entry, ~40 lines. The cheapest high-value drawing aid not yet
  shipped. Done but still undiscoverable — consider canvas-hold hint.
- **Actual-size (100 %) zoom action.** Reset-to-fit exists; the other anchor
  for pixel work is scale = 1. A long-press on the reset pill (or a second
  pill state) jumping to 100 % at the view center is pure `ViewTransform`
  math. **→ in review as PR #132; do not re-pick.**
- **Mouse wheel zoom at the pointer.** `CanvasTouchHandler` returns false
  for `ACTION_SCROLL`; a wheel → `NavigationStep`-style zoom anchored at
  the pointer serves trackpad/keyboard-cover users.
- **Layer "solo" via eye long-press.** Long-press the visibility eye hides
  every other layer (view filter, journaled per-layer as ordinary
  visibility entries or grouped — needs a small design call).
- **Export current layer as PNG.** `CpuFlatten` restricted to one layer plus
  `ImageEncode`'s alpha-preserving PNG; asset makers' favourite. Kid
  youtuber loves it.
- **Clear painting (journaled).** A blank restart today means delete +
  create; a whole-document clear as one journal entry is honest and
  undoable.
- **"Continue" launcher shortcut.** `ShortcutManager` dynamic shortcut to
  the most recently updated painting — long-press the launcher icon, jump
  straight back in. No permissions; the Studio already knows the newest id.
  Pairs with the one-tap promise. (`StudioViewModel`, `BangniApp`.)
- **HSV picker sized to its panel.** `PICKER_SIZE` was a fixed 220 dp in a
  panel that reaches 320 dp on tablets. **→ in review as PR #143**
  (`pickerSizeFor`, capped at 280 dp); do not re-pick.
- **Panel close affordance.** Dismissal is scrim-tap or Back; a side sheet
  on a tablet reads as permanent furniture to a first-time user. A close
  icon in the panel header costs ~12 lines. (`ColorPanel`, `LayerPanel`,
  `BrushSettingsSheet` headers.) — also listed as shovel-ready #20.
- **Checkerboard "scales with zoom" — premise corrected (k3 §2).** The
  transparent-paper checker is *screen-space* (`gl_FragCoord / u_checkerPx`
  in `Shaders.kt`'s checker program): zoom never changes its size, so the
  banding fix this entry used to prescribe (8/16/32 dp by `view.scale`)
  would have no visible effect. The honest open question is different:
  screen-anchored cells slide under the paper during pan/zoom instead of
  moving with it; a canvas-anchored checker would track the paper but
  aliases under minification. A visual decision, not a banding fix.
  (`Shaders.kt` checker program, `EngineSession.setCanvasAppearance`.)
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
- **Mascot empty state + ambient polish (14-year-old delight).** Warm the
  Quiet Studio without repainting: newest `thumb.png` dim behind title,
  empty state with a brush-stroke cat, focus handle pulse, `NavHost`
  crossfade 150 ms, transient toast as paint blob. Keeps tokens intact.
- **Daily doodle prompt (offline, XS).** Roll of 60 prompts in
  `values/strings.xml`; Studio empty state + overflow "Today's idea". No
  network, title becomes prompt. From `muse.md#8.6`.
- **Magic ink: invisible → revealed by Water (S).** New `BrushPreset`
  "Invisible" writes alpha 0 with hue in G channel; `Water` reveals as soft
  pigment. No new tool kind, just preset + `BrushModel` flag. From
  `muse.md#8.2`.
- **Rainbow brush (XS).** Hue cycles with pressure/distance
  (`h = (h + step*d) % 360`); pure `DabGenerator` dynamics. From `muse.md#8.3`.
  **Fifth-pass correction: the XS estimate is wrong — this is blocked, not
  XS.** `09-color-and-mixing.md` §3.1 pins one colour per stroke: the stroke
  buffer accumulates alpha coverage only and the merge applies a single
  `u_color`, so per-dab hue cannot reach the merged layer without a buffer
  + merge redesign. Do not attempt as a preset trick; the split-bristle
  lanes of Chinese Ink show how deep "per-dab appearance" already reaches.
  (glm.md §B.4.14.)
- **Stamp brush: emoji/star/heart scattered (S).** Custom tip bitmap +
  jitter, 8 stencils, no new engine. From `muse.md#8.4`. **Fifth-pass note:
  shapes need alpha-masked dab textures, which is exactly the blocked
  grain/texture-loader path (CC0 provenance first — AGENTS.md rule); budget
  it as blocked-on-"Brush grains", same as glm.md §B.4.13.**
- **Shake-to-undo (XS).** `SensorManager` shake → `undo()`, haptic tick.
  From `muse.md#8.5`.
- **Tilt-the-table ink drip (M, quirky, opt-in).** Tilt device → ink pools
  via `WatercolorWetKernel` advection. From `muse.md#8.9`.
- **Time-lapse from undo journal (M).** Replay tile deltas into `MediaCodec`
  MP4. From `muse.md#8.7`.
- **Sound-of-brush (XS, opt-in, off by default).** Pitch maps to pressure
  via `AudioTrack`. From `muse.md#8.13`.
- **Tool-switch confirmation chip (k3 §4.7).** Switching to a tool hidden in
  the GROUPED rail's More menu (blur, eyedropper) leaves a highlighted "⋯"
  and no confirmation of *which* tool answered. A one-second tool-name chip
  under the top strip (the history readout's exact pattern) closes the loop
  for every tool change. (`CanvasScreen`, `ToolRail`.)

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
  *(Fixed in `ColorPanel.kt:732` via `remember(state.dish.a, state.dish.b)` — muse.md#3.8.)*
- **`BrushSettingsSheet` preview** allocates a bitmap per 50 ms debounce
  tick while sliders drag — bounded, but reusable bitmaps would remove the
  GC churn. **→ in review as PR #130 (ping-ponged targets + `RenderBuffer`);
  do not re-pick.**
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

## New observations from the fourth pass (muse.md, 2026-08-28)

- **Mixing dish t is now durable** — `Prefs` stores `t` beside wells, `dishTJob`
  debounces per-frame slider writes (200 ms) and flushes on `onCleared`. See
  PR #104. Pattern to watch: any `Slider.onValueChange` wired directly to
  DataStore will churn disk; debounce or persist on `onValueChangeFinished`.
- **Clear layer now confirms** — `CanvasDialog.ClearLayer` mirrors
  `MergeLayers`/`FlattenLayers`, parked mid-stroke via `CanvasUiPolicy`.
  See PR #105. The asymmetry "Clear needs no confirm because undo covers it"
  is now resolved as "confirm, because fat-finger on phone".
- **Slider ledge mirroring bug**: `SliderLedge.mirrored()` uses `scaleX = -1f`
  which flips paint but not pointerInput Coords. Left-handed DOCK/SHORT drags
  invert. Fix is to remove the graphics-layer hack or invert value
  (`1 - value`). Needs device verification (no lint failure).
- **Layer opacity stranded**: `LayerPanel` clears `draggedId` on `documentBusy`
  but not `opacityLayer`. A busy enqueue can strand the inline slider.
- **Transparent-paper UX**: `∅` on `surfaceVariant` reads as gray paper; a
  2×2 checker thumbnail would read as see-through.
- **Hex field still commits mid-typing**: `ColorPanel` now preserves cursor
  (`lastReflected` + `syncSiblings`) but `FFF` still parses to white on the
  second char. Gate on blur or debounce per field.
- **Rename duplicate titles**: `StudioViewModel.rename` allows duplicates;
  shelf shows two "Cat"s. Needs uniqueness guard or subtitle.
- **Knot sliders churn**: `BrushSettingsSheet` `p0..p3` recompose whole sheet
  per pixel; throttle to finished or debounce.
- **Fill eager readback**: `FillTool` eagerly composites + `glReadPixels` per
  tile before CPU scan; progressive PBO faults per `04-tools.md §7` would
  be cheaper. Device perf gate still required.
- **Watercolor retain**: 18.25 MiB grow-only scratch outside `TilePool` is
  correct per `WatercolorScratchBudget` but invisible to user; consider
  diagnostics or reclaim on long idle.
- **Quiet Studio vs child delight**: The theme is coherent and beautiful for
  an adult studio; a child sees "settings app". One illustration (mascot
  empty state), one saturated accent beyond saffron (ink ring already tinted),
  and rounded dock soften without betraying the palette. Recorded as
  larger-idea "Mascot empty state + ambient polish".

## New observations from the fifth pass (glm.md, 2026-08-28, v1.1.0)

- **Watercolor per-dab GPU cost is the top device-perf gate for 1.1.x.**
  `WatercolorPass.stamp` executes, for *every dab*: a color-source region
  copy (per-tile blits), a wet-source copy, a wet-update draw, a per-key wet
  backup (two blits through scratch per touched tile), the tile-reservation
  loops, then per-tile color draws and a per-tile wet blit write — roughly
  6–12 FBO bind/draw/blit ops per dab, sequential by design (each dab reads
  the previous dab's wet output, which is what makes water flow believable).
  The core Watercolor preset has `spacing = 0.2`·radius: at the default 40 px
  size that is a dab every 4 px of travel; a fast two-second stroke is
  hundreds of dabs. Not a bug — the plan's "one direct RMW update per
  accepted dab" is exactly what shipped — but no device has ever run it
  (AGENTS.md records no device was available for v1.0.0 acceptance). **Measure
  first.** If it misses the §2 frame budget on mid-range hardware, the
  promising directions: (a) extend the `hasColorContent` gate to Water (it
  currently only checks `waterLoad·flow > 0`), (b) coalesce the two source
  copies, (c) raise the Water tool's effective spacing. Blind optimization
  before a measurement violates the repo's own rules. (glm.md §B.2.4.)
- **The `builtin.paintbrush` preset *is* the Watercolor brush now** (re-keyed
  `icon: "watercolor"`, name `@string/preset_paintbrush` = "Watercolor"/
  水彩笔 in 1.1.0). Nothing to fix; recorded so a future reader grepping for
  a "paintbrush" preset is not surprised, and because it is why #25's glyph
  entry is dead.
- **The NewCanvasDialog comment claiming the custom-paper swatch "waits for
  the color panel (roadmap step 7)" was stale** — step 7 shipped in v1.0.
  Fixed with PR #131; the pattern to watch is comments naming a blocker
  that shipped.
- **The app's locale set is exactly two directories** (`values/`,
  `values-b+zh+Hans/`; `values-night` carries no strings). GLM review rounds
  repeatedly flag "missing other locales"; the standing refutation is this
  line plus lint's MissingTranslation gate.
- **Redo's long-press readout parity** was the model for PR #119: when an
  interaction ships on one side of a pair (undo/redo, wells A/B, hard/soft
  eraser), check its sibling before assuming the asymmetry is deliberate.

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
recommended (revisited in fourth pass: coherence holds, but child delight
needs one illustration/mascot, not a repaint).

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

The fourth pass (muse.md, 2026-08-28, 8d638d3) additionally verified: the
front-buffered drain/release protocol still holds after watercolor; the
`pendingStrokeFallback` CAS chain; `SandwichCache` ping-pong and
availability; `CompositePass` batching; `HalfTurn` symmetry; history
load/recovery/replay and `nextName` floor; `values` / `values-b+zh+Hans`
completeness (new `layer_clear_*` in both); RMW ordering; and that
`MixingDish.gradient` is now correctly remembered (`ColorPanel.kt:732`) so
it no longer recomputes 9 Mixbox mixes per drag frame.

The fifth pass (glm.md, 2026-08-28, v1.1.0) additionally verified, focused
on the post-#58 features: **Watercolor reservation/rollback ordering**
(`WatercolorPass` — pool exhaustion cannot half-apply a dab: reservations
are made, verified bindable, then committed; rollback removes exactly the
fresh slices); **`WatercolorEditPolicy`** coverage vs every `PixelOp` kind
(`Copy → Keep` is right — duplicate does not destroy the source's wet
layer; Flatten and UndoRedo nuke all); **wet tick-epoch rollover** (expired
pages pruned and live pages re-encoded before the epoch advances, so modulo
age cannot resurrect stale water); **`TracingReferencePolicy.normalizedSize`**
extreme-aspect and byte-allowance clamps; **`ReferenceImageCodec`** (EXIF
baked by `ImageDecoder` before the PNG write, tiles premultiplied,
`streamTiles` refuses dimension-mismatched assets); **`RailSlotPolicy` +
`LayoutSpec.paintSlotBudget`** (the budget equation matches the rail
constants; the active preset keeps the last budgeted slot);
**`InkBrushDynamics`** (malformed digitizer samples neutralized, axis wrap
in (−π/2, π/2], stationary pressure steps hysteresis-gated); the
**`CanvasActionGate`** phase machine and `beginStrokeTool`'s hover/tool-
switcher ordering; and zh-Hans coverage of every new key. Prior passes'
"verified clean" lists above remain in force.

The sixth pass (k3.md, 2026-08-28, v1.1.0) additionally verified: the
gesture arbiter's tap/palm/chord state machine with per-pointer axis
tracking; the fill SNAPSHOT→COMPUTE→APPLY generation guards and cancel
rules; the leave/checkpoint handoff including the stranded-scrim grace;
`CpuFlatten`'s sparse-key read skipping (no I/O for absent tiles) and
`Composite.tile`'s zero-opacity skip before any disk read; that the
hidden-layer stroke preview is deliberate (AGENTS.md; toast at pen-down);
that merge-down's blend-mode-only confirm matches the opacity-baking
deviation; the dish gradient's remember-wrapping; and the reference
panel's gesture routing (drawing, picking and tap-undo correctly
suppressed while it owns navigation). The one latent trap found is
shovel-ready #28.

## New observations from the sixth pass (k3.md, 2026-08-28, v1.1.0)

- **The open-PR list moved while the pass ran.** k3 picked the shortcuts
  legend and the custom-paper swatch while the fifth pass's #125/#131 were
  already in flight for the same items; both k3 PRs closed in favour. The
  header's "re-check the open-PR list" line is now load-bearing — check it
  *again* immediately before opening a PR, not only when picking.
- **GLM review on test-only deltas converges to test-hardening rounds.** The
  pattern across #111/#120/#143: exact-substring contract assertions get
  flagged for whitespace/regex brittleness round after round. The settled
  idiom: collapse whitespace in the source reader, assert per call site
  rather than by totals, guard against vacuous regex matches, and name the
  offenders in failure messages. Write new contract tests in that shape
  directly.
- **`rememberSaveable` without a `Saver` is a crash on rotation for any
  non-Bundle type** — GLM caught `HsvColor` (a plain engine/core data class)
  in the k3 custom-paper picker before merge. Pure-JVM value types need a
  `listSaver`/custom saver or plain `remember`; the suite cannot see it.


---

## hy3 review (2026-08-28) — `hy3.md`

A fresh, source-verified review for a 14-year-old user, written up in
`hy3.md`. Every item below was cross-checked against the prior backlog; only
genuinely new findings are listed (prior items are not re-litigated). Five were
implemented as open PRs against `main` (1.1.0); the rest are shovel-ready.

### Landed (open PRs — leave in flight, do not re-pick)

- **Watercolor wetness wiped at the 16-bit tick epoch rollover** — `engine/core/WatercolorWetKernel.kt` (CPU `ageFactor`) and `engine/gl/WatercolorShaders.kt` (`u_epochRollover`) dried `surfaceWater`/`saturation` to 0 on `EPOCH_REBASE`, contradicting AGENTS.md's "preserves water". Fixed to re-stamp the tick and preserve channels. Pinned by `WatercolorWetKernelTest`. PR **#127**.
- **`SandwichCache.rebuild` allocated a boxed tile-key list every frame** while
  drawing/panning (stale flags never clear until the whole canvas is built).
  Now reuses a persistent `IntArray` scratch, with a separate `invalidateScratch`
  so `buildTile` re-entrancy cannot corrupt the loop. PR **#108**.
- **Landscape (DOCK) rail hid one of the two brush sliders behind a tab** — now
  both sliders render side by side, like `SHORT`. PR **#110**.
- **Tapping a layer on a phone closed the whole Layer panel** — now stays open
  (matches the tablet sheet); dismiss via scrim/Back. PR **#114**.
- **Transparent-paper choice had no explanatory hint** — a one-line helper now
  appears under the swatches. Strings in both locales. PR **#117**.

### Deferred (blocked on the in-canvas Settings entry point)

- **Opening Settings from the canvas ejects the user to the Studio**
  (`ui/navigation/BangniNavHost.kt:42-45` → `navigateUp`). Feels like losing
  work. Needs Settings to render as an overlay above the live canvas; that entry
  point does not exist on `main` yet, so it is blocked on the same work as the
  replayable-hint item below.
- **First-run hint is one-and-done and omits the real tools** (`ui/canvas/FirstRunHint.kt`).
  A "Tips" replay belongs in Settings/About, which (see above) has no in-canvas
  surface on `main`. Deferred until the canvas can host Settings.

### Shovel-ready (new, unimplemented)

**Performance (hot path)**
- `CanvasRenderer.setStack` allocates a layer-id `Set` + filtered list per
  opacity/visibility drag tick (`CanvasRenderer.kt:842`, pushed by
  `CanvasViewModel.previewLayerOpacity`/`toggleLayerVisibility`); do it
  allocation-free and only re-observe when blend/structure actually changes.
- `StrokeBuffer.uploadFill` uses the `List` overload of `grid.keysFor`
  (`StrokeBuffer.kt:96`); use the `IntArray` overload like the other callers.
- `LayerEditPolicy.changedTiles` allocates a `LinkedHashSet` + `toList()` per
  commit (`LayerEditPolicy.kt:35,54`); reuse a scratch.
- `CanvasRenderer.endStroke`/`cancelStroke` copy `mergedKeys` per stroke end
  (`CanvasRenderer.kt:716,748`); pass the reused field directly.
- `ColorPanel` rebuilds the saturation gradient on every hue change
  (`ColorPanel.kt:258-266`); remember it keyed on quantized hue.
- `LayerThumbnailPass` re-composites the entire layer each 500 ms refresh
  (`LayerThumbnailPass.kt:79-88`); composite only dirty tiles, as the main
  canvas does.

**Visual / layout**
- `FULL` rail can overflow/clip on short landscape windows and puts the size
  slider far from the active tool (`ToolRail.kt:187-223`, `CanvasScreen.kt:842`);
  cap rail height with internal scroll or anchor sliders near the active tool.
- New-canvas presets show only text + dimensions (`NewCanvasDialog.kt:340-384`);
  add a tiny aspect-ratio / orientation glyph per preset.
- Color-chip recent-colors long-press is invisible to sighted users
  (`TopStrip.kt:290-324`); add a dot/badge or teach it in onboarding.
- Active-tool re-tap-to-open-settings has no cue (`ToolRail.kt:494-507`); add a
  subtle gear/pencil-edit badge.
- Blur + Eyedropper are buried in "More" with no teaching on medium phones
  (`ToolRail.kt:567-595`); surface in onboarding or show the active tool name.
- Recent-colors popover auto-dismisses after 4 s (`CanvasScreen.kt:1984`,
  `RECENT_POPOVER_MS`); extend the timeout or dismiss only on an explicit tap.
- HSV ring/square picker is a dead zone for screen readers
  (`ColorPanel.kt:274-355`); expose via `customActions` (set hue / saturation /
  value).

**Missing features (competitor parity; respect no-permission / no-internet)**
- Text tool (`ToolKind.TEXT`, rasterize to the active layer).
- Selection + free transform (rect/ellipse/lasso gating `StrokeMerge`/`FloodFill`
  + a transform pass reusing RMW ping-pong).
- Layer groups / folders (`LayerStack` nesting + collapsible UI).
- Gradient fill (multi-stop Mixbox, reuse `MixingDish.gradient` + `FillTool`).
- Layer clipping / clip-to-below (`clipToBelow` in `Layer.kt`; `Composite`
  restricts the upper layer to the lower layer's coverage; one `LayerProps`
  journal entry).
- Import image as a real editable/exportable/undoable layer (Photo Picker, no
  permission; decode into `TileStore` tiles of a new `Layer`).
- Stamp / sticker tool (CC0 `ImageVector` silhouettes) — note: vector
  silhouettes dodge the texture-loader blocker in the muse entry above, but
  provenance still must be recorded in AGENTS.md; see the blocked-on-grains
  note under the muse stamp entry and glm.md §B.4.13.
- Perspective / vanishing-point guide + optional snap (beyond `CompositionGuide.kt`).
- Blend-mode visual gallery (live per-mode thumbnails in `LayerPanel.kt`).
- Non-destructive adjust layer (Hue/Sat/Brightness).
- Auto-shape assist (snap a rough stroke to a clean shape with handles).
- Expanded color-history ribbon (chronological strip of every color this session).
- Pixel-grid snap + square guide + "pixel" canvas preset (`CompositionGuide.kt`
  + `CanvasPresets.kt`).

**UX / aesthetics (restated from hy3.md; not yet landed unless noted)**
- No visual feedback when a stroke is refused because the document is busy
  (`CanvasViewModel.beginStrokeTool` returns null while `CanvasActionGate` is
  busy) — reuse the `strokeLayerNotice` toast path.
  **→ in review as PR #122.**
- Keyboard shortcuts undiscoverable (`engine/core/CanvasShortcut.kt`); add a
  Shortcuts section in Settings/About. **→ in review as PR #125.**
- Mixing-dish slider position not durable (`DishState.t` resets on recomposition);
  persist beside the wells. **→ in review as PR #104.**
- Panel close affordance missing (close icon in panel headers).
  **→ in review as PR #120.**
- Shelf cards flat; a whisper of elevation + `primaryContainer` "New painting"
  cell adds polish (`StudioScreen`).
- No display face for the app name / panel headers (CC0/OFL font; provenance in
  AGENTS.md).
- Checkerboard doesn't scale with zoom (band `checkerPx` by `view.scale`).

**Delight (novel / quirky; all respect the hard red lines)**
- Daily draw prompt (offline `PromptBank`, "Surprise me" on `StudioScreen`).
- Color name label (offline `ColorName.kt` under the HSV picker).
- Shake-to-undo (accelerometer, debounced; triggers existing undo path).
- Mirror-check toggle (one-click horizontal flip of the composited view)
  — merged into the "Flip-view mirror check" larger idea above (glm.md §B.4.11).
- Satisfying stroke sound/haptic (toggle, off by default; CC0 click on pen-up).
- Custom app accent color (`Color.kt` + `Prefs.kt` via `MaterialTheme`).
- "Made with 帮你Draw" share card + celebratory state (via `ShareCache`/`GalleryExporter`).
- Onion-skin / flipbook peek (previous N committed states faintly beneath the
  live layer; read-only).
- Quick-color radial on canvas long-press (recent/most-used colors + eyedropper).
- Ambient Studio shelf, composition guides, 100% zoom, edge rubber-band, hue
  haptic, layer solo — restated from the prior backlog; still unlanded.
