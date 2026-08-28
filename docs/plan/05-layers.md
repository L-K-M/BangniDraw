# Layers

**What this document covers.** The layer model and everything that acts on
it: `Layer`, `LayerStack` and its pure operations, the paper, the eight v1
blend modes with the premultiplied formulas both the CPU `Composite`
reference and the compositor shader implement, the exact semantics of the
destructive operations (merge down, flatten, clear, delete, duplicate), how
every operation becomes a `HistoryEntry`, how the `MemoryBudget` cap is
consumed, the layer panel's behaviour, and the sandwich
cache invalidation rules. It expands PLAN.md §3.1 (tiled layers, sandwich),
§3.2 (journal undo), decision 4 (honest memory budget) and roadmap step 6.
Tile storage and the GPU pool are in `docs/plan/03-canvas-engine.md`; the
on-disk journal encoding is in `docs/plan/06-document-and-persistence.md`;
the panel's looks are in `docs/plan/08-ui-and-layout.md`. Everything here
that decides something lives in `engine/core` and is plain-JUnit tested
(`docs/plan/11-testing.md`).

## 1. The layer model

```kotlin
// engine/core
@JvmInline value class LayerId(val value: String)   // UUID string — the same id names layers/<layerId>/ on disk (06)

data class LayerProps(
    val id: LayerId,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,          // 0..1, clamped on set
    val blendMode: BlendMode = BlendMode.NORMAL,
    val alphaLock: Boolean = false,   // paint keeps the layer's alpha
    val locked: Boolean = false,      // no pixel edits, no delete, no merge
    // post-v1: val clipToBelow: Boolean = false
)

data class Layer(val props: LayerProps, val tiles: Set<TileKey>) {
    val id get() = props.id
}
```

`LayerProps` is the part that goes into `project.json` and into pixel-free
history entries; `Layer` adds the sparse tile set — the *set of
`TileKey`s that exist* for the layer, over the canvas geometry of
`TileGrid` (owned by `03-canvas-engine.md` §1). GPU slice handles live in
`engine/gl/LayerTextures` (03 §2.2), never in `engine/core`, and no pixels
live here either: pixels are in `TilePool` (GPU) and `TileStore` (disk),
and the CPU dirty mirror in between. This split is what lets `LayerStack` be
immutable and pure: every operation returns a new stack and, when pixels
must move, a *description* of the pixel work for the GL thread (§3.3).

Rules baked into the type:

| Rule | Why |
| --- | --- |
| `LayerId`s are fresh UUIDs (`06-document-and-persistence.md` §Layout: `UUID.randomUUID().toString()`), never reused, never derived from the index | History entries, thumbnails and the GPU index all key by id; an id that could come back would let a stale entry restore pixels into the wrong layer |
| Index 0 is the bottom; `layers.last()` is the top; the panel lists top-first | Compositing walks bottom-to-top; the panel shows what is "in front" at the top, like every other painting app |
| `opacity` is a float 0..1 in the model, a 0–100 % integer in the UI | No rounding drift in the document; the slider entry coalesces (§5) so the stored float is whatever the last tick produced |
| Names are free text; the default is `Layer N` where N = `nextName` at creation (a counter that only grows, including across undo), a duplicate is `<name> copy` | Names never need to be unique; the id is the identity |
| `alphaLock` and `locked` are independent | Alpha lock is a painting mode ("colour inside what I have"); lock is protection. Both can be on |

**Alpha lock semantics** (the one place a layer property changes what a
stroke does). The stroke buffer merge into the layer is normally
source-over. With alpha lock it becomes "normal, alpha forced to
destination": in premultiplied terms with `s` = stroke pixel, `d` = layer
pixel,

```
Cr = d.rgb·(1 − s.a) + s.rgb·d.a        Ar = d.a
```

which is `lerp(cd, cs, s.a)` in straight colour, re-premultiplied by the
untouched destination alpha. Transparent pixels stay transparent whatever
is painted over them. An **eraser on an alpha-locked layer is a no-op**
with a haptic tick and the hint "Alpha lock is on" — erasing to
transparent contradicts the lock, and silently painting nothing would
read as a bug. Erase mode itself is `Cr = d.rgb·(1 − s.a)`, `Ar = d.a·(1 −
s.a)` (`04-tools.md` §3.7).

**Locked** refuses every pixel edit on the layer (stroke, fill, smudge,
blur, clear) with the same tick-and-hint pattern ("Layer is locked"),
refuses delete, and refuses merge in either direction. Rename, opacity,
visibility, blend mode, alpha lock and reorder stay allowed: lock protects
pixels, not arrangement. **Undo and redo ignore both locks**: they restore
history, they are not edits (the same reasoning as the layer cap in §6.4 —
an entry recorded before the lock was set must stay reversible, or
"nothing is ever lost" breaks). `HistoryEntryTest` covers a stroke entry
undone after its layer was locked.

## 2. The paper

```kotlin
data class Document(
    val width: Int, val height: Int,
    val paperColor: Int,              // ARGB; alpha 0 = transparent paper (06's encoding)
    val stack: LayerStack, ...)
```

