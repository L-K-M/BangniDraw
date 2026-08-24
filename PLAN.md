# 帮你Draw — Plan

> Name: **帮你Draw** (Bāngnǐ Draw — "helps you draw"). The launcher label is
> the full name; the applicationId is `ch.lkmc.bangnidraw` and never changes
> (changing it breaks upgrades for everyone who sideloaded a build). Repo:
> `L-K-M/BangniDraw`, app directory `BangniDraw/`.
>
> This file is the **constitution**: product framing, architecture, key
> decisions, roadmap. The detailed design lives in `docs/plan/` (index at the
> end); hard-to-reverse choices are numbered ADRs in `docs/decisions/`.
> Deviations discovered while building get recorded in [AGENTS.md](AGENTS.md).

## 1. What this is

There is a dearth of good, *simple* sketching and painting apps for Android
tablets. 帮你Draw is one: a layered raster drawing app that gets out of the
way. You open it, tap **+**, and you are drawing — with a pencil that feels
like a pencil, a brush that mixes blue and yellow into green, an eraser that
lives on the other end of the S Pen. Layers, zoom-and-rotate, unlimited undo
that survives closing the app, and every painting quietly kept up to date in
the Android gallery.

It runs everywhere from a phone (quick sketches) to a large Samsung tablet
with an S Pen (real paintings), and the UI reshapes itself between the two
without ever becoming a different app.

It is a **drawing app**, not a photo editor, not a vector tool, not a
note-taking app. Images can come in as a layer or a reference (post-v1); the
output is pictures.

### Principles

1. **The canvas is the app.** Chrome is thin, tactile, and collapsible. No
   menus-in-menus. Every frequent action is one tap or one gesture away;
   everything else is one more.
2. **Fast first.** Pen-to-pixel latency is a feature. Stylus input goes
   through unbuffered dispatch, motion prediction, and a front-buffered GL
   path; the touch path allocates nothing; brushes are stamped on the GPU.
3. **Nothing is ever lost.** Everything autosaves. Undo history is part of
   the document on disk. There is no "save?" prompt because there is nothing
   to lose. Deleting is a deliberate act in the Studio, never a side effect.
4. **Honest about limits.** Canvas size and layer count are bounded by the
   device's memory; the app says what the limits are instead of crashing or
   silently degrading.
5. **Offline, private, permission-free.** No INTERNET permission, no
   accounts, no telemetry. Paintings reach the gallery through MediaStore,
   which needs no permission on the platform versions we support.
6. **Simple beats featureful.** A tool ships when it is excellent, not when
   it exists. Roadmap items are dropped before they are shipped half-baked.

## 2. Tech stack

Single source of truth for versions: `gradle/libs.versions.toml` (and the
committed wrapper for Gradle itself) — exact numbers live there and only
there. The toolchain matches sibling Meltorama2000 (the family's current
Android pair): current Gradle/AGP with AGP's built-in Kotlin support (K2, no
`kotlin-android` plugin), KSP, JDK 17, Compose BOM + Material 3, Hilt,
kotlinx-serialization, kotlinx-coroutines, navigation-compose, DataStore
Preferences. `compileSdkVersion("android-37.0")` (string form) paired with
`android.suppressUnsupportedCompileSdk=37` — these move together or not at
all.

**minSdk 29 (Android 10), targetSdk 37.** 29 is what makes the
"permission-free" promise and the low-latency path uncomplicated:
scoped storage (MediaStore writes without any permission),
`androidx.graphics:graphics-core` front-buffered rendering (API 29+), and
SurfaceControl. Every S Pen tablet that matters runs 10 or later. ADR 0002.

Drawing-specific dependencies:

- `androidx.graphics:graphics-core` — `GLFrontBufferedRenderer` for the
  low-latency stroke path (front-buffered layer while the pen is down,
  multi-buffered composite on commit), `SurfaceControlCompat`.
- `androidx.input:input-motionprediction` — `MotionEventPredictor`, the
  predicted-tail that hides the last few milliseconds of latency.
- **Mixbox 2.0** (`com.scrtwpns:mixbox` on the CPU side; the vendored GLSL
  and `mixbox_lut.png` on the GPU side) for pigment-style color mixing.
  **CC BY-NC 4.0** — see decision 5 and ADR 0003.
