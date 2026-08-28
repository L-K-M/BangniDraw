# 11 — Testing

What this document covers: how 帮你Draw is tested — the JVM-only rule from
PLAN.md §7 and decision 7, the architectural discipline that makes it
possible, the concrete test cases (named the way the family names them) for
every pure class in `engine/core`, the shader contract tests, the
persistence and autosave tests, the golden-stroke fixture, what is
deliberately *not* unit-tested and how that is covered by a manual device
checklist instead, and the CI gates. It expands PLAN.md §7; where PLAN.md
names a class, this document names its test file and its cases. Sibling
documents own the behaviour being tested (`03-canvas-engine.md`,
`04-tools.md`, `05-layers.md`, `06-document-and-persistence.md`,
`07-input-and-stylus.md`, `09-color-and-mixing.md`); the per-step
acceptance criteria live in `12-roadmap.md`.

## 1. The rule and why it holds

`./gradlew testDebugUnitTest` is the suite. There is no `androidTest`
directory, no emulator job, no Robolectric. Everything that *decides*
something runs on a plain JVM under JUnit (`kotlin.test` assertions, as in
Meltorama), and the Android-facing layers — Compose chrome, the GL
renderer, `MotionEvent` plumbing, MediaStore — are kept thin enough that
the interesting failure modes cannot hide in them.

Why this is worth the discipline, in a drawing app specifically:

- **The bugs that matter are arithmetic.** A dab landing 0.5 px off after a
  zoom, a stabilizer that overshoots a corner, a flood fill that leaks
  through a one-pixel gap, an undo that restores the wrong tile — none of
  those need a GPU or a touchscreen to reproduce. They need the number in
  front of a test.
