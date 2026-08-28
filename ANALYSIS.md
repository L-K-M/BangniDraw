# ANALYSIS.md — forward backlog

Consolidated on 2026-08-28 from the source-verified project reviews. This is
forward-only: completed work is removed; open work stays in the ledger until
it merges or closes. Line numbers from earlier reviews may drift, so locate
the named symbol before editing.

Pick-up rules:

1. Fetch `origin/main` and refresh the open-PR list immediately before work.
2. Read `PLAN.md`, the relevant `docs/plan/` section, and `AGENTS.md`.
3. Reproduce a bug with a failing JVM test before fixing it. Keep decisions in
   `engine/core`; use scoped source-contract tests only when Android classes
   prevent a behavior test.
4. Run `testDebugUnitTest` and `lintDebug`. Update `AGENTS.md` when the change
   establishes a durable invariant or exposes a repository footgun.
5. Do not infer device quality from source tests. The device gate at the end
   has never been completed.

## Open PR ledger — do not reimplement

Refreshed from GitHub on 2026-08-28. Review or finish these branches; do not
pick their underlying items from the backlog while they remain open.

- #104 — persist mixing-dish slider position.
- #105 — confirm Clear Layer.
- #106 — preserve user tuning during paintbrush migration.
- #107 — drop a wet page when Watercolor cancel restoration fails.
- #108 — remove per-frame `SandwichCache` tile-key allocation.
- #109 — stop representing water load as brush opacity.
- #110 — show both brush sliders in landscape dock mode; Android check is
  failing, so diagnose before merge.
- #111 — give Pigment Wash a distinct rail glyph.
- #112 — size the Water hover ring to the wet bloom.
- #113 — document blank-water journal behavior.
- #114 — keep the phone Layer panel open after layer selection.
- #115 — preserve unreadable tracing-reference assets.
- #116 — cancel platform-rejected pointer lifts.
- #117 — explain transparent paper in New Canvas.
- #118 — correct RGBA/ARGB bitmap channel conversion.
- #119 — add a redo long-press history readout.
- #120 — add close affordances to Canvas panels.
- #121 — contain denied Gallery URI probes.
- #122 — explain strokes refused while the document is busy.
- #124 — prevent failed tile flushes from committing checkpoints.
- #125 — expose keyboard shortcuts in Settings.
- #126 — preserve a project when delete staging fails.
- #127 — preserve Watercolor wetness across epoch rebasing; Android check is
  failing, so diagnose before merge.
- #128 — prevent duplicate Canvas destinations.
- #129 — retain unavailable/newer/corrupt paintings on the shelf.
- #130 — reuse brush-preview buffers.
- #131 — add custom paper color to New Canvas.
- #132 — add actual-size zoom to the reset-pill long press.
- #133 — preserve concurrent edits across checkpoints.
- #134 — make choice rows single accessible actions.
- #138 — resume dab generation after a full batch.
- #139 — drain and close the tile worker during Canvas teardown.
- #140 — adapt Canvas overlays and reference controls to short windows.
- #141 — add selectable application themes.
- #142 — make Studio refresh publication latest-wins.
- #143 — size the HSV picker to its panel.
- #144 — schedule stationary-finger gesture deadlines.
- #146 — reserve dab capacity for real input ahead of prediction.

## Reliability, persistence, and recovery

### R1 — Bound reopen memory and prevent stale uploads overwriting ink

**Severity:** critical. **Confidence:** high. **Scope:** large.

`CanvasViewModel.streamTiles` reads every tile into arrays, groups them in
local 16-tile batches, and queues asynchronous `EngineSession.uploadTiles`
closures without acknowledgements or backpressure. Canvas becomes Ready
before restoration ends. A delayed disk upload can overwrite a fresh stroke
on the same tile, while queued closures can retain roughly 512 MiB for a dense
4096², eight-layer document. The five-second engine-ready timeout can also
abandon a blank or partial restoration without an honest recovery state.

Build one generation-owned reopen pipeline:

- fixed reusable staging sized by `REOPEN_STAGING_TILES` and
  `UPLOAD_BATCH_TILES`, with GL acknowledgements and bounded producer lead;
- visible tiles first, then off-screen tiles;
- cancel stale uploads on session/attachment generation change;
- allow drawing only after the visible working set is resident;
- report retry/back rather than silently accepting a partial timeout.

Tests: block one upload, attempt a stroke into that tile, and prove the old
upload cannot win; assert the number and bytes of queued batches stay bounded;
rotate/close/reopen mid-stream and prove old generations are ignored. Device
gate: dense 4096²/eight-layer reopen-to-visible and reopen-to-interactive.

### R2 — Make journal publication crash-consistent and gap-tolerant

**Severity:** critical. **Confidence:** high. **Scope:** on-disk design.

`TileFlusher` can publish a stamped history entry before all changed pixels
reach disk. Recovery treats a contiguous entry as applied but structural
replay cannot reconstruct its after-image. Separately, sequence numbers are
consumed before publication: a failed sequence 41 followed by valid 42 makes
recovery stop at the gap and hide later undo.