- OpenGL ES 3.0 (universal on API 29 hardware). No Vulkan, no Skia, no
  native code: the engine is Kotlin + GLSL.

No network dependencies. The manifest requests no permissions.

## 3. Architecture

Single module `:app`, packages first. MVVM: one immutable `UiState` per
screen from a ViewModel `StateFlow`; Hilt DI; single Activity; Compose +
Material 3 for all chrome; the canvas itself is a `SurfaceView` hosted in
Compose and driven by the GL engine. Decision logic is pure JVM Kotlin in
`engine/core`, exhaustively unit-tested; the GL and Compose layers stay thin.

```
ch.lkmc.bangnidraw
├── BangniApp.kt, MainActivity.kt
├── engine/core/      pure JVM — the document model and all decision math
│     Document, LayerStack, Layer, BlendMode, TileGrid, TileKey
│     ViewTransform, FitTransform, GestureArbiter
│     StrokeInput, Stabilizer, PressureCurve, DabGenerator, Dab
│     BrushPreset, ToolKind, ColorMixer (RgbMixer), Composite (CPU reference)
│     FloodFill, HistoryJournal, HistoryEntry, AutosavePolicy
│     CanvasPresets, MemoryBudget
├── engine/gl/        the GPU: CanvasRenderer (graphics-core callbacks),
│     TilePool (texture-array atlas), LayerTextures, StrokeBuffer,
│     DabPass, SmudgePass, CompositePass, SandwichCache, Readback, Shaders
├── engine/mixbox/    MixboxMixer (CPU, via the jar) + LUT asset loader
├── tools/            Tool + BrushTool, EraserTool, SmudgeTool, BlurTool,
│                     FillTool, EyedropperTool (each = engine ops + params)
├── input/            CanvasTouchHandler (MotionEvent → StrokeInput / gestures),
│                     StylusState, PalmRejection, Predictor wrapper
├── data/             ProjectStore, TileStore, HistoryStore, GalleryExporter,
│                     ShareCache, BrushPresetStore, Prefs (DataStore)
├── ui/home/          StudioScreen (+ViewModel): the shelf of paintings
├── ui/canvas/        CanvasScreen (+ViewModel), CanvasSurface, ToolRail,
│                     TopStrip, LayerPanel, ColorPanel, BrushSettingsSheet,
│                     NewCanvasDialog, HoverCursor
├── ui/components/, ui/theme/, ui/navigation/
```

**Threads.** Input arrives on the main thread (unbuffered dispatch while a
stroke is live) and is turned into `StrokeInput` samples and dab batches
without allocating; batches cross to the GL thread through a preallocated
ring buffer. The GL thread (owned by `GLFrontBufferedRenderer`) stamps dabs,
composites, and issues asynchronous PBO readbacks. Persistence runs on
`Dispatchers.IO` and only ever touches CPU-side tile copies handed over by
the readback. The ViewModel is the only writer of `UiState`.

### 3.1 Canvas engine (the heart) — `docs/plan/03-canvas-engine.md`

A **tiled, layered, GPU-composited raster**. The document is pixels: a
canvas of fixed size (chosen at creation, presets by device) holding an
ordered stack of layers, each a sparse grid of **256×256 RGBA8
premultiplied** tiles. Tiles exist only where something was painted. On the
GPU, tiles of all layers live in one pooled **2D texture array** (one slice
per tile); a per-layer index maps `(tx, ty)` → slice. Compositing the
viewport means, per visible tile, one quad per layer bottom-to-top with the
layer's blend mode and opacity — bounded by output pixels, not canvas size.

A stroke is: `MotionEvent` (+historical, +predicted) → `StrokeInput`
(canvas-space x, y via the inverse view transform; pressure, tilt,
orientation, time, tool type) → `Stabilizer` → `DabGenerator` (spacing as a
fraction of radius; size/flow/opacity dynamics from pressure, tilt,
velocity) → a batch of `Dab`s → GPU. Dabs land in a per-stroke **stroke
buffer** (so a stroke never exceeds its *opacity* no matter how many dabs
overlap; *flow* is the per-dab weight), which is merged into the active
layer on pen-up. Read-modify-write tools (smudge, blur, pigment-mixing
brushes) run a ping-pong pass over the dab's rectangle instead, because a
fragment shader cannot read the texture it writes.

