# 0001 — Tiled, layered, GPU-composited raster engine

- **Status:** accepted
- **Date:** 2026-08-24

> Covers the engine model decision from PLAN.md §3.1 and §4.1; the design
> that follows from it lives in `docs/plan/03-canvas-engine.md` and
> `docs/plan/05-layers.md`.

## Context

帮你Draw is a painting app. Its defining tools are per-pixel
**read-modify-write**: a soft brush that accumulates flow under an opacity
cap, a smudge that picks up what is under the dab and drags it, a blur, and
pigment mixing (Mixbox) where the new dab's color depends on the color
already on the layer. Layers with blend modes and opacity sit on top. All
of this must run at 60–120 Hz with pen-to-pixel latency low enough that a
stroke feels attached to the S Pen, on canvases sized for tablets
(2560×1600 screens, canvases of 4096² and beyond), with unlimited undo that
survives the app closing (PLAN.md principle 3) and autosave that never
blocks drawing.

Four engine models were on the table:

| Model | Fits RMW tools? | Speed at 4096² | Memory | Undo/save | Verdict |
| --- | --- | --- | --- | --- | --- |
| **CPU raster** (`android.graphics.Canvas` + `Bitmap`, one bitmap per layer) | Yes, but each dab is a CPU loop over its rectangle; smudge/blur/mixing are CPU pixel loops | A full-layer 4096² RGBA8 is 64 MB; compositing N layers per frame is N × 64 MB of memory traffic on the CPU — not 120 Hz | ∝ canvas × layers, painted or not | Snapshots are whole-bitmap or hand-rolled dirty rects | Too slow for the tools that matter |
| **Vector / stroke-replay** (Meltorama's model: the stroke log is the document, pixels are a cache) | No. Smudge, mixing and blur read pixels that other strokes produced; replay must reproduce them bit-exactly, on any GPU, in order | Replay time grows with history; a long painting session replays thousands of strokes on every undo | Small document, but the cache is a full raster anyway | Replay-based; see ADR 0004 for why that breaks here | Wrong document model for a painter |
| **Skia / native (NDK)** | Yes | Fast, but Skia's raster backend is CPU; its GPU backend needs NDK + a Ganesh/Graphite build we would own | Same as whichever backend | Same problem as either backend | Adds a C++ toolchain, NDK CI, and a large vendored dependency to a hobby app with a Kotlin-only family convention |
| **Tiled raster on the GPU** (GLES 3.0, Kotlin + GLSL) | Yes: a dab is a quad with a fragment shader; RMW is a ping-pong pass over the dab's rectangle | Bounded by *viewport* pixels per frame, not canvas size; dab stamping is what GPUs do | ∝ painted area (tiles exist only where painted) | Tile-granular: dirty tiles are the unit of readback, autosave, and undo deltas | Chosen |

The constraints that decided it: (a) RMW tools are the product, (b) tablet
resolutions, (c) memory should scale with what was painted, not with the
canvas the user chose, and (d) undo and save must be incremental so they
never stall a stroke.

## Decision

The document is **pixels**: a fixed-size canvas holding an ordered stack of
layers, each a sparse grid of **256×256 RGBA8 premultiplied** tiles. On the
GPU every tile of every layer lives in one pooled **2D texture array**
(`GL_TEXTURE_2D_ARRAY`, allocated with `glTexStorage3D`, one slice per
tile); each layer keeps an index `(tx, ty) → slice`. Rendering to a tile is
`glFramebufferTextureLayer` on its slice. Compositing the viewport draws,
per visible tile, one quad per layer bottom-to-top with that layer's blend
mode and opacity, so per-frame cost is bounded by output pixels.

Strokes are stamped as dabs into a per-stroke **stroke buffer** (flow per
dab, opacity per stroke) that merges into the active layer on pen-up; RMW
tools (smudge, blur, mixing brushes) run a ping-pong pass over the dab
rectangle because a fragment shader must not sample the texture it renders
to. The live stroke goes through `GLFrontBufferedRenderer` from
`androidx.graphics:graphics-core`, with the layers below and above the
active one cached as two composites (the "sandwich") so a live stroke is
three passes. Persistence and undo work in tiles: a stroke dirties a set of
tile keys; those tiles are read back asynchronously through PBOs, deflated,
and written; their *previous* contents are the undo delta (ADR 0004).

The engine is **Kotlin + GLSL only**: OpenGL ES 3.0 baseline, no Vulkan,
no Skia, no NDK. RGBA8 render targets everywhere — half-float color
attachments are optional extensions on ES 3.0 and we do not need them for
an 8-bit document.

## Consequences

- **GL expertise is required to work on `engine/gl`.** Texture arrays,
  FBO slice attachment, ping-pong RMW, PBO readback, and the front-buffer
  callback contract are the vocabulary. Mitigation, and the reason the
  family's JVM-only test rule survives: every decision (`TileGrid`,
  `Composite`, `DabGenerator`, `FloodFill`, `ColorMixer`) has a pure-JVM
  reference in `engine/core` that the shaders must match, plus
  `GlShaderContractTest`-style source checks that the uniforms the Kotlin
  side binds exist in the GLSL.
- **ES 3.0 is the floor** and it is universal on API 29 hardware (ADR
  0002). Nothing assumes ES 3.1/3.2 (no compute shaders, no image
  load/store) — RMW is ping-pong, not `imageStore`.
- **The texture-array pool is real complexity.** Its size is bounded by
  `GL_MAX_ARRAY_TEXTURE_LAYERS` and `GL_MAX_TEXTURE_SIZE`, which are 256
  and 2048 in the spec minimum and much larger on real devices — `TilePool`
  queries them at runtime and may need several arrays. Slice allocation,
  free lists, and per-layer index maps are ours to get right; `TilePool`
  is tested through a pure-JVM allocator (`engine/core`) with the GL
  calls kept to a thin adapter.
- **Layer count is capped by memory until tile residency exists.** All
  tiles of all layers are GPU-resident in v1, so `MemoryBudget` derives a
  layer cap and a canvas-size ceiling from the device's memory and the
  canvas size, and the UI states them (PLAN.md decision 4). Eviction to
  disk and re-upload — which would lift the cap — is a post-v1 proposal
  (`docs/proposals/`).
- Memory is ∝ painted area, and a blank 8-layer 4096² canvas costs
  nothing until painted; but a fully painted one costs
  `layers × tiles × 256 KB`, and there is no compression on the GPU side.
- Undo, autosave, thumbnails, and gallery export are all tile-granular
  and incremental for free; the only whole-canvas operation is the
  flatten for gallery/share, which runs off the stroke path.
- GPU state is *not* disposable the way Meltorama's field is: after EGL
  context loss the tiles must be re-uploaded from the CPU mirror /
  `TileStore`, which is why every dirtied tile has a CPU copy until it is
  flushed.
- **Revisit if:** tile residency lands and the cap becomes a non-issue
  (no change of model, just of limits); a device class that matters ships
  without ES 3.0 (none known); the RMW tool set grows to need image
  load/store, which would raise the floor to ES 3.1; or profiling shows
  the three-pass live stroke cannot hold 120 Hz on a Tab S-class device.