Design these together. Choose either a transaction/commit marker plus enough
before/after data to select one complete state, or an equivalent protocol
that cannot expose a partly flushed edit. Allocate sequence numbers only on
successful publication or persist explicit skip records. Preserve downgrade
and corrupt-tail behavior. Record the chosen disk invariant in `AGENTS.md`.

Tests must reopen fixtures killed at: entry temp write, entry rename, each
changed-tile write, metadata write, checkpoint rename, and cleanup. Add
success/failure/success/checkpoint/reload and prove later valid history stays
visible without accepting a partial edit.

### R3 — Give Gallery sync one durable per-project owner

**Severity:** high. **Confidence:** high. **Scope:** medium-large.

Canvas can launch a detached sync while navigating to Studio. Studio reads
stale metadata and can start another, producing two MediaStore rows. A job may
also mutate MediaStore and be cancelled before its URI/revision bookkeeping is
persisted. `refresh()` starts stale-gallery work immediately after listing,
so a just-edited painting may flatten while the user is choosing the next
painting.

`GallerySyncDecision` also permits a full flatten plus PNG compression during
active painting after its 30-second floor (ceiling checkpoints can arrive every
90 seconds). Keep that policy explicit, instrument it, and move its memory work
onto the bounded band path in D3.

Use an application-scoped, per-project coordinator that serializes flatten,
MediaStore mutation, and `ProjectStore.updateGalleryFields`. Recheck the
current Gallery opt-in after flatten and before every external mutation.
Persist the URI/revision at the mutation boundary, make cancellation semantics
explicit, and delay non-urgent stale sweeps until after shelf publication.

Tests: hold a fake exporter across Canvas→Studio handoff, cancellation,
denial, and opt-out; observe one durable URI, no post-opt-out mutation, and no
duplicate flatten. Exercise ownership revocation between each phase.

### R4 — Settle and round-trip per-painting working state

**Severity:** medium. **Confidence:** high. **Scope:** product decision.

`ProjectFile.view` and `lastTool` exist, but save/load do not provide a
coherent restore. Reopen resets zoom, rotation, selected tool, color, and size.
Decide which fields belong to the painting and which remain global. Round-trip
the chosen view/tool/color/size state, version it, and fall back to Fit when a
saved viewport is incompatible with the current window.

Tests: save/reopen each field, migrate files without the fields, clamp invalid
values, and reopen the same painting on a differently shaped viewport.

### R5 — Render non-Normal thumbnails with document compositing semantics

**Severity:** medium. **Confidence:** high. **Scope:** medium.

Thumbnail generation warns and substitutes source-over for Multiply, Screen,
Difference, Add, and other non-Normal modes. The shelf can materially disagree
with the painting. After #118 lands, reuse the CPU `Composite` oracle or the
future band flattener; do not introduce a third blend implementation.

Tests: compare thumbnail pixels with `Composite` for every blend mode, layer
opacity, transparent paper, sparse edge tiles, and hidden layers.

### R6 — Preserve the Gallery copy until project deletion succeeds

**Severity:** medium. **Confidence:** medium-high. **Scope:** small-medium.

Studio removes the optional Gallery row before proving that the private
project was deleted. A project-delete failure leaves the painting but removes
the child’s requested external copy. Confirm the intended semantics in the
persistence plan, then reverse or transactionally coordinate the operations.

Tests: fail staging, recursive deletion, and MediaStore deletion separately;
pin the recoverable state and user-facing retry for each outcome.

### R7 — Make Studio refresh failures retryable

**Severity:** high. **Confidence:** high. **Scope:** small-medium.

The latest-wins work in #142 does not cover exceptions. A non-cancellation
failure from `ProjectStore.list` or `freeBytes` can cancel refresh and leave
the shelf unloaded or stale without a useful action. Catch failures at the
ViewModel boundary, preserve or restore the last usable shelf, expose a
localized retry state, and continue to propagate cancellation.

Tests: make `list` and `freeBytes` throw independently on first and later
refreshes; assert loading terminates, prior content survives when available,
retry succeeds, and cancellation produces no error UI.

### R8 — Publish Studio metadata before recursive size accounting

**Severity:** medium. **Confidence:** high. **Scope:** medium.

`ProjectStore.list` calls `folderBytes`, walking all tiles, history payloads,
and references before returning each shelf entry. Dense projects can contain
thousands of files. Return metadata first, then compute sizes asynchronously
with bounded concurrency and an explicit “calculating” state; never flash a
false `0 B`.

Tests: block size walks and prove titles/thumbnails publish first; delete or
rename during a walk and reject stale results; contain unreadable files.

### R9 — Single-flight expensive render/export actions

**Severity:** medium-high. **Confidence:** high. **Scope:** medium.