**Low latency.** While the pen is down, each input batch stamps its dabs
and re-composites only the dirty rectangle into the **front-buffered
layer** (correct pixels — below + active ⊕ stroke buffer + above — so
erasers, blend modes and mixing all preview truthfully). On pen-up,
`commit()` merges the stroke buffer, redraws the multi-buffered layer, and
clears the front layer. The layers below and above the active one are
cached as two composites (the "sandwich"), so a live stroke is three
passes, not N. Predicted points are drawn as a *removable tail* in the
front layer only — never into the stroke buffer.

Every layer's tiles have a CPU mirror (the persistence copy) only for tiles
dirtied since the last save; the GPU is the working set. Layer count is
capped by `MemoryBudget` from the device's memory class and the canvas size
(decision 4); tile eviction/residency is post-v1.

### 3.2 Document, undo, autosave, gallery — `docs/plan/06-document-and-persistence.md`

One folder per painting under `filesDir/projects/<uuid>/`:

```
project.json                 metadata: size, paper color, layer stack (order,
                             name, opacity, blend, flags), history cursor,
                             gallery URI, timestamps — written LAST (commit point)
layers/<layerId>/<tx>_<ty>.tile   deflated premultiplied RGBA8 tiles, tmp+rename
history/<seq>.entry          one undo step: header + "before" tiles (and
                             "after" tiles once the step has been undone)
thumb.png                    what the Studio shows
```

**Undo is a journal of tile deltas.** Finishing a stroke (or any other
edit) records which tiles of which layer changed and their *previous*
contents, compressed, in `history/`. Undo restores them (capturing the
*current* contents for redo first); redo replays. Non-pixel edits (layer
add/remove/reorder/merge/property change) journal their own inverse. The
journal lives in the project folder, so **undo survives closing and
reopening** the painting, and even process death. It is capped (steps and
bytes, stated in the UI), oldest steps pruned — pruning drops the ability
to undo them, nothing else.

**Autosave** is the only save. Dirty tiles flush after every stroke on the
IO thread; the document file checkpoints on Meltorama's `AutosavePolicy`
(quiet-after-edit with a ceiling), on `ON_STOP`, and on leaving the canvas.
Writes are per-file tmp+rename with `project.json` renamed last, so a crash
mid-write leaves a stale-but-valid document, never a torn one.

**The gallery always holds the latest version of every painting.** Each
painting owns one MediaStore image in `Pictures/帮你Draw/` (`galleryUri` in
`project.json`); the flattened PNG is rewritten *in place* (same URI) on a
debounced schedule and on leave — one gallery entry per painting, not one
per save. If the entry is gone or no longer ours (user deleted it, app
reinstalled), a fresh one is inserted. Deleting a painting from the Studio
asks whether the gallery copy goes too.

### 3.3 Input — `docs/plan/07-input-and-stylus.md`

Everything a Samsung S Pen (or any Android stylus) reports is used:
pressure, tilt, orientation, hover (a size-accurate hover cursor),
`TOOL_TYPE_ERASER` (the eraser end), and the side button
(`BUTTON_STYLUS_PRIMARY`; `BUTTON_SECONDARY` on older Samsung firmware) —
default action: eraser while held, configurable to eyedropper. Palm
rejection: when a stylus is down, touch pointers are ignored, and
`ACTION_CANCEL`/`FLAG_CANCELED` roll back the stroke in flight. Gestures:
one finger draws (touch drawing can be turned off — "stylus only"), two
fingers pan/zoom/rotate (Meltorama's tested `ViewTransform` similarity
math, rotation snaps near 0°), two-finger tap undoes, three-finger tap
redoes, touch long-press samples color. `requestUnbufferedDispatch` while a
stroke is live; `MotionEventPredictor` feeds the predicted tail.

### 3.4 UI — `docs/plan/08-ui-and-layout.md`

