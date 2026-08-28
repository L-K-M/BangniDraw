# Document, undo journal, autosave, gallery

**What this document covers.** The on-disk shape of a painting and every path
that reads or writes it: the project folder, `project.json`, the tile file
codec, the history journal (in memory and on disk), autosave and its clocks,
thumbnails, the Studio listing, delete/duplicate/rename, the MediaStore gallery
mirror, backup exclusion, and format versioning. It expands PLAN.md §3.2 and
decisions 3 and 8; the folder layout there is normative and repeated here only
to be filled in. Pixel semantics (what a tile *is*, premultiplication, the
readback that produces CPU copies) belong to `03-canvas-engine.md`; layer
operations that *produce* journal entries are specified in `05-layers.md`; the
Studio and Canvas screens that trigger everything here are in
`08-ui-and-layout.md`. Classes named here live in `engine/core`
(`HistoryJournal`, `HistoryEntry`, `AutosavePolicy`, `Document`) and `data/`
(`ProjectStore`, `TileStore`, `HistoryStore`, `GalleryExporter`,
`ShareCache`, `Prefs`).

## 1. Principles that shape the format

| Principle | Consequence in this document |
| --- | --- |
| Pixels are the document; the journal is the undo (decision 3) | Tiles on disk are always the *current* state; history holds only deltas. Losing `history/` loses undo, never the picture. |
| One save path (decision 8) | There is no `save()` API anyone calls. There is a dirty set and clocks that drain it. |
| Crash → stale-but-valid, never torn | Every file is tmp+rename; `project.json` is renamed last and is the commit point; loaders discard rather than throw. |
| The journal is part of the document | `history/` lives in the project folder and is loaded before the first frame; undo works one second after reopening. |
| Honest about limits | Journal caps (steps, bytes) and shelf cost are shown in the UI; nothing is pruned silently except journal entries past the cap. |
| Offline, private | Projects are excluded from backup and transfer; the gallery copy is the user's only off-app copy. |

## 2. Folder layout

```
filesDir/projects/<uuid>/
  project.json                       metadata + layer stack + history cursor — written LAST
  layers/<layerId>/<tx>_<ty>.tile    one file per non-empty tile; absent file = empty tile
  history/<seq>.entry                one undo step: JSON header line + "before" payloads
  history/<seq>.redo                 sidecar: "after" payloads, exists only once <seq> has been undone
  thumb.png                          Studio thumbnail
  *.tmp                              in-flight writes; deleted on load and on every save
```

- `<uuid>` and `<layerId>` are `UUID.randomUUID().toString()`; both are validated with a
  strict regex before any path is built from them (`ProjectStore.isValidId`), so a
  hand-edited `project.json` cannot escape the folder.
- `<tx>_<ty>` are non-negative decimal tile coordinates (tile size 256, `TileKey` from
  `03-canvas-engine.md`). A file whose name does not parse is ignored with a log line.
- `<seq>` is a zero-padded 8-digit decimal (`00000042.entry`) so a plain directory sort is
  a sequence sort.