Repeated share/export requests can launch overlapping full-canvas
flatten/encode jobs with no busy state. Coordinate this with R3 and the band
flattener: single-flight by project and action, disable or replace the invoking
action with progress, permit cancellation before any external mutation, and
make re-entry deterministic.

Tests: rapid repeated share/export/gallery actions produce one flatten and at
most one chooser/mutation; cancellation restores controls and temp files are
cleaned.

## Drawing, input, and performance

### D1 — Remove Smudge/Blur allocation from dab and tile loops

**Severity:** medium. **Confidence:** high. **Scope:** medium.

`SmudgePass` builds plans, rectangles, unions, and per-output-tile scissors
inside its hot loops; Blur shares the retained-mode gap. A 200-dab/four-tile
frame can create about 1,400 short-lived objects. Port the retained primitive
bounds pattern used by Watercolor, keeping low-level GL mechanics inside the
pass and decisions in a JVM twin.

First pin pixel and touched-tile parity. Then use allocation counters and
frame traces on a device; do not accept a source-only object count as proof.

### D2 — Surface unsupported GLES and memory budgets

**Severity:** high. **Confidence:** high. **Scope:** medium.

`EngineSession.isSupported` is intended to feed an unsupported-device screen,
but no UI consumes it. A failed probe drains input and shows a blank canvas.
Publish a one-shot capability result into Canvas state and show an honest
screen with Back, retry where meaningful, and copyable device diagnostics.

Tests: callback before/after attachment, recreation, and stale generation.
Validate with ES2 and low-array-layer emulator/device configurations.

### D3 — Replace eager full-frame flatten/readback with bounded bands

**Severity:** high on large documents. **Confidence:** high. **Scope:** large.

CPU flatten can retain roughly 128 MiB output, and Gallery/share/export may
overlap it. Fill eagerly composites and `glReadPixels` tiles before CPU scan.
Implement the planned GL band flattener with bounded buffers and one blend
oracle; then evaluate progressive PBO-backed fill faults from
`docs/plan/04-tools.md §7` instead of eagerly reading the canvas.

Tests: byte parity against `CpuFlatten`/`Composite` for sparse and dense tiles,
all blend modes, transparent paper, edge bands, cancellation, and GL loss.
Measure peak RSS and stall time; preserve the CPU path as a test oracle or
explicit fallback, not a competing semantic implementation.

### D4 — Measure Watercolor before changing its sequential semantics

**Severity:** high performance risk. **Confidence:** high. **Scope:** validate.

`WatercolorPass.stamp` performs roughly 6–12 FBO bind/draw/blit operations per
dab, and the default spacing can emit a dab every four pixels. Each accepted
dab reads the previous wet result, so blind batching can change the paint.
Instrument GPU/CPU time and queue depth first on mid-range hardware. If it
misses the frame budget, evaluate the existing content gate for Water,
coalesced source copies, or larger effective spacing with pixel-reference
tests.

### D5 — Account for and, if useful, reclaim Watercolor scratch

**Severity:** medium. **Confidence:** high. **Scope:** validate/small.

Watercolor retains about 18.25 MiB of grow-only scratch outside `TilePool`.
That matches `WatercolorScratchBudget`, but is invisible in diagnostics. Add it
to memory reporting. Measure whether long-idle reclaim helps more than the
later reallocation costs before implementing reclaim.

### D6 — Remove remaining hot-path churn

Treat each row as an isolated measured change; preserve the named semantics.

| Hot path | Evidence | Implementation and proof |
| --- | --- | --- |
| `CanvasRenderer.setStack` | Allocates an ID `Set` and filtered list per opacity/visibility tick. | Cache availability by a signature covering layer order, active layer, visibility, and blend; keep opacity on its existing data path; test every trigger and no-op tick. |
| `StrokeBuffer.uploadFill` | Uses the boxed `List` overload of `grid.keysFor`. | Use the existing `IntArray` overload; allocation test. |
| `LayerEditPolicy.changedTiles` | Builds `LinkedHashSet` then `toList()` per commit. | Fill a caller-owned sink, then take one immutable snapshot at the async boundary; test order, reentrancy, and retained-lifetime safety. |
| `CanvasRenderer.endStroke/cancelStroke` | Copies `mergedKeys` at every stroke end. | Pass retained data only if lifetime is synchronous and pinned; race/lifetime test. |
| HSV saturation field | Rebuilds the gradient on every hue sample. | Reuse only for the exact hue, or quantify a safe bucket with pixel/ΔE bounds; test recomposition count and error. |
| `LayerThumbnailPass` | Re-composites the whole layer every 500 ms. | Track and composite dirty tiles; compare full-refresh pixels after edit/undo/reorder. |
| Studio thumbnail decode | Decodes at 512 px regardless of cell size. | Choose `inSampleSize` from measured cell demand without blurring large cells. |

### D7 — Establish device metrics and regression budgets

