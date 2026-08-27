# 帮你Draw audit

Date: 2026-08-27
Reviewed: `b69f4bd` (`v1.0.2`)

## Baseline

- `testDebugUnitTest`, `lintDebug`, `assembleDebug`, release/R8 assembly, and
  the no-Mixbox debug test/lint/assembly path pass.
- The JVM suite has 835 `@Test` methods. Its pure engine coverage is broad.
- No phone, tablet, Fold, S Pen, TalkBack, Accessibility Scanner, screenshot,
  upgrade-install, thermal, or native zh-Hans acceptance run has happened.
  Drawing latency, layout quality, and memory ceilings are therefore
  unverified on hardware.
- Findings below are source-proven unless marked **Device** or **Design**.

Disposition:

- **Now**: bounded, testable, and suitable for an isolated PR.
- **Next**: desirable, but coupled enough to retain for focused follow-up.
- **Design**: requires a proposal or persistence/UI decision first.
- **Device**: do not claim fixed until hardware acceptance passes.

## Data integrity and correctness

### C01 — Make checkpoints revisioned and flush-aware

**Critical · Next**

`CanvasViewModel.checkpoint()` ignores a failed
`TileFlusher.checkpointFlush()`, writes `project.json`, and clears
`dirty/contentDirty` (`CanvasViewModel.kt:2193-2245`). RAM-only pixels can then
be treated as saved. The same function reads, folds, and replaces the
Main-confined `document` from the application IO scope. A concurrent title,
layer, history, or readback change can be overwritten and then marked clean.

Implement one snapshot protocol, not two patches:

1. On Main, snapshot the immutable document, journal state, pending deletes,
   content flags, and a monotonic mutation generation.
2. Drain only work belonging to that snapshot.
3. Write on IO.
4. On Main, clear flags only when the generation still matches.
5. If tiles did not drain, retain dirty state, skip thumbnail/gallery work,
   retry with a bounded delay, and never report leave/export as saved.

Test a paused checkpoint with a concurrent edit, and a writer that fails once
then recovers. Reopen must contain both exact pixels and the newer metadata.

### C02 — Stop an older checkpoint stealing a later stroke's pixels

**Critical · Next**

A checkpoint can queue before a stroke's `WriteEntry`, then flush the stroke's
new readback before that entry reads its old disk pixels. The undo payload then
contains the after-image and Undo becomes a no-op
(`TileFlusher.kt:281-309`). Protect/capture checkpoint keys at enqueue time, or
otherwise reserve entry keys until their before-payload is durable. Coordinate
this with C01 so `project.json` never folds work beyond the same boundary.

Deterministic test: disk A; queue checkpoint; queue entry with no mirror copy;
inject B; drain. The entry must contain A and disk must end at B.

### C03 — Add a recoverable stroke WAL

**Critical · Design**

An entry becomes durable before its changed tiles. A kill in that gap reopens
the old pixels while the journal calls the stroke applied. Stroke/fill recovery
cannot reconstruct pixels because entries store only before-images
(`HistoryStore.kt:160-170`, `HistoryRecovery.kt:11-29`). `ISSUES.md` currently
calls the related window pixel-safe; it is not.

Use a durable after-image WAL plus a completion marker. Recovery must handle:

- entry only;
- after-image plus a partial tile flush;
- completed tiles before WAL cleanup;
- storage-full retries.

A marker without an after-image restores consistency only by discarding the
stroke. Record the chosen transaction protocol in an ADR.

### C04 — Hold document actions through stroke journal commit

**Critical · Now**

Pen-up releases `CanvasActionGate` before `WriteEntry` is appended and pushed
(`CanvasViewModel.kt:1328-1448`). A queued Undo can run first; the later push
then treats it as a branch and truncates history.

Separate `input ended` from `commit complete`. Restore tools/chrome at pen-up,
but hold document mutations until the history push or explicit unjournaled
fallback finishes. Empty, cancelled, refused, eyedropper, and fill paths must
release exactly once.