The paper is a **document colour, not a layer**. It costs no tiles, cannot
be selected, has no opacity or mode, and is the composite's starting
destination: `CompositePass` clears the output to the (premultiplied,
opaque) paper colour and then draws layers bottom-to-top (with the sandwich
in use the paper is baked into the `below` half, `03-canvas-engine.md`
§4). A transparent paper (alpha 0) clears to transparent and the viewport
draws a screen-space checkerboard first (03 §3.2), which never reaches a
readback. Consequences that other documents rely on:

- Export and the gallery PNG bake the paper in; flatten (§4) does not.
- The eyedropper's "composite" mode samples the paper too; "current layer"
  does not (`04-tools.md` §Eyedropper).
- Fill with "sample all layers" sees the paper as an opaque field (a white
  paper is a boundary-less area to flood) (`04-tools.md` §Fill).
- Changing the paper colour is a `PaperColor` history entry (§5), pixel-free.

Why not a locked bottom layer: it would consume a layer slot in the budget
(§6), appear in the panel as something the user might delete or reorder,
and every merge/flatten rule would need an exception for it.

## 3. `LayerStack`

```kotlin
data class LayerStack(
    val layers: List<Layer>,          // bottom .. top, size >= 1
    val activeIndex: Int,             // always in layers.indices
    val nextName: Int,                // counter for default names only; ids are UUIDs
) {
    val active: Layer get() = layers[activeIndex]
    fun indexOf(id: LayerId): Int
}

/** Result of an operation: the new stack, the pixel work the GL thread must
 *  run (null for pixel-free ops), and the entry the journal records. */
data class StackEdit(val stack: LayerStack, val pixels: PixelOp?, val entry: HistoryEntry)

sealed interface StackResult {
    data class Ok(val edit: StackEdit) : StackResult
    data class Refused(val reason: Refusal) : StackResult   // never throws
}
enum class Refusal { LAST_LAYER, AT_CAP, LOCKED, HIDDEN_PARTNER, NO_LAYER_BELOW, NOOP }
```

Refusals are values, not exceptions, because every one of them has a UI
consequence (§7): the panel turns the reason into a hint. `NOOP` covers
things like moving a layer onto its own index or setting a property to
its current value — the caller drops those so the journal stays clean.

### 3.1 Operations

All operations are pure functions on `LayerStack` (plus `MemoryBudget` for
the two that add a layer). `i` is an index, `active` the current active
index.

| Operation | Precondition (else `Refused`) | Result stack | Active after | `PixelOp` |
| --- | --- | --- | --- | --- |
| `add()` | `layers.size < budget.maxLayers` (`AT_CAP`) | empty layer inserted at `active + 1` with a fresh id, `nextName + 1` | the new layer | none |
| `delete(i)` | `size > 1` (`LAST_LAYER`); `!locked` (`LOCKED`) | layer removed | if `i == active`: `max(i − 1, 0)` — the layer that was *below*, or the new bottom; else the same layer as before (its index shifts) | `Delete(id)` (frees tiles) |
| `duplicate(i)` | `size < maxLayers` (`AT_CAP`) | copy of props with new id, name `+ " copy"`, `locked = false`, inserted at `i + 1` | the copy | `Copy(src, dst, keys)` |
| `move(from, to)` | `from != to` (`NOOP`) | list with the layer moved; `to` is the *final* index | the moved layer (follows the drag) | none |
| `mergeDown(i)` | `i > 0` (`NO_LAYER_BELOW`); neither locked (`LOCKED`); both visible (`HIDDEN_PARTNER`) | see §4.1 | the merged (lower) layer | `Merge(top, bottom, keys)` |
| `flatten()` | `size > 1` (`NOOP`); no locked layer (`LOCKED`) | see §4.4 | index 0 | `Flatten(...)` |
| `clear(i)` | `!locked`; layer has tiles (`NOOP`) | same props, empty tile set | unchanged | `Clear(id)` |
| `rename(i, name)` | differs (`NOOP`) | props updated | unchanged | none |
| `setOpacity / setVisible / setBlendMode / setAlphaLock / setLocked` | differs (`NOOP`) | props updated | unchanged | none |
| `select(i)` | in range | `activeIndex = i` | `i` | none — **not journaled** |

Selection is a view concern, not an edit: journaling it would bury
strokes under "selected layer 3" steps. Every journaled entry still stores
`activeBefore`/`activeAfter` (§5) so undo lands you where you were.

### 3.2 Invariants

Checked by `LayerStackTest` after every operation, on random operation
sequences (property-style, a few thousand per run — cheap on the JVM):

1. `layers.size >= 1`. `delete` on the last layer is refused; there is no
   path that empties the stack. Why refused rather than "delete and
   create a fresh one": the fresh layer would need an id, a name and a
   history entry pretending to be a delete; "clear" already exists for
   the intent behind it, and the panel offers it when delete is refused.
2. `activeIndex in layers.indices`.
3. Ids are unique within the stack and never reissued (UUIDs). Undo
   re-inserts a layer with its *original* id (from the entry), which is
   safe for the same reason; the tests use a deterministic id source so
   sequences are reproducible.