Measure p50/p95/p99 input-to-present, missed frames, GL queue depth,
allocation rate, reopen-to-visible/interactive, Fill, flatten, and peak memory
on a 120 Hz S Pen tablet, mid-range phone, and low-memory class-B device.
Use 4096²/eight dense layers, every paint tool, rapid zoom/rotation, and a
20-minute S Pen script: fast flicks, pressure and stationary dots, holds,
canceled palms, barrel button, eraser end, hover, tilt, quick lifts, and dense
crossing strokes. Store traces and thresholds so later optimizations have one
repeatable gate.

### D8 — Small input/workflow gaps

- Add mouse-wheel zoom around the cursor; preserve trackpad/pan and shortcut
  behavior.
- Reuse `HueMilestone` for restrained haptics when an eyedropper drag crosses
  a hue band; retain existing accessibility/provider gating.
- Consider a two-finger-plus-pen brush-size gesture only after chord and palm
  arbitration tests prove it cannot steal drawing.
- The transparent-paper checker is screen-space: its cell size is already zoom
  stable, but cells slide under the paper during pan/zoom. Decide whether a
  canvas-anchored checker is worth its minification aliasing; do not apply the
  obsolete scale-banding fix. Coordinate any pixel guide with A14.

## UI, accessibility, and convenience

### U1 — Finish Layer panel accessibility and large-text behavior

**Severity:** medium. **Confidence:** high. **Scope:** small-medium.

Layer rows use a fixed 64 dp height despite two text lines. Blocking layer
feedback lacks an assertive live region, and lock, alpha-lock, and blend state
can remain visual-only in menus. Use minimum rather than fixed height, expose
checked/selected state and action labels, and announce blocking feedback once.

Tests: semantics tree for every state and action; screenshots at 100/200%
font, both locales, narrow phone, and tablet.

### U2 — Announce sliders in artist-facing units

**Severity:** medium. **Confidence:** high. **Scope:** medium.

Brush size, Fill, rail size/opacity, mixing, and tracing opacity sliders can
announce normalized fractions while the UI displays pixels, percent, or named
artist values. Centralize semantics without changing the slider’s internal
range. Spoken label and `stateDescription` must match the visible formatted
value and expose increment/decrement actions where useful.

Tests: semantics for boundaries, rounding, disabled state, and both locales.

### U3 — Make palette selection and actions semantic

**Severity:** medium. **Confidence:** high. **Scope:** small.

Palette selection is primarily a border, and built-in swatches can advertise
a long press with no effect. Expose selected state. Offer labelled edit/remove
actions only for user palettes; built-ins must not announce dead actions.
Test keyboard, TalkBack, long press, and palette deletion focus recovery.

### U4 — Validate and repair first-run TalkBack structure

**Severity:** medium. **Confidence:** medium-high. **Scope:** validate/small.

`FirstRunHint` wraps the screen as a clickable Button with “Got it” while also
containing instructions and a nested button. Semantics merging may hide the
instructions or duplicate dismissal. Inspect the semantics tree and real
TalkBack. Likely shape: pointer-only backdrop plus one semantic card containing
readable text and one explicit dismiss action.

### U5 — Replace Canvas Toasts with one transient host

**Severity:** medium. **Confidence:** high. **Scope:** medium.

Native Toasts bypass the planned themed, replacing, non-overlapping,
stroke-deferred feedback path. Build one Canvas-owned transient host with
priority, deduplication, rail/dock clearance, accessibility timeout, assertive
mode for blocking errors, and deterministic replacement. Migrate existing
locked/busy/storage/action notices together rather than adding another host.

Tests: priority/replacement policy, active stroke deferral, focus-mode
visibility, left/right/dock clearance, and TalkBack timeout.

### U6 — Keep Settings over the painting and make Tips replayable

**Severity:** medium. **Confidence:** high. **Scope:** product/architecture.

Opening Settings from Canvas first checkpoints the document, then navigates
to Studio. Closing it does not return to the drawing context. First-run
guidance is one-shot and omits real tools. Add a route-level Canvas Settings
overlay that keeps the painting alive, settle which preference changes may
recreate the Surface, and put a replayable Tips entry there. Include focus
mode, hidden More tools, active-tool re-tap, recent colors, gestures, and
shortcuts without another forced tour.

Tests: open/close during a dirty document and active-stroke deferral; rotate;
change handedness/theme; restore focus and Canvas state; replay Tips repeatedly.

### U7 — Fix left-handed ledge text at 200% font

**Severity:** medium. **Confidence:** high. **Scope:** small.

`SliderLedge` forces its opacity label to one line in an 88 dp control;
zh-Hans clips at 200% font. Allow growth/wrap or switch to an icon with a
complete content description at the same breakpoint used by `ToolRail`.
Screenshot both hands, locales, short windows, and dock/short modes.

### U8 — Do not strand inline layer opacity while busy

**Severity:** medium. **Confidence:** high. **Scope:** small.

`LayerPanel` clears `draggedId` when `documentBusy` changes but can leave
`opacityLayer` active with no commit path. On the transition, finish or cancel
the preview according to the document-action contract, call
`onOpacityFinished`, and clear all editing ownership exactly once.