Test: existing entry → delayed new stroke → queued Undo → commit. The new
entry must be pushed before Undo and neither entry may be truncated.

### C05 — Gate drawing until reopen uploads complete

**Critical · Next**

The screen becomes interactive before disk tiles are queued to GL. A late
upload can overwrite a fresh stroke on the same tile
(`CanvasViewModel.kt:516-619,2101-2130,2331-2355`). `uploadTiles(last)` has no
completion barrier and redraws/invalidate caches per batch.

Add a session-generation upload barrier. Block drawing and history mutation
until all required tiles are acknowledged; ignore stale-session callbacks.
Then add visible-first ordering, bounded staging (target ≤ 16 MiB), per-key
invalidation, and one coalesced redraw.

Test with paused uploads, a stale replacement session, and a draw attempt.

### C06 — Make leave-gallery sync one durable operation

**High · Design**

Checkpoint launches gallery sync but does not await it. Leave navigates, the
dead ViewModel later receives a URI and becomes dirty, while Studio may start a
second stale sync. Two MediaStore rows can be inserted
(`CanvasViewModel.kt:2139-2300`, `StudioViewModel.kt:206-237`).

Normal path: await the leave sync, checkpoint its URI, then navigate. Crash-safe
exactly-once behavior needs reserve-row → persist URI → write/finalize, or a
stable project ID stored/queryable in MediaStore. Add an ADR for the cross-store
transaction.

### C07 — Treat failed sparse-tile deletion as a failed write

**High · Now**

`TileStore.write()` ignores `File.delete()` failure for an all-zero tile
(`TileStore.kt:47-57`). A stale nonzero tile can reappear after reopen. Throw
`IOException` when an existing tile cannot be deleted so the flusher retains
the mirror, shows storage-full state, and retries.

Test delete failure, retained pending bytes, successful retry, and empty reopen.

### C08 — Never replace missing metadata with a blank painting

**High · Now**

`ProjectStore.load()` returns `NOT_FOUND` for a missing `project.json`, but
Canvas creates a blank document under that ID (`CanvasViewModel.kt:480-505`). A
damaged folder with surviving layers can later be overwritten by blank
metadata. Studio already owns creation. Treat `NOT_FOUND` as open failure and
leave the directory byte-identical.

### C09 — Serialize per-project metadata transactions

**High · Next**

Rename, gallery-field update, background sync, and delete rewrite or move the
same project without a keyed lock (`ProjectStore.kt:138-293`). Concurrent
read-modify-write operations can revert titles/URIs; a late sync can act on a
deleted project. Route them through one per-project transaction owner and
cancel/join stale sync before destructive work.

### C10 — Stage duplication transactionally

**Medium · Next**

Duplicate copies directly into the final UUID directory and leaves it behind
on failure. Studio skips it because it lacks valid metadata, creating invisible
permanent storage (`ProjectStore.kt:179-249,466-477`). Copy into a marked temp
directory, fsync/commit metadata, rename atomically, and sweep abandoned temps.

### C11 — Enforce history size after redo sidecars

**Medium · Now**

`HistoryJournal.noteRedoBytes()` adds sidecar bytes but prunes only on a later
edit. Undoing many entries can nearly double the advertised history cap
indefinitely. Return immediate pruning results, preserve at least one entry,
and delete pruned files only after the next checkpoint.

### C12 — Contain MediaStore provider failures

**Medium · Next**

Initial ownership query and post-write `outcomeOf()` are outside the controlled
failure boundary; delete catches only `SecurityException`
(`GalleryExporter.kt:48-79,143-149,190-211`). Stale/custom providers can also
throw `IllegalArgumentException` or runtime failures. Encapsulate MediaStore in
a driver seam; treat an invalid recorded row as absent and a post-write probe
failure as a retryable failure. Test every provider operation with a fake.

