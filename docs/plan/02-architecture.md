# Architecture — packages, threads, state, data flow

**What this document covers.** It expands PLAN.md §3 into something an
agent can build from: every file in the package tree with its one-line
responsibility, the three threads and what crosses between them, the
state model (who owns what, what survives process death), the Hilt graph,
navigation, lifecycle handling, the error model, and the end-to-end data
flow from pen-down to the gallery. Names are PLAN.md's; helpers that PLAN.md
does not list are marked *(helper)*. The engine internals themselves are
docs/plan/03-canvas-engine.md; the on-disk format is
docs/plan/06-document-and-persistence.md; this document is about how the
pieces are wired, not what each piece computes.

## 1. Shape of the app

Single Gradle module `:app`, Kotlin packages first (family convention: no
feature modules until a real boundary demands one). One `Activity`, one
`NavHost`, two screens. Four layers, dependency arrows pointing down only:

```
ui/          Compose + ViewModels          (android.*, Compose, Hilt)
tools/ input/ data/ engine/gl engine/mixbox (android.*, GL, coroutines)
engine/core  the document model + math     (kotlin.* and java.util only)
```

The rule that makes the JVM-only test suite honest (PLAN.md decision 7):
**anything that decides something lives in `engine/core`**, and everything
above it is a thin adapter that translates platform events into core calls
and core results into platform effects. If a bug can be reproduced by a
plain JUnit test, it is in core; if it cannot, the code is in the wrong
package.

## 2. Package layout with responsibilities

Names are exact (PLAN.md §3). One class per file unless the table says
otherwise; small sealed hierarchies share a file with their root.

### 2.1 Root