Test busy transitions during drag, immediately after release, layer deletion,
and panel dismissal; assert one committed value and no stranded editor.

### U9 — Render transparency as transparency

**Severity:** medium. **Confidence:** high. **Scope:** small.

#117 explains the option but does not fix its visual language.
`NewCanvasDialog` and `LayerPanel` show `surfaceVariant` plus “∅”, which reads
as gray paper. Use the shared checker treatment and a localized “See-through”
label. Pin checker contrast in light/dark themes and screenshot every paper
swatch at 200% font.

### U10 — Let color fields survive incomplete input

**Severity:** medium. **Confidence:** high. **Scope:** small-medium.

The cursor-preservation fix does not stop hex input such as `F`→`FF`→`FFF`
from parsing and committing midway. Keep a local draft, validate complete
forms, and commit on blur/IME action or a documented debounce. Invalid and
partial text must remain editable without replacing the current color.

Tests: paste, delete, rapid typing, focus transfer, IME done, lowercase, alpha
forms, rotation/save restoration, and external color changes during a draft.

### U11 — Disambiguate duplicate painting titles

**Severity:** medium. **Confidence:** high. **Scope:** product/small.

Rename accepts duplicate non-blank titles, leaving visually identical shelf
cards distinguished only by relative time. Choose one policy: reject with a
localized message, generate a visible suffix, or always show a stable subtitle.
Apply the same rule to duplicate/import creation. Test Unicode normalization,
case, whitespace, and concurrent rename.

### U12 — Stop pressure-knot slider model churn

**Severity:** low-medium. **Confidence:** high. **Scope:** small.

Four knot sliders call `onPresetChanged` on every drag frame and recompose the
whole settings sheet. Keep local preview state and publish on
`onValueChangeFinished`, or use one bounded debounce if live canvas preview is
required. Flush on dismissal. Test final-value delivery, cancellation,
rotation, and write count during a long drag.

### U13 — Strengthen focus-mode affordance

**Severity:** medium. **Confidence:** high. **Scope:** small.

`FocusHandle` is 6×48 dp at 35% opacity, has weak discoverability, and lacks a
clear semantic action. Increase visible width/contrast without shrinking the
touch target, expose “Show controls,” retain handedness, and teach focus mode
once or through replayable Tips. Verify burn-in/occlusion against light and
dark artwork.

### U14 — Fix physical left-slider direction

**Severity:** medium. **Confidence:** high source, device validation required.
**Scope:** small.

`SliderLedge.mirrored()` applies `scaleX = -1f`, which mirrors paint but not
pointer-input coordinates. Left-handed drags can feel reversed. Remove the
graphics transform and keep the value axis consistent, or invert value and
semantics together. Test raw pointer positions and TalkBack actions, then
verify both physical hands on a device before merging.

### U15 — Make rail and tool capabilities discoverable

**Severity:** medium. **Confidence:** high. **Scope:** several small PRs.

- **U15a — Rail overflow.** The FULL rail can clip on short landscape
  windows and separates sliders
  from the active tool. Cap/scroll the tool region or anchor sliders; test the
  shortest supported height and 200% font.
- **U15b — Preset silhouettes.** New-canvas preset rows need a tiny
  aspect/orientation glyph, not only text
  and dimensions.
- **U15c — Recent-color cue.** Mark the recent-color chip as expandable; do
  not hide its history behind an
  unexplained long press.
- **U15d — Tool settings and More.** Add a subtle settings cue to active-tool
  re-tap. Teach tools buried in More
  and show the selected hidden tool’s name instead of leaving only “⋯” active.
- **U15e — Sighted timeout.** Screen-reader timing is already correct. Consider
  a longer sighted default or explicit dismissal so a child can inspect it.
- **U15f — HSV actions.** Give the HSV ring/square `customActions` for hue,
  saturation, and value.
- **U15g — Blur shortcut.** Assign or explicitly reserve a Blur shortcut
  after #125; avoid silently
  changing an established chord.
- **U15h — Water teaching.** Teach the Water tool’s distinct behavior; #112
  covers its hover geometry,
  not discoverability.
- **U15i — Dead glyph.** Audit the dead `PAINTBRUSH` glyph after #111 lands.
  Remove it or document its
  reserved role; do not reintroduce a glyph collision.
- **U15j — Watercolor preview.** Add a deterministic CPU Watercolor preview
  in `BrushPreview`: a soft wash
  band with the model falloff, granulation speckle, and a darker rim. Pin its
  pixels on the JVM; it is a settings preview, not a second live-paint oracle.

Each subchange needs keyboard/TalkBack semantics and both-locale copy where
visible. Keep tool coaching contextual and dismissible.

### U16 — Finish small polish and platform guards

**Severity:** low-medium. **Confidence:** high unless marked validate.

- **U16a — Picker halo.** Give the HSV marker contrasting light and dark
  halos at black/white corners.