- **A CPU reference pins the GPU.** Shaders cannot be executed on CI, so
  every shader has a pure-Kotlin twin (`Composite`, the CPU dab stamp inside
  `DabGenerator`'s fixtures, `FloodFill`) that *is* the specification. The
  shader contract tests then hold the GLSL to the twin by string (§4).
  When one changes, both change — an AGENTS.md rule inherited from
  Meltorama.
- **Emulators are slow, flaky, and prove the wrong thing.** An emulator has
  no S Pen, no 120 Hz panel, no front-buffer usage flag. The things only a
  device can tell us are exactly the things an emulator cannot (§7).

The rule is enforced structurally, not by good intentions:

| Package | May import `android.*`? | Test style |
| --- | --- | --- |
| `engine/core` | **No.** `java.*`/`kotlin.*` only. A lint check `NoAndroidInEngineCore` (custom, to verify feasibility; until it exists, a `grep` step in `scripts/check.sh`) fails the build on any `import android` under this package. | plain JUnit, exhaustive |
| `engine/mixbox` | `MixboxMixer` no (it wraps the `com.scrtwpns.Mixbox` jar, which is plain Java); the LUT loader yes | JUnit for the mixer |
| `data/` | `java.io.File` and `java.util.zip` only for `ProjectStore`/`TileStore`/`HistoryStore` — they take a root `File`, never a `Context`. `GalleryExporter`, `ShareCache` (`FileProvider`), `Prefs` (DataStore) are Android and untested. | JUnit with a temp dir |
| `tools/` | No — a tool is engine ops + params | JUnit |
| `input/` | `CanvasTouchHandler` yes (it consumes `MotionEvent`); `GestureArbiter`, `PalmRejection` decisions, `StylusState` no | JUnit for the decisions |
| `engine/gl`, `ui/` | Yes | not unit-tested (§7); shader *sources* are, as strings |

The seam that makes `input/` testable: `CanvasTouchHandler` does nothing but
unpack a `MotionEvent` into a small value type (`PointerSample`: id, tool
type, x, y, pressure, tilt, orientation, buttons, time, historical flag)
and hand it to `GestureArbiter`. Every decision — draw, navigate, tap-undo,
cancel, ignore-as-palm — is made from `PointerSample`s, so the tests feed
timelines of samples and never see a `MotionEvent`.

## 2. Test layout and naming

```
app/src/test/java/ch/lkmc/bangnidraw/
  engine/core/      one XxxTest.kt per class listed in PLAN.md §3 (incl. GestureArbiterTest.kt)
  engine/gl/        GlShaderContractTest.kt, GlslDeclarationOrderTest.kt
  engine/mixbox/    MixboxMixerTest.kt
  tools/            FillToolTest.kt, BrushPresetTest.kt (JSON presets load and validate)
  data/             ProjectStoreTest.kt, TileStoreTest.kt, HistoryStoreTest.kt,
                    TornWriteTest.kt, BrushPresetStoreTest.kt
  input/            PalmRejectionTest.kt, StylusStateTest.kt
  ui/canvas/        CanvasViewModelTest.kt (autosave and gallery-sync clocks, fake
                    dispatcher), LayoutSpecTest.kt (window → layout decisions, pure),
                    CanvasUiStateTest.kt, HsvMathTest.kt (§3.16)
  fixtures/         golden-stroke/*.json, fill/*.pgm, composite/*.txt
```

Naming follows Meltorama: backtick sentence names that state the invariant
in plain words (`` `a tap yields exactly one dab` ``), one claim per test,
assertion messages that say what broke rather than what was compared.
Property-style tests use a fixed-seed `kotlin.random.Random(42)` and loop
over a few hundred cases; no property-testing library (one fewer
dependency, and the invariants below are simple enough to state by hand).

Numeric comparisons use explicit tolerances that are *named* at the top of
the test file (`private const val PX_EPS = 1e-3f`), never bare `assertEquals`
on floats.

## 3. Pure classes and their cases

Every class in this section lives in `engine/core` (or is pure for the
reason stated) and has one test file. The lists are the minimum a PR
introducing the class must land with; more is welcome.

### 3.1 `ViewTransform` — `ViewTransformTest`

Ported verbatim from Meltorama with its tests (roadmap step 1). Adds the
canvas-specific pieces:

- `` `two-finger drag composes translation, scale and rotation as one similarity` ``
- `` `a pinch about a point keeps that point fixed on screen` `` (property, random anchors)
- `` `rotation snaps to zero inside the snap window and not outside it` ``
- `` `scale clamps to the min and max and clamping preserves the anchor` ``
- `` `rebase after a gesture leaves the composed matrix unchanged` ``
- `` `screenToCanvas is the inverse of canvasToScreen` `` (property)
- `` `FitTransform centres the canvas at the largest scale that fits` `` for
  portrait, landscape and square canvases in portrait/landscape windows
- `` `the reset-view pill is shown iff the transform is not identity` ``
  (`isIdentity(tolerance)`)

### 3.2 `GestureArbiter` — `GestureArbiterTest`

Input is a timeline of `PointerSample`s (`down` / `move` / `up` / `cancel`
with id, tool type, position and nanosecond timestamps); output is the
decision sequence of `07-input-and-stylus.md` §3: `Draw(pointerId,
source)`, `Navigate`, `TapUndo`, `TapRedo`, `LongPressPick(x, y)`,
`CancelStroke`, `Ignore(pointerId)`.

- `` `one finger down-move-up is a stroke` ``
- `` `a second finger within the pending window turns the first into navigation and rolls back its stroke` `` — the stroke's samples so far are cancelled, not committed (`07-input-and-stylus.md` §3, `PENDING_MS`)
- `` `a second finger after the window is ignored while the stroke continues` ``
- `` `two fingers down and up quickly without movement is tap-undo` ``
- `` `three fingers is tap-redo` ``
- `` `a two-finger tap that moved more than the slop is a navigation, not undo` ``
- `` `stylus down makes every finger pointer Ignore until stylus up` `` (palm rejection)
- `` `ACTION_CANCEL or FLAG_CANCELED mid-stroke yields CancelStroke, never EndStroke` ``
- `` `stylus-only mode makes one finger a pan-only Navigate and two fingers full navigation; no finger ever draws` `` — the one-pointer `Navigate` is asserted to carry zero zoom and zero rotation (`07-input-and-stylus.md` §stylusOnly)
- `` `a touch long-press without movement is LongPressPick` ``
- `` `TOOL_TYPE_ERASER pointers yield Draw with source ERASER_END` ``
- `` `the stylus button held at down selects the button action for the whole stroke` `` — releasing mid-stroke does not switch tools halfway (a stroke has one tool)
- `` `the arbiter allocates nothing after warm-up` `` — decisions are reused mutable objects or an enum + preallocated payload; the test counts allocations indirectly by asserting the returned decision instance identity is stable across calls (the honest version of "zero allocation on the touch path")

### 3.3 `Stabilizer` — `StabilizerTest`

The stabilizer (`04-tools.md` §4) is a pull-string / exponential
follower with a per-preset strength.

- `` `strength zero passes samples through unchanged` ``
- `` `the stabilizer never overshoots` `` — property: for a monotone input
  path in x, output x is monotone and never exceeds the latest input
- `` `a sharp corner is rounded, not cut` `` — output passes within `r` of the corner and does not cross the input polyline's outside
- `` `on pen-up the output catches up to the last input exactly` `` (the tail is flushed; a stroke ends where the pen lifted)
- `` `the first sample is emitted unchanged` `` (no dead start)
- `` `output is invariant under a canvas-space translation` `` (property)
- `` `pressure, tilt and orientation are smoothed with the same window as position` ``
- `` `a stationary pressure change is forwarded` `` under the dynamics-aware
  policy so a flexible tuft can press in place
- `` `stationary pressure does not reshape a Standard brush segment` `` in
  `StrokeDriverTest`, pinning the legacy position-only gate

### 3.4 `DabGenerator` — `DabGeneratorTest`

Turns stabilized `StrokeInput`s into `Dab`s (`x, y, radius, flow,
hardness, angle, aspect, seed, wetness, bristle-along phase,
bristle-across phase` — the eleven
`DAB_STRIDE` fields of
`02-architecture.md` §3.2) at spacing `s · radius` along the
path, with size/flow dynamics from pressure, tilt, velocity and the
per-stroke opacity ceiling (`04-tools.md` §3.3).

- `` `a tap yields exactly one dab` `` — one down+up sample, or down and up at the same point, emits exactly one dab at that point
- `` `a zero-length move with unchanged dynamics emits no additional dab` ``
- `` `dab spacing is invariant under canvas scale` `` — property: the same
  screen gesture at view scale 1 and view scale 4 produces dab lists whose
  canvas-space spacing differs only by the `screen→canvas` factor; the
  *count per canvas unit of radius* is identical. This is the test that
  catches "brush gets sparse when zoomed in"
- `` `spacing is measured along the path, not per input sample` `` — a fast stroke with few samples and a slow one with many, same geometry, produce the same dab positions within `PX_EPS`
- `` `leftover distance carries across batches` `` — splitting one input list into two batches gives the same dabs as one batch
- `` `pressure maps through the preset's PressureCurve` `` for size, opacity and flow independently
- `` `radius never goes below the preset minimum or above its maximum` `` (property over random pressure)
- `` `tilt widens and lightens the pencil per its preset` ``
- `` `velocity dynamics are computed from canvas-space speed` `` (so zoom does not change the feel)
- `` `jitter is deterministic for a given stroke seed` ``
- `` `a marker dab's angle follows orientation` ``
- `` `Chinese ink pressure spreads a pointed tuft into its belly` `` and a
  directionless first touch stays round
- `` `Chinese ink records a stationary press` `` at the same centre
- `` `Chinese ink keeps brush direction through a turn` `` — the tuft retains
  its incoming axis, then eases toward the new tangent
- `` `Chinese ink depletion follows swept distance rather than dab count` `` —
  changing spacing preserves end wetness and one seed fixes the stroke's lanes
- `` `Chinese ink speed exposes bristles without collapsing width` ``
- `` `erase-mode presets emit dabs flagged erase with the same geometry` ``
- The **golden stroke** (§6) lives here too.

### 3.5 `PressureCurve` — `PressureCurveTest`

- `` `the identity curve is identity` ``, `` `endpoints are pinned to 0 and 1` ``,
  `` `curves are monotone` `` (property over the built-in set and random control points),
  `` `pressure above 1 is clamped` `` (Android may report >1; research facts)

### 3.6 `TileGrid` — `TileGridTest`

- `` `a dirty rect maps to exactly the tiles it overlaps` `` — including rects that touch a tile edge without entering the next tile
- `` `a rect partly outside the canvas maps only to in-canvas tiles` ``
- `` `a dab's dirty rect includes its full radius plus the hardness feather` ``
- `` `tile keys are stable and hashable` `` (`TileKey(tx, ty)` equality/hash)
- `` `tileCount for a canvas size is ceil in both axes` `` — a 4096² canvas is 16×16; a 1000×1000 canvas is 4×4

### 3.7 `LayerStack` — `LayerStackTest`

Immutable ops returning a new stack plus the `HistoryEntry` that inverts them
(`05-layers.md` §3.1).

- `` `add inserts above the active layer and makes it active` ``
- `` `delete of the only layer is refused` `` — a document always has one layer
- `` `delete makes the layer below active, or the new bottom` ``
- `` `reorder is a permutation` `` (property: same multiset of ids, order changed as asked)
- `` `moving a layer to its own index is a no-op and journals nothing` ``
- `` `duplicate copies properties and tiles and places the copy above` ``
- `` `merge down equals compositing the two layers` `` — see `CompositeTest`; here the *structural* claim: the result has one fewer layer, the lower layer's id, name and position, and the upper's tiles are gone
- `` `flatten leaves exactly one layer with the composite` ``
- `` `alpha lock, visibility, opacity and blend edits each journal their inverse` ``
- `` `every op's inverse applied to its result restores the original stack` `` (property over random op sequences)
- `` `a stack cannot exceed the MemoryBudget layer cap` `` — `add` returns a `Refused(reason)` result, never throws

### 3.8 `HistoryJournal` + entry encoding — `HistoryJournalTest`, `HistoryEntryCodecTest`

The journal (`06-document-and-persistence.md` §5) is a list of
entries with a cursor, capped by steps and bytes (`Limits`).

- `` `push appends and moves the cursor to the end` ``
- `` `push after undo truncates the redo branch` ``
- `` `undo then redo is identity on the journal` `` — property over random
  sequences of push/undo/redo: `(cursor, entries)` equal before and after `undo(); redo()`
- `` `undo then redo is identity on tile bytes` `` — using an in-memory
  `TileStore`, random tile edits recorded as entries; after `undo(); redo()`
  every tile's bytes equal the pre-undo bytes (`contentEquals`, all of them)
- `` `undo of a pixel entry restores before-tiles and captures after-tiles` `` — the "after" set is captured only on the first undo and reused on repeat undo/redo
- `` `undo at cursor zero and redo at the end are no-ops` ``
- `` `prune by step count drops the oldest entries` ``
- `` `prune by bytes drops oldest until under budget` ``
- `` `redo accounting preserves one applicable entry when its sidecar exceeds the cap` `` —
  applied entries prune oldest-first, then the far redo tail; the nearest
  redo transition survives and remains applicable from the cursor
- `` `prune of a journal whose cursor is at zero keeps the cursor at zero` ``
- `` `the journal reports counts and bytes for the UI` `` (`stats()` matches the sum)
- Codec: `` `every entry kind round-trips through the on-disk encoding` ``
  (pixel delta, layer add/remove/reorder/merge/property), `` `the header
  is versioned and a newer minor version loads` ``, `` `a truncated payload
  decodes to a Corrupt marker, not an exception` `` (the loader skips it;
  see §5), `` `tile payloads are deflated and inflate to identical bytes` ``.

### 3.9 `FloodFill` — `FloodFillTest`

CPU scanline flood over a `PixelSource` (current layer or the composite),
producing a coverage mask; `FillTool` uploads the mask (`04-tools.md` §Fill).
Fixtures are small ASCII/PGM images under `fixtures/fill/`.

- `` `filling inside a closed shape never leaks` `` — a ring fixture: the mask is exactly the interior; property over random closed rectangles/ellipses drawn with 1-px borders
- `` `a one-pixel gap leaks and a closed gap does not` `` — the classic gap fixture, both variants, so the behaviour is documented rather than accidental
- `` `expand by N grows the mask by exactly N pixels` `` — for a filled disc, the expanded mask equals the disc dilated by N (separable Chebyshev — two 1-D max filters — per `04-tools.md` §Fill); expand 0 is identity
- `` `expanded pixels stop at the canvas edge` ``
- `` `tolerance zero fills only exact matches and tolerance max fills everything contiguous` ``
- `` `tolerance is Chebyshev distance on un-premultiplied RGB plus alpha, per 04-tools.md §Fill` `` — `dist = max(|Δr|,|Δg|,|Δb|,|Δa|)/255`; hand-computed threshold pixels on both sides
- `` `global mode fills every matching pixel, contiguous mode only the connected region` ``
- `` `sampling all layers uses the composite, sampling current uses the layer` `` (two-layer fixture where they differ)
- `` `the anti-aliased edge is a one-pixel ramp and never exceeds full coverage` ``
- `` `a fill on a transparent layer with all-layers sampling fills under line art without a halo` `` — the roadmap step 8 acceptance, as a fixture
- `` `the fill's dirty rect is the mask's bounding box` `` (what gets journaled)

### 3.10 `Composite` — `CompositeTest`

The CPU reference compositor: premultiplied RGBA8 in, per-pixel
`blend(dst, src, mode, opacity)`. Each blend mode gets hand-computed pixels
from `05-layers.md` §4, in a table-driven test
(`fixtures/composite/<mode>.txt`: `dst src opacity → expected`).

- `` `composite with normal at opacity 1 is source-over` `` — property over random premultiplied pixels: `out = src + dst·(1−src.a)`
- `` `every blend mode at opacity 0 leaves the destination unchanged` ``
- `` `every blend mode over a transparent destination at opacity 1 equals the source` `` (the separable modes; the exception list is stated in the test)
- `` `multiply, screen, overlay, darken, lighten, add, difference match hand-computed pixels` `` — one row per fixture line
- `` `blend results stay premultiplied` `` — property: `r,g,b ≤ a` always
- `` `an erase dab subtracts alpha and never adds color` ``
- `` `alpha lock keeps the destination alpha exactly` ``
- `` `merge down equals compositing the two layers` `` — property: for random
  two-layer tiles, `LayerStack.mergeDown` tile bytes == `Composite.over(lower, upper, mode, opacity)`, byte for byte; this is the test that keeps merge and rendering honest with each other
- `` `the stroke buffer merge caps at the stroke opacity regardless of overlapping dabs` `` — two cases, one per `BufferMode` (`04-tools.md` §2; the stroke buffer itself is owned by `03-canvas-engine.md` §7): with the ceiling `o = opacity · pressureOpacityMax` (`04-tools.md` §3.3, §3.7), `Max` — N dabs of flow f at the same point give coverage `min(f, o)` regardless of N; `Accumulate` — `min(o, 1−(1−f)^N)`; never above `o`
- `` `the mixing merge formula matches the shader's stated formula` `` — the pigment-mixing merge (`09-color-and-mixing.md` §3.1, `03-canvas-engine.md` §7.4) in Kotlin using `ColorMixer`; hand-computed cases for t = 0, 1, 0.5, the dilution term and the alpha handling (alpha is ordinary source-over coverage, eq. (1) of 09 §3.1, never a latent lerp)
- `` `8-bit rounding is round-to-nearest, matching the GPU's UNORM conversion` `` — pinned so CPU and GPU agree on the last bit where it is defined

### 3.11 `MemoryBudget` — `MemoryBudgetTest`

Inputs: `totalMem` bytes, `isLowRamDevice`, `largeMemoryClass` MB, GL limits
(`glMaxTextureSize`, `glMaxArrayLayers`), canvas size — `DeviceMemory` and
`CanvasSize` of `10-performance.md` §4. Outputs: `Result(maxLayers,
maxCanvasEdge, poolArraySlices, poolArrayCount, history caps, thumbnail cache,
transient image bytes)`; the pinned worked table is 10 §4's (`05-layers.md`
§6).