Two screens. **Studio**: the shelf of paintings, newest first — big
thumbnails, tap to open, hold for delete/duplicate/share, a **+** that asks
only for a size (presets sized to the device) and a paper color, and a
storage readout so decisions about deleting are informed. **Canvas**: the
painting, edge to edge; a slim top strip (back, undo, redo, layers, color,
menu) and a **tool rail** on the left or right (handedness setting) holding
the tools plus two thin sliders (size, opacity). Tap the active tool again
for its settings sheet. Layers and color are slide-in panels that dismiss
with a tap on the canvas. A **focus** toggle hides all chrome. On compact
widths the rail becomes a bottom dock and panels become full-height sheets;
on expanded widths panels float beside the rail. All of that is decided by
window size class, not device type — foldables and multi-window just work.

Design language: a quiet studio. Neutral, low-saturation chrome in light
and dark (follows the system), one accent taken from the icon (saffron on
indigo), chunky tactile controls with haptic ticks, no decoration that
competes with the picture.

## 4. Key design decisions

1. **Raster tiles on the GPU, not Canvas/Bitmap and not vectors** — a
   painting app's tools (smudge, soft brushes, pigment mixing, blend modes)
   are per-pixel read-modify-write; only the GPU does that fast at tablet
   resolutions; tiles keep memory proportional to what was painted and make
   undo, autosave and readback incremental. ADR 0001.
2. **minSdk 29** — permission-free MediaStore, front-buffered rendering,
   SurfaceControl. ADR 0002 (folded into the toolchain ADR).
3. **The pixels are the document; the journal is the undo.** No stroke
   replay for undo (replaying pigment-mixed, smudged strokes is not
   deterministic across GPUs and is slow at scale). Tile deltas are exact,
   bounded, and persist. ADR 0004.
4. **Honest memory budget.** `MemoryBudget` (pure JVM) turns memory class +
   canvas size into a layer cap and a size ceiling; the New Canvas dialog
   and the layer panel show them. No silent downgrade. Eviction to lift the
   cap is post-v1.
5. **Mixbox for natural color mixing, behind an interface, with its license
   stated loudly.** `ColorMixer` has two implementations: `MixboxMixer`
   (default) and `RgbMixer`. Mixbox is CC BY-NC 4.0: 帮你Draw is a
   non-commercial, public-domain hobby app and that is compatible, but the
   *combined* app cannot be sold; the README, About screen and ADR say so,
   and stripping Mixbox is a one-line change. ADR 0003.
6. **Zero-secret signing (Meltorama/Kararead model)** — the checked-in
   debug keystore signs both build types; sideload-only distribution via
   GitHub Releases; switching keys later breaks upgrades. ADR 0005.
7. **JVM-only test suite** — everything that decides something lives in
   `engine/core` and is tested with plain JUnit; a CPU reference compositor
   and CPU dab/fill implementations pin the shader semantics. No emulator
   job until something genuinely requires one.