4. `layers.size <= budget.maxLayers` after `add`/`duplicate`; **not**
   enforced on undo, redo or load (§6.4).
5. Tile keys of a layer are within the canvas (`0 <= tx < ceil(w/256)`).

### 3.3 Pixel work

`PixelOp` is the message `CanvasViewModel` hands the GL thread through
`EngineSession.applyPixelOp` (`GLFrontBufferedRenderer.execute {}`,
`02-architecture.md` §4.3) after applying the new stack. The
model is updated first, the pixels follow within the same frame; the
compositor tolerates a one-frame window where a layer's tile set lists a
slice whose pixels are still being written, because every `PixelOp` writes
into *newly allocated* slices and swaps the index on completion.

```kotlin
sealed interface PixelOp {
    data class Copy(val src: LayerId, val dst: LayerId, val keys: Set<TileKey>) : PixelOp
    data class Merge(val top: LayerId, val topProps: LayerProps,
                     val bottom: LayerId, val keys: Set<TileKey>) : PixelOp
    data class Clear(val layer: LayerId) : PixelOp
    data class Delete(val layer: LayerId) : PixelOp
    data class Flatten(val order: List<LayerProps>, val result: LayerId) : PixelOp
    data class Restore(val layer: LayerId, val tiles: Map<TileKey, ByteArray?>) : PixelOp  // undo/redo
}
```

`Merge`, `Flatten` and `Copy` are tile-bounded GPU passes (one quad per
affected tile per source layer, the same shader as `CompositePass` in §4
mode); `Restore` uploads decoded tiles (`null` = delete the tile). None of
them touch the CPU mirror directly: the tiles they produce are marked
dirty like a stroke's, so autosave flushes them (`06-document-and-persistence.md`
§Autosave).

## 4. Blend modes

v1 ships eight separable modes. The stack composites in **premultiplied
RGBA8**, bottom-to-top, layer by layer, with the layer's opacity applied
to the *source* before blending (`s = layerPixel * opacity`, which scales
alpha and colour together — correct for premultiplied data). The general
separable form, with `s`/`d` premultiplied source/destination and
`cs = s.rgb / s.a`, `cd = d.rgb / d.a` the straight colours:

```
Co = s.rgb·(1 − d.a) + d.rgb·(1 − s.a) + s.a·d.a·B(cs, cd)
Ao = s.a + d.a·(1 − s.a)
```

The first two terms are the parts where only one layer has coverage; the
third is where both do, weighted by their joint coverage. For most modes
`s.a·d.a·B(cs, cd)` simplifies to an expression in `s.rgb`/`d.rgb`
directly, which is why premultiplied compositing is cheap: **only overlay
needs the unpremultiply**, because its `B` branches on the straight
destination colour.

| Mode | `B(cs, cd)` | `s.a·d.a·B` in premultiplied terms | Unpremultiply? |
| --- | --- | --- | --- |
| `NORMAL` | `cs` | `s.rgb·d.a` → whole thing collapses to `s.rgb + d.rgb·(1 − s.a)` | no |
| `MULTIPLY` | `cs·cd` | `s.rgb·d.rgb` | no |
| `SCREEN` | `cs + cd − cs·cd` | `s.rgb·d.a + d.rgb·s.a − s.rgb·d.rgb` | no |
| `OVERLAY` | `cd ≤ 0.5 ? 2·cs·cd : 1 − 2(1−cs)(1−cd)` | `s.a·d.a·B(cs, cd)` with `cs`, `cd` divided out (0 where alpha is 0) | **yes** |
| `DARKEN` | `min(cs, cd)` | `min(s.rgb·d.a, d.rgb·s.a)` | no |
| `LIGHTEN` | `max(cs, cd)` | `max(s.rgb·d.a, d.rgb·s.a)` | no |
| `ADD` | `min(1, cs + cd)` | `min(s.a·d.a, s.rgb·d.a + d.rgb·s.a)` | no |
| `DIFFERENCE` | `abs(cs − cd)` | `abs(s.rgb·d.a − d.rgb·s.a)` | no |

`ADD` is linear dodge with the clamp inside the joint-coverage term, so an
additive layer over transparent paper stays plain source-over (it must:
nothing to add to). All maths is on the stored sRGB-encoded values, not
linearised — the same convention as Mixbox ("RGB in, RGB out") and every
consumer app's blend modes; `09-color-and-mixing.md` §1 owns
that decision.

The shader function, which `CompositePass`, `Merge` and `Flatten` all
call (`03-canvas-engine.md` §3.3 writes the same arithmetic in
straight-colour form inside `composite.frag`; this table is normative).
`mode` is the `int` uniform `u_blend` per quad and comes from an explicit
`enum class BlendMode(val shaderId: Int)` — `NORMAL(0)`, `MULTIPLY(1)`,
… `DIFFERENCE(7)` in the table's order — not from `ordinal`, so reordering
the enum can never silently swap modes; `GlShaderContractTest` greps the
GLSL for every `shaderId` (`11-testing.md` §Shader contract):