- `` `the layer cap is monotone non-decreasing in totalMem` `` (property over sorted random memory sizes, fixed canvas)
- `` `the layer cap is monotone non-increasing in canvas area` ``
- `` `a low-RAM device gets the flat 256 MiB tile budget` ``
- `` `maxLayers is clamped to MIN_LAYERS..MAX_LAYERS` `` — 1..16 (`10-performance.md` §4); `maxCanvasEdge` only admits sizes at which `MIN_USEFUL_LAYERS` (4) plus the stroke-buffer reserve fit, so the New Canvas dialog never offers a size that cannot be painted
- `` `the pool spans enough arrays for every layer` `` — `maxLayers · tilesPerLayer ≤ poolArraySlices · poolArrayCount`, `poolArraySlices ≤ glMaxArrayLayers` when queried, and `maxCanvasEdge ≤ glMaxTextureSize` (the pool spans several texture arrays precisely because the ES 3.0 minimum of 256 slices holds only one 4096² layer)
- `` `the presets a device is offered all fit within its own budget` `` (cross-check with `CanvasPresets`)
- `` `refusals are values of a pure enum, never text or resource ids` `` — `engine/core` is java.*/kotlin.* only, so no `@StringRes`; the enum → string-resource mapping lives in `ui/` and is not unit-tested

