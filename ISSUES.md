# ISSUES

Findings from full security/bug reviews of `main`. Each entry states
what was found, the impact, and what was done about it — a declined fix
says why, so a later reader does not re-litigate it blind.

## Review 3 — 2026-08-27, `398f9c3`

Scope: all 218 commits after step 5 through PR #53
(`e8d30a8`…`398f9c3`), including the fixes merged after Review 2. Method:
commit-by-commit plan comparison, close read, then failing JVM tests before
each fix.

### Fixed

- **Pen-up released queued actions before the stroke was journaled.** Undo,
  redo, leave, share, export, opacity previews, and later edits could run
  against the preceding cursor while the merged stroke still waited for
  readback. The eventual push could truncate the wrong branch or reuse its
  sequence. Stroke/fill completion now transfers explicit engine ownership to
  history, and the document gate opens only after the entry is durable.
- **History writes were not crash-safe.** A crash after `.entry` but before
  tile flush reopened metadata over old pixels; deferred append/readback/disk
  failures were dropped, letting checkpoints pass incomplete edits. Each
  pixel edit now writes a temporary `<seq>.after` roll-forward image before
  tiles, and a failed `WriteEntry` remains the FIFO head with action ownership
  until every stage succeeds. Recovery validates output owners, folds sparse
  membership between entries, and degrades a corrupt tile to transparent
  without discarding the structural edit.
- **Undo/redo could persist pixels without its cursor or model.** A crash
  after restored tiles flushed but before `project.json` reopened the old
  history state over new pixels. `history/transition.json` records the exact
  target before GL mutation; recovery reapplies it idempotently and removes
  the marker only after the target checkpoint lands.
- **Redo accounting could invalidate its own transition marker.** Capturing a
  first-undo sidecar could prune an applied prefix or redo tail and move the
  cursor after the marker's absolute endpoints were chosen. Sidecar bytes are
  now accounted before the transition, while pruning waits until its target
  checkpoint lands; a second checkpoint records the exact pruned membership
  before files are deleted.
- **Divergent history gaps were treated as corruption.** Undo followed by a
  new edit never reuses sequence numbers, but load required a contiguous
  range and discarded the new branch. Project format 2 records exact `seqs`;
  the v1 reader path conservatively infers already-shipped gapped records.
  Malformed bounds, speculative membership, and stale legacy files no longer
  authorize replay or deletion. Allocation stays above every entry and
  sidecar left by an interrupted delete, and sequence exhaustion cannot
  overwrite one.
- **Checkpoint outcomes and generations were ignored.** Readback, tile-flush,
  and `project.json` failures could still navigate, clear dirty state, or
  delete recovery files; a concurrent edit could be folded out of the model.
  Checkpoints now snapshot exact document/history/pixel revisions, serialize
  through the gate, retry pending results, and commit cleanup only for the
  generation written. Gallery sync uses that captured pixel revision.
- **Renderer teardown raced history and replacement streaming.** Release
  could open the gate after GL merge but before journal push, while a new
  renderer uploaded stale disk tiles before the old PBO landed. Readbacks pin
  their originating session; release returns its real drain result; new
  sessions wait for release, document work, and the flusher FIFO. They retry
  transient storage pressure, relist and publish the current sparse stack,
  then unblock input after uploads are queued.
- **Queued layer commands targeted mutable indices.** A delayed delete,
  clear, move, merge, rename, or opacity change could hit a different layer
  after earlier queued work reordered the stack. Actions and dialogs now
  capture stable layer IDs; moves capture an anchor and merges both partners.
- **Advertised layer caps left no transient GPU capacity.** Dense max-layer
  canvases exhausted the pool during fill, sandwich construction, or
  merge/flatten scratch passes; partial caches could also hide paper or
  layers. The cap reserves four full-canvas transient equivalents, cache use
  requires every requested tile, and over-cap legacy stacks use the exact
  direct path until reduced. Reopen also verifies that all resident tiles fit
  before publishing the renderer; nonconforming low-array drivers are rejected
  instead of running with a smaller pool than the UI advertised.
- **Live front-buffer ink reused stale accumulation state.** A view,
  background, or surface-size change during contact deferred the committed
  redraw but left the next dab incremental, so existing ink could jump,
  disappear, or present uninitialized pixels. Scene invalidation is now
  ordered after its GL mutation. Uncoordinated `SurfaceHolder` redraws also
  protect the current or next stroke from their later front-buffer release.