- **U16b — Hover allocation.** Hoist eraser-hover dash/path arrays out of
  the draw loop.
- **U16c — Safe license intent.** Guard the About license intent when no
  activity can handle it; show a
  localized fallback rather than throwing.
- **U16d — Aging timestamps.** Age relative Studio timestamps while the
  shelf remains open without
  refreshing project storage.
- **U16e — Insets validation.** Validate edge-to-edge screenshots before
  changing suspected double-applied
  Studio system insets.
- **U16f — Preset names.** Ensure future built-in brush JSON cannot display
  raw `@string/...` tokens:
  `brushPresetName` must resolve every `assets/brushes/*.json` ID or a model
  resource key. Test the entire asset set and name offenders.

## Missing artist features

These are ordered roughly by value to a young artist. Persisted-format or
rendering-model changes require a proposal/plan update before code.

### A1 — Custom brush library

Allow “Save as brush,” naming, favorites, and rail reordering. Start with
size, opacity, flow, hardness, stabilizer, and mixing; place technical dynamics
under Advanced. Preserve built-ins, validate names/IDs, version serialized
presets, and preview migrations. Test fork/edit/delete/reorder/reopen and rail
budget behavior.

### A2 — Symmetry and kaleidoscope

Offer horizontal, vertical, and radial 4/6/8 modes with a visible movable
axis. Journal one logical stroke and derive mirrored dabs deterministically so
undo, prediction, and time-lapse do not see unrelated strokes. Test seams,
off-canvas axes, rotation, pressure, every paint model, and cancellation.

### A3 — QuickShape, rulers, and perspective

Hold to offer line/circle/rectangle snapping while preserving the original
until acceptance. Add straightedge/ellipse guides and later vanishing points
with optional snap. Keep suggestions reversible and disabled during gestures
that already own the hold deadline. Test noisy strokes, cancellation, zoom,
rotation, palm/chord arbitration, and journal replay.

### A4 — Pressure scratchpad

Add a disposable drawing area beside pressure curves so a child can feel the
preset without returning to a painting. It must never enter project history or
autosave. Exercise S Pen pressure/tilt, eraser end, touch fallback, reset, and
screen-reader labels.

### A5 — Selection, transform, and image layers

Add rect/ellipse/lasso selection, move/scale/rotate, and Photo Picker import as
a normal editable/exportable/undoable tiled layer. Reuse RMW ping-pong and keep
the permission-free Picker boundary. Define transform resampling, selection
mask storage, cancellation, memory bounds, and undo before UI. Test sparse
edges, clipping, GL loss, EXIF, huge images, and reopen.

### A6 — Text tool

Rasterize editable text to the active layer or define a versioned text layer;
settle fonts, fallback, shaping, transform, and when edits become pixels.
Offline/OFL assets need provenance. Test zh-Hans, emoji/fallback, multiline,
RTL, rotation, missing fonts, reopen, export, and undo.

### A7a — Layer groups and folders

Add collapsible `LayerStack` nesting with explicit group isolation, opacity,
blend, reorder, merge, flatten, thumbnail, and downgrade semantics. Reject
cycles and bound depth. Test nested sparse groups, moves, undo/reopen, and old
files before changing the format.

### A7b — Clip to below

Add a journaled `clipToBelow` layer property and restrict upper coverage to the
resolved lower layer/group. Specify behavior when the base is hidden, deleted,
reordered, sparse, or itself clipped. Pin CPU/GPU compositing and flatten.

### A7c — Adjustment layers

Add nondestructive Hue/Saturation/Brightness only after defining placement,
masking, group interaction, thumbnails, flatten/export, and downgrade behavior.
Use one CPU/GPU color oracle; test transparent and premultiplied edges.

### A8 — Artist inspection tools

#132 owns actual-size zoom. Remaining: nondestructive horizontal mirror,
grayscale/value preview, and layer solo. Keep these view-only so they never
dirty pixels or export accidentally. Test transform composition, hover/input
mapping, transparent paper, and restoration after GL loss.

### A9 — Export and portable backup

Add current-layer PNG and OpenRaster project export. Keep shares permissionless,
stream large outputs, preserve blend/opacity metadata where ORA supports it,
and clearly report unsupported features. Test transparent/sparse layers,
Unicode names, low storage, cancellation, and import into an independent ORA
reader.

### A10 — Gradient fill, crop, and resize

Implement multi-stop Mixbox gradients through the document-action/journal
model, plus crop/resize with explicit anchor and resampling choices. Bound
memory and make cancellation atomic. Test transparent stops, every blend mode,
odd edge tiles, grow/shrink, undo/reopen, and checkpoint kills.

### A11 — Full history management

#119 adds only a compact redo readout. Add a sheet showing operation kind,
layer, undo/redo depth, and disk use; allow per-painting history cleanup that
never changes current pixels. Test cleanup at each journal/checkpoint state and
prove reopen preserves the displayed image.