### 3.12 `CanvasPresets` — `CanvasPresetsTest`

- `` `the preset list is the fixed one of 10 §4, ordered small to large, each annotated with the budget's maxLayers` ``
- `` `every preset has an even side and both orientations` ``
- `` `a preset above maxCanvasEdge is offered disabled, never dropped` `` (`08-ui-and-layout.md` §2.1)
- `` `a custom size above maxCanvasEdge is refused with the reason` ``
- `` `the default preset for a phone fits a phone budget` `` (2 GB / low-RAM inputs)

### 3.13 `AutosavePolicy` — `AutosavePolicyTest`

Ported from Meltorama with its five tests (`a fresh change waits for a quiet
moment`, `the quiet wait never pushes a write past the ceiling`, `at or past
the ceiling the write is due now`, `the wait is never negative`, `the
ceiling leaves room for at least one quiet wait`). Constants stay as
Meltorama's unless `06-document-and-persistence.md` changes them; the test
reads them from the object, never restates them.

### 3.14 `ColorMixer` — `ColorMixerTest`, `MixboxMixerTest`, `RgbMixerTest`

`ColorMixer.mix(a: Int, b: Int, t: Float): Int` over ARGB ints
(`09-color-and-mixing.md`). `MixboxMixer` is in `engine/mixbox` but is pure
(it calls `com.scrtwpns.Mixbox.lerp`), so it runs on the JVM.

- `` `blue plus yellow through Mixbox is green` `` — `mix(0xFF0000FF, 0xFFFFFF00, 0.5)`: hue in the window `[70°, 170°]` (`09-color-and-mixing.md` §10), saturation above 0.3, value above 0.15. A window, not a pixel, because Mixbox's LUT may change in a patch release; the *claim* is "green"
- `` `blue plus yellow through RgbMixer is grey-ish` `` — saturation below 0.15 (the whole reason Mixbox exists, as a test)
- `` `t of 0 and 1 return the endpoints exactly for both mixers` ``
- `` `mixing a color with itself is that color` `` (property, both mixers)
- `` `mix is symmetric` `` — `mix(a, b, t) == mix(b, a, 1−t)` within one 8-bit step (property, both)
- `` `RgbMixer is component-linear in the stored sRGB bytes` `` — `mix(a, b, t) == round(a + (b − a)·t)` per channel, not linear light (`09-color-and-mixing.md` §4)
- `` `Mixbox latent round-trips` `` — `latentToRgb(rgbToLatent(c)) == c` within one step for the swatch palette
- `` `the mixing dish uses the same mixer as the dab merge` `` — a one-line test that `ColorPanel`'s dish math is `ColorMixer`, not a private lerp (guards the roadmap step 7 acceptance from drifting)

