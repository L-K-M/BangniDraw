# ISSUES

Findings from full security/bug reviews of `main`. Each entry states
what was found, the impact, and what was done about it — a declined fix
says why, so a later reader does not re-litigate it blind.

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