| File | Responsibility |
| --- | --- |
| `BangniApp.kt` | `@HiltAndroidApp` application. Installs `StrictMode` in debug, nothing else. No global state. |
| `MainActivity.kt` | The single `@AndroidEntryPoint ComponentActivity`. Sets `enableEdgeToEdge()`, hosts `BangniNavHost()`, forwards `onTrimMemory` to `TilePool` via the engine session. Declares `configChanges` (§8.3) so rotation never destroys the GL context, and `launchMode="singleTask"` with `resizeableActivity="true"`: multi-window and DeX resize the one instance (§8.4), but the app is never open twice (two Canvases of one project folder would race the single-writer flusher; the Studio's duplicate/delete rely on it — `docs/plan/06-document-and-persistence.md` §8). |

### 2.2 `engine/core` — pure JVM

| Class | Responsibility |
| --- | --- |
| `Document` | Immutable value: `id`, `width`, `height`, `paperColor`, `LayerStack`, `historyCursor`, `galleryUri: String?`, timestamps. Serializable to `project.json`. |
| `LayerStack`, `Layer`, `BlendMode` | Ordered list of `Layer` (id, name, opacity, blend, visible, alphaLock, locked) with pure operations (`add`, `delete`, `duplicate`, `move`, `mergeDown`, `flatten`, `clear`, `rename`, `set…`, `select`) each returning a `StackResult` — the new stack, the `PixelOp` for the GL thread and the `HistoryEntry` for the journal, or a `Refused(reason)`. Detail: docs/plan/05-layers.md §3. |
| `TileGrid`, `TileKey` | Canvas rect ↔ set of 256×256 tile keys; `TileKey(tx, ty)` is an inline value class over a packed `Int` (docs/plan/03-canvas-engine.md §1) so hot paths never box. |
| `ViewTransform`, `FitTransform` | Meltorama's similarity transform, ported verbatim with its tests (`gesture`, `invert`, `invertVector`, `rebase`, `lerp`). `FitTransform` maps canvas pixels to the fitted view box for a given viewport size. |
| `GestureArbiter` | Pointer timeline → decision: draw / navigate / two-finger-tap undo / three-finger-tap redo / long-press pick. Pure state machine fed by `StrokeInput`-level events; docs/plan/07-input-and-stylus.md. |
| `StrokeInput` | One input sample in **canvas space**: x, y, pressure, tilt, orientation, timeNs, `source` (STYLUS / ERASER_END / FINGER / MOUSE), `predicted: Boolean` — the format is docs/plan/07-input-and-stylus.md §2. A mutable, pooled object inside a preallocated `StrokeInputBatch`; never allocated per sample. |
| `Stabilizer` | Position smoothing (pull-string / EMA per preset strength). Stateful per stroke, `reset()` on pen-down. |
| `PressureCurve` | The device-level pressure map (docs/plan/07-input-and-stylus.md §2: per-device calibration composed with the Softer / Linear / Harder preference), applied by the touch handler before a sample reaches the generator. Per-preset size/opacity/flow curves are docs/plan/04-tools.md's `Curve`. |
| `DabGenerator`, `Dab` | Turns consecutive `StrokeInput` into `Dab`s at `spacing × radius` intervals with size/flow/angle dynamics (`begin` / `advance` / `end`, docs/plan/04-tools.md §3). Writes into a caller-supplied `DabBatch` (§3.2); allocates nothing after construction. |
| `BrushPreset`, `ToolKind` | The JSON-serializable brush description (PLAN.md §6 parameter list) and the sealed interface `Brush(preset)`, `Smudge`, `Blur`, `Fill`, `Eyedropper` (docs/plan/04-tools.md §1 — erasers are `Brush` presets with `eraseMode`, not a kind). |
| `ColorMixer`, `RgbMixer` | `interface ColorMixer { fun mix(a: Int, b: Int, t: Float): Int; val isPigment: Boolean }` plus `LatentColorMixer` for weighted N-way mixes (docs/plan/09-color-and-mixing.md §4). `RgbMixer` is the license-free fallback (decision 5). |
| `Composite` | CPU reference compositor: every `BlendMode` over premultiplied RGBA8, the pinned semantics the shaders must match (docs/plan/11-testing.md). Also used to flatten for thumbnails/gallery when the GPU is unavailable. |
| `FloodFill` | Scanline flood fill over a CPU tile window with tolerance / contiguous / expand / AA; returns dirty tile keys. docs/plan/04-tools.md. |
| `HistoryJournal`, `HistoryEntry` | The undo model: a cursor over a bounded list of entries; `push` (truncates redo, appends, prunes by `Limits(maxEntries, maxBytes)`), `undo`, `redo`, `canUndo`, `canRedo` — the API is docs/plan/06-document-and-persistence.md §5.1. Entries reference tile payloads by key — bytes live in `HistoryStore`. |
| `AutosavePolicy` | Meltorama's two-clock policy (`QUIET_MS`, `ONE_CHECKPOINT_MS`, `delayMs(dirtyForMs)`), copied not re-derived. |
| `CanvasPresets`, `MemoryBudget` | The fixed preset list annotated by the budget (`CanvasPresets.forDevice(result)`); `MemoryBudget.compute(DeviceMemory, CanvasSize) → Result(maxLayers, maxCanvasEdge, …)` — pure arithmetic over numbers the caller reads from `ActivityManager`; constants and signature in docs/plan/10-performance.md §4. |
| `Clock`, `Random` *(helper)* | `fun interface Clock { fun nowNanos(): Long }` and `fun interface RandomSource { fun nextFloat(): Float }`. Injected wherever core needs time (stabilizer velocity, autosave) or randomness (jitter, grain phase), so tests are deterministic. |
| `DabBatch`, `DabRing` *(helper)* | Preallocated SoA arrays for a batch of dabs and the SPSC ring of batches that crosses to the GL thread (§3.2). |

### 2.3 `engine/gl` — the GPU

| Class | Responsibility |
| --- | --- |
| `CanvasRenderer` | Implements `GLFrontBufferedRenderer.Callback<DabBatch>`. Owns every other object in this package, all created and torn down on the GL thread. Translates `onDrawFrontBufferedLayer` / `onDrawMultiDoubleBufferedLayer` into pass sequences. |
| `TilePool` | The `GL_TEXTURE_2D_ARRAY` atlas: slice allocation/free list, `glTexStorage3D` sized from the queried limits, upload from CPU tiles, `trim()` on memory pressure. |
| `LayerTextures` | Per-layer `(tx, ty) → slice` index (a dense `IntArray(tilesX × tilesY)` of packed `SliceHandle`s, docs/plan/03-canvas-engine.md §2.2, no boxing) and the layer's dirty-tile set since last readback. |
| `StrokeBuffer`, `TailBuffer` | The per-stroke RGBA8 accumulation target (capped at the stroke opacity on merge), and the per-frame predicted-tail scratch (docs/plan/03-canvas-engine.md §9). |
| `DabPass`, `SmudgePass`, `CompositePass` | One pass each: stamp dabs into the stroke buffer; ping-pong RMW for smudge/blur/mixing; composite N layers of one tile into a target. |
| `SandwichCache` | The two cached composites (below / above the active layer) per visible tile; invalidated by layer edits and by the active-layer change. |
| `Readback` | PBO pool: enqueue `glReadPixels` per dirty tile, poll fences, hand mapped bytes to the IO side as immutable `CpuTile`s (§3.3). |
| `Shaders` | GLSL sources as Kotlin string constants (one `object` per program) with the uniform names the contract test parses. |
| `GlProgram`, `GlFbo`, `GlState` *(helpers)* | Thin RAII wrappers; error checking only in debug. |

### 2.4 `engine/mixbox`

| Class | Responsibility |
| --- | --- |
| `MixboxMixer` | `ColorMixer` over `com.scrtwpns.Mixbox` (`lerp`, `rgbToLatent`, `latentToRgb`). The *only* file that imports the jar. |
| `MixboxLut` *(helper)* | Loads the vendored 512×512 LUT from assets **without premultiplication** and uploads it as the `mixbox_lut` sampler (NEAREST/LINEAR, no mipmaps, no sRGB decode). Detail: docs/plan/09-color-and-mixing.md. |

### 2.5 `tools/`

| Class | Responsibility |
| --- | --- |
| `Tool` | `interface Tool { val kind: ToolKind; fun onStrokeBegin/onStrokeSample/onStrokeEnd/onStrokeCancel(…, ctx: StrokeContext) }` (docs/plan/04-tools.md §1) — a tool is a set of engine operations plus its parameters, nothing more. |
| `BrushTool`, `EraserTool` | `BrushPreset` → `DabGenerator` config; `EraserTool` is `BrushTool` pinned to `eraseMode` presets (the ERASE merge mode), not a separate kind. |
| `SmudgeTool`, `BlurTool` | Route dabs to `SmudgePass` with strength / radius; smudge carries the pickup color state across the stroke. |
| `FillTool` | On tap: request the CPU tile window from the engine, run `FloodFill`, upload the result, journal it. |
| `EyedropperTool` | Sample composite or current layer at a point via a 1×1 readback; returns the color to the ViewModel. |

### 2.6 `input/`

| Class | Responsibility |
| --- | --- |
| `CanvasTouchHandler` | `View.OnTouchListener` + hover listener on the `SurfaceView`. Consumes `MotionEvent` (history + predicted) into `StrokeInput` samples and feeds `GestureArbiter`; the only code that touches `MotionEvent`. Zero allocation in `onTouch`. |
| `StylusState` | Latest hover position/pressure/tilt, button state, tool type; a mutable struct read by `HoverCursor` and the ViewModel on a 60 Hz tick, not per event. |
| `PalmRejection` | Pointer policy: stylus down ⇒ touch ignored; `ACTION_CANCEL` / `FLAG_CANCELED` ⇒ rollback. Pure and tested; `CanvasTouchHandler` obeys it. |
| `Predictor` | Wrapper over `MotionEventPredictor` (`record`, `predict`) so core and tests never see the androidx type. |

### 2.7 `data/`

| Class | Responsibility |
| --- | --- |
| `ProjectStore` | `filesDir/projects/<uuid>/` lifecycle: list (newest first), create, load `project.json`, save (tmp+rename, `project.json` last), delete, duplicate, thumbnail. |
| `TileStore` | Deflated tile files under `layers/<layerId>/`; `write(CpuTile)`, `read(layerId, key)`, `delete`; and the **CPU mirror** (PLAN.md §3.1) — `CpuTile`s read back but not yet flushed, keyed by `(layerId, key)`, dropped once written (docs/plan/06-document-and-persistence.md §5.5, §6.3). `uploadTiles` and the rebuild in §8.2 consult the mirror before reading disk. |
| `HistoryStore` | `history/<seq>.entry` bytes for `HistoryJournal` entries; prune deletes files. |
| `GalleryExporter` | One MediaStore item per painting in `Pictures/帮你Draw/`, rewritten in place via `openOutputStream(uri, "wt")`; re-inserts when ownership is lost. |
| `ShareCache` | `cacheDir/share/` PNG/JPEG for the share sheet via `FileProvider`, swept on Studio open. |
| `BrushPresetStore` | Built-in presets from `assets/brushes/*.json` + user edits in `filesDir/brushes/`; kotlinx-serialization. |
| `Prefs` | DataStore Preferences: handedness, stylus-only, `penButtonAction`, `eraserEndPreset`, pressure curve / calibration, haptics, gallery sync, `mixer` choice (pigment / RGB), journal limits, palettes' active id, `nextSketchNumber`. Exposed as a `Flow<Prefs.Snapshot>`. |

### 2.8 `ui/`

| File | Responsibility |
| --- | --- |
| `ui/home/StudioScreen.kt`, `StudioViewModel.kt` | The shelf. `StudioUiState(paintings, storage, dialog, error)`. |
| `ui/canvas/CanvasScreen.kt`, `CanvasViewModel.kt` | The editor scaffold and its single writer of `CanvasUiState`. |
| `ui/canvas/CanvasSurface.kt` | `AndroidView { SurfaceView }`; creates the `EngineSession`, wires `CanvasTouchHandler`, reports viewport size changes. |
| `ui/canvas/ToolRail.kt`, `TopStrip.kt`, `LayerPanel.kt`, `ColorPanel.kt`, `BrushSettingsSheet.kt`, `NewCanvasDialog.kt`, `HoverCursor.kt` | Stateless composables taking slices of `CanvasUiState` and emitting callbacks. |
| `ui/navigation/BangniNavHost.kt` | Routes (§7). |
| `ui/components/`, `ui/theme/` | Chunky slider, haptic tap modifier, the studio palette. |
| `ui/canvas/EngineSession.kt` *(helper)* | The per-canvas façade the ViewModel and tools talk to (§4.3). Lives in `ui/canvas` because its lifetime is the composable's, but it is not a composable. |

## 3. Threading model

Three threads, each with one owner and one job. Nothing is shared by
locking; everything crosses by handover of immutable or single-writer data.

| Thread | Owner | Does | Never does |
| --- | --- | --- | --- |
| **Main** | Android | Compose, `MotionEvent` → `StrokeInput` → `DabBatch`, ViewModel state updates, journal bookkeeping | touch GL, touch disk, allocate inside `onTouch` |
| **GL** | `GLFrontBufferedRenderer` | every `gl*` call: stamping, compositing, readback, texture uploads | run Kotlin decision logic that could live in core; block on IO |
| **IO** (`Dispatchers.IO`) | coroutines in `viewModelScope` / `ProjectStore` | deflate + write tiles, journal entries, `project.json`, gallery PNG, thumbnails | touch GL objects; touch anything mutable owned by another thread |

### 3.1 Why `GLFrontBufferedRenderer` owns the GL thread

graphics-core creates and drives its own render thread with the EGL
context, two `SurfaceControl`s and the buffer allocation. We do not add a
`GLSurfaceView` beside it. Any GL work that is not a frame goes through
`renderer.execute { … }` (tile uploads, pool trims, readback polls, uniform
updates); frames go through `renderFrontBufferedLayer(batch)` while the pen
is down and `commit()` on pen-up. Redraws with no stroke in flight — pan/
zoom/rotate, layer panel edits, undo/redo uploads, the view rebase after a
resize, the first frame after open — go through
`renderer.renderMultiBufferedLayer(emptyList())` — to verify that the pinned
graphics-core exposes it; the fallback is an empty-param `commit()`
(docs/plan/03-canvas-engine.md §8.6) — after the view/fit uniforms
have been set via `execute {}`; `EngineSession.redraw()` wraps this and is
called by `setView`, `applyPixelOp`, `uploadTiles`, `setActiveLayer` and
after resize. During a two-finger gesture the redraw is throttled to one per
`Choreographer` frame. graphics-core calls used, in full:
`renderFrontBufferedLayer`, `commit`, `cancel`, `execute`,
`release(cancelPending, onReleaseComplete)`, plus `renderMultiBufferedLayer`
/ `clear` if present. The rule
"every `gl*` call is on this thread" is what lets `engine/gl` be lock-free.

### 3.2 Main → GL: the dab ring

```kotlin
class DabBatch(capacity: Int = DAB_BATCH_CAPACITY) {   // SoA, preallocated; the 8 per-dab fields = DAB_STRIDE (10 §4)
    val x = FloatArray(capacity); val y = FloatArray(capacity)
    val radius = FloatArray(capacity); val flow = FloatArray(capacity)
    val hardness = FloatArray(capacity); val angle = FloatArray(capacity)
    val aspect = FloatArray(capacity); val seed = FloatArray(capacity)   // color and opacity are per stroke, not per dab
    var count = 0
    var strokeId = 0L; var predictedFrom = -1   // index of the first predicted dab
    var dirtyLeft = 0; var dirtyTop = 0; var dirtyRight = 0; var dirtyBottom = 0
}

class DabRing(slots: Int = DAB_RING_SLOTS /* 8 */, capacity: Int = DAB_BATCH_CAPACITY /* 1024 */) {
    private val batches = Array(slots) { DabBatch(capacity) }
    // single producer (main), single consumer (GL): two atomic counters
    fun acquire(): DabBatch?      // main: next free slot or null → backpressure (§3.5)
    fun publish(b: DabBatch)      // main: makes it visible; then renderFrontBufferedLayer(b)
    fun release(b: DabBatch)      // GL: after the pass has consumed it
}
```

`CanvasTouchHandler` fills one batch per `MotionEvent` (history + current
+ predicted samples), publishes it, and calls
`renderer.renderFrontBufferedLayer(batch)`. graphics-core hands the same
object to `onDrawFrontBufferedLayer(…, param = batch)`; the GL thread reads
it and releases the slot. On `commit()` graphics-core replays *all* params
since the last commit into `onDrawMultiDoubleBufferedLayer`, and the
library holds the `T` references until that replay has actually run on the
GL thread (it is asynchronous; `commit()` returning means nothing). So slots
are released **on the GL thread, at the end of
`onDrawMultiDoubleBufferedLayer(params)`** (iterate `params`, `ring.release`
each), never from main after `commit()`, and the ring must not reuse a slot
until then because identity matters. `cancelStroke()` drops the active
segment without ever replaying it, so it releases every batch of the current
`strokeId` via `execute {}` right after `renderer.cancel()` — otherwise each
palm rejection leaks a stroke's worth of slots. The ring is
sized for a full stroke's worth of batches at 120 Hz input
(`DAB_RING_SLOTS × DAB_BATCH_CAPACITY`, docs/plan/10-performance.md §4); an
overflow goes into a second, allocating fallback that is logged in debug (it
should never fire; if it does, the slot count is wrong, not the design).

Predicted dabs are in the batch (from `predictedFrom` on) so the front
layer can draw them — `DabPass` stamps them into the per-frame `TailBuffer`,
never into the stroke buffer (PLAN.md §3.1; docs/plan/03-canvas-engine.md
§9); the multi-buffered pass ignores everything from `predictedFrom`, which
is how the tail is "removable" without a second data path.

### 3.3 GL → IO: readback handover

After each `commit()` (and on every tile the stroke dirtied), `Readback`
issues an async `glReadPixels` per tile into one of `READBACK_PBO_COUNT`
(2) PBOs holding `READBACK_PBO_TILES` (64) tiles each (docs/plan/03-canvas-engine.md
§10.1; constants in docs/plan/10-performance.md §4). While
`Readback` has in-flight PBOs, main posts a `Choreographer` frame callback
that calls `renderer.execute { readback.poll() }` — nothing else drives the
poll once the pen lifts and no frame arrives. The poll checks the fence;
when signalled it `glMapBufferRange`s, copies into a fresh `ByteArray` and
posts an immutable value:

```kotlin
class CpuTile(val layerId: LayerId, val key: TileKey, val revision: Long,
              val premulRgba: ByteArray /* 256*256*4, owned, never mutated */)
```

to a `Channel<CpuTile>(capacity = READBACK_PBO_COUNT × READBACK_PBO_TILES)`
consumed by an IO coroutine that swaps it into the `TileStore` mirror,
deflates and `TileStore.write`s it (the journal's "before" tiles are taken
from the mirror/disk *at commit*, before this swap —
docs/plan/06-document-and-persistence.md §5.5). The copy is deliberate: the
PBO goes back to the pool immediately, and the IO side never holds a GL
resource. The `ByteArray`s come from a recycled `TileBufferPool`
(docs/plan/03-canvas-engine.md §10.1). Ordering per tile is by `revision`
(monotonic per commit); a stale readback that arrives after a newer one for
the same key is dropped by the writer.

The GL thread never blocks on the channel: it `trySend`s, and while that
fails (IO is slow, or stuck in a storage-full retry loop) it stops issuing
new readbacks and retries on the next poll tick, so the dirty set simply
waits on the GPU. Worst-case memory held by the handover is therefore
bounded by the two PBO chunks plus the pool, however large the fill or the
canvas.

`EngineSession.flushReadbacks()` runs a blocking
`execute { readback.finishAll() }` (`glClientWaitSync` + map for every
pending PBO, then a blocking `send`) so that committed tiles are never only
a GPU texture about to be freed. It is called from `surfaceDestroyed`,
`detachSession()` and `onStop()`, always before any `release()`.

### 3.4 IO → main

Only through `StateFlow` updates made by the ViewModel after `await`ing a
result (`withContext(Dispatchers.IO) { store.save(doc) }`). IO never calls
into UI or engine objects directly.

### 3.5 Ownership and immutability summary

| Data | Owner / writer | Readers | Crossing rule |
| --- | --- | --- | --- |
| `Document`, `LayerStack`, `HistoryJournal` (in-memory cursor) | `CanvasViewModel` (main) | Compose via `UiState`; IO via a snapshot passed as a parameter | immutable values, replaced not mutated |
| `StrokeInput` ring, `DabBatch` ring | main writes, GL reads | — | SPSC ring; a batch is owned by whoever last acquired/received it |
| GPU tiles, stroke buffer, sandwich, PBOs | GL thread | — | never leave the thread |
| `CpuTile` | created on GL, owned by IO after handover | ViewModel for `FloodFill`/eyedropper via a request/response `CompletableDeferred` | immutable after construction |
| `ViewTransform` | ViewModel (main) | GL via `setView` → a uniform update in `execute {}` followed by `redraw()` | value copied into the frame |
| `StylusState` | `CanvasTouchHandler` (main) | Compose `HoverCursor` on a frame tick | single-writer mutable struct read on the same thread |

Backpressure: if `DabRing.acquire()` returns null, the handler keeps the
samples in the `StrokeInput` ring and coalesces them into the next batch;
nothing is dropped, only delayed by a frame.

## 4. State model

### 4.1 One `UiState` per screen

```kotlin
data class CanvasUiState(
    val document: Document?,                 // null while loading
    val activeLayerId: LayerId?,
    val tool: ToolKind, val preset: BrushPreset, val color: Int,
    val size: Float, val opacity: Float,     // rail sliders (preset overrides)
    val view: ViewTransform, val viewIsIdentity: Boolean,
    val canUndo: Boolean, val canRedo: Boolean,
    val historyCost: HistoryCost,            // steps/bytes vs cap, for the UI readout
    val budget: MemoryBudget.Result,
    val openPanel: Panel?,                   // LAYERS | COLOR | BRUSH_SETTINGS | OVERFLOW, null = none (08 §4)
    val focus: Boolean,
    val saving: SaveStatus,                  // IDLE | SAVING | LEAVING
    @StringRes val error: Int? = null,
)
```

Exposed as `StateFlow<CanvasUiState>`; every change is `_state.update { it.copy(…) }`
on the main thread. Composables are pure functions of the state plus
callbacks. Compose never reads engine objects; the engine never reads
Compose state.

Deliberately *not* in `UiState`: pixels, tile sets, stroke-in-flight data,
the hover position. Those are engine or handler state that change at input
rate, and putting them through a `StateFlow` would recompose the chrome
hundreds of times a second.

### 4.2 The ViewModel is the single writer

`CanvasViewModel` receives every intent — from Compose (`onToolSelected`),
from the touch handler (`onStrokeFinished(strokeId, dirtyTiles)`), from
the engine (`onReadbackComplete`), from lifecycle (`onStop`) — and is the
only place that mutates `Document`, the journal cursor, or `UiState`. Tools
and the touch handler call the `EngineSession` directly for pixel work
(latency), but report *what happened* to the ViewModel afterwards, and the
ViewModel journals and marks dirty. This split is the one place the
architecture bends toward speed: pixels bypass the ViewModel; facts about
pixels do not.

### 4.3 What lives where

| Concern | Lives in | Why |
| --- | --- | --- |
| Layer stack, active layer, tool, color, view transform, panel, focus | `CanvasViewModel` | UI-visible, journalled or restorable |
| Pixels (GPU tiles, stroke buffer, sandwich) | `EngineSession` → `CanvasRenderer` | thread affinity; a cache of what is on disk |
| Stroke in flight (`Stabilizer`, `DabGenerator` state, predicted tail) | `CanvasTouchHandler` | lives and dies with a pointer; never observable |
| Undo entries' bytes | `HistoryStore` (disk) | the journal *is* the persistent undo |
| `projectId`, active layer id, tool kind, view transform, panel | `SavedStateHandle` | restores the open painting after process death |
| Preferences | `Prefs` (DataStore) | cross-session, cross-screen |

`EngineSession` *(helper)* is the per-canvas façade:

```kotlin
class EngineSession(surface: SurfaceView, doc: Document, tiles: TileStore,
                    pool: TilePool.Config, clock: Clock) {
    val renderer: GLFrontBufferedRenderer<DabBatch>
    fun setView(t: ViewTransform, fit: FitTransform)  // uniforms via execute {} + redraw()
    fun redraw()                                   // renderMultiBufferedLayer(emptyList())
    fun setActiveLayer(id: LayerId)
    fun applyPixelOp(op: PixelOp)                  // 05 §3.3: copy/merge/clear/delete/flatten/restore → sandwich invalidation
    fun beginStroke(tool: Tool, strokeId: Long)
    fun renderBatch(b: DabBatch)                   // main → renderFrontBufferedLayer
    fun commitStroke(): Deferred<StrokeResult>     // dirty tiles + readback of the merged ("after") tiles
    fun cancelStroke()                             // renderer.cancel() + drop the buffer
    fun readTiles(layerId: LayerId, keys: LongArray): Deferred<List<CpuTile>>
    fun uploadTiles(tiles: List<CpuTile>)          // fill, undo, redo, session rebuild
    fun flushReadbacks()                           // blocking; §3.3
    fun release()                                  // flushReadbacks() then renderer.release(cancelPending = true) { … }
}
```

It is created in `CanvasSurface`'s `AndroidView` factory (it needs the
`SurfaceView`) and handed to the ViewModel through `attachSession()`;
the ViewModel holds it in a `var session: EngineSession?` that is nulled on
`detachSession()`. The ViewModel outlives the session (rotation is not a
recreate, see §8.3, but multi-window can re-create the surface); the session
never outlives the ViewModel.