### 3.15 Tools and presets — `BrushPresetTest`, `FillToolTest`

- `` `every built-in preset JSON parses and validates` `` — all files under `assets/brushes/`; ranges per `04-tools.md` §2
- `` `an unknown field in a preset is ignored and a missing one takes the default` `` (forward compatibility for user-edited presets)
- `` `each §6 preset exposes the dynamics PLAN.md promises` `` — pencil: pressure→opacity dominant; ink pen: pressure→size, stabilizer strong; marker: orientation-following tip; erasers: erase mode — table-driven from `ToolKind`
- `` `ToolKind routing is exhaustive` `` — class-backed kinds map once;
  Watercolor and Water map through `RmwStrokePolicy`

### 3.16 UI decisions — `LayoutSpecTest`, `CanvasUiStateTest`, `HsvMathTest`

Pure `ui/` logic (`08-ui-and-layout.md` §1, §4, §3.4):

- `LayoutSpecTest`: `` `forWindow picks the rail mode from width class and rail height` `` (the §1 table of 08), `` `each mode's content height matches its budget` ``, `` `no persistent chrome enters the central 60 % of the window` ``, `` `left-handed is the mirror image of right-handed` ``
- `CanvasUiStateTest`: `` `a dialog requested mid-stroke is parked until the stroke ends` ``, `` `undo, redo and layer operations requested mid-stroke run after the commit, in order` ``, `` `a tool or slider change mid-stroke does not alter the stroke in flight` `` (`08-ui-and-layout.md` §4 rule 2), `` `a canvas tap dismisses the open panel and draws nothing` ``, `` `hardware back pops dialog, panel, focus, then leaves` ``
- `HsvMathTest`: `` `ring and square hit-testing round-trip a point to HSV and back` ``, `` `HSV to RGB matches the reference fixtures` ``

### 3.17 Smaller pure classes named elsewhere — one test file each

Sibling documents name pure classes and tests that §3.1–3.16 do not list.
They are part of the suite under the §11 rule (every pure class gets a
`<Class>Test`); this table is the index so none is forgotten at PR time.

| Test | Class (owner) | Minimum cases |
| --- | --- | --- |
| `ScreenTransformTest` | `ScreenTransform` (`03-canvas-engine.md` §3.1) | `view ∘ fit` equals the composed similarity for random views; `invert` round-trips; `effectiveScale = fs·s` |
| `FilterPolicyTest` | `FilterPolicy` (03 §3.4, §15) | the sampler/`u_taps` table at each threshold and one step inside each band |
| `SandwichInvalidationTest` | `SandwichPolicy`/`SandwichState` (03 §4, `05-layers.md` §8–9) | every row of 05 §8's table; `add` stales Below only |
| `TilePoolAllocatorTest` | the pure slice allocator behind `TilePool` (ADR 0001, 03 §2.1) | allocate/free reuse, lazy page growth, `allocateNotOn` never returns an excluded page and creates a page when all are excluded, exhaustion is a value not an exception |
| `StrokeMergeTest` | `StrokeMerge` (03 §7.4, §15) | the four merge modes on hand-computed premultiplied pixels; the opacity cap; MIX bit-exact with PAINT where one side is empty |
| `OffscreenCapacityTest` | `OffscreenCapacity` (03 §7.6) | pressure-size ramps allocate only at new high-water marks; width/height grow independently; retained RGBA8 byte cost is exact |
| `DabStampTest` | `DabStamp` (03 §7.2–7.3) | hardness falloff at `d = 0`, `h·r`, `r`; ≥ 1 px AA band at hardness 1; sub-pixel area weighting; `Max` vs `Accumulate`; loaded Chinese ink stays dense, dry ink has dark hairs and zero-alpha gaps, overlapping dabs keep the same lanes |
| `HistoryEntryTest`, `MergeSemanticsTest` | `05-layers.md` §9 | as listed there (apply → undo → equality; merge/flatten equal `Composite.tile`; the readback-drain case; undo on a locked layer succeeds, 05 §1) |
| `ToolSwitcherTest` | `ToolSwitcher` (`04-tools.md` §9) | push/pop order with eraser end during a button hold; `select` during a temporary replaces the base; pop of a non-top reason is a no-op |
| `GallerySyncDecisionTest` | `GallerySyncDecision` (`06-document-and-persistence.md` §9.2, `12-roadmap.md` step 4) | `(uriPresent, isOwner, threw, modifiedByOther)` → Insert / Rewrite / Reinsert, every combination |
| `ColorsTest` | `Colors.kt` (`09-color-and-mixing.md` §1) | `premultiply`/`unpremultiply` round-trip within 1 LSB for alpha ≥ 1; alpha 0 → 0; `HsvColor` fixtures shared with `HsvMathTest` |
| `DishStepsTest`, `MixOverMathTest` | 09 §9.3, §10 | as listed there, both mixers |
| `MixboxLutTest` | `09` §5.1, ADR 0003 (Mixbox source set) | the asset's sha256 equals the recorded value; dimensions 512×512 |
| `PureCoreTest`, `ManifestTest` | `02-architecture.md` §5, ADR 0002 | no `android.`/`androidx.`/`com.scrtwpns.` import under `engine/core`; the merged manifest's `uses-permission` list is empty |
| `RotationSnapTest` | `RotationSnap` (`07-input-and-stylus.md` §7) | the 3°/5° hysteresis, exact `0f` while snapped, one tick per entry |
| `UserPreferencesTest`, `ThemeColorPolicyTest`, `ToolRailColorPolicyTest`, `CanvasVoidColorPolicyTest` | `AppTheme` and theme policies (proposal 0003, 08 §5.1) | exactly `SAFFRON`, `CORAL`, `VIOLET`, and `TEAL`; enum-name round trip; missing/unknown → `SAFFRON`; every content-role pair ≥ 4.5:1; outline/surface and rail icon/container ≥ 3:1; one shared opaque canvas void |

