# 10 — Performance: budgets, targets, profiling plan

**What this document covers.** The numbers the engine is built against —
latency, frame time, memory, startup, reopen, fill, battery — and the
engineering plan that keeps them true: what is traced, what is benchmarked,
what gates a PR, which devices are tested by hand, and which risks have a
prepared fallback. It expands PLAN.md §1 principle 2 ("fast first") and
principle 4 ("honest about limits") and decision 4 (`MemoryBudget`). The
mechanisms being budgeted (stroke buffer, sandwich cache, PBO readback,
tile pool) are designed in `docs/plan/03-canvas-engine.md`; the layer cap
this document computes is what `docs/plan/05-layers.md` shows in the layer
panel; the on-disk costs come from `docs/plan/06-document-and-persistence.md`.
Where a number here and a number there disagree, this document is wrong
and must be fixed — the code carries one set of constants, listed in §4.

## 1. Device classes

Budgets are stated per class, not per model. `DeviceClass` is derived at
startup from `ActivityManager.getMemoryInfo().totalMem`, `isLowRamDevice()`,
the display refresh rate, and the GL limits queried on the first context
(`GL_MAX_ARRAY_TEXTURE_LAYERS`, `GL_MAX_TEXTURE_SIZE`). It is never derived
from the model name.

| Class | Reference hardware | RAM | Display | Role in targets |
| --- | --- | --- | --- | --- |
| **A — flagship tablet** | Galaxy Tab S9/S10 family | 8–16 GB | 2560×1600 or 2960×1848, 120 Hz | the "must feel perfect" target: every number below is a hard target here |
| **B — budget tablet** | Galaxy Tab S6 Lite / A9+ (Mali) | 4 GB | 2000×1200, 60–90 Hz | the "must not jank, must not die" target; layer cap is visible here |
| **C — S Pen phone** | Galaxy S22–S25 Ultra | 12 GB | ~3088×1440, 120 Hz | class-A latency at a small viewport |
| **D — finger phone** | Pixel phone | 8+ GB | varies, 60–120 Hz | correctness and touch feel; no stylus path |
| **E — foldable** | Galaxy Z Fold | 12 GB | 2176×1812 inner, resizes on fold | class-A engine, adaptive-UI and surface-resize stress |

"Tab S9-class" below means class A; "Tab S6 Lite-class" means class B.

## 2. Targets

Every target is a number with a measurement recipe (§5). A target without
a recipe is a wish; none of those are listed.

### 2.1 Pen-to-pixel latency

The path being measured is *input event enters the process → dab is
stamped → front-buffered layer is presented*. Everything before (digitizer,
kernel, InputDispatcher) and after (display controller scan-out) is
outside the app; we measure our slice and observe the rest in Perfetto.

| Stage (main thread unless noted) | Class A budget | Class B budget | How it is kept |
| --- | --- | --- | --- |
| `MotionEvent` → `StrokeInput` samples (`CanvasTouchHandler`), including historical batch | 0.3 ms | 0.5 ms | no allocation, inverse `ViewTransform` applied per sample as 6 float mults |
| `Stabilizer` + `DabGenerator` → dab batch | 0.5 ms | 1.0 ms | per-dab math is a handful of flops; batch written into the ring buffer in place |
| hand-off to GL thread (`renderFrontBufferedLayer(param)`) | 0.2 ms | 0.3 ms | `param` is the preallocated `DabBatch` ring slot itself (`02-architecture.md` §3.2) — a reference, no copy, no allocation |
| GL: stamp dabs into the stroke buffer (`DabPass`) | 1.0 ms | 2.0 ms | instanced quads, one draw per (tile, batch); dirty rect only |
| GL: re-composite the dirty rect into the front layer (three passes: below-sandwich, active ⊕ stroke buffer, above-sandwich) | 1.5 ms | 3.0 ms | scissored to the dirty rect plus the predicted tail |
| **App total** | **≤ 3.5 ms** (< 1 frame at 120 Hz = 8.33 ms, with margin for the display pipeline) | **≤ 7 ms** (< 2 frames at 60 Hz = 33 ms, comfortably) | |

Predicted-tail (`MotionEventPredictor`) points are drawn on top in the
same front-layer pass and cost nothing extra worth budgeting; they are
never stamped into the stroke buffer (PLAN.md §3.1).

The fallback path (front buffer unsupported on the device, §7) renders
through the multi-buffered layer only; the target there is *≤ 2 frames*
on any class, and the debug overlay says which path is live so a latency
complaint can be triaged in one glance.

### 2.2 Input consumption at 120 Hz

With `requestUnbufferedDispatch` live, a Tab S9 delivers stylus events at
the digitizer rate — expect several hundred samples per second, arriving
in batches (`getHistorySize()` up to a dozen samples per event). The
handler must drain every batch inside the budget above regardless of
size: cost is linear in samples, and the per-sample constant is the one
measured by the `DabGenerator` microbenchmark (§5.2). Dab count is bounded
by spacing, not by sample count: a slow stroke with a big brush produces
fewer dabs than samples, a fast stroke with a tiny brush the reverse
(spacing × radius interpolated along the segment). The ring buffer holds
`DAB_RING_CAPACITY` dabs (§4); if a batch would overflow it, the handler
stamps what fits, `renderFrontBufferedLayer` is called, and the remainder
goes into the next slot — never dropped, never allocated.

### 2.3 Composite frame time

Full composite = what `onDrawMultiDoubleBufferedLayer` does on commit, on
pan/zoom/rotate, and after undo: for every visible tile, one quad per
visible layer bottom-to-top with blend mode and opacity.