### 4.4 Process death

`SavedStateHandle` carries only ids and small values (`projectId`,
`activeLayerId`, `tool`, `view`, `panel`). Everything else is on disk by
construction: tiles flush after every stroke, the journal is on disk, and
`ON_STOP` checkpoints `project.json`. Restore = load the document from
`ProjectStore`, re-upload tiles lazily by viewport, restore the journal
cursor from `project.json`. The worst case after a hard kill mid-stroke is
losing the stroke in flight — PLAN.md's "nothing is ever lost" is about
committed work, and a stroke is committed on pen-up.

## 5. The pure-core rule

`engine/core` compiles against `kotlin.*` and `java.util.*` (for `Deflater`
in tests and `zip`) only. Enforced by a lint-adjacent unit test
(`PureCoreTest`, docs/plan/11-testing.md) that greps the source set for
`import android.`, `import androidx.`, `import com.scrtwpns.` and fails on
any hit — cheaper than a module split and just as binding.

Consequences that shape the code:

- **Colors are `Int` ARGB**, not `android.graphics.Color`. Blend math is on
  premultiplied bytes in `IntArray`/`ByteArray`.
- **Time and randomness are injected** (`Clock`, `RandomSource`, §2.2). No
  `System.nanoTime()` or `kotlin.random.Random.Default` inside core; tests
  pass a fixed clock and a seeded source, so a stabilizer test or a jitter
  test is exact.