`ThemeContractTest` pins the app-owned light system bars and launch theme,
`android:forceDarkAllowed = false`, the absence of system-dark inputs/night
resources, the root loading gate, and its cancellation-safe, retrying
preference-failure fallback. `CanvasAppearanceContractTest` pins both the
initial GL appearance before bootstrap and subsequent theme updates, plus the
paired selected-layer text roles.
`AccessibilitySemanticsContractTest` pins the named radio group in Settings.

## 4. Shader contract tests — `GlShaderContractTest`, `GlslDeclarationOrderTest`

Shaders are Kotlin string constants in `engine/gl/Shaders.kt` (plus the
vendored `mixbox.glsl`, loaded from assets and concatenated). CI cannot run
them, so the tests hold the *source* to the contract the Kotlin side
assumes — Meltorama's pattern, whose comments explain the failure each
assertion prevents ("a renamed uniform compiles on both sides and leaves
`glGetUniformLocation` returning −1").

What is pinned, and from where:

| Assertion | Built from | Failure it catches |
| --- | --- | --- |
| `uniform sampler2DArray u_tiles;`, `uniform sampler2D mixbox_lut;`, every `u_*` name `CanvasRenderer`/`DabPass`/`CompositePass` look up | a `Shaders.UNIFORMS` list the Kotlin binding code *also* iterates | rename on one side only |
| a branch on `${BlendMode.MULTIPLY.shaderId}` (…) for every `BlendMode` in the `u_blend` dispatch (`05-layers.md` §4, `03-canvas-engine.md` §3.3) | the enum | a mode falling through to normal |
| `${BlendMode.entries}` shader ids distinct | the enum | two modes collapsing |
| `u_strokeMode` (PAINT / ERASE / MIX) and `u_alphaLock` branches present in `merge.frag` (`03-canvas-engine.md` §7.4) | constants | silent no-op tools |
| `#define BANGNI_MIXING` / `mixbox_lerp(` present in the `*_mix` merge and smudge variants; **absent** from the plain ones (`09-color-and-mixing.md` §5.2) | — | paying the LUT cost on every stroke |
| RMW scratch UVs use `u_beforeTexel`/`u_beforeScale`; blur work uses `u_horizontalTexel`/`u_horizontalScale`; every tap calls `clampLogicalUv` (`03-canvas-engine.md` §7.6) | `SmudgePass` uploads logical/capacity scales from `OffscreenTarget` | retained high-water textures stretching, offsetting, or exposing stale edge texels |
| The stroke-buffer opacity cap `S *= u_strokeOpacity / S.a` (`03-canvas-engine.md` §7.4) | `StrokeBuffer.MERGE_EXPR` | opacity cap drift from `CompositeTest` |
| `i_seed`, `i_wetness`, both `i_bristle*` phases, `u_brushModel`, transported `v_axisMajor`, and dab-local coordinates in `inkBrushMask` | `DAB_STRIDE`, `BrushModel`, `InkBrushMask` and the CPU `DabStamp` oracle | an inactive payload field, location-dependent or per-dab lane swimming, or CPU/GPU dry-brush drift |
| The 8-bit rounding helper | `Composite.ROUND_EXPR` | last-bit disagreement with the CPU reference |
| `layout(location = N)` attribute indices | `Shaders.ATTR_POS`, `ATTR_UV` | VAO binding mismatch |
| `#version 300 es` first line of every source | — | ES 2 parse |

`GlslDeclarationOrderTest` is a tiny lint over every shader string: the
`#version` line is first; `precision` declarations precede any uniform;
every `uniform` is declared before its first use; `uniform sampler2D
mixbox_lut` is declared exactly once, by the includer before the vendored
`mixbox.glsl` (which declares no uniform of its own — `09-color-and-mixing.md`
§5.2); every `in`/`out` between the vertex and fragment
sources matches by name and type. It is regex-based, deliberately dumb, and
its false-positive policy is "fix the shader" rather than "loosen the
regex".

What it does not claim: that the shader compiles or produces the CPU
reference's pixels. That is the device checklist (§7) and, if it ever bites
badly, the trigger for an instrumented golden-image job (§8).

## 5. Persistence tests — a temp dir, no Android

`ProjectStore(root: File)`, `TileStore(layerDir: File)`, `HistoryStore(dir:
File)` take plain files (the Hilt module passes `context.filesDir`). Tests
use `kotlin.io.path.createTempDirectory("bangni-…")` as Meltorama's
`ShareCacheTest` does, and delete it in `@AfterTest`.

`ProjectStoreTest`:
- `` `a full document round-trips through project.json` `` — size, paper, layer stack with all properties, cursor, gallery URI as an opaque string, timestamps
- `` `a file from an older version loads on its defaults` `` and `` `unknown fields from a newer version are ignored` `` (kotlinx-serialization `ignoreUnknownKeys`)
- `` `project ids are the only thing that names a folder` `` — a document whose id disagrees with its folder is refused
- `` `list orders by last-edited, newest first` ``
- `` `delete removes the folder and nothing else` ``
- `` `duplicate copies tiles but not history and gets a fresh id, remapped layer ids and no gallery URI` `` (`06-document-and-persistence.md` §8)

`TileStoreTest`:
- `` `a tile round-trips deflated` `` (256×256×4 bytes, random content, byte-equal)
- `` `writes are tmp plus rename and leave no tmp file behind` ``
- `` `a sparse layer lists only the tiles that exist` ``
- `` `writing an all-transparent tile deletes the file instead` `` (sparseness survives erasing)
- `` `a corrupt tile file loads as Corrupt, not an exception` `` (the layer shows the tile transparent and logs)

`HistoryStoreTest`:
- `` `entries are named by sequence and load in order` ``
- `` `a gap in the sequence stops loading at the gap` `` — everything before it is usable
- `` `prune deletes the files it drops` ``

`TileFlusherTest` (fake clock and dispatcher, §7; a `TileStore` over a temp dir whose writes can be made to fail):
- `` `storage full lifts the mirror cap and keeps committing` `` — writes fail with `err_storage_full`; a commit that would exceed `CPU_MIRROR_CAP_BYTES` is still accepted, the storage-full state is reported, the pending writes are retried on the next autosave tick, and they drain once writes succeed (`06-document-and-persistence.md` §6.3)

`TornWriteTest` — the crash-mid-write simulation, the reason the format is
what it is (`06-document-and-persistence.md` §5.6):
- `` `project.json is the commit point` `` — write tiles and history for step N+1 but the *old* `project.json`; loading gives the step-N layer stack, and the contiguous entry at `nextSeq` is appended to the undo branch as applied (its tiles are on disk) while an entry after a gap is deleted (06 §5.6)
- `` `a history entry whose payload offsets exceed the file is dropped with everything after it` `` — the journal keeps working up to that point, the document loads (06 §5.6: undo history is a prefix or it is lies)
- `` `a missing tile file for a layer loads as transparent` ``
- `` `a leftover tmp file is ignored and cleaned up` ``
- `` `a project.json that fails to parse is reported, never silently replaced` `` — the Studio shows it as unreadable; we do not overwrite a user's painting with an empty one

## 6. The golden stroke — `DabGeneratorGoldenTest`

A recorded stroke (`fixtures/golden-stroke/ink-pen-loop.json`: a list of
`StrokeInput`s captured from a Tab S with an S Pen, canvas-space, with
pressure and tilt, and the exact `BrushPreset` used) is run through
`Stabilizer → DabGenerator`; the resulting dab list is compared field by
field to `fixtures/golden-stroke/ink-pen-loop.dabs.json` with `PX_EPS`.

- `` `the golden stroke produces the pinned dabs` ``
- `` `the golden stroke is batch-split invariant` `` (same result when fed in chunks of 1, 7, 64)

The pinned file is regenerated by
`./gradlew testDebugUnitTest -Dbangni.updateGolden=true` and the diff is
reviewed like code: a change to dynamics *should* change the golden, and the
PR shows exactly how. One preset is enough for v1; a new preset with novel
dynamics (marker's orientation tip, pencil's tilt) adds its own fixture.

## 7. Fake clocks and dispatchers

`CanvasViewModel` owns the autosave and gallery-sync clocks
(`06-document-and-persistence.md` §12) and consumes the pure
`AutosavePolicy`; it takes a `Clock` interface (`nowMs()`) and an injected
`CoroutineDispatcher`; the tests use `kotlinx-coroutines-test`'s
`runTest` with `StandardTestDispatcher` and `advanceTimeBy`, and a fake
clock driven from the same virtual time.

`CanvasViewModelTest` (the one `ui/` test that runs on the JVM, because the ViewModel touches no Android view class):
- `` `a checkpoint fires after the quiet window` ``
- `` `continuous edits still checkpoint at the ceiling` ``
- `` `a checkpoint with nothing unwritten writes nothing` `` (the `hasUnwrittenChanges` rule from Meltorama AGENTS.md — one flag, cleared by the write)
- `` `leave writes immediately and cancels the pending checkpoint` ``
- `` `a write in progress is not overlapped by a second one` ``
- `` `tile flushes after each stroke do not reset the document checkpoint clock` `` — tiles and `project.json` are separate clocks

The gallery mirror's scheduling (`GalleryExporter` triggers, not the
MediaStore call) is tested in the same class against the `06` §9.3 rule:
`` `a checkpoint syncs the gallery only when 30 s have passed since the last sync; leave syncs unconditionally` ``.