- Deleting a layer deletes `layers/<layerId>/` (after its tiles are in the journal, §5.6).
- `filesDir`, not `cacheDir`: the OS may evict cache at any time and a painting is the user's
  work (Meltorama's rule, kept).

## 3. `project.json`

kotlinx-serialization, `Json { ignoreUnknownKeys = true; encodeDefaults = true }`. Every
field has a default so a reader of a newer format still decodes what it knows (§13).

```kotlin
@Serializable
data class ProjectFile(
    val formatVersion: Int = FORMAT_VERSION,          // 1
    val id: String,                                   // == folder name; mismatch → folder wins, log
    val title: String = "",                            // "" means "untitled" — see §10
    val createdAt: Long,                              // epoch ms
    val updatedAt: Long,                              // epoch ms, last content change (not last write)
    val width: Int, val height: Int,                  // pixels; immutable in v1 (crop/resize is post-v1)
    val dpi: Int = 300,                               // metadata only; export writes it into PNG pHYs later
    val paperColor: Int,                              // ARGB; alpha 0 = transparent paper
    val layers: List<LayerRecord>,                    // bottom → top
    val activeLayerId: String,
    val nextLayerName: Int = 0,                       // LayerStack.nextName (05 §1: a default name is never reused).
                                                      // 0 = written before the field existed; the loader floors the
                                                      // counter at one past the highest "@string/layer_default N"
                                                      // present either way, so a hand-edited or pre-field file cannot
                                                      // reissue a name already on a layer (12-roadmap.md step 3)
    val history: HistoryRecord = HistoryRecord(),
    val galleryUri: String? = null,                   // MediaStore content:// of our mirror item
    val lastGallerySyncAt: Long = 0L,                 // epoch ms; 0 = never
    val galleryModifiedAt: Long = 0L,                 // DATE_MODIFIED (s) we set on our last write; 0 = unknown (§9.2)
    val galleryBytes: Long = 0L,                      // SIZE of the PNG we last wrote; 0 = unknown (§9.2)
    val view: ViewRecord? = null,                     // restore zoom/rotation on reopen; null = fit
    val lastTool: ToolRecord? = null,                 // what was in the hand when the canvas was left
)

@Serializable
data class LayerRecord(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,                          // 0..1
    val blend: String = "NORMAL",                     // BlendMode.name; unknown → NORMAL + log
    val alphaLock: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
data class HistoryRecord(
    val cursor: Int = 0,          // entries [oldestSeq, oldestSeq+cursor) are applied; the rest are redo
    val nextSeq: Long = 1L,       // next <seq> to allocate; never reused within a project
    val oldestSeq: Long = 1L,     // first entry still on disk (pruning advances it)
    val entries: Int = 0,         // count on disk, for the Studio readout without listing the dir
    val bytes: Long = 0L,         // sum of .entry + .redo sizes, same purpose
)

@Serializable
data class ViewRecord(val scale: Float, val rotation: Float, val panX: Float, val panY: Float,
                      val windowW: Int, val windowH: Int)   // window in px the view was saved against

@Serializable
data class ToolRecord(val tool: String, val presetId: String?, val size: Float, val opacity: Float,
                      val color: Int)
```

Why `blend` is a string, not the enum: an enum added in a later version would make an
older reader throw; a string degrades to `NORMAL` with a log line. Same reason `tool` in
`ToolRecord` is a string.

`LayerRecord.name` follows `01-product.md` §8: a default name is the resource key
`"@string/layer_default"` plus the number (resolved at display time), a user-typed name is
stored and shown verbatim — the same rule as brush preset names (`04-tools.md` §5.1).

`ViewRecord` is restored only when `windowW/windowH` match the current window within 10 %;
otherwise `FitTransform` (`03-canvas-engine.md` §3.1) recomputes a fit. A rotated view saved
on a tablet and reopened on a phone would otherwise show a corner of the painting.

`ProjectFile` is the *serialized* form. The runtime `Document` in `engine/core` is built from it
by `ProjectStore.load` and never holds JSON concerns; `Document.toFile()` is the inverse. Keeping
them separate is what lets the format change without touching the engine.

## 4. Tile files

One codec (`TileCodec` in `data/`) serves `.tile` files, journal payloads and duplicates.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | magic `"BNDT"` (ASCII) |
| 4 | 2 | format version, u16 big-endian, = 1 |
| 6 | 2 | width in px (256) |
| 8 | 2 | height in px (256) |
| 10 | 1 | compression: 0 = none, 1 = deflate (`java.util.zip.Deflater`) |
| 11 | 1 | reserved, 0 |
| 12 | 4 | uncompressed length in bytes, u32 (= w·h·4 = 262 144) |
| 16 | … | payload |

Payload: premultiplied RGBA8, row-major, top-left origin, no row padding — exactly the bytes
`glReadPixels` returns for a slice and exactly what `glTexSubImage3D` consumes, so the codec
never converts. Width/height are in the header although v1 only writes 256: the header is
16 bytes per 256 KiB and buys a future tile size change for free.

- Deflate level `Deflater.BEST_SPEED`. A tile is written on the IO thread after every stroke;
  speed matters more than the last 10 % of size. To verify on device: measure level 1 vs 6 on
  a real painting and record the numbers in `10-performance.md`; the level is a constant in
  `TileCodec`, not a file-format matter (readers inflate whatever they get).
- **Empty tile = file absent.** Before writing, the flusher checks the raw buffer for all-zero;
  an all-zero tile deletes the file instead. Erasing an area back to nothing therefore reclaims
  disk, and "tiles exist only where something was painted" stays true on disk as well as in
  memory. The check is a `LongBuffer` scan (32 K longs), negligible next to deflate.
- Every write is `tmp` + `renameTo` in the same directory (`writeAtomically`, Meltorama's
  helper). A tile file is therefore always complete or absent.
- Reader validation: magic, version ≤ current, w·h·4 == uncompressed length, inflate produces
  exactly that many bytes. Anything else → that tile is treated as empty and logged. One bad
  tile must never fail an open. The count of unreadable tiles is returned with the load
  result; when it is non-zero the Canvas shows one toast on open ("Some parts of this
  painting could not be read", `err_tiles_unreadable` in `02-architecture.md` §9) — honest
  (principle 4), never silent. An **unreadable-layers count** travels beside it, never folded
  into it (`ProjectStore.LoadResult`, roadmap step 3): a layer whose *record* cannot be
  loaded — an id that is no safe path segment (REVIEW.md R-001), a case-insensitive id
  collision (R-029) — is dropped whole and counted there, with its own toast
  (`err_layers_unreadable`), because a lost layer reported as N lost tiles is a misleading
  readout. The two rules are different granularities on purpose: a bad tile degrades to
  transparent inside a surviving layer; a bad layer id has no degraded value at all. The bad file is left on disk until the tile is next painted
  and flushed (an all-transparent flush deletes it); it is never rewritten with an empty
  tile by the loader, so a future reader with a fix could still recover it.

## 5. History

### 5.1 The journal in memory — `HistoryJournal` (engine/core, pure)

```kotlin
class HistoryJournal(private val limits: Limits) {
    data class Limits(val maxEntries: Int, val maxBytes: Long)   // from MemoryBudget.Result.historyMaxSteps/Bytes (10 §4):
                                                                 // 200 / 256 MiB on ≥ 6 GiB devices, 100 / 128 MiB otherwise; shown in Settings/About

    val entries: List<HistoryEntry>   // oldest first; entries[cursor..] are redoable
    val cursor: Int
    val bytes: Long                   // sum of entry.bytes over entries

    fun push(entry: HistoryEntry): PushResult    // truncates redo, appends, prunes; returns what to delete/prune
    fun undo(): HistoryEntry?                    // cursor-- ; null at 0
    fun redo(): HistoryEntry?                    // cursor++ ; null at end
    fun noteRedoBytes(seq: Long, redoBytes: Long): List<Long> // accounts the new sidecar, prunes, returns seqs to delete
    fun canUndo(): Boolean; fun canRedo(): Boolean
}

data class PushResult(val truncated: List<Long>, val pruned: List<Long>)   // seqs whose files go
```

Rules (each one a JUnit test in `11-testing.md`):

- `push` drops `entries[cursor..]` (the redo branch — a new edit after undo makes the undone
  future unreachable, the universal convention), appends, then prunes from the *oldest* end
  while `entries.size > maxEntries || bytes > maxBytes`. Pruning never removes the entry just
  pushed even if it alone exceeds `maxBytes` (a flatten of a huge painting is still undoable
  once).
- `undo`/`redo` only move the cursor. The journal does not know about pixels; the caller
  (`CanvasViewModel` → `HistoryStore`) applies the entry.
- `bytes` counts the on-disk sizes of `.entry` plus `.redo`; the store reports those back after
  writing (`entry.bytes` is filled in by `HistoryStore`, so the pure class never guesses).
  `noteRedoBytes` enforces the caps immediately. It prunes the oldest applied entries first,
  then the far end of the redo branch if necessary, preserving the nearest applicable redo
  transition and at least one entry. Returned seqs use §5.6's checkpoint-safe deletion path.
- The journal is *never* empty on a limit change: shrinking limits in Settings prunes on next
  push, not retroactively.

### 5.2 Entry model

**This section is normative for `HistoryEntry`** — names, fields and the on-disk encoding.
`05-layers.md §5` describes which layer operation *produces* which kind and must use these
names (`LayerAdd`, `LayerDuplicate`, `LayerReorder`, `LayerProps`, `LayerDelete`, `LayerClear`,
`LayerMerge`, `Flatten`, `PaperColor`); the serialized properties record is `LayerRecord` (§3)
everywhere — what 05 calls the runtime `LayerProps` value class is converted to/from
`LayerRecord` at the journal boundary.

```kotlin
sealed interface HistoryEntry {
    val seq: Long; val timestamp: Long; val bytes: Long
    val activeBefore: String; val activeAfter: String   // active layer id hint, see below

    data class Stroke(…, val layerId: String, val tiles: List<TileKey>) : HistoryEntry
    data class Fill(…, val layerId: String, val tiles: List<TileKey>) : HistoryEntry
    data class LayerAdd(…, val layer: LayerRecord, val index: Int) : HistoryEntry
    data class LayerDelete(…, val layer: LayerRecord, val index: Int, val tiles: List<TileKey>) : HistoryEntry
    data class LayerReorder(…, val layerId: String, val fromIndex: Int, val toIndex: Int) : HistoryEntry
    data class LayerProps(…, val layerId: String, val before: LayerRecord, val after: LayerRecord) : HistoryEntry
    data class LayerMerge(…, val upper: LayerRecord, val upperIndex: Int, val upperTiles: List<TileKey>,
                          val lower: LayerRecord, val lowerTiles: List<TileKey>) : HistoryEntry   // lower's record before the merge reset its props (05 §4.1)
    data class LayerDuplicate(…, val sourceId: String, val copy: LayerRecord, val index: Int) : HistoryEntry
    data class LayerClear(…, val layerId: String, val tiles: List<TileKey>) : HistoryEntry
    data class Flatten(…, val layers: List<LayerRecord>, val tilesPerLayer: Map<String, List<TileKey>>,
                       val result: LayerRecord) : HistoryEntry
    data class PaperColor(…, val before: Int, val after: Int) : HistoryEntry
}
```

What each kind stores and how it is undone/redone. "Before payloads" are in `.entry`,
"after payloads" in `.redo` (§5.4). "Current" means the tile as it is right now, captured by
the rule in §5.5.

| Kind | Header carries | Before payloads | Undo | Redo |
| --- | --- | --- | --- | --- |
| Stroke | layerId, tile keys | those tiles' previous contents | capture current → `.redo`; restore before | restore after |
| Fill | same as Stroke (a fill is a pixel edit of one layer) | same | same | same |
| LayerAdd | LayerRecord, index | none (a new layer is empty) | remove the layer (empty by invariant: any paint on it is a later entry, so undoing it means the paint is already undone) | insert the record at index, no tiles |
| LayerDelete | LayerRecord, index | all of the layer's tiles | insert record at index, restore tiles, recreate `layers/<id>/` | delete again — tiles are unchanged since the entry (later edits were truncated), so no `.redo` is needed |
| LayerReorder | layerId, from, to | none | move to `from` | move to `to` |
| LayerProps | layerId, before/after `LayerRecord` | none | apply `before` | apply `after` |
| LayerMerge | upper record + index, upper tile keys, lower record, lower tile keys | upper's tiles, lower's tiles (previous) | capture lower's current → `.redo`; restore lower's record and tiles; re-insert upper with its tiles | delete upper; reset lower's props (05 §4.1); restore lower from after |
| LayerDuplicate | sourceId, copy record, index | none | delete the copy | copy source's tiles again (source is unchanged since the entry, by truncation) |
| LayerClear | layerId, tile keys | the cleared tiles | restore | clear again (no `.redo` needed) |
| Flatten | all records (order), per-layer tile keys, result record | every tile of every layer | delete result layer; recreate all layers with tiles | capture result's current → `.redo` once; delete all; recreate result |
| PaperColor | before/after ARGB | none | set `paperColor = before` | set `paperColor = after` |

Visibility toggles are `LayerProps` entries too — hiding a layer is undoable, matching what
users expect from other apps. Active-layer selection is a *hint*, not an entry: every header
carries `activeBefore`/`activeAfter`, undo/redo restore the matching one (so undo lands you
where you were, `05-layers.md`), and merely selecting a layer never creates an entry.

Property changes that arrive as a continuous slider drag (opacity) are journaled once at
release with `before` from press time (`05-layers.md`), not per frame.

### 5.3 `<seq>.entry` layout

```
line 1:  UTF-8 JSON header, terminated by '\n' — no other newline in it
bytes:   payload[0] payload[1] …   each an entire tile file (§4, with its 16-byte header)
```

```json
{"v":1,"seq":42,"kind":"Stroke","ts":1756000000000,"layerId":"…",
 "activeBefore":"…","activeAfter":"…",
 "payloads":[{"layer":"…","tx":3,"ty":5,"off":0,"len":41230},{"layer":"…","tx":4,"ty":5,"off":41230,"len":39877}],
 "data":{ …kind-specific fields from §5.2, e.g. "index":2, "before":{…}, "after":{…} }}
```

`off` is relative to the first byte after the newline. Payloads carry their own layer id
because `LayerMerge` and `Flatten` hold tiles of several layers in one entry. A payload of
`len 0` records "this tile was empty before" — restoring it deletes the tile; without that
record, undoing a stroke that touched a virgin tile could not know to clear it.

Writing: payloads are deflated into memory first (their lengths are then known), the header
is built, header + payloads go to `<seq>.entry.tmp`, rename. Memory: a stroke touches at
most a few dozen tiles; a `Flatten` of a 4096² eight-layer painting is 2 048 payloads —
that is written streaming (header built from lengths obtained by deflating each tile once
into a temporary file, then concatenated), which is the one place the simple path is not
taken. The header is JSON, not binary, so a broken folder can be inspected with `cat`.

### 5.4 The redo sidecar — `<seq>.redo`

The "after" contents of a step are only needed once the step has been undone. Two options
were on the table:

- append an "after" section to `<seq>.entry` — makes an already-committed file mutable, so a
  crash mid-append could corrupt an entry we still depend on for undo;
- a sidecar `<seq>.redo` with the same layout as `.entry` (header line + payloads) — written
  tmp+rename, so `.entry` stays immutable from the moment it lands.

**Sidecar.** Rules:

- Written on the *first* undo of `<seq>`, before any pixel is restored. Later undo/redo cycles
  of the same step reuse it: between an undo and a redo nothing else can edit pixels (any edit
  pushes and truncates), so "current" at the second undo equals "after" at the first.
- Deleted (together with `.entry`) when the step is truncated by `push` or pruned.
- Kinds whose redo needs no pixels (`LayerAdd`, `LayerReorder`, `LayerProps`, `PaperColor`,
  `LayerDuplicate`, `LayerClear`, `LayerDelete`) never write one.
- On load, a step with cursor ≤ its index (undone) whose `.redo` is missing or unreadable loses
  its *redo* ability only: the step and every later one are dropped from the redo branch
  (`HistoryStore.load` truncates at the first bad one), the undo branch is untouched.

### 5.5 Where "current" and "before" tiles come from

The GPU is the working set; CPU copies exist for dirtied tiles only (PLAN §3.1). The journal
needs pre-edit contents without an extra GPU round trip. Rule, in order:

1. the unflushed CPU mirror in `TileStore` (`mirror[layerId][key]`) — present when the tile was
   changed since its last flush, and by invariant it holds the tile as of the last *commit*;
2. else the `.tile` file on disk — it is the state as of the last flush and nothing newer
   exists, or step 1 would have hit;
3. else empty (`len 0`).

The invariant in step 1 needs one ordering guarantee from the engine: the readback of commit
N has landed in the mirror before commit N+1's entry is captured. `CanvasRenderer` sequences
this on the GL thread — `commit()` for stroke N+1 first drains the readback of stroke N
(`Readback.await`, normally already complete a frame or two after pen-up). **This is a
requirement on `03-canvas-engine.md`**: its `Readback` must expose an awaitable handle per
commit and `CanvasRenderer.commit()` must await the previous one before capturing the next
entry. A fast painter is never blocked on the *disk*; only on the previous readback, which is
bounded by one frame of GPU work.

**Capture happens at commit, and this rule is normative** — not "at stroke start" and not by an
extra readback of the touched tiles (`02-architecture.md` §10, `03-canvas-engine.md` §10.2 and
`10-performance.md` §2.5 describe the same ordering; the mirror/disk rule needs no additional
GPU round trip). For a `Stroke`: the set of dirty tile keys is known from the stroke's
bounding rects (`TileGrid`), the before contents come from the rule above, and the entry is
handed to the IO writer *before* the readback of the stroke's own result overwrites the mirror.
Concretely the order on commit is: (a) snapshot before-tiles (references to the current mirror
buffers, or disk reads scheduled on IO), (b) merge stroke buffer on GPU, (c) issue readback
into *fresh* buffers, (d) on readback completion, swap the fresh buffers into the mirror and
mark them dirty. (a) never copies raw memory: the old mirror buffers become the entry's payload
sources, the new buffers become the mirror. The payload sources are deflated *at capture* on
`Dispatchers.Default` (≈ 40–80 KiB per tile instead of 256 KiB raw) and the raw buffer is
returned to the pool, so what an unwritten entry holds in memory is its deflated size —
counted as "in-flight journal payloads" in `MemoryBudget` (`10-performance.md §2.6`).

Applying an entry (undo/redo) goes the other way: `HistoryStore` reads payloads on IO →
`TileCodec.decode` → `TilePool.upload` on the GL thread (via `execute`) → the same buffers go
into the mirror as dirty, so the restored tiles are flushed like any edit. Undo is an edit as
far as the flusher is concerned.

### 5.6 Crash-safety ordering

Every edit follows this sequence on the single IO writer (§6.3), in this order and never
reordered:

1. every "before" tile of the step that is *not* held as a mirror buffer is copied from its
   `.tile` file on disk into the entry (the bytes are already deflated, header included — no
   inflate/deflate) — this must happen *before* any flush of that key, or the "before" would be
   the "after" and undo a no-op;
2. `<seq>.entry` (and for undo, `<seq>.redo` *before* the restored tiles are flushed) written
   tmp+rename; for `LayerDelete`/`LayerMerge`/`Flatten` the entry is written *before* any
   `layers/<id>/` directory is deleted;
3. the readback of the step is awaited (`Readback.await` handle attached to the job), then the
   tiles the step *changed* are flushed (`.tile` tmp+rename; empties deleted). Entry before
   tiles: a crash between the two leaves an entry whose "after" is not on disk yet — the loader
   rule "entries with seq ≥ `nextSeq` are applied" (below) then restores a before-state the user
   can redo out of, whereas tiles-before-entry would leave pixels with no way to undo them;
4. `project.json` written last, at the next checkpoint (§6), with `history.cursor`,
   `nextSeq`, `oldestSeq` reflecting what is on disk.

Truncation/pruning deletes `.entry`/`.redo` files *after* the `project.json` that no longer
references them is written — a crash in between leaves an orphan file, which the loader
ignores, never a referenced file that is missing.

Load (`HistoryStore.load(dir, record)`):

- list `history/`, parse seqs; seqs below `oldestSeq` → delete and log (orphans of a pruning
  the checkpoint never saw). Entries with `seq ≥ nextSeq` are *not* orphans: truncation orphans
  always have seqs allocated before the checkpoint, so anything at or past `nextSeq` was pushed
  after it. A contiguous run of them (from `nextSeq` upward, no gap) is appended to the *undo*
  branch as applied — its tiles are on disk by §5.6 or restorable from the entry — and `nextSeq`
  advances past it; the first gap ends the run and the rest are deleted. A hard crash therefore
  keeps undo for every committed stroke, not only up to the last checkpoint;
- read headers only (first line), lazily; payload bytes are read on undo;
- an entry whose header does not parse or whose payload offsets exceed the file → that entry
  and every later one are discarded with a log line. Undo history is a prefix or it is lies.
  Layer ids are *not* checked against `project.json` at load: an entry legitimately names
  layers that no longer exist (a stroke on a layer deleted later, the upper layer of a merge).
  An entry's layer is validated when it is *applied*; if the id is absent at undo time the
  journal is inconsistent and truncates from that entry then;
- entries with index ≥ `cursor` (the redo branch) whose `.redo` is needed and missing are
  discarded from that point (§5.4);
- the resulting counts/bytes replace `HistoryRecord` in memory; the next checkpoint writes the
  truth.

None of this ever throws to the UI. The painting opens with as much undo as could be proven.

### 5.7 Reopen path

```
ProjectStore.load(id)                             IO
  ├─ project.json → ProjectFile → Document          (validate ids, layers non-empty, activeLayerId ∈ layers)
  ├─ TileStore.loadLayer(layerId) for each layer   IO, parallel per layer: read every .tile, inflate
  ├─ HistoryStore.load → HistoryJournal            IO, headers only
  └─ CanvasScreen: TilePool.upload(all tiles)      GL thread, in one execute{} batch per layer
```

"Lazily per layer" is the interface (`loadLayer` returns a `Flow<DecodedTile>`). v1 loads
every tile of every layer — residency is post-v1 (PLAN §3.1), and the `MemoryBudget` already
guarantees the whole painting fits — but *streams* them: the viewport's tiles first, uploaded
in per-frame batches, and the canvas becomes interactive when the visible tiles of every layer
are resident while the rest fill in behind (`10-performance.md` §2.7, `03-canvas-engine.md`
§12). A 4096² painting with eight full layers is 2 048 tiles ≈ 512 MiB raw across the pool —
the budget's job to refuse, not this document's. Undo is available as soon as the journal
headers are parsed, which is before the tiles finish uploading; the undo button enables when
both are done, to avoid restoring into a half-uploaded layer.

## 6. Autosave

### 6.1 What "dirty" means — the only saved-state question

Meltorama's lesson (AGENTS.md, PR #45): a second flag that stays true after a write makes
the checkpoint loop rewrite forever, and a flag that is wrong tells users their work is safe
when it is not. So there is exactly one question, `Document.hasUnwrittenChanges`, computed:

```kotlin
val hasUnwrittenChanges: Boolean
    get() = dirtyTiles.isNotEmpty() || metaDirty || journalDirty
```

| Component | Set by | Cleared by |
| --- | --- | --- |
| `dirtyTiles: Set<Pair<layerId, TileKey>>` | readback completion, undo/redo application | the flusher, per tile, after rename |
| `metaDirty` | any change to `ProjectFile` fields: layer stack, title, view, tool, cursor moves | `project.json` rename |
| `journalDirty` | `push`/`undo`/`redo` (cursor or file set changed since last `project.json`) | `project.json` rename |

`updatedAt` moves on pixel/layer/title changes, not on view or tool changes, so the Studio's
"edited 5 min ago" reflects painting, not looking.

### 6.2 Clocks — `AutosavePolicy` (engine/core, pure, ported from Meltorama)

Tiles do not wait for a clock: they flush after every stroke (§6.3). The clocks govern
`project.json` + `thumb.png` (the *checkpoint*):

| Clock | Value | Why |
| --- | --- | --- |
| Quiet | `QUIET_MS = 10_000` after the last change | a pause is when a write costs nothing |
| Ceiling | `ONE_CHECKPOINT_MS = 90_000` since the document first differed from disk | someone painting steadily never pauses |
| `ON_STOP` | immediately, with `runBlocking`-free `NonCancellable` IO | last callback before the process may be reclaimed |
| Leave | on back/Studio navigation, before the screen is popped | the Studio must list the truth |

`delayMs(dirtyForMs) = min(QUIET_MS, max(0, ONE_CHECKPOINT_MS − dirtyForMs))` — the same
function and constants as Meltorama's; reusing tested numbers beats retuning by feel.

A checkpoint that finds `!hasUnwrittenChanges` does nothing. A checkpoint that fires while a
stroke is live (pen down) still runs: it writes the metadata as of the last commit, which is
consistent by construction because the stroke buffer is not part of the document until
`commit()`.

Since `.tile` and `.entry` files are ahead of `project.json` between checkpoints, a crash in
that window loses at most: the redo branch and layer-stack edits since the last checkpoint (the
loader treats entries past `nextSeq` as applied, §5.6, so undo of every committed stroke
survives) — never pixels of committed strokes, because a stroke's tiles are on disk seconds
after pen-up. The loader's rules (§5.6) reconcile the extra files.

### 6.3 The single writer — `TileFlusher` (data)

A fast painter commits several strokes a second, each touching tens of tiles. Naively
launching a coroutine per tile queues thousands of writes and thrashes the IO pool. Instead:

```kotlin
class TileFlusher(scope: CoroutineScope, store: TileStore, history: HistoryStore) {
    private val pending = LinkedHashMap<Pair<String, TileKey>, ByteBuffer>()   // latest copy wins; guarded by `lock`
    private val queue = Channel<FlushJob>(capacity = 64)                         // ordered: entries, dir deletes
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun markDirty(layerId: String, key: TileKey, pixels: ByteBuffer)   // main/GL → replaces any pending copy
    suspend fun enqueue(job: FlushJob)                                  // WriteEntry / DeleteLayerDir / Checkpoint
    // one coroutine: loop { wake.receive(); drain queue in order — each job in the §5.6 sequence }
}
```

- One coroutine on `Dispatchers.IO.limitedParallelism(1)` drains. Coalescing is free: a tile
  dirtied five times before the drainer reaches it is written once, with the latest bytes.
- Ordering (§5.6) is enforced by the job queue: `WriteEntry(seq)` copies disk-sourced
  "before" tiles into the entry, writes the entry, awaits the step's readback, then flushes the
  tiles that entry changed; `Checkpoint` flushes everything pending, then `project.json`.
- `pending` and `TileStore.mirror` are written by the GL/main thread (`markDirty`, §5.5 step (d))
  and drained/dropped by the IO coroutine; both are guarded by one lock, and a buffer handed
  to `markDirty` is immutable from then on (the readback allocates fresh ones), so the lock
  covers map mutation only, never a copy.
- Backpressure: `pending` is bounded by the number of tiles in the painting, not by stroke
  rate (one CPU copy per dirty tile, the mirror), and the mirror is capped at
  `CPU_MIRROR_CAP_BYTES` (64 MiB, `10-performance.md` §4) — a stroke commit waits for it to
  drop below that before its readback is accepted. The one exception is a full disk, which
  never drains: when a `TileStore` write fails with `err_storage_full` the flusher enters a
  **storage-full** state — the mirror cap is lifted (memory then grows bounded only by the
  layer budget, `10-performance.md` §4), strokes keep committing, a persistent banner
  (string key `err_storage_full`: "Storage full — free up space to keep saving") stays up
  (`02-architecture.md` §9), and the pending writes are retried on each autosave tick (§6.2);
  the first successful write leaves the state and drops the banner. Leaving the canvas while
  in that state shows a dialog saying the last N minutes may be unsaved (N measured from the
  oldest unflushed dirty tile). Unwritten
  entries hold deflated payloads (§5.5) and the job queue is bounded (`capacity = 64`):
  `enqueue` suspends the ViewModel side when IO lags, surfaced as a "saving…" counter in the
  debug overlay rather than growing without bound.
- The flusher owns the mirror's *dirty* subset; `TileStore.mirror` entries are dropped once
  flushed, so the CPU side shrinks back to nothing when the painter pauses. (§5.5 step 2 then
  serves "before" tiles from disk — a read of ~40 KB per tile at commit, on IO, off the
  critical path.)

### 6.4 Thumbnails

`thumb.png`: longest side 512 px, composite over paper (alpha kept if paper is transparent —
the Studio draws a checkerboard under it). Produced by `CompositePass` into a small offscreen
FBO on the GL thread + readback, *only at checkpoints, on leave and on `ON_STOP`*, and only
if pixels changed since the last thumbnail — never per stroke, because the render borrows the
GL thread while the user may be mid-gesture. A checkpoint that cannot get the GL thread within
250 ms (surface gone) keeps the previous thumbnail: a stale picture of the right painting still
identifies it (Meltorama's rule).

## 7. The Studio listing — `ProjectStore.list()`

```kotlin
data class Summary(val id: String, val title: String, val updatedAt: Long, val width: Int, val height: Int,
                   val layerCount: Int, val thumbnail: File?, val bytes: Long, val galleryUri: String?)
```

- A folder without a decodable `project.json` is not a painting (mid-save, mid-delete, or
  corrupt) and is skipped — but not deleted; a corrupt-but-present folder is a support
  question, not an eviction.
- Sorted by `updatedAt` desc (the document's own field, not file mtime: mtime moves on
  checkpoints that only saved view state).
- `bytes` is measured by walking the folder on every listing — a few hundred `stat` calls per
  painting against a screen that decodes a thumbnail per painting. The Studio shows the shelf's
  total and `filesDir.usableSpace` (Meltorama's `freeBytes`, with its `UsableSpace` lint note),
  because "nothing evicts but the user" needs the user to be able to decide.
- `history.bytes` is shown per painting in the hold menu ("undo history 38 MB — clear") so the
  journal cap's cost is visible; clearing history deletes `history/` and resets `HistoryRecord`.
  It is a deliberate, irreversible act and therefore confirms (`ConfirmDialog`, `01-product.md`
  H3: "Clear undo history for "Cat study"? The painting is not changed.") and is only offered
  from the Studio, never from an open Canvas.

## 8. Delete, duplicate, rename

**Delete.** Rename the folder to `<uuid>.deleting` first (atomic within `filesDir`), so a kill
mid-delete leaves a folder the lister ignores; then delete recursively; `list()` also sweeps
any `*.deleting` leftovers. If the user answered "also remove from gallery", `GalleryExporter`
deletes `galleryUri` (best effort; a `SecurityException` from lost ownership is swallowed — the
item is not ours to delete then, and the user can remove it in the gallery app).

**Duplicate.** Copy the folder to a fresh `<uuid>` with every `layerId` remapped (directory
names and `LayerRecord.id`s), `title = "<title> copy"`, `createdAt = updatedAt = now`,
`galleryUri = null`, `lastGallerySyncAt = 0` (the copy gets its own gallery item on its first
sync), `view`/`lastTool` kept. **History is not copied**: entries embed the *old* layer ids and
rewriting a journal is a migration with no payoff — a duplicate is "start from here", and copying
history would also double the journal's disk cost for a folder the user has not yet edited. The
copy's `HistoryRecord` is the default. Duplicate runs from the Studio, and the app is a single
task with a single activity instance (`02-architecture.md` §2.1: `launchMode="singleTask"`),
so no Canvas of the source can be open at the same time: leaving the Canvas checkpointed it
(§6.2), and the source's tiles are flushed and current by construction.

**Rename.** `title` edits from the Studio hold menu or the Canvas top-strip menu set `metaDirty`
and `updatedAt`; the gallery item's `DISPLAY_NAME` follows on the next sync (§9.3).

## 9. Gallery sync — `GalleryExporter` (data)

The product promise (PLAN §3.2): the gallery holds the latest version of every painting, one
item each, in `Pictures/帮你Draw/`, no permission (API 29+ scoped storage, fact-checked in the
research notes).

### 9.1 What gets written

Flattened composite at full canvas size, `image/png`, `DISPLAY_NAME = "<sanitized title or
Sketch N>.png"`. Over the paper color, *unless* the paper is transparent (`paperColor` alpha
0) — then alpha is kept, because a user who chose transparent paper wants a transparent PNG.
Rendering: `CanvasRenderer.flatten(1)` (`03-canvas-engine.md` §10.4 — the §4 rebuild
machinery, tile row by tile row, i.e. bands of 256 rows, bounded VRAM regardless of canvas
size), read back per band as premultiplied RGBA8 into one full-size direct `ByteBuffer` (at
each band's row offset) followed by a single `copyPixelsFromBuffer` into a premultiplied
`ARGB_8888` `Bitmap` — Android bitmaps are premultiplied, so the copy is byte-for-byte (03
§2.4) and `Bitmap.compress(PNG)` writes straight alpha itself. The encode transiently needs
*two* 4096² buffers: 64 MiB direct + a 64 MiB `ARGB_8888` bitmap.
Both are native memory (bitmaps are native-backed on API 26+; minSdk is 29), so `largeHeap`
is irrelevant to them; `MemoryBudget` counts the 128 MiB as native headroom next to the GPU
pool and the biggest preset is sized accordingly (`10-performance.md`).

### 9.2 The MediaStore flow (Meltorama's `ImageSaver`, extended for rewrite)

```
sync(document):
  uri = document.galleryUri
  if uri != null && ownsRow(uri):            // query OWNER_PACKAGE_NAME == packageName, row exists
      update(uri, IS_PENDING=1)
      openOutputStream(uri, "wt").use { compress PNG }      // "wt" = truncate; rewrites in place
      update(uri, IS_PENDING=0, DATE_MODIFIED=now/1000, DISPLAY_NAME=title.png)
  else:
      insert(EXTERNAL_CONTENT_URI, DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH=Pictures/帮你Draw, IS_PENDING=1)
      write; update(IS_PENDING=0)            // on any failure: delete the pending row (never a ghost)
      document.galleryUri = newUri; metaDirty = true
  document.lastGallerySyncAt = now; lastSyncedRevision = document.pixelRevision
```

- A `SecurityException` on the rewrite path (ownership lost: uninstall/reinstall, or the
  user's gallery app replaced the file) is caught and falls through to insert. We never
  prompt through `RecoverableSecurityException`; a second item is the honest outcome and the
  old one is the user's to delete.
- **The user edited our gallery copy in another app** (cropped it in Gallery, drew on it in
  a photo editor) and that app wrote the same row in place, so it is still "ours" by
  `OWNER_PACKAGE_NAME`. Overwriting it would destroy their edit, which the family never does
  (principle 3). `ownsRow` therefore also compares the row's `DATE_MODIFIED` and `SIZE` with
  `galleryModifiedAt`/`galleryBytes` recorded in `project.json` after our last write (§3;
  both `0` = unknown → treated as ours, for folders written before this rule). A mismatch
  means someone else wrote the file: the row is **abandoned** (the URI is forgotten, the
  item stays as the user left it) and a fresh item is inserted, exactly as for lost
  ownership. `GallerySyncDecision` (`12-roadmap.md` step 4) takes `(uriPresent, isOwner,
  threw, modifiedByOther)` and this is its fourth input.
- **User deleted the gallery copy → re-insert on the next sync.** Decided: the mirror is the
  product promise ("the gallery always holds the latest version"), a missing row is
  indistinguishable from a lost one, and the alternative (silently stopping) is exactly the
  kind of unexplained state the family avoids. The escape hatch is the setting: with gallery
  sync off nothing is ever written, and turning it off does not delete existing items.
- `IS_PENDING` around the rewrite hides a half-written PNG from other apps for the duration.

### 9.3 Debounce policy

| Trigger | Condition |
| --- | --- |
| Leave canvas | `pixelRevision != lastSyncedRevision` (title changes alone also count, for `DISPLAY_NAME`) |
| Checkpoint | same, **and** `now − lastGallerySyncAt ≥ 30 s` |
| `ON_STOP` | same as leave, but skipped if the flatten cannot get the GL thread (surface already gone) — the next open's leave catches up |
| Studio open | any painting with `pixelRevision` ahead of its last sync (recorded in `project.json` as `lastGallerySyncAt < updatedAt`) is synced in the background, one at a time, on the CPU path below |

`pixelRevision` is an in-memory counter bumped per pixel edit (stroke, fill, undo, redo, layer
ops); it is not persisted — `updatedAt > lastGallerySyncAt` is the on-disk equivalent. The
**Off-canvas flattens (the Studio-open row above) have no GL context** (none exists until the Canvas
screen, `10-performance.md`). They use the CPU reference compositor `Composite` (engine/core)
over tiles streamed by `TileStore.loadLayer`, on `Dispatchers.Default`, one band of tile rows
at a time so at most one row of tiles per layer is resident (8 layers × 16 tiles × 256 KiB
= 32 MiB) plus the two 64 MiB output buffers of §9.1 — never a whole painting; seconds for a
4096²×8 painting is accepted because nothing is on screen. `Composite` is the pinned reference
the shaders must match (PLAN §7), so the two paths produce the same pixels. The
30 s floor keeps a steady painter from flattening a 4096² canvas every 10-second quiet
checkpoint; the on-leave sync guarantees the gallery is exact whenever the user is not
looking at the canvas. Flatten + encode of a large painting is seconds of IO; it runs on
`Dispatchers.IO` after the checkpoint, never blocks the flusher, and a newer sync request
cancels a running one (conflated).

### 9.4 Setting

`Prefs.gallerySync: Boolean = true`. Off → no MediaStore writes at all; existing items stay.
Turning it back on → the Studio-open rule syncs every painting whose `updatedAt` is newer than
its `lastGallerySyncAt`. The setting is in Settings (PLAN §5.3) with one line of copy: "Keep a
copy of every painting in the gallery (Pictures/帮你Draw)".

### 9.5 Share and export

Share uses `ShareCache` (`cacheDir/share/`, served by the `FileProvider` path in
`res/xml/file_paths.xml` — a `cache-path` named `share` only, never the project store), the
same flatten as §9.1, PNG or JPEG per the user's choice in the share sheet. Export "Save as…"
is a plain insert (a new item; not the mirror). Both are PR 4 in the roadmap.

## 10. Titles

- A new painting has `title = ""`. Displayed as `"Sketch N"` where `N` comes from
  `Prefs.nextSketchNumber` (incremented at creation and stored in DataStore, so numbers never
  repeat even after deletes). The number is stored into `title` at creation ("Sketch 12"), so the
  displayed name is stable and the gallery file is named the same; `""` only ever appears in
  a folder written by a build that predates this (none — but the reader handles it).
- Editable anywhere the title is shown. Sanitization for `DISPLAY_NAME`: strip `/`, `\`, and
  control characters, trim, cap at 80 chars, fall back to `"Sketch N"` if empty. CJK titles
  are fine (MediaStore stores UTF-8; the research facts confirm the folder name `帮你Draw` works).

## 11. Backup and transfer exclusion

`res/xml/backup_rules.xml` (API 29–30) and `res/xml/data_extraction_rules.xml` (API 31+),
both **allowlists** naming `datastore` and nothing else — copied from Meltorama with the
comment updated:

```xml
<full-backup-content>
    <include domain="file" path="datastore" />
</full-backup-content>
```

The allowlists include the entire DataStore file and legacy shared
preferences, so every setting — including `AppTheme` — survives backup
restore and device transfer. Painting folders also survive plain restarts,
but excluded from the allowlists they do not transfer.

`files/projects/` holds the user's paintings and their entire undo history;
the app's painting-data promise is that no painting leaves the device except
the gallery copy they can see. **Honest consequence, stated in About and
README:** uninstalling the app removes every project folder. The gallery
copies survive (MediaStore items outlive their owner package; they merely stop being ours to
rewrite — §9.2 handles that with a fresh insert). A future "export project" (OpenRaster,
post-v1) is the intended way to move paintings between devices.

## 12. Class map

| Class | Package | Thread | Responsibility |
| --- | --- | --- | --- |
| `Document`, `LayerStack` | engine/core | main (VM) | runtime model; `hasUnwrittenChanges`, `pixelRevision`, `toFile()`/`fromFile()` |
| `HistoryJournal`, `HistoryEntry` | engine/core | main (VM) | the pure journal algebra |
| `AutosavePolicy` | engine/core | main (VM) | `delayMs`; constants shown in About |
| `ProjectStore` | data | IO | folder lifecycle: list/load/checkpoint/delete/duplicate; `project.json` |
| `TileStore`, `TileCodec` | data | IO | `.tile` read/write, mirror map, empty detection |
| `HistoryStore` | data | IO | `.entry`/`.redo` read/write/validate; applies entries via `TilePool` |
| `TileFlusher` | data | IO (single) | the writer coroutine; coalescing; ordering |
| `GalleryExporter` | data | IO | MediaStore mirror, share/export encodes |
| `ShareCache` | data | IO | `cacheDir/share` rotation |
| `Prefs` | data | — | DataStore: app theme, paint-slot assignments, brush tuning, gallery sync, journal limits, and other application settings |
| `CanvasViewModel` | ui/canvas | main | owns the clocks, calls `flush()`, applies entries, pushes `UiState` |

Test hooks (`11-testing.md`): `HistoryJournal` round-trips through `HistoryStore`'s encoder on
a JVM temp dir (headers + payload offsets); `TileCodec` round-trips random and all-zero tiles;
`AutosavePolicy.delayMs` table; `ProjectStore.load` on fixtures with a torn `project.json.tmp`,
a contiguous entry past `nextSeq` (must be applied) and one after a gap (must be deleted), a missing `.redo`, and a bad tile header — each opens.

## 13. Format versioning and migration

- `formatVersion` (in `project.json`), the `.tile` header version and the `"v"` field of entry
  headers are three independent integers, all currently 1.
- **Readers accept any version ≤ current**; a version > current is refused: the painting is
  listed in the Studio greyed out with "made by a newer version of 帮你Draw" and cannot be
  opened, never silently rewritten by an older format.
- **Writers always write the current version.** A checkpoint of a project loaded from an
  older version therefore migrates it in place; migration is `ProjectFile` defaults plus, when a
  field's meaning changes, an explicit `Migrations.v1to2(file)` function called in
  `ProjectStore.load` and unit-tested on a fixture folder of the old version kept under
  `app/src/test/fixtures/projects/`.
- Tiles and entries are never rewritten just to bump their version; a folder may legitimately
  mix tile versions after a migration. That is why the tile and entry headers carry versions of
  their own.
- Bumping any of the three requires: the new reader, a fixture of the old format, and a line in
  AGENTS.md.
