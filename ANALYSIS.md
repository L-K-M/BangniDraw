# ANALYSIS.md — forward backlog

Consolidated on 2026-08-31 from the 2026-08-30 consolidation, the fable.md
deep review of 2026-08-31, and the nine review-looped PRs that review
produced (#175–#183). This is forward-only: completed work is removed;
everything else is stated fully enough to pick up cold. Line numbers from
earlier reviews may drift, so locate the named symbol before editing.

Ids in parentheses — (fable 2026-08-31 F-5), (fable 2026-08-30 GL-2) — point
at the fuller prose in that review's fable.md. **They carry the review's date
because fable.md is replaced by each new review and the numbering restarts**:
F-3, F-4, F-5, F-9 through F-13, F-15, F-16, F-18 through F-21, F-23 and F-24
all exist in both the 2026-08-30 and 2026-08-31 reviews meaning different
things. Only the newest review's prose is on disk; the older ids stay because
the entries they annotate are still open, and git history holds their text.

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

## PR ledger

No fable PRs open as of 2026-08-31: #175–#183 all merged the same day.
(#174, the `:desktop` app module, is a separate line of work and was not part
of this review.) The #157–#170 ledger is deleted along with the older
#104–#146 one; do not resurrect ledger entries from history. Anything those
PRs did not fix lives below under its own id.

What the nine landed, so the entries below can be read against current main:

| PR | fable | What changed |
| --- | --- | --- |
| #175 | F-1, F-2 | A failed Gallery write no longer publishes a truncated PNG, `outcomeOf` cannot turn a good export into a duplicate, a pending row stranded by process death is reclaimed, and `sync` contains provider faults including the `RemoteException` family. |
| #176 | F-7 | `DabPass` orphans its instance VBO before uploading. |
| #177 | — | The five deferred contract-test fixes, plus `ContractTestSources.readNormalized`/`readCompact`. |
| #178 | F-9, F-11 | Tool-sheet sliders publish on release; the layer caption ellipsizes. |
| #179 | F-3 | Each atomic write gets its own scratch file; `sweepTmp` spares live writers; `BrushPresetStore` is serialized. |
| #180 | F-17 | CI builds the shipped release configuration; `.gitignore` no longer swallows source directories named `build`. |
| #181 | F-13–F-16 | One term per thing in the Chinese strings. |
| #182 | F-4 | Merging down warns before it clears the surviving layer's alpha lock. |
| #183 | F-8 | `PointerSample.tool` is filled only where it is read, so a move, a lift, a hover move and every predicted sample stop paying a `getToolType` JNI call whose answer is discarded. |

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
MediaStore mutation, and `ProjectStore`'s three independent
decode-modify-write cycles on one `project.json` — `updateGalleryFields`,
`updateReferenceGalleryFields`, **and `rename()`**, which earlier drafts of
this entry omitted. #179 made those writes individually atomic (one scratch
file each, so no writer can truncate another's in-flight file), which removes
the corruption but not the lost update: renaming a painting while the
background stale sweep reaches it can still drop the rename and report it
saved. Recheck the
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
with the painting. Reuse the CPU `Composite` oracle or the future band
flattener; do not introduce a third blend implementation.

Tests: compare thumbnail pixels with `Composite` for every blend mode, layer
opacity, transparent paper, sparse edge tiles, and hidden layers.

### R6 — Preserve the Gallery copy until project deletion succeeds

**Severity:** medium. **Confidence:** medium-high. **Scope:** small-medium.

Studio removes the optional Gallery row before proving that the private
project was deleted. A project-delete failure leaves the painting but removes
the child's requested external copy. Confirm the intended semantics in the
persistence plan, then reverse or transactionally coordinate the operations.

Adjacent hardening while in the file (fable 2026-08-30 DL-6): `GalleryExporter.delete`
lacks `withdraw`'s URI containment (R-159's hardening), so a malformed stored
URI aborts the whole project-delete path before `store.delete` runs and the
painting silently survives. Contain the parse/probe; the delete *ordering*
stays this entry's open question. [ready, S]

Tests: fail staging, recursive deletion, and MediaStore deletion separately;
pin the recoverable state and user-facing retry for each outcome; a malformed
recorded URI must not stop the private delete.

### R7 — Make Studio refresh failures retryable

**Severity:** high. **Confidence:** high. **Scope:** small-medium.

The latest-wins refresh work does not cover exceptions. A non-cancellation
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
with bounded concurrency and an explicit "calculating" state; never flash a
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

### R10 — Contain the app scope and its ungated entry points

**Severity:** medium-high. **Confidence:** high. **Scope:** small. (fable
DL-4, DL-7)

`appScope` has no `CoroutineExceptionHandler`, and gallery sync, share, and
export run flatten + encode + MediaStore work on it with no containment — a
`DISPLAY_NAME` conflict or bitmap failure kills the process. Separately,
`TileFlusher.enqueue` after `closeAndJoin` throws into that same handler-less
scope from two ungated call sites (a layer edit racing a fast
back-navigation). Add the handler (log + localized failure state, never a
crash) and gate the two enqueue sites. Tests: force each failure and prove
the process survives with the documented user-visible outcome.