```glsl
// s, d premultiplied; s already scaled by layer opacity
vec4 blendLayer(vec4 s, vec4 d, int mode) {
    float as = s.a, ad = d.a;
    vec3 both;
    if      (mode == 0) both = s.rgb * ad;                                   // NORMAL
    else if (mode == 1) both = s.rgb * d.rgb;                                // MULTIPLY
    else if (mode == 2) both = s.rgb * ad + d.rgb * as - s.rgb * d.rgb;      // SCREEN
    else if (mode == 3) {                                                    // OVERLAY
        vec3 cs = as > 0.0 ? s.rgb / as : vec3(0.0);
        vec3 cd = ad > 0.0 ? d.rgb / ad : vec3(0.0);
        vec3 lo = 2.0 * cs * cd;
        vec3 hi = 1.0 - 2.0 * (1.0 - cs) * (1.0 - cd);
        both = as * ad * mix(hi, lo, step(cd, vec3(0.5)));                   // cd <= 0.5 -> lo
    }
    else if (mode == 4) both = min(s.rgb * ad, d.rgb * as);                  // DARKEN
    else if (mode == 5) both = max(s.rgb * ad, d.rgb * as);                  // LIGHTEN
    else if (mode == 6) both = min(vec3(as * ad), s.rgb * ad + d.rgb * as);  // ADD
    else                both = abs(s.rgb * ad - d.rgb * as);                 // DIFFERENCE
    return vec4(s.rgb * (1.0 - ad) + d.rgb * (1.0 - as) + both, as + ad * (1.0 - as));
}
```

The CPU reference (`engine/core/Composite.kt`) is the same function on
`Int` ARGB premultiplied pixels with float intermediates and round-to-
nearest on store; `CompositeTest` runs every mode against hand-computed
pixels (opaque-over-opaque, half-over-half, over-transparent, source alpha
0, and the overlay branch on both sides of 0.5) and `GlShaderContractTest`
pins the uniform names and the `shaderId` branches. Because RGBA8 targets are
the guaranteed baseline on ES 3.0, both sides quantise to 8 bits after
every layer; the tests allow ±1 LSB between CPU and GPU, exactly 0 within
the CPU reference itself.

```kotlin
object Composite {
    /** Both premultiplied ARGB; `opacity` scales src first. Argument order
     *  (dst, src) is the one `CompositeTest` in 11-testing.md uses. */
    fun blend(dst: Int, src: Int, mode: BlendMode, opacity: Float): Int
    /** Full stack over `paper` (alpha 0 = transparent) for one tile — the
     *  reference flatten/export path and what the tests compare against. */
    fun tile(layers: List<Layer>, key: TileKey, paper: Int, pixels: TileReader): IntArray
}
```

`Composite.tile` is also what the JVM tests use to verify merge and
flatten semantics without a GPU (§4.1, §4.4 state the semantics in terms
of it).

### 4.1 Merge down — exact semantics

`mergeDown(i)` with `top = layers[i]`, `bottom = layers[i − 1]`:

- **Pixels.** For every `key` in `top.tiles`, the result tile is
  `Composite.tile([bottom, top], key, paper = TRANSPARENT)` — top blended over
  bottom **over transparent**, using `top.blendMode` and `top.opacity`
  and `bottom.opacity` (bottom's own blend mode does not participate:
  there is nothing beneath to blend into). Bottom's tiles at keys the
  top does not have are untouched.
- **Props.** The result keeps `bottom.id` and `bottom.name`; `opacity = 1`,
  `blendMode = NORMAL`, `alphaLock = false`, `visible = true`, `locked =
  false`. The top layer is removed.
- **Appearance.** Bottom's opacity is baked into the merged pixels and
  source-over is associative, so the picture is unchanged **iff
  `bottom.blendMode == NORMAL` and (`top.blendMode == NORMAL` or bottom
  is opaque wherever top has coverage)**. A normal bottom at *any*
  opacity merges exactly. Two cases change the picture: a non-normal
  bottom mode is *dropped* (the merged layer is normal, so it no longer
  multiplies/screens into what is beneath), and a non-normal top is
  *baked against bottom only* — where bottom is not fully opaque under
  top's coverage, top used to blend with the layers beneath too and now
  does not. Baking to normal/100 % is still the honest choice (what you
  see in the merged layer is what the two layers looked like on their
  own), so the panel confirms exactly when `bottom.blendMode != NORMAL ||
  top.blendMode != NORMAL`, with the matching sentence: "Bottom layer's
  blend mode will be dropped" / "Top layer's blend mode will be baked
  against this layer only" (both when both). Opacity never triggers it.
  Undo restores both layers precisely (§5).
- **Why both must be visible.** Merging a hidden top would either destroy
  its content (if hidden means "contributes nothing") or reveal it (if
  hidden means "temporarily not shown") — both surprise. Refusing with
  "Show both layers to merge" costs one tap and loses nothing.

### 4.2 Duplicate