- **No logging** in core; callers log. No `Log.d` sneaks in.
- **Geometry is in canvas pixels**, never view pixels or dp. The touch
  handler converts once via `ViewTransform.invert` and core never sees a
  screen.

The GL and Compose layers are thin by the same discipline. `CanvasRenderer`
decides nothing: which tiles are dirty, what the composite order is, what
the dab parameters are, all arrive computed. A composable decides nothing
either: whether the rail is a dock is `LayoutSpec.forWindow(widthClass,
heightDp, hand)` in `ui/canvas/LayoutSpec.kt` *(helper, pure;
docs/plan/08-ui-and-layout.md §1)*, whether undo is enabled is
`canUndo` in the state.

## 6. Hilt module layout

One `@InstallIn(SingletonComponent::class)` module, `di/AppModule.kt`
*(helper)*, plus constructor injection everywhere else.

| Binding | Scope | Why |
| --- | --- | --- |
| `ProjectStore`, `TileStore`, `HistoryStore` | `@Singleton` | one `filesDir`, shared by Studio (list/delete) and Canvas (load/save); hold no per-painting state |
| `GalleryExporter`, `ShareCache` | `@Singleton` | wrap `ContentResolver`/`cacheDir`; stateless |
| `BrushPresetStore`, `Prefs` | `@Singleton` | caches; flows shared across screens |
| `ColorMixer` | `@Singleton`: `MixboxBinding.create() ?: RgbMixer` (build-time presence, docs/plan/09-color-and-mixing.md §4); the runtime choice `Prefs.mixer` (pigment / RGB) selects between it and `RgbMixer` in the ViewModel and the shader variant | decision 5 — the swap is one binding |
| `Clock`, `RandomSource` | `@Singleton` | real implementations; tests construct core classes directly with fakes |
| `DeviceMemory` | `@Singleton` via `ActivityManager` (GL limits filled in on the first context) | read once at startup; docs/plan/10-performance.md §4 |
| `EngineSession`, `CanvasRenderer`, `TilePool` | **not injected** | per-canvas, needs the `SurfaceView`; constructed by `CanvasSurface` |
| `CanvasTouchHandler`, `Predictor` | not injected | per-view |
| `StudioViewModel`, `CanvasViewModel` | `@HiltViewModel` | standard |