- **Several tool lifecycle edges were unwired.** Zero-write smudge cancel
  never completed its restore gate; pigment fill used RGB source-over; erasing
  an alpha-locked layer silently ran a no-op history step; and normal six-digit
  hex typing replaced the draft after digit three. Each path now has an
  explicit policy and regression test.
- **Finger and eyedropper timing depended on move events.** A stationary
  finger never crossed the draw or stylus-only long-press deadline, quick taps
  produced no dot, and a queued eyedropper read could mutate colour after
  pen-up. The handler schedules its arbiter clock, resolves quick taps, and
  gives the final sample explicit generation ownership.
- **Tail UI policies exposed unusable controls.** Smudge showed an inert
  pigment switch under RGB mixing, very narrow RGB fields violated the 48 dp
  target floor, the one-layer cap suggested an impossible deletion, custom
  brush icons were ignored, and wide Studio guidance appeared below its add
  card. Pure policies and layout tests now pin each state.
- **Adaptive chrome covered interactive controls.** Side rails overlapped
  sheet controls, compact dock/ledge chrome covered panel and fill-cancel
  controls, floating cards reached under the top strip, and the post-v1 wider
  grouped rail reopened the overlap. Panel and progress bounds now derive
  from the full live chrome geometry.
- **Desktop and focus-mode input contracts were incomplete.** Mouse wheel,
  Ctrl-wheel, and middle drag were unwired; mouse buttons entered the delayed
  finger arbiter; mouse hover triggered palm rejection; panel shortcuts opened
  chrome while focus mode remained set; Reset View could remap a live stroke;
  and a same-frame layer selection could admit a stroke against the old active
  layer. Stroke admission now snapshots the current ViewModel layer. The
  handler owns one canonical transform, publishes it synchronously to GL,
  cancels before resize or listener replacement, seeds replacements before
  attachment, honors platform cancellation flags, and gates conflicting
  commands.
- **Metadata-only project rewrites bypassed format migration.** Rename,
  duplicate, and gallery-sync writes could preserve format 1 or rewrite an
  unsupported future format while dropping unknown fields. Every writer now
  rejects future formats and upgrades accepted metadata to the current one.
- **Idle thumbnail fences could remain unsubmitted.** Their zero-time polls
  never flushed the producer, so an idle layer panel could stay blank on a
  deferred driver. The first fence wait now requests command submission.
- **Two verification paths hid failures.** Strict GL checks discarded an
  earlier error before reporting the targeted operation, and tag CI never
  assembled the Mixbox-disabled variant. Strict mode now reports a dirty GL
  precondition; release CI tests and assembles both variants.
- **Sandwich visibility still allocated per frame.** Visible canvas bounds
  returned a new rectangle and captured a resolver lambda on every composite,
  despite the allocation-free GL contract. Reused bounds and a stable resolver
  now keep the steady-state path allocation-free.

### Validation

Regression coverage includes journal admission and sequence gaps, crash
roll-forward and undo/redo transitions, fail-once storage retries, session
release/reattach ordering, transient tile capacity, cache readiness, live
preview recovery, stable layer targets, fill mixing, color drafts, mouse
navigation, and adaptive panel intersections. The normal and Mixbox-disabled
JVM suites, lint, and debug assembly pass.

### Reviewed and clean

The post-v1.0.2 tail correctly reports failed sparse-tile deletion, stages
concurrent shares independently, and rejects a missing Canvas project instead
of recreating it with default content.

## Review 2 — 2026-08-27, `9f4ad22`

Scope: every direct push after step 5's first commit (`c649855`, the
head Review 1 covered) through the v1.0.0 tag — commits `cab83b5`…
`9f4ad22`: step 5's remainder, steps 6–9, and the settings/release
tail. Method: close read of each commit against the plan documents and
AGENTS.md's recorded rules.

### Fixed

- **Undo/redo validation broke against checkpoint-lagged tile sets**
  (step 6). The immutable model's tile keys only reconcile with pixels
  at the checkpoint fold, so between an undo and the next checkpoint
  the model can still list keys whose pixels an undone stroke already
  emptied. `LayerHistory`'s add-undo (exact `Layer(props)`) and
  clear-undo (`isNotEmpty`) refused those undos — add layer → paint →
  autosave checkpoint → undo stroke → undo add silently did nothing —
  and clear-undo/redo failed the same way; the renderer's `prepareCopy`
  exact source-set check refused a duplicate's redo. Fixed both ways:
  `LayerHistory` no longer compares tile sets where folds decide
  membership, and an applied undo/redo now folds its restore outcomes
  into the model immediately (`LayerTileUpdates.apply`, idempotent with
  the checkpoint fold). Pinned by `LayerHistoryTest` and
  `LayerTileUpdatesTest`.