### A12a — Journal-delta session playback

Build a coarse local player from persisted tile deltas and checkpoint state.
Label it as session playback: commit order has no true stroke timing. Bound
working memory, omit private tracing references, and test seek/cancel, corrupt
tails, cleanup boundaries, and pixel parity with the current painting.

### A12b — True event-stream time-lapse

True time-lapse needs stroke evolution and event time that tile deltas do not
contain. Define a bounded, versioned event/stroke stream before promising it.
Preserve cancellation, prediction policy, migrations, and privacy; make
recording opt-in if it materially increases storage.

### A13a — Blend-mode visual gallery

Show live per-mode thumbnails instead of names alone. Reuse the compositing
oracle and test every mode, opacity, transparent input, keyboard/TalkBack
selection, and constrained panel layouts.

### A13b — Richer color history

Offer a chronological session/project color ribbon with explicit retention,
capacity, duplicate, and clear semantics. Test color-space consistency, large
histories, keyboard/TalkBack selection, and reopen.

### A14 — Pixel mode

Add a square/pixel canvas preset, nearest-neighbor zoom, pixel grid, optional
grid snap, and one-pixel tools. Define when smoothing is disabled and ensure
exports contain no guide. Test fractional transforms, rotation, stylus hover,
selection, and exact PNG pixels.

### A15 — Stamps and stickers

Start with repo-owned or CC0 vector silhouettes; record provenance in
`AGENTS.md`. Texture brushes remain blocked until the grain loader and asset
contract exist. Define tint, scale, rotation, spacing, and journaling; do not
smuggle unlicensed clip art into the APK.

### A16 — Small workflow features

- Clear Painting, with confirmation and one undoable document action.
- A launcher “Continue” shortcut to the latest available painting.
- Crop-safe composition teaching around the existing guides, not another
  duplicate guide engine.

## Delight and proposals

Keep these optional, offline, and free of accounts, feeds, streaks, ads, or
completion pressure.

- **Idea Spark:** one dismissible Studio prompt drawn from an offline set.
- **Brush dice:** seeded, reversible mutations from a saved brush, previewed
  before acceptance.
- **Pigment recipes:** friendly mix language (“mostly blue, a little yellow”)
  and optional drag-to-smear in the dish.
- **Contextual tool coach:** brief first-use labels for ambiguous/hidden tools;
  replayable through Tips.
- **Paper personality:** stable canvas-space procedural tooth. Decide
  explicitly whether it exports; record provenance for any texture asset.
- **Edge resistance:** restrained pan overshoot with reduced-motion support.
- **Ambient shelf:** a faint color wash sampled from the newest painting.
- **Visible card overflow:** keep long press, but expose management visibly.
- **Favorites and collections:** stars/folders without counts or gamification.
- **Friendly color names:** optional names alongside exact HSV/RGB values.
- **Mirror check:** a temporary view-only flip prompt, never a pixel mutation.
- **Share card:** optional “Made with 帮你Draw” frame generated locally after
  an ordinary clean export remains available; add an optional reduced-motion
  post-share celebration without delaying the chooser.
- **Onion skin:** optional previous/next-frame overlays after a real frame
  model exists; never bake them into paint.
- **Radial quick color:** pen-button/long-press color wheel only if it does not
  conflict with eyedropper, palm, or system gestures.
- **Tilt ink drip:** proposal for opt-in device-tilt advection/pooling in wet
  paint. Define sampled tilt events for deterministic replay, reduced-motion
  behavior, and a clear cancel path before touching `WatercolorWetKernel`.
- **Mascot/illustrated empty state:** one restrained character or illustration
  can make Studio warmer without repainting the whole product.
- **Motion polish:** one focus-handle pulse, a restrained 150 ms destination
  crossfade, and a paint-blob-shaped transient; honor reduced motion and avoid
  animation on the drawing hot path.
- **Pen-up haptic:** a restrained completion tick, default off and separate
  from sound; measure latency and retain accessibility/provider gating.

Record these rather than implementing them as shortcuts:

- **Magic Ink — proposal or decline.** Hidden RGB in transparent pixels breaks
  the premultiplied tile invariant; it cannot be a preset trick.
- **Rainbow brush — blocked.** Per-dab color needs a new dab buffer, preview,
  merge, journal, prediction, and shader contract.
- **Shake to undo — proposal/likely decline.** False triggers and motor/access
  concerns require an explicit default-off decision and strong motion guard.
- **Sound brush — proposal/likely decline.** It must be default-off, latency-
  safe, accessible, and use licensed/CC0 audio with provenance.

## Visual direction

Preserve the canvas-first physical-handed layout, warm paper/slate identity,
indigo brand field, sparse chrome, focus mode, generous touch targets, and
artwork-led shelf. #141 owns four fixed selectable palettes and replaces
system-following light/dark behavior; it is not an arbitrary accent picker. If
that policy lands, record it before proposing generated custom-accent tokens.
Do not build a competing theme path while #141 is open.