## 8. What is not unit-tested, and the device checklist instead

Not unit-tested, deliberately:

| Area | Why not | Covered by |
| --- | --- | --- |
| GL rendering (`engine/gl`) | no GPU on CI; the semantics are pinned by `Composite` + contract tests | device checklist; a golden-image emulator job only if a real shader/CPU divergence ever ships (§9) |
| `MotionEvent` plumbing (`CanvasTouchHandler`) | it is unpacking; the decisions are in `GestureArbiter` | device checklist (S Pen rows) |
| Compose UI | screenshot tests need an emulator; the layout *decisions* (`LayoutSpecTest`, `CanvasUiStateTest`) are pure | device checklist (layout rows), review of screenshots in the PR |
| MediaStore, `FileProvider`, DataStore | Android framework | device checklist (gallery rows) |
| `GLFrontBufferedRenderer` callbacks | library-owned thread and EGL | device checklist (latency rows) |

The manual checklist is run on real devices before a roadmap step's PR is
merged (the step's `12-roadmap.md` acceptance says which rows apply) and in
full before a `v*` tag. Reference devices: one S Pen tablet (Tab S family),
one 4 GB S Pen tablet (Tab S6 Lite class), one phone without a stylus, and
a foldable when available. Results go in the PR description as the
checklist with ticks; a failing row blocks the merge.

| # | Check | From step |
| --- | --- | --- |
| D1 | Draw a fast zigzag with the S Pen: the line follows the tip with no visible lag; no gaps, no sparse dabs at any zoom | 2 |
| D2 | Pressure: light touch → thin/faint, hard → full; the pencil and ink pen differ as §6 says | 5 |
| D3 | Tilt: pencil on its side shades wide and light; upright is a hard line | 5 |
| D4 | Flip the S Pen: eraser end erases; flip back: the previous tool returns | 5 |
| D5 | Hold the side button: eraser (or eyedropper per setting) for the stroke; release mid-stroke does not switch | 5 |
| D6 | Hover: the cursor shows at the tip, sized to the brush at the current zoom, and hides on pen-up-and-away | 5 (polish re-checked in 9) |
| P1 | Rest a palm on the glass while drawing with the pen: no marks, no zoom | 2 |
| P2 | Palm first, then pen: the pen stroke starts cleanly | 2 |
| P3 | System edge-swipe during a stroke: the stroke rolls back, nothing half-drawn remains | 2 |
| N1 | Two-finger pinch/rotate/pan, including with the pen resting on the glass; rotation snaps near 0°; the reset pill appears and works | 2 |
| N2 | Two-finger tap undoes, three-finger tap redoes; neither leaves a dot | 9 |
| N3 | Stylus-only setting: a finger does nothing but navigate | 9 |
| K1 | Draw, kill the app from Recents mid-painting, reopen: the painting is there, undo works back through the strokes | 3 |
| K2 | Draw, force-stop via Settings, reopen: same | 3 |
| K3 | Leave to Studio and back: no visible reload jank, cursor position preserved | 3 |
| G1 | Paint, wait a minute: the gallery shows one image of it; paint more: the *same* entry updates (no duplicates) | 4 |
| G2 | Delete the gallery copy in Gallery, paint more: a fresh entry appears | 4 |
| G3 | Delete a painting in the Studio: the gallery question is asked and honoured | 4 |
| G4 | Share: the share sheet gets a PNG named after the painting | 4 |
| L1 | 8 layers on a 4096² canvas on an 8 GB tablet: drag-reorder, merge down, blend modes visibly correct, no jank while drawing | 6 |
| L2 | On the 4 GB tablet the cap is smaller and is *shown*, not hit as a crash | 6 |
| M1 | Watercolor over dry paint: blue over yellow gives green; the dish gives the same green | 12 |
| M2 | Smudge drags color; blur softens; neither leaves seams at tile borders | 7 |
| F1 | Fill inside line art with expand 2: no halo under the lines | 8 |
| U1 | Rotate the device mid-stroke: the stroke is committed or rolled back, never duplicated; the view keeps its centre | 9 |
| U2 | Fold/unfold (Z Fold) and multi-window resize: the layout reflows by width, panels reopen where they were | 9 |
| U3 | One-handed on a phone: every tool and the undo are reachable with the thumb | 9 |
| U4 | Focus mode hides all chrome; a tap brings it back | 9 |
| U5 | Select every theme: named radio state and chrome update immediately; restart preserves it; toggling Android dark mode changes nothing | 13 |
| S1 | `adb shell dumpsys package` shows no requested permissions | every release |

## 9. Instrumented tests: none in v1

There is no `androidTest` source set and no emulator job. Adding one is a
recorded decision (AGENTS.md), and the triggers would be, in rough order of
likelihood:

1. **Persistence against real Android file APIs** — if a torn-write or
   permission behaviour turns out to depend on the platform (`renameTo`
   atomicity on a specific filesystem, `filesDir` quirks) and the temp-dir
   tests cannot express it.
2. **MediaStore in-place rewrite** — if a device family breaks the
   `openOutputStream(uri, "wt")` ownership flow in a way the checklist keeps
   finding; an emulator can at least catch the platform-version dimension.
3. **Golden GL images** — if the CPU reference and the shaders diverge in a
   shipped build despite the contract tests, a small emulator job rendering
   a fixed document and comparing against `Composite` would be the honest
   fix.

Until one of those happens, the cost (a 10-minute emulator job that flakes)
buys nothing the device checklist does not.

## 10. CI gates and lint

`ci.yml` runs `./gradlew testDebugUnitTest lintDebug assembleDebug` on
every push to `main` and every PR (CICD.md; family contract: least-privilege
permissions, concurrency, timeouts, wrapper validation first). Tests and
lint are hard gates from the scaffold PR onward; `release.yml` re-proves both
at the tagged commit.

Lint policy:

- **`MissingTranslation` is a gate**, as in Meltorama. The app ships
  `values/` and `values-b+zh+Hans/` (roadmap step 10 adds the Chinese
  strings, but the rule applies from step 1). Consequently every brand or
  symbol string — `app_name` (帮你Draw is the same in both locales),
  `gallery_folder`, `+`, blend-mode symbols — carries
  `translatable="false"`, and a new user-visible string is added to both
  files in the same PR or the build fails.
- `app/lint.xml` carries Meltorama's `ObsoleteSdkInt` ignore for
  `mipmap-anydpi-v26` (adaptive icons; the advice is wrong there) and
  nothing else. New suppressions need a comment saying why, in the file.
- `warningsAsErrors` is **on** for lint so a warning cannot rot; a lint
  rule that is genuinely wrong for us is disabled by id in `lint.xml` with
  the reason, not tolerated as noise.
- `abortOnError` is on (the default); `checkDependencies` off (single module).
- The `engine/core` Android-import check (§1) runs in the same job before
  Gradle, as a shell step, until a custom lint rule replaces it.

`scripts/check.sh` (family script style, `--help` from the header comment)
runs the same three Gradle tasks plus the import grep locally, so "CI green"
is reproducible before pushing.

## 11. Coverage stance

No percentage target, no coverage tool in CI. Targets get gamed and the
untestable layers (§8) would drag the number into meaninglessness. The rule
that replaces it is structural and reviewable:

- **Every pure class gets a test file**, named `<Class>Test`, in the same
  package under `src/test`. A PR that adds a class to `engine/core`,
  `tools/`, or the pure parts of `input/`/`data/` without one is not
  mergeable; the reviewer (human or GLM, per CLAUDE.md) asks for it.
- **Every invariant PLAN.md or a sibling document states in words has a
  test that states it in code** — the lists in §3 are those sentences.
- **Every bug fixed on a device gets a JVM regression test** when the fault
  was in a decision (it almost always is), named for the symptom
  (`` `a zoomed-in brush does not go sparse` ``), so the checklist row that
  found it never has to find it again.
- A class that turns out to be untestable on the JVM is a design smell to
  fix by moving the decision out, not a reason to write an instrumented
  test.