### C13 — Give every share a unique staging location

**Medium · Now**

`ShareCache.stage()` uses the human filename directly. Sharing two paintings
with the same title overwrites the first URI while its receiver may still be
reading it, contradicting the retention promise. Put each share in a unique
child directory while keeping the friendly basename, then rotate whole share
directories. Validate the basename at the boundary.

### C14 — Stop Canvas flusher workers at end of ownership

**Medium · Next**

Every Canvas starts a `TileFlusher` receive loop in application scope and
discards its `Job`; `onCleared()` never stops it. Each open leaks a worker,
channel, pool, and captured project references for the process lifetime.
Queue an idempotent shutdown after the final checkpoint/drain, or use one
application-owned keyed registry. Test repeated open/clear cycles.

### C15 — Persist the document workspace that the schema already defines

**Medium · Next**

`ProjectFile.view` and `lastTool` exist but are never written or read. Reopen
resets position, tool, color, size, and opacity. Round-trip validated workspace
state; restore view only when saved and current viewport dimensions are within
the specified 10%, otherwise fit. Reject invalid floats and unknown tools or
presets without rejecting the painting.

### C16 — Render exact shelf thumbnails

**Medium · Design**

`Thumbnails.write()` approximates every non-Normal layer as source-over even
though blend modes ship. Use exact `Composite` semantics in a tile/band-bounded
pipeline. Add golden pixels for every blend mode, opacity, visibility, edge
tile, and transparent paper. Do not reintroduce a full-canvas allocation.

### C17 — Do not silently commit partial strokes on GPU pool exhaustion

**Critical · Design**

`DabPass` skips tiles when no slice exists; merge can retain earlier swaps and
continue. History then records a successful commit for only part of the mark.
Preflight/reserve the entire transaction or render into temporary outputs and
swap atomically. Exhaustion must become an explicit, localized refusal.

## Drawing latency, memory, and performance

### P01 — Make input and dab continuation lossless under backpressure

**Critical · Design**

When all eight `DabRing` slots are held, Canvas drops the current/historical
sample. Separately, `DabGenerator` advances its endpoint when a batch fills and
has no remainder protocol. GL stalls or a dense pen-up catch-up can create gaps.

Add a bounded, preallocated raw-input queue and explicit
`full-with-remainder` continuation. Never advance generator state past
unemitted dabs. Compare a stalled eight-slot run with an unlimited oracle.

### P02 — Budget and validate sandwich caches

**High · Next**

Above and Below caches allocate dense visible-tile slices, including empty
Above and paper-only Below. A fitted 4096² canvas can consume about 128 MiB not
fully represented in `MemoryBudget`. Failed rebuilds can still be treated as
ready. Avoid empty Above allocation, include all transient/cache slices in the
budget, require requested-key completeness, and fall back to direct
composition when incomplete.

### P03 — Remove steady-state drawing allocations

**High · Now (bounded first slice)**

Each dab creates immutable rectangles in `DabBatch` and again in `DabPass`;
frame composition creates pairs/lists/matrices; `ConcurrentLinkedQueue`
allocates a node per published batch. The only allocation test exercises
two-finger navigation and permits 80 bytes per move.

First slice: store mutable primitive dirty bounds in `DabBatch` and primitive
bounds in `DabPass`, with an allocation regression test. Follow with a
preallocated SPSC publication ring and cached frame geometry after replay
instrumentation exists.

### P04 — Skip release `glGetError` drains

**High · Now**

`GlErrors.check()` always drains `glGetError`; `strict` changes only reporting.
Several calls occur in a cached live frame. Return before querying when strict
diagnostics are disabled. Keep explicit allocation checks where failure affects
correctness. Validate debug behavior with unit/source contracts and profile
release on Adreno and Mali.

### P05 — Reuse smudge/blur offscreen targets

**High · Now**