Incremental backlog:

- Tint the New Painting cell with restrained `primaryContainer`.
- Give shelf art about 1 dp of lift; keep shadows out of the canvas.
- Round only the dock’s top corners so dock and rail read as one object.
- Add one OFL display face for the Studio name/panel headers only, with
  provenance; keep controls and canvas labels in the system face.
- Let child delight come from art, contextual teaching, and one illustration,
  not denser chrome or a broad toy-like redesign.

#143 owns responsive HSV sizing, #120 panel-close affordances, and #117 the
transparent-paper explanation.

## Acceptance gate

The app is not child-ready until this correctness and fault matrix passes. D7
is the canonical S Pen, frame-pacing, memory, and thermal gate and requires real
hardware. Emulators may cover only layout, process-kill, corruption, and
storage-provider injection. They cannot clear S Pen, accessibility, performance,
thermal, or signed-upgrade gates.

- Reopen a dense 4096²/eight-layer painting and try to draw immediately;
  observe progress, memory, upload ordering, and cancellation.
- Exercise every paint tool plus undo/redo, background/foreground, rapid zoom,
  rotation, app background/foreground, Surface recreation, and forced GL loss.
- Fill storage; deny and revoke Gallery access; inject corrupt tile,
  reference, `project.json`, history entry, and thumbnail files.
- Kill the process at every checkpoint phase: journal publication, tile
  readback/write, metadata temp/rename, thumbnail, Gallery sync, and cleanup.
- Repeatedly reopen panels and Studio actions while drawing to expose leaked
  workers, collectors, stale Deferreds, and duplicate full-canvas jobs.
- TalkBack and Accessibility Scanner at 100/200% font on the shortest phone
  window; keyboard-only, mouse/wheel, left-hand mode, and RTL locale without
  mirroring the physical rail.
- Human review of native zh-Hans wording.
- Screenshot matrix: phone portrait/landscape, 7-inch and 14-inch tablet,
  light/dark, transparent paper, every Canvas panel/dialog/popover, empty and
  dense shelf, and every error/progress state.
- On real hardware, test a release-build upgrade from the published signing
  lineage with old projects, prefs, journal, custom presets/palettes, and
  Gallery links intact.

Store device model, OS, refresh rate, build SHA, document fixture, traces, and
screenshots with the result. Source confidence is not a substitute.

## Verified clean and durable notes

Do not reopen these without new evidence:

- At the review snapshot, JVM tests, lint, debug/release assembly, and the
  no-Mixbox build passed; the manifest requested no permissions. Backup and
  share boundaries were sane.
- Front-buffer drain/tail folding, navigation pointer-up compaction,
  per-pointer axis reads, predictor ownership, and live zoom state were correct
  at the review snapshot. This does not clear the cancellation, overflow,
  stationary-deadline, or prediction-reserve work in #116/#138/#144/#146.
- Touch processing retained scratch/ring storage and avoided per-sample
  allocation. `pendingStrokeFallback`, `SandwichCache` ping-pong,
  `CompositePass` batching, and `HalfTurn` producer/consumer symmetry held.
- Clean-file structural replay, the `nextName` floor, two-locale string
  completeness, RMW `ResolveCurrent` ordering, and intentional navigation-only
  Compose recomposition were verified. R2 remains open for publication crashes
  and sequence gaps; do not generalize this to all recovery.
- Watercolor base reservation ordering and edit policy, tracing-reference
  codec/policy, rail-budget selection, Chinese Ink dynamics, and
  `CanvasActionGate` were verified. #107 and #127 own separate cancellation-
  restore and epoch-rebase defects; do not infer those PRs are complete.
- The pure gesture arbiter’s existing per-pointer/chord paths, Fill
  generation/cancellation, leave/checkpoint scrim policy, `CpuFlatten`
  sparse-read skipping, `Composite` zero-opacity
  skipping, deliberate hidden-layer preview, merge confirmation, remembered
  dish gradients, and reference-panel gesture routing were verified.
- Layer-thumbnail polling is panel-gated; scrim accessibility/focus gating and
  the base theme were coherent.
- `MixingDish.gradient`, thumbnail memory caching, composition guides,
  pressure curves, and other completed work are not backlog items.
- `builtin.paintbrush` is the Watercolor preset. The app has exactly two
  string locales: `values/` and `values-b+zh+Hans/`; brand-only omissions are
  deliberately non-translatable.
- AAPT2 startup failure was transient in the review sandbox; stop/retry or CI
  settled it. Do not diagnose it as an app defect without new evidence.

Implementation notes:

- For source-contract tests, collapse whitespace, scope assertions to each
  call site/function, make regex matches non-vacuous, and name offenders.
- `rememberSaveable` needs a `Saver` for pure data classes not supported by
  Android’s Bundle. Rotation crashes are invisible to the JVM-only suite.
- The open-PR list changed during prior reviews. Refresh it both when choosing
  work and immediately before opening a PR.