8. **One save path, no prompts** (Meltorama's lesson, PR #45/#46): autosave
   is the save; nothing evicts a painting but the user; the Studio shows
   what the shelf costs.
9. **Tools are presets over one engine.** Pencil, pen, brush, airbrush,
   marker and both erasers are `BrushPreset`s over the same dab pipeline;
   only smudge/blur (RMW), fill (CPU flood, GPU upload) and eyedropper are
   separate tool kinds. New brushes ship as JSON, not code.

## 5. Screens

1. **Studio** — shelf grid (thumbnail, title, "edited 5 min ago"), newest
   first; **+** → New Canvas dialog (size preset / custom within the
   budget, orientation, paper color); hold a painting → delete (confirm,
   with the gallery-copy question), duplicate, share; storage readout;
   About/Settings entry.
2. **Canvas** — the editor. Top strip · tool rail with size/opacity
   sliders · layer panel · color panel (HSV wheel, swatches, **mixing
   dish** where two swatches blend Mixbox-style) · brush settings sheet ·
   focus mode · reset-view pill when the view is not identity · hover
   cursor for stylus.
3. **Settings / About** — handedness, stylus-only drawing, S Pen button
   action, pressure curve, haptics, gallery sync on/off, licenses (Mixbox
   CC BY-NC notice), version.

## 6. Tools — `docs/plan/04-tools.md`

| Tool | Kind | What makes it feel right |
| --- | --- | --- |
| Pencil | preset | grainy hard dab, pressure → opacity (mostly) and a little size; tilt → wider, lighter (shading with the side) |
| Ink pen | preset | hard round, pressure → size, strong stabilizer, no grain |
| Paintbrush | preset | round soft-edged, pressure → size + flow, pigment mixing on, bristle-ish texture |
| Airbrush | preset | very soft, low flow, high spacing density |
| Marker | preset | hard, semi-transparent, builds up to a cap (opacity), squared tip follows orientation |
| Eraser (hard / soft) | preset (erase mode) | same dab pipeline, subtracts alpha; the S Pen eraser end and button map here |
| Smudge | RMW | picks up color under the dab and drags it; strength; pigment mixing |
| Blur | RMW | softens under the dab |
| Fill | fill | bucket flood fill: tolerance, contiguous/global, sample current or all layers, expand by N px (no halos under line art), anti-aliased edge |
| Eyedropper | pick | tap/long-press samples the composite (or current layer) |
| Post-v1 | | lasso/rect selection + transform (move/scale/rotate), straight-line/shape assist, symmetry guide, gradient fill, watercolor/wet brushes, texture grains, image import as layer/reference, canvas crop/resize |

Every brush exposes: size, opacity, flow, hardness, spacing, pressure
curves (size / opacity / flow), tilt effect, velocity effect, jitter,
stabilizer, pigment mixing on/off. Presets are JSON in assets (built-in)
and `filesDir/brushes/` (user-edited).

## 7. Testing

- `./gradlew testDebugUnitTest` is the suite. Pure-JVM coverage of:
  `ViewTransform` (gesture composition, clamping, rebase), `GestureArbiter`
  (draw vs navigate vs tap-undo decisions from pointer timelines),
  `Stabilizer`/`DabGenerator` (spacing invariant under resolution, pressure
  curves, zero-length strokes), `TileGrid` (dirty-rect → tile keys),
  `LayerStack` ops (reorder/merge/delete invariants), `HistoryJournal`
  (undo/redo/truncate/prune, round-trip through the on-disk encoding),
  `FloodFill` (tolerance, expand, gap behavior on fixtures), `Composite`
  (every blend mode against hand-computed pixels), `MemoryBudget`,
  `AutosavePolicy`, `ColorMixer` (blue + yellow → green; `RgbMixer` stays
  RGB-linear).
- Shader contract tests: the GLSL sources are parsed for the uniforms and
  declaration order the Kotlin side binds (Meltorama's
  `GlShaderContractTest` pattern), and the CPU `Composite` is the pinned
  reference the shaders must match — when one changes, both change.
- Lint (`lintDebug`) is a hard CI gate from day one.

## 8. CI/CD — [CICD.md](CICD.md)

Family contract on every workflow: least-privilege `permissions:`, explicit
`concurrency:`, `timeout-minutes:` on every job, wrapper-validation before
any Gradle execution.

- **ci.yml** — push to main + PRs: `testDebugUnitTest lintDebug
  assembleDebug`, rolling debug APK artifact.
- **release.yml** — `v*` tags: tag↔versionName gate, re-prove tests+lint at
  the tagged commit, `assembleRelease`, sha256 sidecar, GitHub Release
  titled `帮你Draw vX.Y.Z` with `bangnidraw-vX.Y.Z.apk`. No signing secrets.
- **zai-code-review.yml** — GLM reviews every PR (hardened
  `pull_request_target`); responses follow [CLAUDE.md](CLAUDE.md).
- Releases are cut only with `scripts/release.sh X.Y.Z --push` (shared
  `lkm-release` engine). Never hand-edit `versionCode`; never create a `v*`
  tag by hand.

## 9. Privacy & licensing red lines

- No permissions in the manifest. No INTERNET, ever. No analytics.
- Paintings are the user's: app-private project folders (excluded from
  cloud backup, like Meltorama), one gallery copy they can see and delete.
- License: Unlicense (public domain) for everything we write. **Mixbox is
  CC BY-NC 4.0** and is the only non-public-domain component; the app is
  therefore non-commercial as distributed (ADR 0003). Any other third-party
  asset (brush grains, fonts, sample art) must be public domain or CC0 with
  provenance recorded in AGENTS.md.
- The app icon (`media-sources/icon.png`) is the project's own.