Smudge/blur calls `OffscreenTarget.ensure()` with every dab's dimensions; the
target deletes and reallocates if either side changes. Pressure can therefore
cause `glTexStorage2D` churn per dab. Make capacity grow-only or bucketed and
use viewport/scissor for logical dimensions. Test a pressure ramp: no delete or
storage allocation after capacity is reached.

### P06 — Replace fill's full-canvas boxed pipeline

**High · Next**

Fill performs a boxed map lookup per pixel, allocates several full-canvas
masks and span objects, and snapshots every relevant GPU tile synchronously in
one GL task. A 4096² fill can exceed 128 MiB and cancellation cannot interrupt
the snapshot.

Sequence:

1. Dense packed tile index and primitive span stack.
2. Allocation/time tests on blank and noisy 4096² fixtures.
3. Progressive PBO faults and cancellable expansion.
4. Bounding-box masks instead of whole-canvas scratch.

### P07 — Keep readback off the live-frame critical path

**High · Design**

Readback may wait up to one second, maps/copies/scans up to 16 MiB on GL, and
runs before live composition. `READBACK_MIN_FRAME_AGE` is unused. Retain pending
ranges, age and budget mapping per GL entry, prioritize the front render, move
CPU scans/copies off GL, and never synchronously finish fences from the
flusher. Preserve commit ordering from C01–C03.

### P08 — Enforce a real CPU-mirror ceiling

**High · Design**

`TileFlusher.hasMirrorRoom()` has no production caller; every readback is
accepted. Blocked/full storage can grow the heap toward the whole document.
Admission must happen before a commit, preserve pixels, and surface an explicit
refusal or saving state. Coordinate this with storage-full recovery in C01.

### P09 — Conflate eyedropper sampling

**Medium/high · Next**

Every raw/historical sample queues GL work. Radius four performs 81 synchronous
1×1 `glReadPixels` calls. Sample only the newest point once per display frame,
read one reusable rectangle, and discard stale generations.

### P10 — Stream flattening, encoding, and thumbnails

**Medium/high · Design**

A 4096² flatten allocates 64 MiB, encoding adds an equal bitmap plus compressed
copies, and JPEG adds another bitmap. Thumbnail generation rereads/inflates all
visible tiles. Build a banded encoder or bounded GL flatten; check cancellation
per band/tile; update thumbnails incrementally/downsampled. Keep the existing
CPU path only as a bounded fallback.

### P11 — Stop unnecessary background wakeups

**Medium · Next**

Layer thumbnails poll Main at 10 Hz even when clean; prediction runs for RMW
tools that discard its output. Use event-driven thumbnail wakeups and disable
prediction when the selected tool cannot consume it. Add thermal/memory hooks
to cancel background flattening and shed optional caches.

### P12 — Make Studio loading scale with visible work

**Medium · Next**

`ProjectStore.list()` recursively stats every file in every project before
publishing the shelf, despite the startup plan saying metadata-only. Visible
cells decode thumbnails repeatedly and the budgeted `LruCache` does not exist.
Publish metadata first, compute storage totals separately, and add a revision-
keyed thumbnail cache sized by `MemoryBudget.thumbnailCacheBytes`.

### P13 — Single-flight long operations

**High · Next**

Repeated share/export/save/duplicate taps can launch concurrent full flattens
or writes. Model the current operation in UiState, reject duplicates, cancel
obsolete work, and show progress only after a short delay so fast operations
do not flash UI.

### P14 — Build the performance harness promised by the plan

**High · Now (CI/build slice), then Device**

No replay harness, benchmark task, Perfetto script/trace sections, GPU timers,
JankStats, GC/thermal counters, or actual drawing allocation gate exists.

Immediate slice:

- add the already-passing no-Mixbox release path to CI;
- add deterministic input/dab replay and allocation tests.

Device slice: measure input-to-front latency, p50/p99 callbacks, reopen, fill,
memory high-water, cold first stroke, long-session thermal behavior, and
front-buffer fallback on at least one Adreno and one Mali/S Pen device.