Nothing is `@ActivityScoped`: with one activity that scope is just a
singleton that dies on config change, which is the wrong lifetime for
everything we have.

## 7. Navigation

`ui/navigation/BangniNavHost.kt`, Meltorama's `GooNavHost` pattern:

```kotlin
@Serializable object StudioRoute
@Serializable data class CanvasRoute(val projectId: String)

@Composable
fun BangniNavHost() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = StudioRoute) {
        composable<StudioRoute> {
            StudioScreen(onOpen = { id -> nav.navigate(CanvasRoute(id)) })
        }
        composable<CanvasRoute> {
            // navigateUp, not popBackStack (Meltorama): a second tap during the
            // pop transition must not pop the start destination too.
            CanvasScreen(onBack = { nav.navigateUp() })
        }
    }
}
```

`CanvasRoute` carries a `projectId` only — "new painting" is created by
`StudioViewModel` (folder + `project.json` with zero tiles) *before*
navigating, so the canvas screen has exactly one entry path and the
`SavedStateHandle` always has an id to restore from. `CanvasViewModel`
reads it with `savedStateHandle.toRoute<CanvasRoute>()`.

Settings/About is a sheet reachable from the Studio menu, not a route; it
has no state worth a back-stack entry.

## 8. Lifecycle

### 8.1 `ON_STOP` autosave