| Scenario | Class A (2560×1600 viewport) | Class B (2000×1200 viewport) |
| --- | --- | --- |
| 8 layers, all opaque-ish, canvas at 1:1 | ≤ 4 ms | ≤ 8 ms |
| 8 layers, zoomed out (whole 4096² canvas visible: 256 tiles × 8) | ≤ 4 ms (fill-bound, not draw-bound: output pixels are constant) | ≤ 8 ms |
| Live stroke re-composite (dirty rect only, 3 passes) | ≤ 1.5 ms | ≤ 3 ms |
| Sandwich rebuild after switching active layer | ≤ 4 ms for the visible tiles (a canvas-space composite of the *present* tiles into two scratch slices, `03-canvas-engine.md` §4); a fully painted 4096² stack takes tens of ms, done once at layer switch and never per frame | ≤ 8 ms |

Why it is bounded: the composite cost is `viewport pixels × visible
layers` fragment invocations, independent of canvas size; empty tiles
(no slice in the layer's index) are skipped on the CPU before any draw
call, so a sparse painting is cheaper than the table says. The scheme
is the one in `03-canvas-engine.md` §3.2: every layer accumulates into
**one** viewport-sized RGBA8 target (`Accum`). Normal-mode layers (the
common case, and also add/erase) draw their tile quads with hardware
`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` — one texture read per
quad, and a tile that is absent is simply not drawn. Only a layer whose
blend mode needs the backdrop (multiply, screen, overlay…) first blits
the dirty rect `Accum → Scratch` and samples `Scratch` as the backdrop in
the shader (two reads per fragment, and `Accum` is left untouched where
the layer has no tile). So the table's `visible layers` passes is an
upper bound; the fixed-cost table below counts exactly two viewport
targets, `Accum` and `Scratch`.

### 2.4 Zero allocation on the touch path

The rule: **from `onTouchEvent` to `renderFrontBufferedLayer` no object is
allocated in steady state.** Concretely:

- `StrokeInput` is never allocated per sample: samples are mutable
  objects pooled in a preallocated `StrokeInputBatch`
  (`07-input-and-stylus.md` §2) and the per-stroke ring the handler keeps
  for backpressure holds `STROKE_INPUT_CAPACITY` of them, reused across
  strokes with a `size` cursor. Overflow (a stroke longer than the
  capacity) spills the oldest samples to a second pool that is allocated
  once and kept.
- `Dab` is likewise a slot in `DabBatch` (SoA `FloatArray`s, the
  `DAB_STRIDE` = 8 fields x, y, radius, flow, hardness, angle, aspect,
  seed — `02-architecture.md` §3.2), written by
  `DabGenerator.begin/advance/end(…, out)` (`04-tools.md` §3) into a caller
  supplied batch.
- The ring buffer between threads is `DabRing`: `Array<DabBatch>` of
  `DAB_RING_SLOTS` batches with atomic head/tail. The main
  thread fills the head slot and publishes that `DabBatch` as the `param` of
  `renderFrontBufferedLayer`; the GL thread reads it and releases the slot
  after the multi-buffered replay (`02-architecture.md` §3.2). Slots are
  never reallocated.
- No lambdas, no `forEach` on collections, no `Pair`, no boxing on this
  path. `MotionEvent` accessors that take an `axis` int are fine;
  `PointerCoords`/`PointerProperties` objects are preallocated once.
- `Stabilizer` state is a fixed-size window (`FloatArray`), not a list.

The rule is enforced three ways: a lint-style grep in CI (§6), the
`AllocationCounterTest` pattern in JVM tests (§6), and the debug overlay
showing GC events during a stroke (§5.3). The GL thread obeys the same
rule for the stamp and composite passes (uniform arrays and draw lists are
preallocated per pass); the exceptions are the once-per-stroke events
(§2.5), which are allowed to allocate on the GL thread *after* the front
layer has been cleared.

### 2.5 Stroke-end costs

Pen-up triggers, in order: (1) `commit()` — the stroke buffer is merged
into the active layer's tiles and the multi-buffered layer is redrawn;
(2) a PBO readback of every dirtied tile; (3) journal entry + tile flush on
IO. The next stroke may begin within one frame of pen-up (a fast hatching
motion does exactly that), so:

| Step | Thread | Budget | Non-stall rule |
| --- | --- | --- | --- |
| merge stroke buffer → active layer (`MergePass`, per dirty tile, `03-canvas-engine.md` §7.4) | GL | ≤ 2 ms for 64 tiles | scissored, one draw per tile; Mixbox merge uses the LUT sampler, same cost class |
| full composite of the multi-buffered layer | GL | §2.3 | — |
| `glReadPixels` into PBOs for dirty tiles | GL | ≤ 0.5 ms to *issue* for one PBO (64 tiles): ≈ 8 µs per tile | each PBO holds `READBACK_PBO_TILES` (64) tiles = 16 MiB; per tile one `glFramebufferTextureLayer` (tiles are array slices, so every tile is its own FBO attachment) + one `glReadPixels` at byte offset `i × TILE_BYTES` — the 256 FBO rebinds of a whole-layer stroke are what the issue budget pays for; a stroke dirtying more than 64 tiles is read in 64-tile chunks, one PBO each, across successive GL entries; never `glMapBufferRange` in the same frame; the map happens ≥ 2 frames later, or on the next commit, whichever comes first, from `Readback.poll()` |
| copy mapped PBO → CPU tile mirror | GL (copy) then handed to IO | ≤ 1 ms for 64 tiles (memcpy) | `Readback` owns `READBACK_PBO_COUNT` (2) PBOs in rotation, 32 MiB in the fixed table; when both are in flight, the oldest is mapped (a stall, counted in the overlay as `readbackStalls`) |
| journal "before" tiles + flush dirty tiles | IO | not on the critical path | no GPU readback beyond the one above. In GL order, for stroke N: (1) the before-tiles are journaled by taking the dirty tiles' *current* bytes from the CPU mirror (or from disk when the tile is not mirrored — disk equals GPU for any tile whose last stroke has been read back and flushed); (2) only then is N's mapped readback applied to the mirror; (3) flush. The mirror generation of a tile is never overwritten by N's map before N's journal copy is taken — the old mirror buffer is *handed* to `HistoryStore` as the entry's payload and the freshly mapped buffer becomes the mirror, no memcpy. `06-document-and-persistence.md` §5.5 specifies this ordering; deflate on IO |

A second stroke starting while step 4 is still pending simply keeps
painting — the stroke buffer is per-stroke, dabs stamp into it freely, and
only the *merge* of stroke N+1 waits for N's readback to be **mapped**
(`Readback.await`, `06-document-and-persistence.md` §5.5 — normative;
`03-canvas-engine.md` §10.1), so the "before" tiles N+1 journals are N's
result, never N's predecessor. In practice N's chunk has been mapped a
frame or two after pen-up, long before N+1's pen-up. Ordering is by GL
command order, so readback N always sees the merge of N and not N+1.

### 2.6 Memory budget

Units: a tile is 256×256×4 B = **256 KiB**; a 4096² layer fully painted
is 16×16 = 256 tiles = **64 MiB**; 2048² is 64 tiles = 16 MiB; 1024² is
16 tiles = 4 MiB. GPU textures are native memory and count against system
RAM but not the Java heap.

**Fixed costs** (independent of layer count):

| Item | Class A (2560×1600) | Class B (2000×1200) | Notes |
| --- | --- | --- | --- |
| front-buffered + multi-buffered surfaces | 3 × 15.6 MiB = 46.9 MiB | 3 × 9.2 MiB = 27.5 MiB | owned by graphics-core / SurfaceFlinger, sized to the viewport (2560×1600×4 B = 15.6 MiB; 2000×1200×4 B = 9.2 MiB) |
| `Accum` + `Scratch` composite targets | 2 × 15.6 MiB = 31.3 MiB | 2 × 9.2 MiB = 18.3 MiB | `03-canvas-engine.md` §3.2; the only viewport-sized GL targets |
| readback PBOs | 2 × 16 MiB = 32 MiB | 32 MiB | `READBACK_PBO_COUNT` × `READBACK_PBO_TILES` × `TILE_BYTES` |
| Mixbox LUT | 1 MiB | 1 MiB | 512×512 RGBA8 |
| brush grain textures | ≤ 4 MiB | ≤ 4 MiB | — |
| **Fixed GPU total** | **≈ 115 MiB** | **≈ 83 MiB** | independent of canvas size |

Not in the fixed total, because they live in the tile pool and are paid
from `gpuTileBudgetBytes`: the **stroke buffer** (tiles allocated on
first dab, worst case one layer-equivalent — this is the
`STROKE_BUFFER_RESERVE_LAYERS` = 1 in the cap math below, and nowhere
else) and the two **sandwich caches** (canvas-space, sparse: only the
keys present in a contributing layer, `03-canvas-engine.md` §4 — so
`SANDWICH_MARGIN_PX` is not a texture border but the canvas-space
margin around the viewport that the rebuild fills first). The cap math
does not reserve pages for the sandwich; it rides on the tiles no layer
has painted, and on a fully painted canvas at the cap the lazy page
allocation in §4 is the backstop ("layer limit reached early").

**Per-layer costs**: `tiles(canvas) × 256 KiB` GPU worst case (all tiles
painted), plus the CPU mirror — only the tiles dirtied since the last
flush, so normally a few MiB, worst case one layer-equivalent while the IO
thread catches up (bounded by back-pressure: `TileStore` refuses a new
flush batch until the previous one is written, and the mirror grows at
most to `CPU_MIRROR_CAP_BYTES`).

**Budgets per class** (what `MemoryBudget` computes; the constants are in
§4):

| Input | Class A (8 GB) | Class A / C (12–16 GB) | Class B (4 GB) | Class B, `isLowRamDevice` |
| --- | --- | --- | --- | --- |
| `gpuTileBudgetBytes` = `totalMem × GPU_TILE_FRACTION` (1/8), clamped to 256 MiB..1.5 GiB | 1 GiB | 1.5 GiB | 512 MiB | 256 MiB |
| layer cap at 4096² (64 MiB/layer, 1 reserved for the stroke buffer) | 16 − 1 = **15** | 24 − 1 → **16** (MAX_LAYERS) | 8 − 1 = **7** | 4 − 1 = **3** |
| layer cap at 2048² (16 MiB/layer) | 16 | 16 | 32 − 1 → **16** | 16 − 1 = **15** |
| layer cap at 1024² | 16 | 16 | 16 | 16 |
| largest preset offered (`maxCanvasEdge`: ≤ `MAX_CANVAS_EDGE_V1` and `MIN_USEFUL_LAYERS` + reserve must fit) | 4096² (8192×4096 is post-v1 with eviction) | 4096² | 4096² (with the cap shown: "7 layers at this size") | 2560×1600 and below, Custom up to 3584² (4096² is not offered: 5 × 64 MiB > 256 MiB; the 4096² "3 layers" cap row above is what a Custom 3584² still nearly gets) |
| history journal cap (`HistoryStore`) | 200 steps / 256 MiB on disk | same | 100 steps / 128 MiB | 100 / 128 MiB |
| thumbnails resident in Studio | ≤ 24 decoded at ≤ 512 px longest edge ≈ 24 × 1 MiB | same | ≤ 12 | ≤ 8 |
| Compose + ViewModel + misc Java heap | ≤ 64 MiB | same | ≤ 48 MiB | ≤ 48 MiB |

The roadmap acceptance "8 layers on a 4096² canvas on an 8 GB tablet
without jank" (PLAN.md §10 step 6) sits at 8 × 64 = 512 MiB of a 1 GiB
tile budget, leaving headroom for the fixed costs and for the OS.

The layer-cap numbers are what the layer panel shows as "N of M layers"
and what the New Canvas dialog uses to annotate each preset ("up to 5
layers on this device"); `docs/plan/05-layers.md` and `08-ui-and-layout.md`
consume `MemoryBudget.Result`, they do not recompute it.

### 2.7 Startup, reopen, fill, battery

| Target | Number | How |
| --- | --- | --- |
| Studio cold start to first frame with the shelf laid out | < 500 ms (class A), < 800 ms (class B) | `ProjectStore.list()` reads only `project.json` files (a few KB each) on IO, sorted by `updatedAt` (`06-document-and-persistence.md` §7); thumbnails are decoded lazily per visible card (`BitmapFactory` with `inSampleSize` to ≤ 512 px) and cached in a `LruCache` sized per §2.6; no GL context is created until the Canvas screen |
| Reopen a 4096², 8-layer, fully painted painting (2048 tiles, ~512 MiB raw, typically 100–200 MiB deflated) | < 2 s to interactive (class A); < 4 s (class B) | tiles are read and inflated in parallel on `Dispatchers.Default` (one job per layer, chunked by `REOPEN_INFLATE_CHUNK` tiles) into a fixed staging pool of `REOPEN_STAGING_TILES` (64 = 2 × `UPLOAD_BATCH_TILES`) preallocated direct `ByteBuffer`s — an inflate job *suspends* when the pool is empty, so at most 16 MiB of inflated tiles ever wait for the GL thread whatever the painting's size, and the reopen heap ceiling is that 16 MiB plus the deflated read buffers of the running chunks; uploaded on the GL thread via `execute {}` in batches of `UPLOAD_BATCH_TILES` per frame (`glTexSubImage3D` per tile), each upload returning its buffer to the pool; the viewport's visible tiles are prioritised — the canvas becomes interactive when the *visible* tiles of every layer are resident, the rest stream in behind (the overlay shows `tilesPending`) |
| Bucket fill on a 4096² canvas, worst case (fill the whole canvas, all-layers reference) | < 1 s on class A; cancellable at any time | `FloodFill` runs on `Dispatchers.Default` over the CPU composite of the reference (built by a readback of the visible layers' tiles in the fill's bounding region, expanding as the fill grows); scanline fill with a span stack, `ByteArray` mask, `expand` as a separable dilation; the result is uploaded as tiles into the stroke buffer and merged like a stroke; `isActive` is checked per scanline row, cancellation drops the mask |
| Battery: no continuous render loop | idle app draws 0 frames | the multi-buffered layer is rendered only on commit, view change, undo/redo, layer change, or surface change; the front-buffered layer exists only between pen-down and commit; no `Choreographer` callback is registered while nothing is dirty; the hover cursor is a Compose overlay, not a GL frame |
| Frame rate request | none in v1 (`03-canvas-engine.md` §11): the front path ignores vsync anyway. `SurfaceControlCompat.Transaction.setFrameRate` to the display's max while a stroke is live is a post-v1 experiment ("to verify" that the platform honours it for a SurfaceView child layer) | |

## 3. Why these numbers

- **< 1 frame at 120 Hz** is the point where a stylus stroke stops
  trailing the pen tip visibly; with prediction covering the remaining
  display pipeline, the tail becomes invisible. Above ~2 frames, users
  call the app "laggy" regardless of what else it does.
- **≤ 4 ms composite** leaves half a 120 Hz frame for the system
  compositor and for our own stamp pass in the same frame; on class B
  8 ms is half of a 60 Hz frame for the same reason.
- **Zero allocation** is not an optimisation; it is what prevents a GC
  pause landing mid-stroke, which shows as a visible hitch in the line.
- **1 GiB of tiles on 8 GB** is a quarter of what the OS lets a foreground
  app touch before the low-memory killer gets interested; on 4 GB the
  fraction is the same but the absolute is 512 MiB, and the low-RAM flag halves it, because those devices run closer
  to their limits at rest.

## 4. Constants and budget types the code carries

All in `engine/core`, pure JVM, tested by `MemoryBudgetTest` and
`CanvasPresetsTest`. Nothing in `engine/gl` or `ui/` invents a number.

```kotlin
package ch.lkmc.bangnidraw.engine.core

object PerfConstants {
    const val TILE_SIZE = 256
    const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4          // 262 144

    // Touch path (all preallocated once per CanvasTouchHandler)
    const val STROKE_INPUT_CAPACITY = 8192       // samples kept in the SoA before spilling
    const val DAB_STRIDE = 8                     // x y radius flow hardness angle aspect seed (02 §3.2; colour and opacity are per stroke)
    const val DAB_BATCH_CAPACITY = 1024          // dabs per ring slot
    const val DAB_RING_SLOTS = 8
    const val DAB_RING_CAPACITY = DAB_BATCH_CAPACITY * DAB_RING_SLOTS
    const val STABILIZER_WINDOW = 16

    // Stroke end
    const val READBACK_PBO_COUNT = 2             // in-flight readbacks before a forced map (03-canvas-engine.md §Readback)
    const val READBACK_PBO_TILES = 64            // tiles per PBO = 16 MiB; larger key sets are chunked
    const val READBACK_MIN_FRAME_AGE = 2         // frames a PBO must age before mapping
    const val CPU_MIRROR_CAP_BYTES = 64L shl 20  // back-pressure threshold for TileStore

    // Reopen
    const val REOPEN_INFLATE_CHUNK = 16          // tiles per inflate job
    const val UPLOAD_BATCH_TILES = 32            // glTexSubImage3D calls per GL frame
    const val REOPEN_STAGING_TILES = 2 * UPLOAD_BATCH_TILES  // inflated-but-not-uploaded bound (16 MiB)

    // Composite
    const val SANDWICH_MARGIN_PX = 256           // canvas-space margin around the viewport the sandwich rebuild fills first

    // Budget
    const val MAX_LAYERS = 16
    const val MIN_LAYERS = 1
    const val MIN_USEFUL_LAYERS = 4              // a preset is offered only if this many layers fit
    const val MAX_CANVAS_EDGE_V1 = 4096          // 8192×4096 is post-v1 (needs eviction)
    const val HISTORY_STEPS_LARGE = 200;  const val HISTORY_BYTES_LARGE = 256L shl 20   // totalMem ≥ 6 GiB
    const val HISTORY_STEPS_SMALL = 100;  const val HISTORY_BYTES_SMALL = 128L shl 20
    const val THUMB_MIB_LARGE = 24; const val THUMB_MIB_SMALL = 12; const val THUMB_MIB_LOW_RAM = 8
    const val LARGE_DEVICE_TOTAL_MEM = 6L shl 30
    const val GPU_TILE_FRACTION = 0.125          // of totalMem, before clamps
    const val GPU_TILE_MIN_BYTES = 256L shl 20
    const val GPU_TILE_MAX_BYTES = 1536L shl 20
    const val LOW_RAM_GPU_TILE_BYTES = 256L shl 20
    const val STROKE_BUFFER_RESERVE_LAYERS = 1
}
```

```kotlin
/** Everything MemoryBudget needs; gathered once in MainActivity/Hilt, never re-queried per frame. */
data class DeviceMemory(
    val totalMemBytes: Long,          // ActivityManager.MemoryInfo.totalMem
    val isLowRamDevice: Boolean,      // ActivityManager.isLowRamDevice()
    val largeMemoryClassMb: Int,      // ActivityManager.getLargeMemoryClass()
    val glMaxArrayLayers: Int,        // GL_MAX_ARRAY_TEXTURE_LAYERS, queried on first context (0 = unknown yet)
    val glMaxTextureSize: Int,        // GL_MAX_TEXTURE_SIZE
)

data class CanvasSize(val width: Int, val height: Int) {
    val tilesX get() = (width + TILE_SIZE - 1) / TILE_SIZE
    val tilesY get() = (height + TILE_SIZE - 1) / TILE_SIZE
    val tilesPerLayer get() = tilesX * tilesY
    val layerBytesWorstCase get() = tilesPerLayer.toLong() * TILE_BYTES
}

object MemoryBudget {
    data class Result(
        val gpuTileBudgetBytes: Long,   // what TilePool may allocate for tiles, all arrays together
        val maxLayers: Int,             // for THIS canvas size, 1..MAX_LAYERS
        val maxCanvasEdge: Int,         // largest edge any preset may offer on this device
        val historyMaxSteps: Int,
        val historyMaxBytes: Long,
        val thumbnailCacheBytes: Long,
        val transientImageBytes: Long, // maximum decoded RGBA8 import
        val poolArraySlices: Int,       // slices per texture array TilePool creates (≤ glMaxArrayLayers)
        val poolArrayCount: Int,        // how many arrays fit the budget
    )

    fun compute(device: DeviceMemory, canvas: CanvasSize): Result {
        val gpu = when {
            device.isLowRamDevice -> LOW_RAM_GPU_TILE_BYTES
            else -> (device.totalMemBytes * GPU_TILE_FRACTION).toLong()
                        .coerceIn(GPU_TILE_MIN_BYTES, GPU_TILE_MAX_BYTES)
        }
        val layersThatFit = (gpu / canvas.layerBytesWorstCase).toInt() - STROKE_BUFFER_RESERVE_LAYERS
        val maxLayers = layersThatFit.coerceIn(MIN_LAYERS, MAX_LAYERS)
        val slices = if (device.glMaxArrayLayers > 0) minOf(device.glMaxArrayLayers, 256) else 256
        val arrays = maxOf(1, (gpu / (slices.toLong() * TILE_BYTES)).toInt())
        // maxCanvasEdge: bounded by memory and by the v1 ceiling, never by glMaxTextureSize
        // (tiles are 256). Largest multiple of TILE_SIZE whose square, fully painted, fits
        // MIN_USEFUL_LAYERS + the stroke-buffer reserve in the tile budget.
        val perLayerLimit = gpu / (MIN_USEFUL_LAYERS + STROKE_BUFFER_RESERVE_LAYERS)
        val transientImageBytes = minOf(canvas.pixelBytes, perLayerLimit)
        var maxCanvasEdge = TILE_SIZE
        while (maxCanvasEdge + TILE_SIZE <= MAX_CANVAS_EDGE_V1 &&
               CanvasSize(maxCanvasEdge + TILE_SIZE, maxCanvasEdge + TILE_SIZE).layerBytesWorstCase <= perLayerLimit) {
            maxCanvasEdge += TILE_SIZE
        }
        // History and thumbnails: the step function of §2.6.
        val large = device.totalMemBytes >= LARGE_DEVICE_TOTAL_MEM && !device.isLowRamDevice
        val historySteps = if (large) HISTORY_STEPS_LARGE else HISTORY_STEPS_SMALL
        val historyBytes = if (large) HISTORY_BYTES_LARGE else HISTORY_BYTES_SMALL
        val thumbBytes = (when {
            device.isLowRamDevice -> THUMB_MIB_LOW_RAM
            large -> THUMB_MIB_LARGE
            else -> THUMB_MIB_SMALL
        }).toLong() shl 20
        return Result(
            gpu, maxLayers, maxCanvasEdge, historySteps, historyBytes,
            thumbBytes, transientImageBytes, slices, arrays,
        )
    }
}
```

Worked values the tests pin (so a constant change is a visible diff):

| `totalMem` | canvas | `gpuTileBudgetBytes` | `maxLayers` | `maxCanvasEdge` | history | thumbs | `poolArraySlices × poolArrayCount` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 8 GiB | 4096² | 1 GiB | 15 | 4096 (v1 ceiling) | 200 / 256 MiB | 24 MiB | 256 × 16 |
| 8 GiB | 2048² | 1 GiB | 16 | 4096 | 200 / 256 MiB | 24 MiB | 256 × 16 |
| 4 GiB | 4096² | 512 MiB | 7 | 4096 (5 × 64 MiB = 320 MiB ≤ 512 MiB) | 100 / 128 MiB | 12 MiB | 256 × 8 |
| 4 GiB, `isLowRamDevice` | 4096² | 256 MiB | 3 | 3584 (5 × 49 MiB = 245 MiB ≤ 256 MiB; 3840² would need 5 × 56.3 MiB) | 100 / 128 MiB | 8 MiB | 256 × 4 |
| 12 GiB | 4096² | 1.5 GiB | 16 | 4096 (v1 ceiling) | 200 / 256 MiB | 24 MiB | 256 × 24 |

Whether a real Tab S6 Lite reports `isLowRamDevice` is "to verify" on the
device; the class-B manual run (§8) decides whether 512 MiB is survivable
there or `GPU_TILE_FRACTION` needs a 4 GB step. Either way the constant
changes, not the interface.

`TilePool` allocates its texture arrays lazily, one at a time, up to
`poolArrayCount`; a slice is 256 KiB, an array of 256 slices is 64 MiB.
Allocating lazily means an empty 4096² painting costs one array, not the
whole budget, and that a budget the device cannot actually honour fails at
the *N*th array (caught, reported as "layer limit reached early", the
cap lowered for the session) instead of at startup.

`CanvasPresets.forDevice(result)` returns the presets the New Canvas
dialog offers: `Phone sketch 1080×1920`, `Square 2048²`, `Tablet 2560×1600
/ 1600×2560`, `Large 4096²`, plus `Custom` bounded by `maxCanvasEdge`,
each annotated with `MemoryBudget.compute(device, size).maxLayers`.

## 5. Profiling: what is measured and how

### 5.1 Perfetto and trace sections

`androidx.tracing` (`Trace.beginSection`/`endSection`; the artifact is in
`libs.versions.toml`) wraps every stage in §2.1 and §2.5 with fixed
section names so traces are comparable across builds:

| Section | Thread | Wraps |
| --- | --- | --- |
| `bd.input.batch` | main | one `onTouchEvent` including historical samples |
| `bd.input.dabs` | main | `Stabilizer` + `DabGenerator` for the batch |
| `bd.gl.stamp` | GL | `DabPass` for one ring slot |
| `bd.gl.frontComposite` | GL | the three-pass dirty-rect composite into the front layer |
| `bd.gl.commit` | GL | merge + full composite on pen-up |
| `bd.gl.readback.issue` / `bd.gl.readback.map` | GL | PBO issue / map+copy |
| `bd.gl.sandwich` | GL | sandwich rebuild |
| `bd.gl.upload` | GL | tile upload batch during reopen |
| `bd.io.flush` | IO | `TileStore` deflate + write of one batch |
| `bd.io.journal` | IO | `HistoryStore` entry write |
| `bd.fill` | Default | `FloodFill` run |
| `bd.reopen` | Default | whole reopen, with child sections per layer |

Sections are compiled in for debug builds and cheap enough to leave in
release (`Trace.isEnabled()` gate). Recording: `scripts/perf-trace.sh`
(house style: header doubles as `--help`) runs `adb shell perfetto` with a
config that enables the `gfx`, `input`, `view`, `sched` atrace categories
plus our app's sections, for N seconds, and pulls the trace into
`dist/traces/`. In the Perfetto UI the **InputDispatcher** track shows
when the event left the system for our process; the gap from there to the
end of `bd.gl.frontComposite` on the same stroke is the app-side latency
of §2.1; the following `SurfaceFlinger` present of the front-buffered
layer closes the measurement. GPU counters (Mali or Adreno, where the
device exposes them via Perfetto's GPU data source) are enabled in the
same config to see whether `bd.gl.stamp` is fill-bound.

### 5.2 Benchmark harness

**JVM microbenchmarks** (`app/src/jmh` is *not* used — no extra plugin;
instead a plain JUnit test class tagged `@Category(Benchmark::class)` and
excluded from the default suite) time, with warm-up, over fixed fixtures:

| Benchmark | Fixture | Reports |
| --- | --- | --- |
| `DabGeneratorBench` | a recorded 10 s ink-pen stroke (`StrokeInput` file, `assets-test/strokes/hatching.bin`) at three brush sizes | ns per sample, dabs per sample |
| `StabilizerBench` | same file | ns per sample |
| `FloodFillBench` | 4096² line-art fixture, 4 seed points | ms per fill, ms with `expand = 2` |
| `CompositeBench` (CPU reference) | 8 layers × 4 tiles, every blend mode | ms per tile — a sanity ceiling for the shader, not a target |
| `HistoryEncodeBench` | 64 tiles of noise / of flat color | ms per entry, compressed bytes |

Run with `./gradlew benchDebugUnitTest` (a task added in `app/build.gradle.kts`
that runs only the tagged category) and printed as a table; CI does not
run it (JVM timing on shared runners is noise).

**On-device stroke replay** (debug builds only, Settings → Developer →
*Replay stroke*): replays a recorded `StrokeInput` file through the *same*
`CanvasTouchHandler` → ring buffer → GL path at the recorded timestamps
(so the batch shapes are realistic) and reports ms/dab and ms/frame for
stamp and front composite, plus the max, as a toast and to logcat under
tag `bd.perf`. The recording itself comes from Settings → Developer →
*Record next stroke*, which dumps the SoA arrays to `filesDir/perf/`.
This is the single most useful tool for a brush-cost regression: same
input, same device, before/after numbers.

### 5.3 Debug overlay

A Compose overlay (debug builds, toggled in Developer settings) reading a
`PerfStats` object the GL thread updates without allocation (plain
`@Volatile` fields, sampled by the overlay at 4 Hz):

| Field | Meaning |
| --- | --- |
| `frontMs` / `frontMsMax` | last / max `bd.gl.frontComposite` + `bd.gl.stamp` |
| `commitMs` | last commit |
| `dabsPerFrame` | dabs stamped in the last front-layer render |
| `inputHz` | samples per second over the last second |
| `path` | `FRONT` or `FALLBACK` (§7) |
| `tilesResident` / `tilesBudget` | slices in use / slices the budget allows |
| `tilesPending` | reopen uploads outstanding |
| `mirrorBytes` | CPU mirror size |
| `journalSteps` / `journalBytes` | `HistoryStore` state |
| `readbackStalls` | forced PBO maps since open |
| `gcCount` | `Debug.getRuntimeStat("art.gc.gc-count")` delta during the last stroke — must read 0 |
| `thermal` | `PowerManager.getCurrentThermalStatus()` ("to verify" the exact constants; displayed as an int) |

## 6. Regression gates

What CI enforces (`ci.yml`, JVM-only per PLAN.md decision 7):

1. **Allocation-free tests.** `engine/core` has an `AllocationCounter`
   helper: it reads `ThreadMXBean.getThreadAllocatedBytes` (available on
   the HotSpot JVM that runs `testDebugUnitTest`; "to verify" on the CI
   runner's JDK 17, otherwise the helper degrades to a no-op that *logs*
   rather than fails) before and after the code under test, after a
   warm-up run. `DabGeneratorAllocationTest`, `StabilizerAllocationTest`,
   `DabRingTest` assert **0 bytes** for a 1000-sample batch. This pins the
   pure-JVM half of the touch path. The Android half
   (`CanvasTouchHandler`'s `MotionEvent` access) cannot run on the JVM; it
   is covered by the `gcCount` overlay field and the manual matrix.
2. **Budget tests.** `MemoryBudgetTest` pins the §4 table; changing a
   constant changes a test expectation in the same PR, which makes the
   trade-off visible in review.
3. **Shader contract tests** (PLAN.md §7) fail when a pass gains a uniform
   the Kotlin side does not bind — which is also how an accidental extra
   per-dab uniform upload gets noticed.
4. **Grep gate.** A CI step runs `scripts/check-touch-path.sh`: greps the
   files listed in `scripts/touch-path-files.txt` (`CanvasTouchHandler`,
   `Stabilizer`, `DabGenerator`, `DabRing`, `DabPass`) for `\bPair\(`,
   `listOf(`, `\.map \{`, `\.forEach`, `lazy`, `String` templates in
   non-comment lines and fails with the offending line. Crude, cheap, and
   it catches the common ways the rule erodes.
5. **Documented rule** in AGENTS.md: "no allocation on the touch path;
   see docs/plan/10-performance.md §2.4; the gate is
   `scripts/check-touch-path.sh`."

Device benchmarks are *not* CI gates; they are a manual checklist run
before a release tag (§8 in `docs/plan/12-roadmap.md` references this
section for step 2, 6 and 10 acceptance).

## 7. Known risks and prepared mitigations

| Risk | Symptom | Mitigation (designed now, so it is a switch, not a rewrite) |
| --- | --- | --- |
| Front-buffer usage unsupported on a device (graphics-core falls back internally, or the front layer visibly tears/flickers) | latency measurably ≥ 2 frames; `path = FALLBACK` | `CanvasRenderer` has a `FrontPath` enum: `FRONT_BUFFERED` (default) and `MULTI_ONLY`. In `MULTI_ONLY`, each input batch calls `renderFrontBufferedLayer` still, but `onDrawFrontBufferedLayer` draws nothing and the same dirty-rect composite is done in the multi-buffered callback via `commit()` per batch. The switch is automatic (a per-device denylist in Prefs populated when the first stroke shows the fallback signature — "to verify" how to detect it: graphics-core's `isValid()`/log output) and manual (Settings → Developer). |
| Mali texture-array slice limits (`GL_MAX_ARRAY_TEXTURE_LAYERS` at the spec minimum 256) | fewer tiles per array than assumed | already the design: `poolArraySlices` is `min(queried, 256)` and `TilePool` spans several arrays; a tile's address is `(arrayIndex, slice)`; the composite binds the array a tile lives in per draw (tiles of one layer are allocated array-contiguously where possible to keep binds low). |
| PBO readback stalls on some drivers (`glMapBufferRange` blocks even after ageing) | `readbackStalls` climbs; hitch at stroke end | `Readback` measures the map time; if the moving average exceeds `READBACK_STALL_MS` (2 ms) for 8 maps, it switches to **sync small-rect readback**: `glReadPixels` directly into a client `ByteBuffer`, but only for the dirty tiles, issued in the *next* frame after commit so the merge and composite are presented first. Logged, shown in the overlay. |
| EGL context loss (surface destroyed, GPU reset) | textures gone | the document is on disk and in the CPU mirror; `CanvasRenderer.onContextLost` marks all tiles non-resident, and the reopen path (§2.7) re-uploads from `TileStore` (dirty tiles still in the mirror first, then disk). The stroke in flight is cancelled (same code path as palm rejection); its journal entry was never written, so undo stays consistent. |
| 4 GB devices, low-memory kills | `onTrimMemory` / process death mid-session | budgets in §2.6; the thumbnail cache and the sandwich cache are dropped (rebuilt on next stroke) and the CPU mirror flushed on any of: `onTrimMemory(TRIM_MEMORY_UI_HIDDEN / TRIM_MEMORY_BACKGROUND)`, `ComponentCallbacks2.onLowMemory()`, and `ActivityManager.getMemoryInfo().lowMemory` polled at stroke end (cheap, off the touch path). `TRIM_MEMORY_RUNNING_LOW` is *not* relied on: from API 34 the `TRIM_MEMORY_RUNNING_*` / `MODERATE` / `COMPLETE` levels are deprecated and, for apps targeting 34+, reportedly only `UI_HIDDEN` and `BACKGROUND` are delivered ("to verify" against the API 34 deprecation note; if it still fires on a device it is handled as a bonus); process death is survivable by design (PLAN.md §3.2) — the recovery test in step 3 is run on class B. |
| Thermal throttling in a long session | frame time creeps up, `thermal` rises | render-on-demand already keeps the GPU idle between strokes; when `PowerManager` reports a severe status, the predicted tail length is halved (fewer wasted front-layer pixels) and the gallery sync debounce doubles. No visible feature is disabled. |
| A brush preset that is too expensive (tiny spacing × huge radius) | `dabsPerFrame` in the thousands, stamp cost blows the budget | `DabGenerator` clamps effective spacing so that `dabs per canvas pixel of travel` ≤ `MAX_DABS_PER_PX` (a preset-independent constant, "to tune" on device); the brush settings sheet shows a cost hint derived from the same formula. |
| Foldable / rotation surface resize mid-stroke | viewport-sized targets are wrong size | Input rebases the view exactly once, the renderer reallocates `Accum`/`Scratch` without rebasing again, and the attachment recovery rebuilds the canvas-space live preview (`03-canvas-engine.md` §8.6). Only `surfaceDestroyed` *cancels* when no replacement target arrives (`02-architecture.md` §8.2). Checklist row U1 in `11-testing.md` §8. |

## 8. Manual device matrix

Run before tagging a release and after any PR that touches `engine/gl`,
`input/`, or `MemoryBudget`. Results go into the release PR description.

| Device (class) | What is checked | Pass criterion |
| --- | --- | --- |
| Galaxy Tab S9 or S10 (A) | stroke replay ms/dab; Perfetto latency; 8 layers 4096² composite; reopen time; fill time; `gcCount` | every §2 class-A number |
| Galaxy Tab S6 Lite (B) | same, plus: layer cap displayed and honoured; process-kill recovery; 30 min session thermal | class-B numbers; no OOM; cap shown before it bites |
| Galaxy S-series Ultra with S Pen (C) | latency; S Pen button + eraser end; compact layout | class-A latency; tools map correctly |
| Pixel phone (D) | finger drawing, palm rejection off-path, gestures, no stylus code paths crash | draws; no stylus-only UI leaks |
| Galaxy Z Fold (E) | fold/unfold mid-stroke and mid-reopen; window size class transitions | no crash, no lost stroke after the cancel, layout reflows |
| Any device in multi-window / DeX (nicety) | resize, hover with mouse | usable; not a target |

## 9. What is deliberately not optimised in v1

- **Tile eviction/residency** (post-v1, PLAN.md §10): the layer cap is
  the honest substitute. Everything in §4 is shaped so eviction can lift
  `maxLayers` later without changing the budget's interface.
- **Half-float render targets** for the composite: RGBA8 is the safe
  baseline on ES 3.0 (research facts); banding in soft gradients is
  accepted for v1, dithering in the composite shader is the cheap fix if
  it shows.
- **Multi-threaded GL** (shared contexts for uploads): reopen streams
  uploads in per-frame batches instead; simpler and within target.
- **Native code**: none. If a CPU hot spot (fill, inflate) misses its
  target on class B by a small factor, the answer is chunking and
  cancellation, not JNI.