### P15 — Profile before changing suspected driver costs

**Device**

Possible costs needing traces, not source-only fixes: framebuffer completeness
checks on every bind, full redraw frequency during historical navigation,
eager shader linking/cold PBO allocation, and texture-page high-water retention.

## UX, layout, accessibility, and visual quality

### U01 — Never silently abandon a pen-down stroke on navigation

**High · Now**

Back and Settings remain actionable during a stroke. Leave checkpoints the last
commit, then surface disposal cancels the visible live stroke. Park navigation
until the stroke journal commit completes, then checkpoint and leave. This is
not corruption of committed pixels, but it violates the UI plan and user
expectation. Test hardware/system Back, top Back, and Settings through one
shared leave policy.

### U02 — Repair the New Canvas dialog

**High · Now**

Four confirmed issues belong in one dialog-level change:

- Default selection uses the largest memory-supported preset although the plan
  requires the largest preset fitting the current screen. An 8 GB phone can
  default to 4096².
- Orientation initializes to Portrait regardless of the window, while row text
  always shows the natural dimensions even when creation swaps them.
- The custom row packs radio, label, separator, and two fixed 96 dp fields into
  one compact row.
- Paper swatches expose only a 28 dp touch target.

Add a pure screen-fit selector using window pixels/orientation, display the
actual oriented size, put custom dimensions on a responsive second row, and
place the 28 dp visual inside a 48 dp target. Test 320/360 dp, landscape, and
200% font on device after pure/contract tests pass.

### U03 — Keep panels clear of rail, dock, and ledge

**High · Now**

Only floating panels reserve rail width; full/side sheets sit beneath the rail,
dock, and slider ledge. Bottom controls such as Paper can be obscured. Add pure
panel insets derived from `LayoutSpec.persistentChrome`, apply side/bottom
geometry for every rail mode and hand, and test representative phone/tablet
windows before device acceptance.

### U04 — Add settings for Smudge, Blur, and Eyedropper

**High · Next**

These shipped tools reset to defaults and expose no tuning. Add persistent
Smudge/Blur size and strength ledges/sheets, plus Eyedropper source and radius.
Keep parameters in ViewModel/prefs; UI must not call engine mechanics directly.
Split one PR per tool family.

### U05 — Report Studio operations truthfully

**High · Next**

Create/share/rename/duplicate errors are silent; Delete shows success before
the store confirms it. Return typed results, keep/reopen user input on failure,
and announce success only after durable completion. This should share the
single-flight operation state from P13.

### U06 — Fix compact field overflow

**High · Now**

Three fixed 92 dp RGB fields plus gaps need 288 dp inside a 300/320 dp panel
that already loses 40 dp to padding. Use equal weights or a responsive flow/two
line layout. Verify 320 dp and 200% font.

### U07 — Close core accessibility gaps

**High · Now in small PRs, then Device**

- Raise 40 dp layer controls and 28 dp paper targets to 48 dp hit areas.
- Add selected/state semantics to layers and swatches.
- Give layer reorder Move up/down/top/bottom custom actions; pointer drag alone
  is inaccessible.
- Expose HSV as named adjustable hue/saturation/value controls; remove the
  current-color no-op click.
- Label Fill slider semantics and make the whole switch row toggleable.
- Omit unavailable Canvas Undo/Redo actions instead of always returning true.
- Mark storage-full as a live region.
- Add `selectableGroup` to Settings choice groups.

Finish with TalkBack, Accessibility Scanner, switch access, 200% font, and
touch-target checks on device.

### U08 — Complete the real-device acceptance matrix

**High · Device**

Required before another quality claim: compact phone, medium tablet/Fold,
expanded tablet/DeX; gesture and three-button navigation; 60/120 Hz; S Pen
pressure/tilt/hover/button/eraser/palm; mouse/keyboard; light/dark; 200% font;
TalkBack/Scanner; process death; full storage; upgrade install; native zh-Hans;
Adreno and Mali performance traces.