### Reviewed and clean

Grain CPU/GL hash parity and the flat-tip feather gradient; tap
  correction across recycled dab batches; tool-switcher nesting;
  fit-composed input inversion; eyedropper generation guards and
  readback y-orientation; sandwich ping-pong page exclusion and blend
  GLSL vs `Composite`; transactional merge/duplicate/flatten passes
  including the opacity-widened merge keys; sparse-history redo
  sidecar owners and recovery replay; smudge deposit/absorb and blur
  GLSL vs `SmudgeKernel`; RMW capture/cancel restore ordering through
  `ResolveCurrent`; flood-fill tolerance/expansion/AA; palette and
  stored-color parsing; layout, shortcut, and pressure tables.

## Review 1 — 2026-08-27, `c649855`

Scope: every Kotlin file in `app/src/main` (data, engine/core,
engine/gl, input, ui), the manifest, the FileProvider paths, and the
backup rules. Method: close read; suspects verified by writing tests
first where a fix followed.

### Fixed

- **History payload refs could overflow the size guard** — fixed in
  [#20](https://github.com/L-K-M/BangniDraw/pull/20). A payload ref
  with `off` near `Long.MAX_VALUE` wrapped
  `bodyOffset + off + len` negative, so `readEntry`/`readPayloads`
  accepted an entry whose payload cannot address the file; undo then
  either read "before" bytes from a wrong offset or crashed with
  `IndexOutOfBoundsException`, violating the "never an exception for
  content" contract. Local-only impact (hand-edited file in the app's
  own storage), but it broke `SECURITY.md`'s bounds-check promise.

### Reviewed and clean

Path traversal (project ids, layer ids, journal refs, tile names,
share filenames), FileProvider exposure (`cacheDir/share` only, not
exported), MediaStore ownership/tamper checks and pending-row
cleanup, backup/transfer exclusion of paintings, serialization
version gates and per-field degradation, torn-write tolerance
(tmp+fsync+rename, `.deleting` sweep), journal prefix validation,
tile codec bounds, brush preset decoding, GL pool double-free
guards, GLSL/CPU composite parity, `Long`/`Int` overflow in
`MemoryBudget`/`CanvasSize`/`TileGrid`.

### Considered, not fixed

1. **A committed stroke's `WriteEntry` job can trail the checkpoint
   through the flusher queue** (`CanvasViewModel.onStrokeMerged` vs
   `checkpoint`). Both enqueue from different `appScope` coroutines
   with no mutual ordering, so `project.json` can land between the
   seq allocation and the entry write. A kill in that window reopens
   with `HistoryStore.load` truncating the journal at the missing
   entry — one undo step lost, pixels safe (the checkpoint flushed the
   mirror and folded the model before writing). Not fixed: bounded,
   self-healing, already the documented §5.6 outcome ("a prefix or it
   is lies"); closing the window would couple the GL thread, the
   capture hook and the checkpoint mutex for an unlikely scheduling
   race.

2. **`GalleryExporter.rewrite` clears `IS_PENDING` and renames in
   `finally` even when the stream write threw** — the row briefly
   keeps the previous pixels under the new `DISPLAY_NAME`; the next
   sync rewrites them. Not fixed: cosmetic and self-correcting;
   restructuring the block risks the "never a ghost row" path.

3. **CPU flatten peak memory.** `CpuFlatten` + `ImageEncode` hold the
   full RGBA buffer plus an equal-sized `Bitmap` on IO (~128 MiB plus
   PNG staging at 4096²). `MemoryBudget.maxCanvasEdge` scales the
   ceiling down on small devices, so the worst case only occurs where
   RAM allows it. Not fixed: bounded by design; superseded by the GL
   band flatten of `03-canvas-engine.md` §10.4 (roadmap step 4).

4. **`CanvasTouchHandler.captureNavPointers` may anchor navigation on
   an ignored palm.** The arbiter ignores palms; the handler's nav
   capture takes the first two *tracked* pointers regardless, so a
   resting palm can serve as the stationary anchor of a pinch. No
   user-visible defect identified and no device exists here to test a
   change; recorded so the subtlety is not rediscovered as a "bug".