`CanvasScreen` observes `LocalLifecycleOwner` with a `LifecycleEventEffect`
and calls `viewModel.onStop()`, which calls `session.flushReadbacks()`
(bounded by the PBO pool, milliseconds) then writes `project.json`. Note
that `surfaceDestroyed` usually precedes `ON_STOP`, which is why the flush
also hangs off the surface callback (§8.2). `ON_STOP` is the
last callback a backgrounded process reliably gets; the `AutosavePolicy` loop
(§2.2) covers the foreground crash it cannot. Leaving the canvas
(`onBack`) does the same write with `SaveStatus.LEAVING` and a scrim, then
navigates.

### 8.2 Surface recreation and session rebuild: GPU state is a cache

graphics-core's EGL context lives in a `GLRenderer` that persists across
`SurfaceHolder` callbacks: `surfaceChanged` re-creates its SurfaceControls
and render targets, `surfaceDestroyed` detaches them, and `TilePool`'s
textures survive both. graphics-core registers no client-visible callback
for any of this, so `CanvasSurface` registers its **own**
`SurfaceHolder.Callback` on the `SurfaceView`. On `surfaceDestroyed` it
calls `session.flushReadbacks()` (§3.3) and `cancelStroke()` — after the
surface is gone the renderer has no targets and a
`renderFrontBufferedLayer`/`commit` issued then is silently dropped, so a
stroke in flight is cancelled through the same path as palm rejection.
`surfaceChanged` needs nothing beyond the size report of §8.4 and a
`redraw()`.

