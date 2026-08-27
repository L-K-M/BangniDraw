# qwen.md — Independent review of 帮你Draw (v1.0.2, `b69f4bd`)

A close read of the whole codebase (`app/src/main`, all design docs in
`docs/plan/`, AGENTS.md, REVIEW.md, ISSUES.md) plus a local
`testDebugUnitTest lintDebug assembleDebug` run (green). No device is
available, so everything here is code-level evidence: pixel paths, state
machines, and layout math — not on-device feel.

**Verdict up front.** The engine is exceptional: the stroke pipeline,
front-buffered preview, sandwich cache, RMW tools, journal persistence and
the input stack are the most carefully reasoned Android drawing code I have
seen, with the CPU-reference/GLSL pinning done properly. The defects are
concentrated in three places: **tool-parameter reachability** (smudge, blur
and the eyedropper have no settings UI, so the plan's promised controls are
silently absent), **tool identity** (rail icons collide), and a scattering
of small polish/perf items. No data-loss or crash-class bug was found in
the engine, persistence, or input paths.

Each entry ends with a confidence tag: **[DO]** — good to implement now,
**[MAYBE]** — needs a product decision or a device to judge, **[SKIP]** —
not worth it / deliberately out of scope.

---

## 1. Bugs and correctness issues

### 1.1 Smudge, blur and eyedropper have no settings UI at all — plan-level gap
`docs/plan/08-ui-and-layout.md` §3.5 specifies per-tool-kind settings
("smudge/blur show Stroke (no opacity), Tip, Dynamics (size only),
Stabilizer, Mixing"; "eyedropper shows sample: composite / current layer").
PLAN.md §6 says smudge has "strength". In the code:

- `ToolRail.secondarySlots`: the smudge/blur/eyedropper `ToolSlot.onClick`
  only acts when the tool is **inactive** — tapping the active smudge, blur
  or eyedropper does nothing. Only fill opens its settings sheet
  (`onFillSettingsRequested`).
- `CanvasScreen.CanvasPanelContent` BRUSH_SETTINGS reads
  `(state.toolSelection.kind as? ToolKind.Brush)?.preset ?: return` — for a
  non-brush kind the panel renders empty.
- `ToolRail.BrushSliders` renders only when `activeBrush != null`
  (`ToolKind.Brush`); the canvas ledge in `CanvasScreen` only when
  `ledgePreset != null`. So when smudge/blur is active the rail has **no
  size slider at all** — size, strength, pickup rate, blur radius and
  stabilizer are frozen at `SmudgeParams()` / `BlurParams()` defaults
  forever, and there is no way to change them anywhere in the app.

Consequence: three shipped tools are fixed-parameter stubs behind a
working RMW engine. `RmwDabPreset` and `SmudgePass` fully support the
parameters; only the UI surface is missing. **[DO]**

### 1.2 Tool icons collide
`ToolRail.iconFor` and `secondarySlots` map:
pencil → `Icons.Filled.Gesture` **and** smudge → `Icons.Filled.Gesture`;
airbrush → `Icons.Filled.BlurOn` **and** blur → `Icons.Filled.BlurOn`;
marker → `Icons.Filled.Edit` (a pencil glyph) and ink pen →
`Icons.Filled.Create` (also pencil-like). In the FULL rail, four of the
ten slots are ambiguous with a neighbor, and the active-tool ring cannot
disambiguate two identical glyphs. `material-icons-extended` is already a
dependency, so distinct icons cost one mapping change. **[DO]**

### 1.3 Per-frame allocation on the GL render path
`CanvasRenderer.visibleCanvasRect` (called from
`rebuildSandwichIfNeeded` → `compositeIntoAccum` → both `drawFrame` and
`drawStrokeFrame`, i.e. **every rendered frame**) builds
`listOf(...)` of four `Pair`s and calls `ScreenTransform.invert` (which
returns a `Pair<Float, Float>` — two boxed floats each). That is 8+ small
objects per frame on the render thread, in the same codebase whose own
rule (10-perf §2.4) forbids per-frame allocation on hot paths. A
scratch-array/`invertX`/`invertY` version is a five-minute fix. **[DO]**

### 1.4 Screen can sleep mid-painting
No `FLAG_KEEP_SCREEN_ON` anywhere. A phone/tablet on the default 30 s–1 min
timeout turns the display off while the user is mid-thought with the pen
hovering. Every serious drawing app keeps the screen awake while the
canvas is open. One `DisposableEffect` in `CanvasScreen` (add/clear the
window flag) fixes it. **[DO]**

### 1.5 Undo/redo long-press readout missing
Plan §3.1: "long-press shows 'n steps'". `TopStrip.NavigationCluster` has
plain `IconButton`s with no long-press. The journal already exposes
`stats().entries`. Trivial to add (a long-press surface showing
"137 steps · 42 MB of 200 / 256 MB", which also makes the history cap
visible where it is used). **[DO]**

### 1.6 Studio empty state only exists on compact widths
`StudioScreen`: the "No paintings yet" copy renders only when
`compact && state.paintings.isEmpty()`. On a tablet (medium/expanded) a
first-time user sees one lonely "+" tile and no explanation. Plan §2
specifies the empty state for all widths. **[DO]**

### 1.7 `LayerPanel` drag handle sits on a dead click target
The drag handle is an `IconButton(onClick = {})` with a
`detectDragGestures` `pointerInput` layered on it. It works, but the
accessibility node for it is an empty-action button; TalkBack will read a
draggable-looking control that announces no action. Minor; fold into any
layer-panel change: give the handle `contentDescription` semantics of
"drag to reorder" and drop the fake click. **[MAYBE]**

### 1.8 `ProjectStore.rename`/`updateGalleryFields` rewrite the whole
`project.json` read-modify-write on the caller's thread
Both run inside `viewModelScope.launch(Dispatchers.IO)` from
`StudioViewModel` — fine. Not a bug. **[SKIP]**

### 1.9 Deleted-while-open race on the shelf
`StudioViewModel.delete` → `store.delete(id)` renames the folder; if the
user re-opened the same painting in another window it would silently
checkpoint back into existence. Single-activity `singleTask` makes this
unreachable today. **[SKIP]**

## 2. General issues

### 2.1 The review loop retired mid-project
Steps 3–10 merged by direct push, un-reviewed by GLM, and the "PR review
loop" documented in EXECUTION.md no longer ran. `ISSUES.md` shows two
manual security passes compensating. Nothing to fix in code; a process
note for whoever maintains the repo. **[SKIP]**

### 2.2 `AGENTS.md` vs reality drift on a few details
- AGENTS.md says the eraser end + button "default action: eraser while
  held, configurable to eyedropper — applied at the next pen-down through
  `ToolSwitcher`, never mid-stroke" — accurate.
- The plan §3.5 "Reset to preset" exists in `BrushSettingsSheet`
  (`onReset`), fine.
- `docs/plan/12-roadmap.md` status notes all say "device check not run —
  no device has ever been available". That remains true; the first
  device-pass will surface preset-feel issues the JVM suite cannot. Not
  actionable without hardware. **[SKIP]**

### 2.3 Eyedropper params are not reachable either
Beyond 1.1: `EyedropperParams.source` (CurrentLayer vs Composite) and
`radius` exist and the engine honors them, but every call site constructs
`EyedropperParams()` defaults, and the active-eyedropper tap is a no-op.
Covered by 1.1's fix. **[DO — same PR as 1.1]**

### 2.4 `OVERFLOW` panel kind is dead code
`CanvasPanel.OVERFLOW` is declared, handled in `panelAnnouncement` and
`CanvasPanelContent`, but never set as `openPanel` (the strip's overflow
is a local `DropdownMenu`). Harmless; remove it when the file is next
touched, or leave it. **[SKIP]**

### 2.5 Test suite never exercises `CanvasPanel.OVERFLOW`, `BrushSliders`
and several pure policies only cover happy paths
`CanvasUiPolicyTest`/`LayoutSpecTest` exist but nothing pins the
smudge/blur sliders question (there are none). When 1.1 lands it must
bring tests for the new slider/sheet gating. **[DO — with 1.1]**

## 3. Performance problems

The engine's frame budget machinery is real (`PerfStats`, `publishFrame`,
`beginFrame`, PBO readbacks with fence polling). What I can fault from a
desk:

### 3.1 The `visibleCanvasRect` allocation (1.3 again)
The one place the code contradicts its own no-allocation rule on the
render path. **[DO]**

### 3.2 Sandwich rebuild on every navigation frame is full-viewport
`rebuildSandwichIfNeeded` recomputes the visible rect and re-uploads
viewport-margined tiles every pan/zoom frame — the plan accepts this
(§2.6, margin). The `SANDWICH_MARGIN_PX` expansion is the only buffer; a
fast flick-pan can still outrun it. No fling exists (deliberate), so this
is likely fine. **[SKIP]**

### 3.3 Full-viewport recomposite on every committed frame
`drawFrame` composites paper + below + active + above + present even when
only the view transform changed (pan/zoom has no dirty-rect path). The
sandwich keeps this at ~5 passes; on a 120 Hz tablet during navigation
this is the main cost. A scissored/incremental committed path is post-v1
work; note it in ANALYSIS.md. **[MAYBE]**

### 3.4 `CpuFlatten` peak memory (~128 MiB at 4096²)
Already documented (ISSUES.md Review 1 item 3, AGENTS.md). Bounded by
design; the GL band flatten is owed and would supersede it. **[SKIP]**

### 3.5 Stroke path is allocation-free except two spots worth noting
- `onStrokeSample` path: `engine.acquireDabBatch()` ring, no allocation —
  correct.