### U09 — Complete and validate theme tokens

**Medium/high · Next + Device**

Material tokens used by screens are missing from the custom schemes, allowing
stock colors to leak in. Some active borders have weak contrast (about 2.38:1)
and dim panel text is near 4.41:1. Define all used tokens, prefer subtle saffron
containers with indigo selection, add contrast tests, and inspect both themes
on device.

### U10 — Give tools distinct icons

**Medium/high · Design**

Pencil/Smudge and Airbrush/Blur share icons; Eraser uses a delete-sweep symbol.
The unlabeled rail needs distinct silhouettes. Create a small coherent vector
set and validate recognition at actual rail size in both themes.

### U11 — Resolve immersive-mode document conflict

**Medium · Design + Device**

System bars are always hidden, while one UI section ties immersive entry to
Focus mode. Decide the behavior, record it, and test gesture plus three-button
navigation. Do not change it from source preference alone.

### U12 — Replace system Toasts with one notification host

**Medium · Next**

The plan calls for one styled, state-driven transient message clear of chrome,
deferred during strokes, with accessibility announcements. Current Toasts and
bottom overlays can overlap each other and the reset pill. Build one stacked
host for notices, fill progress, storage-full, and delayed busy state.

### U13 — Add fine brush adjustment and true-size preview

**Medium/high · Next**

`ThinSlider` is a normal Material slider. Add the planned off-slab 0.25× fine
gain and a true canvas-scale preview blob near the pointer. Keep a named numeric
accessibility action. Test pointer mapping as pure arithmetic.

### U14 — Replace pressure sliders with an accessible curve editor

**Medium · Design**

Four unrelated sliders obscure the pressure response. Add a draggable graph
with visible knots while retaining exact, named numeric controls for TalkBack.

### U15 — Hide ineffective pigment controls under RGB mixing

**Medium · Now**

Mixing controls appear for every non-eraser even when the selected RGB mixer
makes them ineffective. Pass mixer capability into the sheet and hide or
clearly disable the group with a reason.

### U16 — Make palettes scan-friendly and onboarding finite

**Medium · Next**

Recent colors can hold 16 swatches in one horizontal strip, while the plan says
eight per row. Use a responsive 48 dp grid/flow with selected semantics. Show
the mixing hint once, then persist dismissal or replace it with a tooltip.

### U17 — Make Studio cards useful visual objects

**Medium · Now**

Transparent paintings sit on a flat theme surface; only the image box opens;
wide empty Studio omits the autosave reassurance; same-path thumbnail rewrites
can retain a stale decoded bitmap. Add a lightweight checkerboard, make the
whole card one merged click/semantic target, show empty guidance on every
width, and key decode/cache by painting revision.

### U18 — Show delayed loading and busy states

**Medium · Next**

Canvas loading is blank; leave can appear frozen; concurrent share/export gives
no progress. Show nothing for fast work, then a labeled progress/scrim after a
short threshold. Keep Back semantics explicit and do not cover a live stroke
without first applying U01.

### U19 — Expose history capacity

**Low/medium · Next**

Step/byte/max values exist in `UiState` but nowhere in Canvas UI. Add a compact
overflow row or popover rather than persistent chrome.

### U20 — Add mouse-wheel zoom through the input abstraction

**Low/medium · Next + Device**

Canvas installs touch and hover listeners only. Route generic-motion wheel zoom
through `CanvasTouchHandler`, anchor it at the cursor, and test mouse/DeX.

### U21 — Use plural resources

**Low · Now**

English renders `1 paintings` and similar history counts. Use `<plurals>` and
the appropriate zh-Hans form.

### U22 — Add custom paper color at creation

**Medium · Design**

The dialog still offers five fixed colors despite the full color picker now
existing. Reuse a compact picker without nesting a large Canvas panel inside an
AlertDialog; preserve 48 dp targets and live contrast.