## 10. Roadmap — PR-sized steps

Each step lands as one reviewed PR on `main`; CI green and GLM review at
steady-state before merge (policy: [CLAUDE.md](CLAUDE.md)). Detail and
acceptance tests per step: `docs/plan/12-roadmap.md`.

| # | PR | Contents | Acceptance |
| --- | --- | --- | --- |
| 1 | Scaffold | Gradle/Compose skeleton, CI + release + review workflows, scripts, docs, icon, theme stub, Studio/Canvas placeholder screens, `ViewTransform` ported with tests | CI green; app launches; icon correct |
| 2 | Engine core | tiled layer store, texture-array pool, compositor, front-buffered `CanvasRenderer`, view transform gestures, one round brush, stylus + touch input, palm rejection, prediction | draw at 60/120 Hz on a Tab S with visibly low latency; pinch-zoom-rotate |
| 3 | Document + undo + Studio | `ProjectStore`/`TileStore`/`HistoryStore`, autosave, journal undo/redo, Studio shelf (new/open/delete), thumbnails | kill the app mid-painting → reopen → nothing lost, undo still works |
| 4 | Gallery sync + share | `GalleryExporter` in-place MediaStore updates, share sheet, export PNG/JPEG | gallery shows one up-to-date image per painting |
| 5 | Tool set | pencil, ink pen, paintbrush, airbrush, marker, hard/soft eraser, eyedropper; presets JSON; brush settings sheet; S Pen eraser end + button | every preset matches its §6 description on device |
| 6 | Layers | layer panel: add/delete/duplicate/reorder (drag)/merge down/flatten, opacity, blend modes, visibility, alpha lock; memory budget shown | 8 layers on a 4096² canvas on a 8 GB tablet without jank |
| 7 | Mixing + smudge + blur | Mixbox in dab merge, smudge, blur; `ColorMixer` switch; mixing dish in the color panel | blue + yellow = green on canvas and in the dish |
| 8 | Fill | bucket fill with tolerance / all-layers reference / expand / anti-alias | fill line art with no halos |
| 9 | Adaptive UI polish | compact vs expanded layouts, handedness, focus mode, gesture shortcuts, haptics, hover cursor, first-run hint | usable one-handed on a phone; roomy on a tablet |
| 10 | v1.0 | Settings/About (licenses), zh-Hans strings, README screenshots, release v1.0.0 | tagged release with APK |

Post-v1 (each its own proposal in `docs/proposals/`): selections +
transform, rulers/shape assist, symmetry, gradient fill, wet/watercolor
brushes, brush grains, import image as layer/reference, canvas crop/resize,
tile residency/eviction (lifts the layer cap), OpenRaster export,
time-lapse recording.

## Renaming

If the name ever changes, touch: `app_name` in every `values*/strings.xml`,
`RELEASE_APP_NAME` in `scripts/release.sh`, the APK basename in
`scripts/build.sh` and `release.yml`, the release title in `release.yml`,
the artifact name in `ci.yml`, the gallery folder constant in
`GalleryExporter`, README/PLAN/AGENTS prose, and `media-sources/`. Never
the applicationId.

## Document index

- `docs/plan/01-product.md` — vision, audience, device classes, non-goals
- `docs/plan/02-architecture.md` — packages, threading, data flow, state
- `docs/plan/03-canvas-engine.md` — tiles, GPU pool, compositing, stroke
  pipeline, front-buffered rendering, sandwich cache, readback
- `docs/plan/04-tools.md` — every tool's parameters and math
- `docs/plan/05-layers.md` — layer model, blend modes, operations, budget
- `docs/plan/06-document-and-persistence.md` — on-disk format, journal,
  autosave, gallery sync
- `docs/plan/07-input-and-stylus.md` — S Pen, touch, gestures, prediction
- `docs/plan/08-ui-and-layout.md` — screens, adaptive layout, design language
- `docs/plan/09-color-and-mixing.md` — Mixbox integration, color panel
- `docs/plan/10-performance.md` — budgets, targets, profiling plan
- `docs/plan/11-testing.md` — what is tested how
- `docs/plan/12-roadmap.md` — the steps above, with acceptance tests
- `docs/decisions/` — ADRs 0001–0005