- `CanvasTouchHandler.applyNavigation` allocates two `ViewTransform`s per
  move (documented in 12-roadmap, deliberate follow-up). **[SKIP]**

### 3.6 Thumbnail decode in the Studio re-decodes full PNGs per visit
Bounded by shelf size; fine for tens of paintings. If the shelf grows,
cache bitmaps by `(file, size, mtime)` in the ViewModel. **[MAYBE]**

## 4. Missing features

Only gaps against *what the plan itself promises for v1* are listed here;
the post-v1 backlog (selections, symmetry, rulers, gradients, wet brushes,
grains, image import, crop/resize, tile eviction, OpenRaster, time-lapse,
pressure calibration, custom brush import) is already well-curated in
`docs/plan/12-roadmap.md` §5 and needs no re-litigation.

### 4.1 Tool-kind settings sheets (smudge/blur/eyedropper) — §1.1
The single largest plan-vs-shipment gap. **[DO]**

### 4.2 Rail sliders for smudge/blur
Plan §3.2: the two thin sliders "edit the *active tool's* size and
opacity". With smudge active, opacity = strength. Ships with 4.1. **[DO]**

### 4.3 Size/opacity preview blob on the rail and ledge sliders
Plan §3.2 describes a preview blob (true-size circle, capped at 96 dp
with "142 px" beneath) while dragging; `ThinSlider` renders none. The
brush settings sheet has numeric readouts, but the rail/ledge sliders —
the most-used controls — give no feedback of the actual pixel size. Also
unimplemented: "touching the slab jumps the thumb, then drags" and the
0.25× off-slab fine gain. **[DO]**

### 4.4 Fill settings sheet exists; smudge/blur don't — same root as 4.1
**[covered]**

### 4.5 No way to resize a canvas after creation
Post-v1 backlog ("canvas crop/resize") — fine to defer, but worth stating
in ANALYSIS.md as the single most-requested gap for real painters. **[MAYBE]**

### 4.6 No double-tap-undo or other single-gesture undo
Two-finger tap exists. Fine as designed. **[SKIP]**

## 5. Visual issues and layout problems

### 5.1 Rail transparency over busy paintings
`RAIL_ALPHA = 0.92`/`LEDGE_ALPHA = 0.94` let busy canvas pixels show
through chrome. The Quiet Studio language wants a clean separation;
0.92 alpha on a warm surface over a saturated painting muddies both. Full
opacity is cheaper and crisper. **[MAYBE]**

### 5.2 The layer badge shows only the index
`TopStrip` badge = `activeLayer` (index+1), no total ("3" not "3 / 16").
Plan §3.1 says "badge shows active layer index when panel closed" — plan
compliant, but "3/16" costs nothing and answers "how many layers do I
have" without opening the panel. **[MAYBE]**

### 5.3 Slider-ledges and dock overlap the reset pill zone on compact
`CanvasScreen` computes `resetBottomPadding` per rail mode; the storage
banner and fill progress card both `align(BottomCenter).padding(16.dp)`
and can cover the reset pill on DOCK (120 dp of chrome). Acceptable;
noted. **[SKIP]**

### 5.4 The color-panel header is scrollable
The `ColorPanel` header text scrolls away with content; the panel also
lacks a close button (dismiss is canvas-tap/back). Modeless by design —
fine. **[SKIP]**

### 5.5 Paper swatch in the layer panel shows no checker for transparent
`PaperRow.PaperSwatch` draws `drawRect(surfaceVariant)` then
`drawRect(Color.Transparent)` — transparent paper shows as a flat gray
square, unlike the New Canvas dialog which renders "∅". Inconsistent.
**[MAYBE]**

### 5.6 Studio title row: no app-wordmark or icon
The Studio header is plain text "帮你Draw" + gear. The plan's mock has the
same; fine, but the adaptive-icon artwork exists and a small mark would
warm the empty shelf. **[MAYBE]**

## 6. User-friendly / convenient / fast interface

### 6.1 Keep screen on (1.4) **[DO]**

### 6.2 First-run hint is shown once, ever
If a user dismisses it by accident, there is no way to see it again.
A "Show the canvas hints again" row in Settings (or long-press the
studio +) would cost little. **[MAYBE]**