The only cold path is a **new `EngineSession`** — after `release()` on
Compose dispose of `CanvasSurface`, after `detachSession()`, or on a genuine
`EGL_CONTEXT_LOST`. Then `TilePool` is empty, `LayerTextures` indexes are
empty, the sandwich invalid. Rebuild order:

1. tiles the viewport needs, from the `TileStore` mirror (§2.7) if present
   (read back but not yet written) else `TileStore.read` on IO →
   `uploadTiles`;
2. the rest lazily as the view moves.

Nothing on the GPU is authoritative, so no data is lost, only a frame or
two of blank-then-filled. `release(cancelPending: Boolean,
onReleaseComplete: (() -> Unit)?)` is the actual signature; the session
passes `cancelPending = true` after its own flush.

### 8.3 Configuration changes

`MainActivity` declares
`configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|keyboardHidden|uiMode"`
(Meltorama's manifest). Rotation therefore does not recreate the activity or
the ViewModel, and the `SurfaceView` is resized rather than replaced. Locale
changes are deliberately *not* in the list so strings refresh — and this is
why `@StringRes` ids, not strings, travel through state (§9).

### 8.4 Resizes: multi-window, fold, panel open/close

`CanvasSurface` reports `(width, height)` on `surfaceChanged`; the ViewModel
computes `newFit = FitTransform.of(doc, width, height)` and applies
`view = view.rebase(oldFit, newFit)` — the canvas point at the old center
stays at the new center, zoom and rotation untouched. Same call when the
window size class changes, so a fold/unfold, a split-screen drag, and a
DeX window resize are one code path with one test.

### 8.5 Memory pressure

`onTrimMemory(TRIM_MEMORY_UI_HIDDEN / TRIM_MEMORY_BACKGROUND)` and
`onLowMemory()` (docs/plan/10-performance.md §7 — the `RUNNING_*` levels are
not relied on) → `session.trim()` → `execute { tilePool.trim(keepViewport =
true) }` after forcing pending readbacks to complete (`flushReadbacks()`).
The `TileStore` mirror is not trimmed (it is the only copy until written);
everything on the GPU can be re-read.

## 9. Error model

Failures are values, and the value is a `@StringRes Int`. The ViewModel has
no `Context`, no `Resources`, no locale, and never formats a message
(Meltorama convention: `EditorViewModel.reportEngineError(@StringRes reason)`).

```kotlin
sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>
    data class Failed(@StringRes val reason: Int, val cause: Throwable? = null) : StoreResult<Nothing>
}
```

| Source | Reason id | Surface |
| --- | --- | --- |
| `ProjectStore` load (on opening a painting, including a `SavedStateHandle` restore whose folder is gone or unreadable) | `err_project_read` | the Canvas never shows: navigate back to the Studio with the toast; the folder is left untouched (06 §7: a corrupt painting is a support question, not an eviction) |
| `ProjectStore` save | `err_project_write` / `err_storage_full` | snackbar; save retried on next policy tick |
| `TileStore` read (corrupt `.tile`, 06 §4) | `err_tiles_unreadable` | one toast on open with the count; the tiles show transparent |
| `TileStore` write | `err_storage_full` | persistent banner "Storage full — free up space to keep saving" (string key `err_storage_full`); painting continues (GPU + CPU mirror intact): the `TileFlusher` enters its storage-full state (06 §6.3 — the mirror cap is lifted so commits are never blocked on a disk that cannot drain, the write is retried on each autosave tick), and leaving the canvas shows a dialog saying the last N minutes may be unsaved |
| `GalleryExporter` | `err_gallery_write` | snackbar once per session; gallery sync marked stale, retried on leave |
| `HistoryStore` | `err_history_write` | undo for that step unavailable; stated in the snackbar |
| GL (shader compile, out of slices) | `err_gpu_init` / `err_gpu_memory` | dialog with the budget numbers; canvas falls back to read-only view via CPU `Composite` |
| `MemoryBudget` refusal | none — not an error | New Canvas dialog disables the option and says why |

`cause` is logged at the boundary that produced it (with `Log.e`, tag =
class), never propagated to UI. Exceptions never cross a thread boundary:
GL callbacks catch, log, and post a `Failed`; IO coroutines return
`StoreResult`. A crash is a bug, not an error path.

## 10. Data flow: pen down → pixels → tiles → gallery

```
 MAIN THREAD                              GL THREAD                          IO DISPATCHER
 ───────────                              ─────────                          ─────────────
 MotionEvent (unbuffered, +history)
   │ CanvasTouchHandler
   │  PalmRejection ─ GestureArbiter ──▶ (navigate? → ViewTransform.gesture → setView)
   │  Predictor.record/predict
   ▼
 StrokeInput[] (canvas space, ring)
   │ Stabilizer → DabGenerator
   ▼
 DabBatch (ring slot) ──publish──────▶ onDrawFrontBufferedLayer(batch)
   renderFrontBufferedLayer(batch)        DabPass → StrokeBuffer (dirty rect)
                                          CompositePass: below ⊕ (active ⊕ stroke) ⊕ above
                                            → front-buffered layer  ══▶ SCREEN (~1 frame)
 … (per event while pen down) …
 ACTION_UP
   commitStroke() ──────────────────▶ commit():
                                          onDrawMultiDoubleBufferedLayer(all batches)
                                            skip predicted dabs; merge StrokeBuffer → layer tiles
                                            (Mixbox/erase/alpha-lock rules)
                                          "before" = the mirror/disk copy of each dirty tile,
                                            taken at commit before the readback lands (06 §5.5)
                                          Readback: PBO glReadPixels per dirty tile
                                          fence signalled → CpuTile(after)
                                                                     ─────channel────▶ swap into mirror, deflate
                                                                                       TileStore.write(after)
 ◀── StrokeResult(dirtyTiles) ──────                                                   HistoryStore.write(before)
 CanvasViewModel:
   HistoryJournal.record(entry)
   document.dirty = true; AutosavePolicy.delayMs → checkpoint timer
   UiState.copy(canUndo = true, historyCost = …)
                                                                     timer / ON_STOP / leave
                                                                     ─────────────────▶ ProjectStore.save(doc)
                                                                                         layers/*.tile already on disk
                                                                                         project.json tmp+rename LAST
                                                                     debounce (gallery) ▶ CanvasRenderer.flatten (GL, 03 §10.4) → PNG
                                                                                         (CPU Composite only off-canvas, 06 §9.3)
                                                                                         GalleryExporter.rewrite(galleryUri)
                                                                                         thumb.png
```

Undo runs the same path backwards: `HistoryJournal.undo()` yields the
entry; IO reads its "before" tiles; `uploadTiles` puts them on the GPU
(capturing "after" via readback first, for redo); the sandwich for those
tiles is invalidated; one multi-buffered redraw.

Two properties of this flow are load-bearing and should be preserved by
anyone touching it:

1. **The screen path has no disk and no ViewModel in it.** Pen → dab → GL
   → front buffer is main-thread math plus one GL pass. Persistence and
   state are downstream of `commit()`.
2. **Disk is downstream of readback, never of the stroke.** Tiles reach
   the IO thread only as `CpuTile`s the GL thread has finished with, so
   persistence can never observe a half-merged stroke and the GL thread
   never waits on a file.
