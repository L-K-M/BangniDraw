# 0004 — Undo is a persisted journal of tile deltas

- **Status:** accepted
- **Date:** 2026-08-24

> Covers PLAN.md decision 3 and §3.2. Formats, pruning parameters and the
> `HistoryJournal` API are specified in
> `docs/plan/06-document-and-persistence.md`; the tile/readback machinery
> it rides on is `docs/plan/03-canvas-engine.md`.

## Context

The requirement is unlimited-feeling undo that **survives closing and
reopening a painting** (and process death — principle 3 says nothing is
ever lost). Three models were candidates:

| Model | How undo works | Fits a raster painter? |
| --- | --- | --- |
| **Stroke-log replay** (Meltorama: the log is the document; undo = rebuild from the log minus one stroke, bounded by periodic snapshots) | Re-run every stroke's stamps | No — see below |
| **In-memory bitmap snapshots** (the classic desktop model: copy the layer, or its dirty rect, before each edit) | Restore the copy | Yes for the semantics, but lives in RAM and dies with the process; a 4096² layer copy is 64 MB |
| **Persisted tile deltas** | Before each edit, capture the *previous* contents of exactly the tiles it will change; undo writes them back | Yes, and it is the same tile granularity autosave already uses |

Why replay — the right answer for Meltorama's displacement field — is the
wrong one here:

1. **Non-deterministic RMW across GPUs.** Smudge, blur, and Mixbox
   merges read pixels the previous strokes produced. Their results depend
   on texture filtering, blending precision, LUT sampling and the exact
   order of dab batches. Two GPUs (or one GPU after a driver update, or
   the front-buffered vs multi-buffered path) do not reproduce them
   bit-exactly. A replayed painting would slowly differ from what the
   user saw, and an undo would visibly "shift" the picture. Meltorama can
   afford replay because a displacement stamp is a pure function of
   parameters into a field; a painter's stamp is a function of the
   canvas.
2. **Unbounded replay time.** A painting is thousands of strokes across
   layers. Undo at stroke 3,000 means replaying 2,999 (or since the last
   snapshot — which is a raster snapshot anyway, so the snapshot *is* the
   real mechanism and replay is just a slow delta encoding).
3. **Order and layer coupling.** Smudge order, layer merges, alpha lock,
   fill-with-all-layers-reference all mean a stroke's effect depends on
   the whole stack at the time. Replaying a subset (undo in the middle)
   is not even well-defined.
4. **Persistence.** A stroke log could be persisted, but then reopening a
   painting means replaying it entirely before the first pixel appears.

Tile deltas have none of these problems: they are *exact* (what was
there is what comes back), *bounded* (cost ∝ tiles touched by the edit,
never ∝ history length), and *already computed* — the autosave path reads
back every dirtied tile after a stroke; the undo delta is the previous
version of the same tiles, which the CPU mirror or `TileStore` holds.

## Decision

Every edit is a `HistoryEntry` appended to `history/<seq>.entry` in the
project folder. For pixel edits (stroke, fill, smudge, blur, erase, layer
merge) the entry holds `layerId`, the list of `TileKey`s changed, and the
**before** contents of those tiles (deflated premultiplied RGBA8; a tile
that did not exist before is recorded as "absent"). Non-pixel edits (add,
remove, reorder, rename, opacity, blend mode, visibility, alpha lock,
paper color) hold their own inverse as a small serialized record.

The entry types, the `.entry`/`.redo` file layout and the
`HistoryJournal` API are defined in
`docs/plan/06-document-and-persistence.md` §5; this ADR does not restate
them.

`HistoryJournal` (pure JVM, `engine/core`) owns the cursor and the
truncate/prune rules; `HistoryStore` (`data/`) does the I/O. **Undo** =
capture the *current* contents of the entry's tiles as `after` (so redo is
possible), write the `before` tiles into the layer (GPU upload + tile
files), move the cursor back. **Redo** = write `after`, move forward. A
new edit while the cursor is behind the head truncates the redo branch
(entries are deleted from disk). The cursor lives in `project.json`,
written last, so a crash between "tiles written" and "cursor written"
leaves the previous consistent state, never a half-undo.

The journal is **capped** in steps and bytes (values in
`docs/plan/06-document-and-persistence.md`, shown in the UI); the oldest
entries are pruned. Pruning removes the ability to undo them and nothing
else — the pixels are the document, so the head of the journal is never
required to open or render the painting.

## Consequences

- **Exact and bounded.** Undo of a stroke costs the tiles it touched,
  regardless of how long the session has been; it is the same whether the
  painting is reopened on the same device or another.
- **Survives process death and reopening.** The journal is files in the
  project folder, flushed with the tiles after each edit; on reopen,
  `HistoryJournal` reads the entry headers (not the blobs) and undo is
  immediately available.
- **Journal size ∝ edited area, not stroke count.** A tiny pencil stroke
  costs one or two deflated tiles; a full-canvas fill costs the whole
  layer (before-state, so a fill over blank paper is nearly free, a fill
  over a painting is not). The Studio's storage readout includes history
  size per painting, and the cap keeps the worst case finite. Repeated
  undo/redo does not grow storage because `.redo` for a step is written
  once, on its first undo, and never rewritten (06 §5.4).
- **Redo needs "after" captures**, taken at the moment of the first undo.
  That is one extra readback of the affected tiles per undo — but those
  tiles are the ones currently resident and just displayed, so it is
  cheap; and `after` is only written once.
- Pruning is a real loss the user can see: the undo button greys out at
  the cap boundary and the settings screen states the cap. Preferable to
  silent RAM growth.
- Layer merge and delete journal *whole layers* (all tiles) as before
  state — the most expensive entries; the UI does not warn, the cap
  handles it.
- No stroke log exists, so there is no time-lapse-by-replay for free;
  time-lapse (post-v1) will be frame captures, not replay. Likewise
  "re-render at higher resolution" is impossible by design — the canvas
  size is fixed at creation, which is the honest position for a raster
  app.
- **Revisit if:** storage measurements on real paintings show the cap
  biting too early (raise the cap or add delta-of-delta compression, not
  a change of model); or a future vector/shape layer type needs its own
  undo (it would journal its own inverse as a `LayerOp`, still not
  replay).