### 6.3 No quick access to recent colors from the top strip
The color swatch button only opens the panel. A long-press popover with
the Recent palette (16 colors) would make the most common action —
switching between two working colors — one gesture instead of three.
`recentColors` already exists in the ViewModel. **[DO]**

### 6.4 Leave flow is instant; no "closing" scrim ever appears
The plan §4.8 promises a translucent "Closing…" scrim only if the
checkpoint takes > 300 ms; `leave()` awaits the checkpoint with no scrim
or feedback at all, so on a big painting with a pending gallery flush the
back tap appears dead for seconds. **[DO]**

### 6.5 No stroke color feedback on the canvas
The hover cursor shows size but not the current color until you draw.
Low cost: tint the hover ring (inner stroke) with the brush color.
**[MAYBE]**

## 7. Improved aesthetics

### 7.1 Tool icons (1.2) **[DO]**

### 7.2 The rail's active ring is thin at 2 dp and the temporary dashed
ring is easy to miss
Fine per plan. **[SKIP]**

### 7.3 Checkerboard colors are theme tokens — good
No action. **[SKIP]**

### 7.4 Paper color change is instant on the GPU (engine.setPaperColor)
good. **[SKIP]**

## 8. Novel / cool / delightful / quirky ideas

Ranked by "wow per effort". None of these exist in the plan; each would
need a proposal before shipping per the repo's process, but they are
concrete enough to implement directly.

### 8.1 Time-lapse recording of a painting session
Already backlog ("time-lapse"). The engine already checkpoints composite
tiles; recording one thumbnail-scale frame per stroke and encoding on
export is cheap. Delightful for sharing process videos. **[MAYBE]**

### 8.2 "Smoothing / lazy mouse" preview ghost
A subtle, dim preview of the stabilized path while drawing (the
Stabilizer's leash is already computed per sample) would teach users what
the stabilizer does and looks alive. Medium effort. **[MAYBE]**

### 8.3 Palette-from-canvas
"Extract palette" from the current painting: quantize the composite's
colors (k-means on readback tiles, off the GL thread) into a 8–16 swatch
user palette. The readback machinery exists; delightful and genuinely
useful. **[DO]**

### 8.4 Quick-shape assist (hold-to-snap) 
Backlog ("rulers / shape assist"). A lighter version: hold pen still
200 ms at stroke end to snap the last segment to a straight line /
circle — no new UI. **[MAYBE]**

### 8.5 Two-finger twist undo scrub
Two-finger rotate is already navigation; a "scrub" gesture (three-finger
horizontal drag through the journal) is the time-machine UX Procreate
users love, and the journal is built for it. Medium effort, high delight.
**[MAYBE]**

### 8.6 App shortcuts
`android:shortcuts` for "New sketch" and "Continue last painting" — the
Studio already has everything needed. Cheap, useful on the launcher.
**[DO]**

### 8.7 Edge "quick color bar"
A dismissible, always-visible vertical strip of the last 8 used colors on
the far edge (opposite the rail) — one tap to switch color without
opening the panel. **[MAYBE]**

### 8.8 Export time-lapse is 8.1; skip. **[covered]**

## 9. What is excellent (do not regress)

- The stroke pipeline: unbuffered dispatch, prediction with adaptive
  disable, the front-buffered preview sharing one `mergeStroke` with the
  commit, tail erasure via dirty rects.
- Persistence: tmp+rename everywhere, `project.json` last, journal prefix
  validation, torn-write tolerance, the layer-id trust boundary.
- The CPU-reference ↔ GLSL pinning discipline and its contract tests.
- Accessibility semantics, reduced-motion handling, 48 dp targets, string
  coverage (both locales complete; only `translatable="false"` keys
  differ).
- The code's own documentation quality — every non-obvious block explains
  *why*. Keep it.

## 10. Implementation plan (this review's follow-through)

Entries marked [DO] above, in branch order:

1. **Smudge/blur/eyedropper settings** (1.1 + 2.3 + 4.1 + 4.2): params
   state in the ViewModel, per-tool-kind settings sheets, rail/ledge
   sliders for smudge/blur, tests for the gating policy.
2. **Distinct tool icons** (1.2/7.1).
3. **Keep screen on** (1.4/6.1).
4. **Render-path allocation fix** (1.3/3.1).
5. **Undo/redo long-press readout** (1.5).
6. **Studio empty state on wide widths** (1.6).
7. **Leave scrim** (6.4).
8. **Recent-colors popover on the top-strip swatch** (6.3).
9. **App shortcuts** (8.6).
10. **Palette-from-canvas** (8.3) — biggest novel item; likely its own
    proposal + PR.

Each lands as its own branch → PR → review loop → merge, per the repo's
policy.