**The handler alone does not close this.** `StudioViewModel` calls
`GalleryExporter.sync` from its background stale sweep on `viewModelScope`,
not `appScope`, so a provider fault there is outside whatever handler
`appScope` gains (#175). That path is contained at the source instead —
`sync` catches `SecurityException`, `IOException`, `RuntimeException` and
`RemoteException` at all three of its cross-process calls — but the general
lesson holds: audit which scope each ungated entry point actually runs on
before assuming one handler covers it.

### R12 — Distinguish "post-write probe failed" from "legacy row"

**Severity:** medium. **Confidence:** high. **Scope:** small-medium.
**R11 is deliberately absent, and this entry deliberately keeps its number.**
R11 was the checkpoint `.tmp` sweep racing live writers; #179 fixed it —
`AtomicFiles` tracks its in-flight temp paths and `sweepTmp` skips exactly
those, which is precise where the sweep-on-load-only and skip-young-files
options were heuristics. Nothing below it was renumbered to close the gap: the
ids are how earlier reviews, commit messages and `REVIEW.md` refer to these
entries, so a gap is cheaper than a shift.

A post-write gallery query that returns nothing, or throws, records the legacy
"0/0 = ours" state, which disables §9.2's modified-by-other check for that row
until a later sync records real values. Persist a sentinel distinguishing
"probe failed" from "legacy row" and re-probe before deciding. Tests: fail the
post-write query and prove the tamper check still fires on a later foreign
modification.

**Read #175's trade before changing this.** The null-cursor path already
produced 0/0 before that PR; #175 routed a *throwing* query there too, and
deliberately. Letting the throw propagate was strictly worse — `sync` returned
null, `project.json` kept its pre-write size and date, the next probe read that
mismatch as a foreign edit, and REINSERT abandoned the freshly published row
while inserting a duplicate beside it, permanently, from one flaky metadata
read. So the fix bought "no duplicate, ever" at the price of a one-row,
one-cycle window in which a genuine foreign edit would be overwritten rather
than respected. That window is narrow — it needs another app to edit that exact
file between our write and the next sync — but it is real, and it is what the
sentinel would close. **Do not "simplify" `outcomeOf` back to propagating.**
The persistent-failure case also costs a full truncate-and-rewrite every sync
until a baseline read succeeds; a retry counter or backoff belongs here too.

### R13 — Checkpoint-adjacent efficiency debt

Three bounded items, cheapest first (fable 2026-08-30 DL-10, DL-12, DL-8):

- `committedReferenceName` re-reads and re-parses `project.json` on every
  checkpoint even for the majority of projects with no reference; one
  `exists()` short-circuit. [tiny]
- `TileFlusher.latestRevision` never prunes deleted layers' keys — bounded
  (~1 MB worst case); free to fix alongside `DeleteLayerDir`. [tiny]
- `Thumbnails.write` re-reads and re-inflates every tile of every visible
  layer at every pixel-dirty checkpoint, under the checkpoint mutex (~8 k
  reads / ~2 GiB inflate at the dense ceiling). Distinct from R5/D6; wants
  dirty-tile tracking or the D3 band flattener. [M]
- `HistoryStore.delete` logs but does not propagate a failed file delete, so
  trim metadata advances past a still-present entry and pruning silently
  stops reclaiming space; return the failure (or throw) so the trim loop can
  retry next launch. Also mirror the absent-vs-failed logging distinction in
  `sweepOrphanRedos`, which still logs "failed" for a sidecar the stale-run
  deletion already removed. (#168 review follow-ups) [S]

### R14 — Fill's "expand" reaches about twice its setting around a corner

**Severity:** medium. **Confidence:** high on the mechanism, unmeasured on the
cost of the cure. **Scope:** medium — behavior change plus a measurement.
(fable 2026-08-31 F-5) **[needs a product call]**

`FloodFill.expand` dilates in two separable passes: `spreadRow` writes into a
`horizontal` buffer, then `spreadColumn` reads *that buffer* and grants a fresh
`params.expand` budget. Each leg is wall-checked independently, so a pixel
reachable by ≤`expand` horizontal steps *and then* ≤`expand` vertical steps is
covered even when every wall-respecting path between them is longer than
`expand`. Around a corner with a small gap — precisely the geometry `expand`
exists to bridge — a fill can bleed past the joint into an adjacent region. The
default is `expand = 2`, so this is ordinary use, and the box-blur `antialias`
pass makes the extra reach read as a soft edge rather than an obvious artifact.
The existing `expand crosses an antialias skirt but stops at a color wall` test
is 1×6 and structurally cannot catch a corner case.

Deliberately not fixed in the 2026-08-31 session. Separable row+column dilation
with a square (L∞) structuring element is a defensible, conventional design,
and the leak is a property of that choice meeting per-leg wall checks.
Replacing it with `expand` rounds of 1-px dilation bounds the geodesic reach
exactly, but changes fill output for every existing painting and multiplies the
pass count by up to 8 at `MAX_EXPAND` — a product call plus a 4096²
measurement, not a drive-by. Start with a 2-D corner fixture that fails today.

## Drawing, input, and performance

### D1 — Remove Smudge/Blur allocation from dab and tile loops

**Severity:** medium. **Confidence:** high. **Scope:** medium.

`SmudgePass` builds plans, rectangles, unions, and per-output-tile scissors
inside its hot loops; Blur shares the retained-mode gap. A 200-dab/four-tile
frame can create about 1,400 short-lived objects. Port the retained primitive
bounds pattern used by Watercolor, keeping low-level GL mechanics inside the
pass and decisions in a JVM twin. (#164 already gave each draw size its own
`FullRectQuad`; the loop allocations remain.)

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
dab, and the default spacing can emit a dab every four pixels. Quantified on
the 2026-08-30 pass (fable 2026-08-30 F-5): at the default paintbrush (size 40, spacing
0.2 → ~4 px step) a 2000-px stroke is ~500 dabs × ~8–15 GL ops
(`copyColorSource` N blits + `copyWetSource` + wet FBO draw + `backupWet`
2 blits per newly-touched wet tile + reservation + per-output-tile color
draws + `writeWet`). Each accepted dab reads the previous wet result, so
blind batching can change the paint. Instrument GPU/CPU time and queue depth
first on mid-range hardware. If it misses the frame budget, evaluate the
existing content gate for Water, coalesced source copies, or larger effective
spacing with pixel-reference tests.

Two adjacent wet-system costs, both post-measurement (fable 2026-08-30 F-6, GL-2, GL-3):

- The wet-overlay 100 ms tick runs for up to 48 s after the last wet dab
  (`EngineSession.pumpWetOverlay` re-posts while any wet page exists) —
  accepted design (proposal 0002), but worth one line in the D7 measurement
  script: idle-after-watercolor power.
- The 10 Hz redraw expires on the fixed `MAX_DRY_NANOS`, not on the water
  actually written: light washes are visually dry after ~12 s but redraw for
  36 more. Track a per-tile upper bound of written water at `writeWet` time
  and expire on it. [ready, M]
- The wet-overlay damage rect grows monotonically within a gesture (one AABB
  unioned per dab, cleared only when fully dry), so late-stroke fade frames
  approach full-viewport size. Needs bucketed per-tile damage, not a simple
  clear. [ready, M]

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
| Per-present allocations (fable 2026-08-30 GL-7) | `Mat4.orthoYUp` array per present; `ScreenTransform.of`'s boxed `Pair` per frame; an unconditional `FitTransform` per front frame; void-band `IntRect`s/ArrayList per reference frame; two `IntRect`s per overlay draw. | Retain/reuse each; allocation tests. [S, batch] |
| `TilePool.clear` (fable 2026-08-30 GL-10) | Wipes the whole `GlState` shadow per fresh tile on the stroke path. | Update just the two fields it changes. [tiny] |

### D7 — Establish device metrics and regression budgets

Measure p50/p95/p99 input-to-present, missed frames, GL queue depth,
allocation rate, reopen-to-visible/interactive, Fill, flatten, peak memory,
and idle-after-watercolor power on a 120 Hz S Pen tablet, mid-range phone, and
low-memory class-B device. Use 4096²/eight dense layers, every paint tool,
rapid zoom/rotation, and a 20-minute S Pen script: fast flicks, pressure and
stationary dots, holds, canceled palms, barrel button, eraser end, hover,
tilt, quick lifts, and dense crossing strokes. Store traces and thresholds so
later optimizations have one repeatable gate.

### D8 — Small input/workflow gaps

- Reuse `HueMilestone` for restrained haptics when an eyedropper drag crosses
  a hue band; retain existing accessibility/provider gating.
- Consider a two-finger-plus-pen brush-size gesture only after chord and palm
  arbitration tests prove it cannot steal drawing.
- `NewCanvasDefaultsPolicy`'s last fallback is an unfiltered `indexed.first()`
  where its first two stay inside the `enabled` set, so with nothing enabled
  the dialog would open on a greyed-out row. Very likely unreachable —
  `MemoryBudget.compute` clamps the GPU tile budget to a 256 MiB floor, under
  which `PHONE_SKETCH` always fits — so this is tidiness, not a live defect.
  `enabled.firstOrNull() ?: indexed.first()` costs nothing.
  (fable 2026-08-31 F-6) [tiny]
- The transparent-paper checker is screen-space: its cell size is already zoom
  stable, but cells slide under the paper during pan/zoom. Decide whether a
  canvas-anchored checker is worth its minification aliasing; do not apply the
  obsolete scale-banding fix. Coordinate any pixel guide with A14.

(Mouse-wheel/trackpad zoom at the cursor shipped in #157: `ScrollZoom`
factor/pivot in `engine/core`, `handleScroll` on the touch handler, generic-
motion wiring, non-finite input drops.)

### D9 — GL-layer sweep: state hygiene and stalls

From the 2026-08-30 GL sweep; each verified in source, none yet measured on a
device. (#164/#165/#167 fixed the sweep's quad-sharing, built-flag, and
committed-frame-culling findings; these remain.)

- **GlState shadow invalidation** (fable 2026-08-30 GL-5): between-frame GL entry points
  (`applyPixelOps`, thumbnail pumps, overlay refresh) never invalidate the
  `GlState` shadow; correctness currently rests on `presentToWindow`
  happening to end scissor-off. Sharpest latent case: `TileCopyPass` blits
  with no scissor call at all, and a stale-enabled scissor would silently
  truncate a layer duplicate that then persists. One `invalidate()` per
  entry point. [ready, S]
- **`CanvasRenderer.onContextLost()` has no caller** (fable 2026-08-30 GL-8): the §12
  recovery path is dead code, and three `forgetAll`s don't reset their
  `GlFbo`s so it would misbehave even if wired. Needs a decision on who
  detects context loss. [ready/verify, M]
- **Eyedropper block read** (fable 2026-08-30 GL-9): a pick at radius 4 issues 81
  synchronous 1×1 `glReadPixels`, each a pipeline stall on the GL thread.
  One block read (or one per touched tile) would be a single stall.
  [ready, S-M]
- **`FullRectQuad` re-upload orphaning** (#164 review follow-up): a size
  change mid-stroke still takes the `glBufferSubData`-into-a-possibly-
  in-flight-buffer path with no orphaning (`glBufferData(null)` first).
  Affects every consumer, including the present quad. Needs a GPU profiler
  to prove the stall before and after. [verify, S]

### D10 — Undo and redo mark every layer's thumbnail dirty

**Severity:** medium. **Confidence:** high. **Scope:** small.
(fable 2026-08-31 F-10)

The forward-edit path scopes thumbnail invalidation correctly, passing
`changedLayers(before, after)` plus the specific `changedKeys` a pixel op
touched. The undo/redo path passes the **whole stack**:
`pixelLayers = foldedStack.layers.map(Layer::id)`. One undo of a single stroke
on a 16-layer document therefore queues up to 16 isolated-layer composites and
PBO readbacks instead of one.

A different axis from D6's `LayerThumbnailPass` row, which is about how much of
*one* layer is recomposited — fixing that alone would not help, because the
ViewModel is still declaring all sixteen dirty. The `restores` list already in
scope names exactly which layers received pixels.

### D11 — The desktop `GLES30` actual hands LWJGL heap buffers, which cannot work

**Severity:** high for the desktop port, none for shipped Android.
**Confidence:** high — verified empirically against the pinned LWJGL.
**Scope:** medium. (fable 2026-08-31 F-18)

`engine-gl/src/desktopMain/.../platform/GLES30.kt` bridges Android's
`(count, array, offset)` overloads to LWJGL's buffer-only API with
`IntBuffer.wrap` / `FloatBuffer.wrap` at twelve call sites, and its own KDoc
documents that as the design.

`*Buffer.wrap()` is **always** heap-backed — an unconditional JDK contract.
LWJGL's generated bindings extract a raw pointer via
`MemoryUtil.memAddress(buffer)`, which for a non-direct buffer does not throw:
it returns a small offset-derived integer. Checked against the pinned
`lwjgl 3.4.3`: a heap `IntBuffer.wrap` yields `memAddress = 16`, a direct
buffer yields a real ~48-bit address. `0x10` is in the unmapped zero page, so
the driver faults the moment it writes the out-parameter.

Ordinary startup hits it — `glGetIntegerv` in `GlCaps`, `glGenTextures` in
`TilePool`, `glGenFramebuffers` in `GlFbo`, `glGetProgramiv`/`glGetShaderiv`
in `GlProgram` — as does the reopen path's `Buffer`-passthrough entries, which
**R-043 deliberately allows to be heap buffers** because Android's JNI glue
tolerates them via `GetPrimitiveArrayCritical`. LWJGL has no such fallback.

No user impact today: no desktop shell exists (DESKTOP.md Phase 2 is
unlanded) and Android is untouched. But it means DESKTOP.md's "thin one-line
delegation" premise is wrong for about twelve of the sixty-four entries, and
the facade as built cannot start the engine it was extracted to enable.

Fix: `MemoryStack.stackPush()` direct buffers for the array entries (the `n`
is always tiny), and a staging direct buffer in the seam for the
`Buffer`-passthrough entries, since R-043 commits the shared engine to
accepting heap buffers. Deliberately not attempted in the 2026-08-31 session:
it cannot be verified in CI without a desktop GL context, and an unverifiable
rewrite of twelve GL entry points is worse than a precise record. Land it
behind whatever first creates a real context.

### D12 — Adjacent gaps around the same seam

**Severity:** low. **Confidence:** high. **Scope:** tiny each.
(fable 2026-08-31 F-19)

- The no-Mixbox build is verified for Android only; the desktop target's
  `nomixbox` source set is never compiled or tested, and no `nomixboxTest`
  directory exists. Mixbox is CC BY-NC 4.0 (ADR 0003) — the whole reason the
  stripped variant exists — so a permissive desktop build is a plausible real
  combination. One extra CI invocation:
  `-Pbangnidraw.mixbox=false :engine-gl:desktopTest`.
- Neither `:engine-core` nor `:engine-gl` is covered by any lint task; the
  unqualified `lintDebug` finds none, and those KMP modules register only
  publish-lint-jar tasks. Since #173 the `input/` package — real stylus
  handling — lives in `engine-core`, now unlinted. Confirm whether AGP's KMP
  lint wiring exists yet; if it genuinely does not, that belongs in AGENTS.md's
  "don't fix these" list rather than being rediscovered every review.
- `PlatformImportBanContractTest`'s `QUALIFIED_ANDROID` regex requires a
  lowercase segment after `android.`, so `android.R.*` and `android.Manifest.*`
  slip through; `isComment()` treats `/* x */ code` as fully commented. Both
  are the same "front-runs the compiler" character as the declined R-034, so
  this is optional polish — the desktop compiler is still the hard gate.
- `ClasspathEngineAssets` (desktop) has no tests at all, despite having shipped
  a real alpha-channel bug one commit before the review. `MixboxLutTest` reads
  the PNG directly via `ImageIO`, bypassing the decode path that had the bug.

### D13 — #173's frame-callback tripwire is weaker than its comment claims

**Severity:** low. **Confidence:** high. **Scope:** tiny.
(fable 2026-08-31 §8)

The frame-callback cache bound asserts inside `AndroidCanvasInput`, and its
comment says *"the JVM suite fails loudly"* — but no test anywhere references
`AndroidCanvasInput` or `Choreographer`, there is no Robolectric (R-037
declined adding it), and Kotlin's `assert()` is a no-op on Android by default.
The claimed net exists on neither side. The sibling predictor tripwire *is*
meaningfully covered, because its caller is pure JVM and the test fake mirrors
the check. Fix the comment, or extract a platform-agnostic bounded cache a JVM
test can drive.

Related and equally cheap: the `frameScheduler` swap-cancel branch has no test
exercising an actual swap — every test assigns once, to a fresh handler. That
branch is pure JVM and `QueuedFrameScheduler` already exists, so it is
testable today.

## UI, accessibility, and convenience

### U1 — Finish Layer panel accessibility and large-text behavior

**Severity:** medium. **Confidence:** high. **Scope:** small-medium.

Layer rows use a fixed 64 dp height despite two text lines. Blocking layer
feedback lacks an assertive live region, and lock, alpha-lock, and blend state
can remain visual-only in menus. Use minimum rather than fixed height, expose
checked/selected state and action labels, and announce blocking feedback once.
(#162 fixed the header's button starvation; the rows remain.)

Tests: semantics tree for every state and action; screenshots at 100/200%
font, both locales, narrow phone, and tablet.

### U2 — Announce sliders in artist-facing units

**Severity:** medium. **Confidence:** high. **Scope:** medium.

Brush size, Fill, rail size/opacity, mixing, and tracing opacity sliders can
announce normalized fractions while the UI displays pixels, percent, or named
artist values. Centralize semantics without changing the slider's internal
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

`FirstRunHint` wraps the screen as a clickable Button with "Got it" while also
containing instructions and a nested button. Semantics merging may hide the
instructions or duplicate dismissal. Inspect the semantics tree and real
TalkBack. Likely shape: pointer-only backdrop plus one semantic card containing
readable text and one explicit dismiss action.

### U5 — Replace Canvas Toasts with one transient host

**Severity:** medium. **Confidence:** high. **Scope:** medium.

Native Toasts bypass the planned themed, replacing, non-overlapping,
stroke-deferred feedback path — the 2026-08-30 pass counted 8+
`Toast.makeText` call sites in CanvasScreen alone. Build one Canvas-owned
transient host with priority, deduplication, rail/dock clearance,
accessibility timeout, assertive mode for blocking errors, and deterministic
replacement. Migrate existing locked/busy/storage/action notices together
rather than adding another host. The transient chips of U15d and U17 below
should ride this host, not add competitors.

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

### U9 — Custom paper color for an existing painting

**Severity:** medium. **Confidence:** high. **Scope:** small (UI-only).
(fable 2026-08-30 F-3)

`NewCanvasDialog` gained a sixth custom-HSV paper swatch in #131, but the
Layer panel's paper menu (`paperChoices()` in LayerPanel.kt) still offers
exactly five fixed colors. A painting created before the user knew about
custom paper — or whose paper they change their mind about — can never reach
the color the creation dialog offers. Add the same custom choice to the
panel's paper menu, reusing #131's compact HSV picker dialog; `setPaperColor`
already takes any ARGB. (The old U9 — transparency rendered as gray — shipped
in #159 as the shared quadrant checker.)

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

**Severity:** low-medium. **Confidence:** high (re-confirmed 2026-08-30).
**Scope:** small.

Four knot sliders call `onPresetChanged` on every drag frame
(`BrushSettingsSheet.CurveEditor`), recompose the whole settings sheet, and
re-trigger the 50 ms preview debounce. Keep local preview state and publish on
`onValueChangeFinished`, or use one bounded debounce if live canvas preview is
required. Flush on dismissal. Test final-value delivery, cancellation,
rotation, and write count during a long drag.

**#178 built the mechanism and deliberately stopped at this boundary.**
`DeferredSettingSlider` (in `BrushSettingsSheet.kt`) drags a local draft and
publishes once on release, and every slider in the *tool* sheets now goes
through it — `ToolSheetCommitContractTest` pins that no bare `SettingSlider`
call remains there. It was not extended to the brush sheet or the curve knots
because those have a live preview reading the committed preset, so deferring
changes what the user *sees* rather than only how often state is republished.
That is this entry's call to make: either accept the preview lagging to
release, or add the bounded debounce. The knots' two `CurveEditor` sites still
pass `onFinished = {}`.

### U13 — Strengthen focus-mode affordance

**Severity:** medium. **Confidence:** high. **Scope:** small.

`FocusHandle` is 6×48 dp at 35% opacity, has weak discoverability, and lacks a
clear semantic action. Increase visible width/contrast without shrinking the
touch target, expose "Show controls," retain handedness, and teach focus mode
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
  windows and separates sliders from the active tool. Cap/scroll the tool
  region or anchor sliders; test the shortest supported height and 200% font.
- **U15b — Preset silhouettes.** New-canvas preset rows need a tiny
  aspect/orientation glyph, not only text and dimensions.
- **U15c — Recent-color cue.** Mark the recent-color chip as expandable; do
  not hide its history behind an unexplained long press.
- **U15d — Tool settings and More.** Add a subtle settings cue to active-tool
  re-tap. Teach tools buried in More, and show the selected hidden tool's
  name instead of leaving only "⋯" active: in GROUPED mode, picking Blur or
  Eyedropper closes the menu with no visible confirmation (fable 2026-08-30 F-12). The
  transient-readout surface pattern already exists (zoom/rotation and history
  readouts in CanvasScreen.kt); show a one-second tool-name chip under the
  top strip on selection, at minimum for tools with no visible rail slot.
- **U15e — Sighted timeout.** Screen-reader timing is already correct.
  Consider a longer sighted default or explicit dismissal so a child can
  inspect it.
- **U15f — HSV actions.** Give the HSV ring/square `customActions` for hue,
  saturation, and value.
- **U15g — Blur shortcut.** Assign or explicitly reserve a Blur shortcut;
  avoid silently changing an established chord.
- **U15h — Water teaching.** Teach the Water tool's distinct behavior; #112
  covered its hover geometry, not discoverability.
- **U15i — Dead glyph.** Audit the dead `PAINTBRUSH` glyph. Remove it or
  document its reserved role; do not reintroduce a glyph collision.
- **U15j — Watercolor preview.** BrushSettingsSheet shows
  `watercolor_preview_hint` text where every other brush renders a live
  stroke (fable 2026-08-30 F-10). The kernels are pure JVM (`WatercolorColorKernel`,
  `WatercolorWetKernel.paperRelief`), so a deterministic CPU wash — flat band
  with the model's rim darkening, granulation speckle from `paperRelief`,
  spread-softened edges — can render in `BrushPreview` and be pixel-pinned on
  the JVM. It is a settings preview, not a second live-paint oracle;
  approximations are fine as long as the four sliders visibly change it.

Each subchange needs keyboard/TalkBack semantics and both-locale copy where
visible. Keep tool coaching contextual and dismissible.

### U16 — Finish small polish and platform guards

**Severity:** low-medium. **Confidence:** high unless marked validate.

- **U16a — Picker halo.** `HsvRingSquareSized` draws both markers as a single
  `onSurface` stroke ring (fable 2026-08-30 F-9), so the marker vanishes at the square's
  white/black corners — near-invisible over dark values in light themes and
  over light values in dark. Draw a two-tone halo (light outer + dark inner
  ring, or vice versa) so the marker is visible on any sample. Pure UI.
- **U16b — Hover allocation.** Hoist eraser-hover dash/path arrays out of
  the draw loop.
- **U16c — Safe license intent.** Guard the About license intent when no
  activity can handle it; show a localized fallback rather than throwing.
- **U16d — Aging timestamps.** Age relative Studio timestamps while the
  shelf remains open without refreshing project storage.
- **U16e — Insets validation.** Validate edge-to-edge screenshots before
  changing suspected double-applied Studio system insets.
- **U16f — Preset names.** Ensure future built-in brush JSON cannot display
  raw `@string/...` tokens: `brushPresetName` must resolve every
  `assets/brushes/*.json` ID or a model resource key. Test the entire asset
  set and name offenders.
- **U16g — Duplicate import.** `androidx.compose.foundation.Canvas` is
  imported twice in BrushSettingsSheet.kt (fable 2026-08-30 F-4). Fold into any PR
  touching the file.
- **U16h — Spacing slider travel.** The brush-spacing slider maps linearly
  over 0.5 %–200 % of diameter; the useful painting range (2–20 %) occupies
  the first tenth of the track (fable 2026-08-30 F-11). Map through a square-root or
  log scale the way `BrushSizeScale` does for size; display value unchanged.

### U17 — Studio, theme, and settings sweep leftovers

From the 2026-08-30 Studio sweep (the sweep's G-1/G-2/G-3/G-5/G-6/G-9/G-10/
G-13 findings shipped in #161/#162/#163/#166/#169):

- **U17a — Cold start always paints the Saffron-light launch window** (fable
  G-4), so the three dark themes flash cream on every launch (themes.xml pins
  `windowBackground`; the ViewModel's tone is null until DataStore emits).
  Fix needs a synchronously readable tone mirror. [ready, M]
- **U17b — Shelf thumbnails letterbox onto the transparency checkerboard**
  (fable 2026-08-30 G-7), so every non-4:3 painting appears to have a see-through
  border (StudioScreen.kt thumbnail cell). Constrain the checker to the
  artwork rect. [ready, S]
- **U17c — Appearance section pushes every other setting below the fold**
  (fable 2026-08-30 G-11): eight theme rows, and nothing on them says the last three
  repaint the app dark. A swatch FlowRow and/or Light/Dark sub-headings.
  [ready, S]
- **U17d — Canvas help is the ninth item of the ⋮ menu** (fable 2026-08-30 G-12) — the
  least discoverable placement for the screen with the least discoverable
  gestures. [idea]
- **U17e — TalkBack reachability of the unavailable-card ⋮** (fable 2026-08-30 G-8):
  the card's `semantics(mergeDescendants = true) { disabled() }` wraps the
  footer button #166 added; an IconButton is its own merging node so it
  likely survives, but this needs a semantics-tree check on device.
  [verify]
- **U17f — Canvas title appears nowhere on the canvas** (fable 2026-08-30 F-13): only
  the a11y `canvasDescription` carries it. Cheapest honest fix: include the
  title in the U15d transient chip family when the canvas opens and/or after
  rename. Immersion is by design, so nothing persistent.
- **U17g — Overflow menu is one flat list of nine items** (fable 2026-08-30 F-15): the
  export pair could collapse into one "Export…" with a format chooser,
  making room as actions accrue (Clear painting, future layer export).
  [idea — do it when the next item lands, not before]

### U18 — The two transient readout chips are two different designs

**Severity:** low. **Confidence:** high. **Scope:** tiny.
(fable 2026-08-31 F-12)

The history/undo-depth readout uses `inverseSurface` + `shapes.large` + no
elevation; the zoom/rotation readout uses `surfaceContainerHigh` +
`shapes.medium` + `tonalElevation = 3.dp`. Same role, same position, adjacent
in time — a two-finger tap-undo right after a navigation gesture shows both —
in two visual languages. U15d proposes a third chip of this family for tool
names, so settling one shared surface now is worth more than the tidiness. The
`inverseSurface` treatment reads better over arbitrary artwork.

### U19 — The brush-preset picker is thirteen unlabelled text chips in one row

**Severity:** medium. **Confidence:** high. **Scope:** small.
(fable 2026-08-31 F-20)

`BrushSettingsSheet` puts every same-erase-mode preset into one flat list and
renders them as plain-text `FilterChip`s in a single `horizontalScroll`, with
no icons, no grouping, and no cue that more exist off-screen. This is the only
in-flow way to reach the specialty presets beyond the five on the rail.
`ToolGlyphs` already resolves a per-preset icon from the stored
`BrushPreset.icon` key; the chip row simply never calls it.

Distinct from U15d, which is about non-paint tools hidden behind rail overflow;
this is the paint-preset switcher itself. Fix: `leadingIcon` from the existing
glyph resolution, and split core from specialty.

### U20 — No numeric entry for brush size or opacity

**Severity:** low-medium. **Confidence:** high. **Scope:** small.
(fable 2026-08-31 F-22)

Every tool parameter is slider-only with a display-only value label, so there
is no way to type an exact size to match a previous stroke. The app already
ships the pattern: `NewCanvasDialog`'s `DimensionField` is an
`OutlinedTextField` with a numeric keyboard. Tap or long-press the value label
to swap in that field, committing on IME action; the drag interaction is
untouched. Note #178 made these sliders publish on release, so the label now
tracks a local draft — read `DeferredSettingSlider` before wiring the field.

## Missing artist features

These are ordered roughly by value to a young artist. Persisted-format or
rendering-model changes require a proposal/plan update before code.

### A0a — Studio shelf search, sort, or filter

**Severity:** medium for the target persona. **Confidence:** high.
**Scope:** medium. (fable 2026-08-31 F-21) **[needs a product call]**

`ProjectStore.list()` is hard-ordered newest-first with no query, and
`StudioScreen` renders it straight through. For the "quick sketch on the train"
persona accumulating small sketches over weeks, the only way to find an older
painting is to scroll and read thumbnails. Different from U11 (duplicate
titles) and from the Delight backlog's "Favorites and collections", which is
about starring rather than finding. Smallest useful version: a title-substring
filter applied client-side, no persistence change.

### A0b — Canvas rotation lock

**Severity:** low-medium. **Confidence:** high. **Scope:** small.
(fable 2026-08-31 F-23) **[needs a product call]**

Two-finger navigation always composes rotation; the snap-right-angles
preference changes only which angle is settled on, not whether rotation happens
at all. A slightly twisted pinch always rotates the paper. `01-product.md` §6
benchmarks the rail and gesture feel against Procreate, which has an explicit
rotation lock. One more `SwitchRow` beside "Snap right angles" and a guard in
`applyNavigation` — no new gesture, which is what that document refuses to add.

### A1 — Custom brush library

Allow "Save as brush," naming, favorites, and rail reordering. **Cheaper than
first scoped** (2026-08-30): the brush editor already exposes ~every
`BrushPreset` field and persists per-preset tuning (`BrushTuning`), and
`BrushPresetStore` accepts arbitrary ids — "Save as new brush" is now mostly
UI + id allocation + rail-budget behavior, not new machinery. It is the
single highest-leverage missing feature: today every tweak overwrites the
built-in's tuning, so a child cannot keep two versions of a pencil. Preserve
built-ins, validate names/IDs, version serialized presets, and preview
migrations. Test fork/edit/delete/reorder/reopen and rail budget behavior.

### A2 — Symmetry and kaleidoscope

Offer horizontal, vertical, and radial 4/6/8 modes with a visible movable
axis. Journal one logical stroke and derive mirrored dabs deterministically so
undo, prediction, and time-lapse do not see unrelated strokes. Still the top
delight-per-effort item, and every seam the 2026-08-30 pass touched
(DabGenerator's one-batch emit loop, single journal entry per stroke, guide
overlays) accommodates it. Test seams, off-canvas axes, rotation, pressure,
every paint model, and cancellation.

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

Actual-size zoom shipped. Remaining: nondestructive horizontal mirror,
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

The compact redo readout shipped. Add a sheet showing operation kind, layer,
undo/redo depth, and disk use; allow per-painting history cleanup that never
changes current pixels. Test cleanup at each journal/checkpoint state and
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

- Clear Painting, with confirmation and one undoable document action
  (re-confirmed 2026-08-30: Clear Layer exists with confirmation; clearing
  the whole painting still requires deleting layers one by one).
- A launcher "Continue" shortcut to the latest available painting.
- Crop-safe composition teaching around the existing guides, not another
  duplicate guide engine.

### A17 — Expose Chinese-ink capacity ("how far a stroke paints")

**Scope:** M. **Readiness:** ready. (fable 2026-08-30 F-16)

`InkBrushDynamics`/`InkBrushMask` hardcode ink capacity, bristle width, tuft
weight, edge drying, and response length. Exposing *ink capacity* — how far a
stroke paints before fly-white — as an Advanced slider on the calligraphy
preset lets artists choose lush vs. dry brushwork. Needs a `BrushPreset`
field (serializable, defaulted), plumbing into the dab's wetness, no shader
change (wetness is already per-dab). This also unlocks the Rake preset below.

### A18 — New zero-engine presets from the existing tip space

**Scope:** S each (JSON + strings + glyph + a feel pass). (fable 2026-08-30 F-17, F-22)

`TipShape.Flat` + `TipOrientation` combinations support a credible
flat/angled sketching pencil and a true chisel calligraphy pen as pure JSON
presets; the shipped set uses only some of the space. A "Dotty pen"
(spacing ≈ 2.0, Max buffer, hardness 1 — a dotted line) is one such
zero-engine preset kids would love. After A17 lands, a "Rake" preset
(ChineseInk model, tiny capacity, low flow) produces streaked dry-brush lanes
from the first dab — grass, hair, wood grain: the fly-white lane mask is the
hard part of a rake brush and it already exists. Each preset needs a glyph
and a device feel pass; do not ship blind.

## Delight and proposals

Keep these optional, offline, and free of accounts, feeds, streaks, ads, or
completion pressure.

- **Idea Spark** [ready, S]: one dismissible Studio empty-shelf prompt drawn
  from an offline set — the empty shelf today is two gray sentences. Strings
  + picker, zero engine risk, both locales in the same change (fable 2026-08-30 F-26).
- **Long-press "+" to create instantly** [ready, tiny]
  (fable 2026-08-31 F-24): `NewCanvasDialog` already persists the last custom
  size and paper colour across sessions, yet a repeat sketcher must still open
  the dialog and tap Create every time. `NewPaintingCell` is a plain
  `.clickable`; `ResetViewPill` already demonstrates the `combinedClickable`
  tap-plus-long-press pattern. Long-press goes straight to
  `onCreate(lastSize, lastPaper)`. Purely additive — the tap default is
  untouched, so it costs a first-time user nothing.
- **A haptic tick as the mixing dish crosses 50 %** [idea, tiny — speculative]
  (fable 2026-08-31 F-25): the colour ring already ticks at hue detents via
  `HueMilestone.crossed`, and D8 proposes reusing it for the eyedropper drag.
  The dish slider — the control for the app's signature pigment feature — has
  no haptic at all. Flagged speculative on purpose: unlike a pigment-wheel
  spoke, "50 % of a linear mix" is not obviously a landmark worth feeling.
  Only worth doing alongside another reason to touch that function.
- **Brush dice:** seeded, reversible mutations from a saved brush, previewed
  before acceptance.
- **Pigment recipes:** friendly mix language ("mostly blue, a little yellow")
  and optional drag-to-smear in the dish.
- **Contextual tool coach:** brief first-use labels for ambiguous/hidden tools;
  replayable through Tips.
- **Paper personality:** stable canvas-space procedural tooth. Decide
  explicitly whether it exports; record provenance for any texture asset.
- **Edge resistance:** restrained pan overshoot with reduced-motion support.
- **Ambient shelf:** a faint color wash sampled from the newest painting.
- **Favorites and collections:** stars/folders without counts or gamification.
- **Friendly color names:** optional names alongside exact HSV/RGB values.
- **Mirror check:** a temporary view-only flip prompt, never a pixel mutation.
- **Share card:** optional "Made with 帮你Draw" frame generated locally after
  an ordinary clean export remains available; add an optional reduced-motion
  post-share celebration without delaying the chooser.
- **Onion skin:** optional previous/next-frame overlays after a real frame
  model exists; never bake them into paint.
- **Radial quick color:** pen-button/long-press color wheel only if it does not
  conflict with eyedropper, palm, or system gestures.
- **Ink-load meter** (fable 2026-08-30 F-27): while the calligraphy brush draws, a
  hair-thin arc around the hover cursor showing remaining ink
  (`currentWetness`). Charming, but it puts UI on the hot path — only worth
  it with the ink-economy proposal below, and only if the cursor already
  redraws that frame.
- **Mascot/illustrated empty state:** one restrained character or illustration
  can make Studio warmer without repainting the whole product.
- **Motion polish:** one focus-handle pulse, a restrained 150 ms destination
  crossfade, and a paint-blob-shaped transient; honor reduced motion and avoid
  animation on the drawing hot path.
- **Pen-up haptic:** a restrained completion tick, default off and separate
  from sound; measure latency and retain accessibility/provider gating.

### Wet-physics proposals (write the proposal doc before code)

The wet system (surface water + absorbed saturation per 4×4 cell, paper
relief hash, pigment carried by flow) and the ink system (load depletion,
lane masks, paper tooth) are genuinely simulation-shaped; these classic
techniques are within reach, ordered by plausibility (fable 2026-08-30 §7). Each needs
a proposal + GLSL/JVM shader-twin review; none is a preset trick.

- **Blotting / lift-out ("dry tissue")** [proposal, M] (fable 2026-08-30 F-19): a third
  deposit mode beside `PIGMENT`/`CLEAR_WATER` in `WatercolorColorKernel` —
  `LIFT`: where the dab lands, reduce surface water sharply and scale the
  layer's premultiplied color toward transparent in proportion to how wet
  the cell is (wet pigment lifts, dry pigment stays — physically right and
  self-limiting). One enum value, one kernel branch + GLSL twin, a
  Water-tool mode toggle. The most delightful cheap physics on the table.
- **Salt scatter** [idea, depends on LIFT] (fable 2026-08-30 F-20): a stamp seeding N
  random points in the dab (jitter machinery exists), each running the LIFT
  kernel with a small radius and a granulation-shaped falloff — pale blooms
  on a wet wash.
- **Finite ink / dip to reload** [idea, product call] (fable 2026-08-30 F-21):
  `InkBrushDynamics.inkUse` resets every stroke ("every stroke starts
  loaded"). An opt-in "ink economy" mode would persist load across strokes
  and reload on a long-press of the color chip (or a dip gesture in the
  mixing dish) — the calligraphy ritual, real fly-white pacing, a natural
  rhythm brake. Mechanically tiny (skip one reset; add a reload call).
- **Paper dryness / climate** [proposal, S-M] (fable 2026-08-30 F-23): watercolor dry
  time is fixed (24 s/unit). A global "paper" setting — Dry (12 s), Normal,
  Damp (48 s) — scales the evaporation rate at sample time; because age is
  stored as ticks and evaporation is computed lazily, a global multiplier
  applies cleanly to existing wet texels. One setting + plumbed constant +
  GLSL twin. Wet-on-wet painters get a slow-drying sheet; sketchers get
  fast-drying.
- **Tilt ink drip:** proposal for opt-in device-tilt advection/pooling in wet
  paint. Define sampled tilt events for deterministic replay, reduced-motion
  behavior, and a clear cancel path before touching `WatercolorWetKernel`.

Record these rather than implementing them as shortcuts:

- **Wax resist — likely decline** (fable 2026-08-30 F-24): crayon-then-wash resist needs
  a per-pixel resist channel the premultiplied RGBA8 tile does not have.
  Same class as Magic Ink.
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
artwork-led shelf. The eight-theme system (#141/#148/#150) landed and is
coherent: palette decisions live in `engine/core/ThemeColorPolicy`, screens
use scheme roles, the canvas void stays neutral per theme tone. It replaces
system-following light/dark behavior with four fixed selectable palettes per
tone; record that policy before proposing generated custom-accent tokens, and
do not build a competing theme path.

Incremental backlog:

- Tint the New Painting cell with restrained `primaryContainer`.
- Give shelf art about 1 dp of lift; keep shadows out of the canvas.
- Add one OFL display face for the Studio name/panel headers only, with
  provenance; keep controls and canvas labels in the system face.
- Let child delight come from art, contextual teaching, and one illustration,
  not denser chrome or a broad toy-like redesign.

(The dock's top-corner rounding shipped in #158.)

## Review-round follow-ups (small, file-specific, shovel-ready)

Deferred from the #157–#170 and #175–#183 review loops under the
steady-state rules; each is real, verified against the code, and intentionally
not worth its own CI cycle at the time. Good warm-up batch for a future
session. (The five contract-test items that led this list landed in #177; item
5 below is what remains of that family.)

1. **`CanvasViewModel.finishCheckpoint` KDoc** — document the cross-file
   FAILED-retry contract: a FAILED thumbnail write deliberately keeps
   `thumbDirty` set, and `checkpointLocked`'s fast path exempts it
   (`!dirty && !thumbDirty`), which is what makes the retry fire. Optionally
   pin both halves in the checkpoint contract test.
2. **`StudioScreen.kt`** — de-indent the relocated `DropdownMenu` block by
   one level (leftover from the pre-#166 nesting; cosmetic, would trip a
   formatter check if one is ever enforced), and add a source-contract
   assertion pinning `items(state.paintings, key = { it.id })` so the
   saveable dialog state's stable-key dependency (asserted three times in
   review, R-176) cannot silently become positional.
3. **`HistoryStore`** — see R13's propagation/logging bullet.
4. **`FullRectQuad`** — see D9's orphaning bullet.
5. **Migrate the remaining contract tests to `ContractTestSources`** (#177
   review, R-189). About ten `:app` tests — `ColorPanelAllocation`,
   `ColorPanelAccessibility`, `ColorPanelSelection`, `CompositionGuides`,
   `ActualSize`, `LayerPanelDragHandle`, `CanvasCheckpoint`,
   `SaveableTransient`, `BrushSettingsCurvePlot` — plus the `engine-gl` twin
   still carry a private `WHITESPACE = Regex("\\s+")` and collapse only. That
   absorbs wraps between whole tokens and nothing else, so the wrap Kotlin's
   style guide actually produces (break after `(`, trailing comma) still
   false-fails them. `readNormalized`/`readCompact` now exist; the migration
   is mechanical. **The one rule when you do:** a needle must not depend on
   anything the canonicalizer deletes — a trailing comma, or whitespace
   hugging a paren — in quoted text as much as in code.
6. **Two English help strings name a control something it is not called**
   (#181 review). English is the source-of-truth locale, so a zh-Hans pass
   deliberately left them: `help_brush_paint_body` heads a paragraph "Buffer:"
   where the label is "Build-up", and `help_fill_body` says "Composite" where
   the label is "All layers". Note that English legitimately *shortens* long
   labels into headings — "Snap right angles" for "Snap rotation to right
   angles" — so only a heading that uses a different *word* is a defect.
7. **Pin the orphan's `null` data argument** in
   `DabPassOrphanContractTest` (raised against merged code, after #176 and
   #177 both landed). The test pins the orphan call's position and its size
   expression but not the argument that makes it an orphan: `glBufferData`
   only performs the cheap storage re-specification when `data` is `null`. A
   future edit passing a `Buffer` — folding the orphan and the sub-data into
   one full upload, or pre-zeroing for determinism — still precedes the
   `glBufferSubData` and still sizes to the committed capacity, so every
   assertion stays green while the hottest upload in the engine starts pushing
   `instanceCapacityDabs * DAB_FLOATS * 4` bytes per tile instead of
   `n * DAB_FLOATS * 4`. Extend the needle to
   `"instanceCapacityDabs*DAB_FLOATS*4,null,"`. The size pin exists precisely
   because ordering alone does not pin it; the same argument applies here.
8. **An orphaned pending row from a killed `insert`** (#175 review, R-210's
   unfixed half). #175 reclaims a pending row whose URI was recorded; a kill
   between `resolver.insert` and its publish leaves one whose URI never was,
   so nothing can find it without a query by display name and folder. It is
   invisible in gallery apps and never collected. Needs that query, or a
   startup sweep of own-package pending rows in the app's folder.

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
  share boundaries were sane. Since #180, CI also builds the configuration
  that actually ships (Mixbox in, R8 on) rather than only debug and the
  stripped release.

Added by the 2026-08-31 review — three of these refute hypotheses that review
started with:

- **Bottom-anchored Canvas overlays clear the dock and ledge correctly.** That
  pass began suspecting the storage-full banner had the defect the fill
  progress card once had. It does not, and structurally cannot:
  `CanvasOverlayClearance` yields DOCK 120 dp / SHORT 64 dp / else 16 dp, and
  the banner, the reset pill and the fill card are siblings in **one** `Column`
  under a single shared `padding(bottom = overlayBottomPadding)`, so they
  cannot drift apart. Both clearance constants decompose exactly into control
  height plus one 16 dp gap. The history and view readouts are top-anchored, so
  the concern never applied to them.
- **R-042's decline (attach-before-events scheduler contract) is correct.**
  Re-traced independently: `frameScheduler` is assigned in exactly one place
  (`AndroidCanvasInput.init`), the adapter is constructed in exactly one place,
  **outside** `key(canvas)`, and a new handler always arrives paired with a new
  adapter. Composition resolves before `AndroidView`'s update, so the
  assignment always precedes any dispatch.
- **The #173 record port preserved input fidelity.** A line-by-line diff of the
  pre-port `onTouch`/`onHover`/`onGenericMotion`/`fill` against the current
  adapter confirms pressure, tilt, orientation, hover distance, scroll ticks,
  button state, `FLAG_CANCELED` handling and the historical-then-current
  ordering are all preserved, and the predicted tail's array order matches the
  deleted `fill()` exactly.
- **Engine-core's document, history and blend maths hold.** Merge/flatten
  undo-redo tile-set arithmetic including the opacity-widening path, the eight
  blend formulas against 05 §4, `MemoryBudget`'s saturating arithmetic,
  `HistoryJournal`'s prune and byte accounting across repeat undo/redo, and
  `SandwichPolicy.stale`'s per-operation table were re-derived by hand.
- **CPU/GLSL twins match.** `StrokeMerge` vs `mergeStroke`, the watercolor
  colour/wet/overlay kernels vs their shaders, and `InkBrushMask` vs
  `dab.frag`'s `inkBrushMask`, constant-for-constant. R-055's deferred
  elliptical-feather asymmetry **has since been fixed** — both sides now use an
  `fwidth`/analytic-gradient feather — which matters because seven shipped
  presets use `TipShape.Flat`, making R-055's old "unreachable today" caveat
  stale.
- **The #171/#172 module extraction is clean.** No leftover `engine/gl`,
  `engine/core` or `mixbox` directories under `app/src/main`; no stray
  `android.opengl.GLES30` imports in `:app`; all 64 facade constants match 1:1
  across the facade and both actuals; `engine-core`'s widened members are
  minimum-necessary; all three modules pin JDK 17.
- **Material3 1.4.0's slider semantics commit.** Checked against the artifact
  the build resolves, not assumed: `SliderKt.sliderSemantics`' `setProgress`
  handler sets the value and then invokes `onValueChangeFinished` under a null
  guard. TalkBack and keyboard changes therefore commit exactly as a release
  does, which is what makes #178's commit-on-release safe. Re-check if that
  dependency moves; older Material builds fired only `onValueChange`.
- **`RemoteException` is a sibling of `RuntimeException`, not a subtype**
  (`DeadSystemException` -> `DeadObjectException` -> `RemoteException` ->
  `AndroidException` -> `Exception`, read from the platform jar). Any
  "contain provider faults" catch chain that lists only
  `IOException`/`RuntimeException` misses the canonical dead-provider case.
  `GalleryExporter` now covers it at all three cross-process paths; audit other
  `ContentResolver` callers against the same fact.
- **The watercolor tick-epoch rebase is correct** (re-derived 2026-08-30,
  refuting closed PR #127's premise): at rebase time every surviving page
  carries an old-epoch tick, so `EPOCH_REBASE`'s force-dry branch
  (`updatedTick <= nowTick` → `MAX_DRY_TICKS`) is exactly right — such a
  page is at least one full modulus (~109 min) old; larger stored ticks get
  the exact modulo age. Mid-stroke epoch crossings are handled (`stamp` →
  `resetActiveEpoch` before per-dab work), and `pruneExpired` uses
  full-precision nanos.
- Also hunted and not found on the 2026-08-30 pass: `Stabilizer.easeAngle`
  under accumulated unnormalized angles (the modulo re-wraps; downstream
  trig is angle-safe); `DabGenerator` batch-overflow resume, tap fix-up
  generation guard, NaN pressure funnels, zero-dt velocity deferral;
  `InkBrushDynamics`' segment state machine and its `copyInto` parity for
  the predicted tail (the two-frame tuft-axis/path-tangent lane model
  matches `dab.frag` constant-for-constant); locale parity (the 5 keys
  absent from zh-Hans are all `translatable="false"`); `BrushPreview`
  passing `brushModel` through so the calligraphy preview renders the ink
  mask.
- `RmwDabPreset`'s water parameters beyond amount + spread (granulation,
  edge darkening) are deliberately not exposed on the Water tool: a
  colourless tool cannot deposit rims. Recorded as intentional (fable 2026-08-30 F-18).
- Review-round claims checked and refuted with evidence, kept here so they
  are not re-raised: Material3's `Shapes.large` is `CornerBasedShape` and
  its four-corner `copy(...)` compiles without casts (R-177); Compose
  `border` strokes inward from the shape outline and draws after content,
  so `clip(shape).border(w, c, shape)` renders a visible ring (claimed
  regression in #159's swatch, disproven); `SmudgePass` on main carries
  zero bare `quad` references (the split is complete); the Studio grid
  already passes `items(state.paintings, key = { it.id })` (R-176, asserted
  three separate times); `loadPaintSlots` gates on `paintSlotIds.first()`
  so the retry window suspends rather than crashes (R-179); `TileGrid`
  dimensions are vals derived from vals — `tileCount` cannot move under a
  live `SandwichCache` (R-180).
- Front-buffer drain/tail folding, navigation pointer-up compaction,
  per-pointer axis reads, predictor ownership, and live zoom state were
  correct at the review snapshot. This does not clear the cancellation,
  overflow, stationary-deadline, or prediction-reserve work formerly tracked
  as #116/#138/#144/#146 (all merged).
- Touch processing retained scratch/ring storage and avoided per-sample
  allocation. `pendingStrokeFallback`, `SandwichCache` ping-pong,
  `CompositePass` batching, and `HalfTurn` producer/consumer symmetry held.
- Clean-file structural replay, the `nextName` floor, two-locale string
  completeness, RMW `ResolveCurrent` ordering, and intentional navigation-only
  Compose recomposition were verified. R2 remains open for publication crashes
  and sequence gaps; do not generalize this to all recovery.
- Watercolor base reservation ordering and edit policy, tracing-reference
  codec/policy, rail-budget selection, Chinese Ink dynamics, and
  `CanvasActionGate` were verified.
- The pure gesture arbiter's existing per-pointer/chord paths, Fill
  generation/cancellation, leave/checkpoint scrim policy, `CpuFlatten`
  sparse-read skipping, `Composite` zero-opacity skipping, deliberate
  hidden-layer preview, remembered dish gradients, and reference-panel gesture
  routing were verified. **"Merge confirmation" is struck from this list**:
  the 2026-08-31 review found it gated on blend mode alone while `mergeDown`
  also cleared the surviving layer's alpha lock, silently (#182). A
  verified-clean note is only as good as the question it was checked against,
  and that one had been read as "does the dialog appear when blend modes
  differ" rather than "does it name everything the merge changes".
- Layer-thumbnail polling is panel-gated; scrim accessibility/focus gating and
  the base theme were coherent.
- `MixingDish.gradient`, thumbnail memory caching, composition guides,
  pressure curves, and other completed work are not backlog items.
- `builtin.paintbrush` is the Watercolor preset. The app has exactly two
  string locales: `values/` and `values-b+zh+Hans/`; brand-only omissions are
  deliberately non-translatable. Terminology after #169: the pinned photo is
  "tracing image"/描图图像 everywhere user-facing; the creation-flow noun is
  "sketch"/草图 (速写 names the activity in the About text); the fill tool's
  "Reference" setting (参考) is the unrelated sampling-source term.
- AAPT2 startup failure was transient in the review sandbox; stop/retry or CI
  settled it. Do not diagnose it as an app defect without new evidence.

Implementation notes:

- For source-contract tests, collapse whitespace (or strip it when every
  needle is space-free), scope assertions to each call site/function, make
  regex matches non-vacuous, make missing markers fail loudly
  (`substringAfter`'s silent full-receiver fallback is the classic trap),
  and name offenders. Watch substring subsumption: `surface` is a prefix of
  `surfaceVariant`.
- `rememberSaveable` needs a `Saver` for pure data classes not supported by
  Android's Bundle. Rotation crashes are invisible to the JVM-only suite.
  Saveable state inside lazy items additionally requires stable item keys
  (the Studio grid has them).
- The GLM review workflow edits its single PR comment in place on follow-up
  rounds — fetch current comment bodies; do not reconstruct rounds from
  notification events alone.
- A PR whose files conflict with `main` silently gets **no** `pull_request`
  CI run (GitHub cannot build the merge ref), while `pull_request_target`
  workflows still run — an absent `android` check on a fresh push means
  merge conflict, not a skipped queue. REVIEW.md's append-at-tail sections
  made this chronic across parallel PRs; the fix that ended it was giving
  every open branch one identical ledger (main's sections plus every open
  branch's own section in PR order), so sibling merges stop conflicting.
- The open-PR list changed during prior reviews. Refresh it both when
  choosing work and immediately before opening a PR.