### U23 — Reconsider compact layer-panel auto-close

**Low · Device**

Selecting a layer immediately closes the compact panel, making multi-layer work
repetitive. Observe a real phone workflow before changing; likely keep it open
until canvas contact or explicit dismissal.

### U24 — Add restrained list motion

**Low · Next**

Studio items are keyed but survivors jump after deletion/duplication. Add small
placement/removal motion and honor disabled system animations.

## Missing features already in the product roadmap

Do not bypass the proposal process. Suggested priority:

| Priority | Feature | Reason / gate |
| --- | --- | --- |
| 1 | Pressure calibration UI | High S Pen value; needs Samsung-device sampling. |
| 2 | Symmetry | Small engine change, strong delight; define guides and undo. |
| 3 | Import image as layer/reference | Common workflow; system Photo Picker keeps permissions at zero. |
| 4 | OpenRaster export | Narrow interoperability feature; validate format mappings. |
| 5 | Custom brush JSON/grain import | Useful and bounded; strict schema/size/provenance checks. |
| 6 | Rulers and shape assist | Ordinary dab strokes preserve brushes and journal semantics. |
| 7 | Gradient fill | Build after fill memory/cancellation work. |
| 8 | Crop/resize | Whole-document journal and memory plan first. |
| 9 | Selections and transform | Expected, but large mask/floating-layer design. |
| 10 | Tile eviction/residency | Needed to lift layer caps; major cache protocol. |
| 11 | Wet watercolor | Defer until latency, memory, and thermal gates pass. |
| 12 | Time-lapse | Defer until bounded flatten/encode exists. |
| 13 | PSD import | Value is uncertain relative to complexity; proposal must justify it. |

## Novel, delightful opportunities

### N01 — Mirror View

Non-destructive horizontal workspace flip for spotting proportion errors.
Input and overlays follow the flipped view; stored pixels and exports do not.

### N02 — Palette from Painting

Quantize the current bounded thumbnail into six to eight editable swatches.
Decide whether paper participates and expose the generated palette as ordinary
user colors.

### N03 — Solo Layer Peek

Press-and-hold visibility temporarily solos a layer; release restores the exact
visibility stack. Also provide a normal accessible Solo command.

### N04 — S Pen hover scrub

Hover plus side button adjusts size horizontally and opacity vertically while
the cursor previews both. Research actual Samsung event delivery before design.

### N05 — Canvas navigator

A temporary minimap appears only when zoomed in or during a two-finger hold. It
shows the viewport, allows a quick jump, then fades. Particularly useful on
4096² work without adding permanent chrome.

### N06 — Two-slot tool swap

Let the user pin two tools/colors and toggle them with one rail gesture or
keyboard shortcut. This gives pencil↔eraser and ink↔smudge speed without a
larger rail.

### N07 — Tilt-aware cursor

For chisel/flat brushes, preview tip angle and elongation under hover. The mark
becomes predictable before contact and makes S Pen tilt feel intentional.

### N08 — Saved canvas recipes

Allow named local presets such as “Pocket sketch” or “Square poster” containing
size, orientation, paper, and initial layer count. Keep the default dialog
simple; recipes appear after first save.

### N09 — Playful symmetry feedback

When symmetry is eventually added, snap the axis with a quiet haptic and show a
brief mirrored cursor flourish. It adds delight without changing raster or
undo semantics.

## Implementation order

1. C07, C08, C11: isolated persistence correctness.
2. C04 and U01: serialize commit, then safe leave.
3. C13, P04, P05, P03: bounded correctness/performance fixes.
4. U02, U03, U06, U07, U15, U17, U21: source-provable UX/accessibility.
5. P14's CI slice.
6. Reassess C01, C02, and C05 together before changing the persistence/upload
   protocol.
7. Run U08/P14 device gates before claiming drawing smoothness or visual
   completion.