Copies every tile of the source into new slices under a new id; props as
in §3.1. The copy is a straight `glCopy`-class operation (`03-canvas-
engine.md` §Tile pool), no blending. Memory cost is one more layer's
tiles, which is why it is gated by the cap like `add`.

### 4.3 Delete and clear

`delete` frees the layer's slices and removes it. `clear` frees the slices
and keeps the layer (props untouched, including the active selection). The
panel offers "Clear" wherever it refuses "Delete" for the last layer.

### 4.4 Flatten

Composites all **visible** layers, in order, with their modes and
opacities, **over transparent** — `Composite.tile(visibleLayers, key,
paper = TRANSPARENT)` for every key in the union of their tile sets — into one
new layer (fresh id, named `Flattened`), normal, 100 %, unlocked. Hidden layers are dropped. Alpha
is preserved: transparent paper stays transparent, an opaque paper is
*not* baked in, so the flattened painting still sits on a paper you can
recolour. The panel always confirms flatten ("Flatten N layers? Hidden
layers are discarded. You can undo this.") because it is the one
operation whose undo entry can be large (§5).

## 5. History entries

Every operation in §3.1 except `select` produces exactly one
`HistoryEntry`. The journal itself (sequence numbers, on-disk encoding,
the redo "after" capture, truncation, prune) is `06-document-and-
persistence.md`'s; this section says what each entry **stores as
"before"** so that undo is exact, and what redo needs.

The `HistoryEntry` sealed interface is declared **once**, in
`engine/core`, with the members and field types written out in
`06-document-and-persistence.md` §5.2 (`LayerAdd`, `LayerDelete`,
`LayerReorder`, `LayerProps`, `LayerMerge`, `LayerDuplicate`,
`LayerClear`, `Flatten`, `PaperColor`, plus `Stroke`/`Fill`); every entry
carries `seq`/`timestamp`/`bytes` and `activeBefore`/`activeAfter` (the
active layer's *id* before and after the edit — so undo lands where you
were, §3.1). The header holds `LayerRecord`s (the serialised form of
`LayerProps`) and tile *keys*; the tile payloads live beside the header
(`.entry` / `.redo`), never inside the Kotlin object. This section is the
mapping from §3.1's operations to those kinds and what each stores as
"before":

| §3.1 op → entry | "Before" data stored | Undo does | Redo does | Size |
| --- | --- | --- | --- | --- |
| `add` → `LayerAdd` | index, record | remove that layer (it is empty by construction — an add followed by painting is two entries) | re-insert an empty layer with the *same id* | bytes |
| `duplicate` → `LayerDuplicate` | source id, index, copy's record | remove the copy | re-copy from the source: legal because redo only runs when nothing changed since the undo, so the source is pixel-identical to what was copied. No tiles stored. | bytes |
| `move` → `LayerReorder` | id, from, to | move `to → from` | move `from → to` | bytes |
| `rename / set*` → `LayerProps` | full record before and after | restore before | restore after | bytes |
| paper colour → `PaperColor` | colour before and after | restore | restore | bytes |
| `delete` → `LayerDelete` | index, record, **all** tiles | re-insert with same id, upload tiles | remove again | layer's tiles |
| `clear` → `LayerClear` | **all** tiles of the layer | upload tiles | clear again | layer's tiles |
| `mergeDown` → `LayerMerge` | upper's index + record + **all** upper tiles; lower's id and record (`lower: LayerRecord`, because §4.1 resets its opacity/mode/flags); lower's tiles **at upper's keys only** (`len 0` where lower had none) | restore lower's record and those tiles (deleting the empty ones), re-insert upper with its tiles | journal captures the merged lower tiles at those keys as "after" on undo; redo uploads them and removes upper | upper + intersection |
| `flatten` → `Flatten` | every layer's record + **all** tiles, in order; the result's record | remove the result, re-insert all layers | remove them, re-insert the result with its "after" tiles | whole painting |

Where the "before" bytes come from: `TileSource.bytesOf(layerId, key)`
returns the CPU dirty-mirror copy if the tile is dirty (deflating it on
the IO thread), otherwise the *already deflated* file from `TileStore` —
a byte copy, no inflate/deflate round trip. Since dirty tiles flush after
every stroke, the disk path is the common one; the mirror path only wins
during the seconds after a stroke. A tile whose readback is still in
flight (the last stroke commit or a previous `PixelOp`; readbacks age two
frames or more, `10-performance.md` §4) is in *neither* place, so a
`PixelOp` that frees or overwrites slices (`Delete`, `Clear`, `Merge`,
`Flatten`) first drains the pending readbacks of every affected layer on
the GL thread (`Readback.await`, the same guarantee 06 §5.5 gives
consecutive strokes) and only then consults `TileSource.bytesOf`. The
model update (`StackEdit`) is still applied immediately; only the pixel
work and the entry's capture wait, and the entry is finalised on the IO
thread before the next entry may start (the journal is strictly ordered —
`06-document-and-persistence.md` §5). Without the drain, a Delete
tapped within a frame of pen-up would journal stale pixels and undo would
restore them — the case `HistoryEntryTest` pins.

Coalescing: an opacity slider drag is one `LayerProps` entry from press to release
(the ViewModel holds the pending entry and updates the layer live). Two
visibility toggles are two entries — they are deliberate taps. Nothing
else coalesces.

Journal cost: a `Flatten` or `DeleteLayer` of a fully painted 4096²
layer stores roughly what that layer costs on disk (deflated; typically a
small fraction of the 64 MiB raw). One entry may exceed the journal's byte
cap; the rule (owned by `06`) is that it is recorded anyway and prunes
everything older — the UI's "undo history: N steps" readout shrinks
visibly, nothing is refused.

## 6. Memory budget

`MemoryBudget` is pure JVM and **owned by `10-performance.md` §4**:
`MemoryBudget.compute(DeviceMemory, CanvasSize): Result`, with the
constants (`GPU_TILE_FRACTION`, `MAX_LAYERS`, `STROKE_BUFFER_RESERVE_LAYERS`,
…) and the pinned worked table living there and in `MemoryBudgetTest`.
This document consumes `Result.maxLayers` and `Result.maxCanvasEdge`; it
does not recompute them. `DeviceMemory` is gathered once (`totalMem`,
`isLowRamDevice()`, `getLargeMemoryClass()`, the GL limits) and the
budget is computed once per document open for that canvas size.

### 6.1 What a layer costs

Tiles are 256×256 RGBA8, 262 144 bytes each, and a layer allocates a tile
only where something was painted. The budget assumes the worst case — a
**fully painted layer**:

```
tilesPerLayer  = ceil(w / 256) · ceil(h / 256)
bytesPerLayer  = tilesPerLayer · 262 144          // GPU slices; L below
```

The CPU dirty mirror is bounded separately: it holds only tiles dirtied
since the last flush, flushes after every stroke, and the engine applies
back-pressure (a stroke commit waits for the mirror to drop below
`CPU_MIRROR_CAP_BYTES`, 64 MiB — `10-performance.md` §4)
rather than letting it grow (`06-document-and-persistence.md`
§6). So it is accounted as a fixed reserve, not per layer.

### 6.2 The formula (10's, restated for reading)

Let `C = CanvasSize.layerBytesWorstCase` and
`W = CanvasSize.wetLayerBytesWorstCase`:

```text
gpuTileBudgetBytes = isLowRamDevice ? LOW_RAM_GPU_TILE_BYTES (256 MiB)
                                    : (totalMem · GPU_TILE_FRACTION (1/8)).coerceIn(256 MiB, 1.5 GiB)
poolCapacityBytes  = whole texture arrays that fit gpuTileBudgetBytes
required(N)        = N · C + N · W + max(
                         C · STROKE_BUFFER_RESERVE_LAYERS,
                         W · WET_GESTURE_BACKUP_LAYERS
                     )
maxLayers          = largest N in MIN_LAYERS..MAX_LAYERS with required(N) ≤ poolCapacityBytes
maxCanvasEdge      = largest whole-tile edge whose square admits MIN_USEFUL_LAYERS
```

What this document relies on:

- **One eighth of `totalMem` for GPU tiles**, clamped. GL textures are native
  memory; fixed surfaces, scratch, the OS, and other processes keep the rest.
- **Persistent state:** every advertised layer may be fully painted and own
  a fully allocated quarter-resolution wet grid.
- **Gesture reserve:** an ordinary stroke needs one full colour-layer buffer;
  a watercolor gesture needs one wet-layer backup. They are mutually
  exclusive, so the formula reserves the larger. Sandwich halves remain
  sparse and unreserved.
- **Floor of 1:** an oversized canvas is refused by the New Canvas dialog;
  the layer cap itself never reports zero.
- **Cap of 16:** a UI bound that also limits pool slices. `TilePool` still
  queries `GL_MAX_ARRAY_TEXTURE_LAYERS` and shards across arrays.
- **The export term:** gallery export needs a canvas-sized native `Bitmap`
  and readback buffer. It is native headroom outside the tile-pool formula.

### 6.3 Worked table

Nominal RAM; the code uses reported `totalMem` and whole-array capacity.
Parentheses are the pre-`MAX_LAYERS` results.

| Canvas preset | Colour C | Wet W | 4 GB (512 MiB) | 8 GB (1 GiB) | 12 GB (1.5 GiB) | low-RAM (256 MiB) |
| --- | --- | --- | --- | --- | --- | --- |
| Phone sketch 1080×1920 | 10 MiB | 1 MiB | 16 (45) | 16 (92) | 16 (138) | 16 (22) |
| Square 2048×2048 | 16 MiB | 1 MiB | 16 (29) | 16 (59) | 16 (89) | 14 |
| Tablet 2560×1600 | 17.5 MiB | 1.5 MiB | 16 (26) | 16 (52) | 16 (79) | 12 |
| Large 4096×4096 | 64 MiB | 4 MiB | 6 | 14 | 16 (21) | 2 |

The preset list comes from `CanvasPresets`. Roadmap step 6's eight-layer
4096² acceptance fits the 14-layer 8 GB cap; its measured-jank criterion
still comes from `10-performance.md`.

### 6.4 What the user sees

- The layer panel header reads **"6 of 14 layers"** (`count of
  Result.maxLayers`, the one string, also 10 §2.6's) — always, not only
  near the cap, so the number is familiar before it matters.
- At the cap the **+** and **Duplicate** controls stay enabled and
  tappable; tapping shows a one-line explanation with the numbers:
  "This 4096×4096 canvas allows 14 layers on this device. Merge or delete
  a layer to add one." Nothing dims silently, nothing fails silently.
- The New Canvas dialog shows, per preset, the layer count that size
  affords ("4096×4096 · up to 14 layers" — `MemoryBudget.compute(device,
  size).maxLayers`) and refuses custom sizes beyond `Result.maxCanvasEdge`
  with the same kind of sentence.
- The cap is enforced **only** by `add` and `duplicate`. Undo, redo and
  document load never refuse: a document that is over the cap (an app
  update that changed the fraction, or a low-RAM flag that differs
  between boots) loads fully and the header reads "16 of 14 layers", with
  **+** explaining. Refusing to open a painting because of a number we
  chose would violate "nothing is ever lost".
- Post-v1 tile residency/eviction (PLAN.md roadmap) lifts `maxLayers`;
  the UI contract above does not change.

## 7. The layer panel — behaviour

Looks, sizes and placement are `08-ui-and-layout.md` §3.3; this document
owns what the panel *does* (08 §3.3 defers to this section and §6.4 for
behaviour). The panel is a slide-in over the canvas (full-height sheet
on compact widths), listing layers **top-first**, with the paper shown as
a swatch row at the bottom (tap → paper colour picker), not as a list
item.

| Gesture | Effect | Notes |
| --- | --- | --- |
| Tap a row | `select(i)` | Active row gets the accent bar and a slightly raised surface; also closes the panel on compact widths |
| Tap the eye | `setVisible(!visible)` | Hidden rows dim; the active layer can be hidden (strokes on a hidden active layer are allowed — they go to the layer, the preview shows them through the sandwich as if visible, and a hint says "Layer is hidden") |
| Drag the handle | `move(from, to)` on release | Rows reflow live during the drag; a haptic tick at every index change and on drop; the dragged layer stays active |
| Swipe a row (expanded widths) / hold a row (all widths) | contextual actions: Duplicate, Merge down, Clear, Delete, Rename, Alpha lock, Lock | Refused actions are shown, tapping one shows the `Refusal` hint (§3): "Can't delete the only layer — Clear instead?", "Layer is locked", "Show both layers to merge", the cap sentence |
| Tap the opacity value | inline slider; one `LayerProps` entry per gesture | Haptic tick at 0 %, 50 %, 100 % |
| Tap the mode label (also reachable as "Blend mode" in the row's overflow) | blend mode chooser (the eight of §4) | One `LayerProps` entry |
| Header **+** | `add()` | New layer above the active one, becomes active, panel stays open |
| Header overflow | Flatten (confirm, §4.4), Merge all visible = flatten | |
| Merge down (row action) | `mergeDown(i)`, with the §4.1 confirmation when either layer's blend mode is not normal | |

**Active layer after an operation** follows §3.1: after `delete` of the
active layer, the layer that was below (or the new bottom) — deleting
another layer leaves the selection alone; after `mergeDown`, the merged layer;
after `add`/`duplicate`, the new layer; after `flatten`, the only layer;
after undo/redo, `activeBefore`/`activeAfter` from the entry. The panel
scrolls the active row into view when it changes by anything other than
a tap.

**Thumbnails.** Each row shows the layer alone over a checkerboard, at the
canvas aspect. They come from the GPU: a `Thumbnail` pass renders the
layer's tiles into a per-layer small texture (longest side 128 px,
uploaded once, reused) and an async PBO readback delivers it
(`03-canvas-engine.md` §Readback). Refresh policy:

- never while a stroke is live (the front-buffer path owns the GPU then);
- on stroke commit / any `PixelOp` completion, the affected layer is
  marked thumb-dirty;
- dirty thumbs refresh at most every **500 ms** per layer and only while
  the panel is open (or about to open — opening the panel forces one
  refresh of all dirty rows); a closed panel costs nothing;
- the panel's whole-picture preview at its top uses the sandwich cache +
  active layer (one composite of already-cached data), refreshed on the
  same schedule;
- the Studio thumbnail (`thumb.png`) is a different thing, produced at
  checkpoint time from the full composite (`06`).

**Feedback.** Every accepted structural op ticks (haptics setting
permitting); every refusal ticks differently (the "error" haptic) and
shows its hint. Hints are snackbar-style, single line, no buttons, except
the "Clear instead?" one which is a real action.

## 8. Sandwich cache invalidation

The sandwich (`03-canvas-engine.md` §4) is two composites in
canvas tile space — `below` = the paper plus all visible layers under the
active one (the paper is baked in, 03 §4), `above` = all visible layers
over it — cached per tile and rebuilt lazily
when a tile is drawn and its side is marked stale. Marking a side stale
is the only thing this document's operations do to it; the rebuild
happens on the GL thread at the next composite of that tile. Pixel edits
on a non-active layer (undo/redo restoring tiles there) rebuild only the
affected tiles, per 03 §4's table. The rules,
with `a` = active index before the operation and `i` the operated index:

| Operation | `below` | `above` | Why |
| --- | --- | --- | --- |
| `select(i)` | stale | stale | membership of both sides changed |
| `setVisible / setOpacity / setBlendMode` on `i < a` | stale | — | |
| … on `i > a` | — | stale | |
| … on `i == a` | — | — | the active layer is composited live between the two halves; opacity/mode of the active layer are uniforms of that pass |
| `setAlphaLock / setLocked / rename` | — | — | no pixels change |
| `move(a, to)`, `to != a` | stale | stale | **corrected 2026-08-25** — every layer the active one crosses leaves one half and joins the other, even for an adjacent move. The old rule ("only the side it crossed into") tracked the *moved* layer, which is in neither half; in `[L0, L1(active), L2]`, `move(1, 0)` puts L0 into `above`, and staling only `below` left L0 in neither half, so it vanished from the canvas. Found by review on PR #11 |
| `move(from, to)`, `from != a` | stale | stale | the moved layer becomes active (§3.1), so `a` changes |
| `add()` | stale | — | the new (empty) layer becomes active; the old active joins `below` (see note) |
| `duplicate(a)` | stale | — | as `add`: the copy sits at `a + 1` and becomes active; `above` is unchanged |
| `duplicate(i)`, `i != a` | stale | stale | the copy becomes active (§3.1), so `a` changes |
| `delete(i)`, `i == a` | stale | stale | new active is the layer below |
| `delete(i)`, `i != a` | the side containing `i` | | the active layer keeps its identity (§3.1) |
| `mergeDown(a)` | stale | — | bottom absorbed the active; merged layer is the new active, `above` unchanged |
| `mergeDown(i)`, `i != a`, `i − 1 != a` | stale | stale | the merged layer becomes active (§3.1), so `a` changes |
| `mergeDown(a + 1)` (merge the layer above into the active) | — | stale | the active layer's pixels change (live pass) and the above set shrinks |
| `clear(a)` | — | — | live pass reads the layer directly |
| `clear(i)`, `i != a` | the side containing `i` | | |
| `flatten()` | stale | stale | both become empty composites |
| paper colour | stale | — | the paper is baked into `below` (03 §4: a non-normal bottom layer over a transparent backdrop would otherwise degenerate to source-over) |
| undo / redo | stale | stale | the entry can be anything; two stale flags cost one rebuild of the visible tiles, and undo is not a per-frame event |
| stroke commit on active | — | — | the stroke buffer merges into the active layer only |

Note on `add`: the active index changes, so by the general principle
(**any change of `a` stales both sides** — which is why merge, duplicate
and move of a *non-active* layer stale both: §3.1 makes the result
active) both would go stale; `add` is special-cased because the new
active layer is empty and sits directly on the old active, so `above` is
provably unchanged — the `below` rebuild is
the only cost of the most frequent structural op. A stale flag is per
side, not per tile: the per-tile rebuild is lazy and viewport-bounded, so
staleness costs at most one extra composite of the visible tiles, roughly
the price of one frame.

Pan/zoom/rotate never stale anything — the cache is in canvas space —
but newly visible tiles are built on demand, which is why the first frame
after a big pan on a deep stack can be heavier (`10-performance.md`).

## 9. Test checklist (JVM)

- `LayerStackTest`: every §3.1 precondition refuses with the right
  `Refusal`; every invariant in §3.2 holds after random sequences; active
  index after each op matches §3.1; ids never repeat across add/undo/redo.
- `CompositeTest`: each mode against hand-computed pixels including the
  overlay branch and alpha-0 sources; alpha-lock merge keeps alpha
  exactly; erase merge.
- `MergeSemanticsTest`: merge result equals `Composite.tile([bottom, top],
  paper = TRANSPARENT)`; flatten equals `Composite.tile(visible, paper =
  TRANSPARENT)` and ignores hidden layers; neither bakes the paper;
  appearance is preserved exactly in the §4.1 cases and changes in the
  two confirmation cases (non-normal bottom, non-normal top over a
  partly transparent bottom).
- `HistoryEntryTest`: for each entry type, apply → undo → stack and tiles
  equal the original (structural and pixel equality via the CPU tile
  reader); undo → redo equals the post-op state; `LayerDuplicate` redo
  reproduces the copy without stored tiles; a delete/clear/merge issued
  while a fake readback of the last stroke is still pending journals the
  *post-stroke* tile (the drain in §5).
- `MemoryBudgetTest` (owned by 10 §7): the table in §6.3 (printed by the
  test so the doc can be regenerated), floor/cap clamps, the low-RAM
  flat budget, `maxCanvasEdge` against the GPU and export terms.
- `SandwichInvalidationTest`: a pure `SandwichState` receives each op and
  the stale flags match §8's table.
- `GlShaderContractTest`: `blendLayer` has one branch per
  `BlendMode.shaderId`, ids are distinct, uniform names match.
