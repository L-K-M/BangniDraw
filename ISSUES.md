# ISSUES

Findings from full security/bug reviews of `main`. Each entry states
what was found, the impact, and what was done about it — a declined fix
says why, so a later reader does not re-litigate it blind.

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
   `finally` even when the stream write threw** — ~~the row briefly
   keeps the previous pixels under the new `DISPLAY_NAME`; the next
   sync rewrites them. Not fixed: cosmetic and self-correcting;
   restructuring the block risks the "never a ghost row" path.~~
   **Superseded 2026-08-31 (fable F-1): fixed.** Both halves of the
   decline were factually wrong, which is why it is struck through
   rather than edited. `openOutputStream(uri, "wt")` truncates the row
   **on open**, so the previous pixels are gone before the write can
   fail — the row does not keep them. And the failure is not
   self-correcting: `sync` returns null, so `project.json` keeps the
   pre-write size and date; the next `probeRow` reads that mismatch as
   another app's edit, and `GallerySyncDecision.REINSERT` answers that
   by contract with "the URI is forgotten, the item stays as the user
   left it". The tamper guard that exists to protect someone else's
   edit therefore stranded our own truncated PNG in the user's gallery
   permanently, beside a fresh duplicate. `rewrite` now deletes the row
   it truncated — the same never-a-ghost rule `insert` already applied
   — so the next sync is a clean INSERT, and the "never a ghost row"
   path the decline worried about is the one the fix adopts. Two further
   halves of the fix are load-bearing and easy to "simplify" away: the
   publish (`IS_PENDING = 0` plus the rename) lives **inside** the same
   guarded region as the write — so either the row is published or it is
   gone. (A complete-but-still-pending row left by *process death* is healed
   separately: `probeRow` reads `IS_PENDING` and a pending row we own is
   rewritten ahead of the tamper check. That reclaim needs a later probe to
   succeed and does not make the guard placement redundant — a publish that
   throws has code still running and must discard the row then and there.)
   And the cleanup delete is itself
   guarded (`runCatching`, through the shared `discardRow` helper), so a
   provider that also refuses the delete cannot replace the failure being
   reported with its own.

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
