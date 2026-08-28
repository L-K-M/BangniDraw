# 0002 — Gesture-scoped watercolor

- **Status:** Accepted — roadmap #12
- **Date:** 2026-08-27

## Problem

A translucent brush is not watercolor. Wet paint must carry neighbouring
pigment, clear water must move existing colour, and paper texture must affect
the result. The feature must still fit the sparse GLES 3.0 renderer, bounded
phone memory, exact colour-tile undo, autosave, and the existing Mixbox colour
model.

The target is a controllable wet-medium approximation. It is not a general
fluid solver.

## Evidence

### Physics and rendering

- Curtis et al.,
  [Computer-Generated Watercolor](https://grail.cs.washington.edu/wp-content/uploads/2015/08/curtis-1997-cgw.pdf),
  separates surface water, pigment, and paper saturation. Its visible cues
  come from local flow, adsorption, paper capacity, and evaporation.
- Washburn,
  [The Dynamics of Capillary Flow](https://journals.aps.org/pr/abstract/10.1103/PhysRev.17.273),
  and Takahashi et al.,
  [Capillary penetration in fibrous matrices](https://journals.aps.org/pre/abstract/10.1103/PhysRevE.56.2035),
  explain why paper absorbs water locally rather than behaving like a flat
  liquid surface.
- Deegan et al.,
  [Capillary flow as the cause of ring stains](https://www.nature.com/articles/39827),
  and González-Gutiérrez et al.,
  [Pattern formation by droplet evaporation and imbibition in watercolor paintings](https://arxiv.org/abs/1909.09098),
  identify contact-line deposition, evaporation, and imbibition as sources of
  dark rims and blooms.
- Small,
  [Simulating Watercolor by Modeling Diffusion, Pigment, and Paper Fibers](https://doi.org/10.1117/12.44417),
  shows that local cellular rules can reproduce the medium's main cues.
- Van Laerhoven and Van Reeth,
  [Real-time simulation of watery paint](https://www.cs.ucf.edu/courses/cap6105/fall09/readings/watercolor_sim.pdf),
  shows that those local rules map to fragment-shader passes.
- Stam,
  [Stable Fluids](https://graphics.stanford.edu/courses/cs448-01-spring/papers/stam.pdf),
  and
  [GPU Gems chapter 38](https://developer.nvidia.com/gpugems/gpugems/part-vi-beyond-triangles/chapter-38-fast-fluid-dynamics-simulation-gpu)
  are the reference for GPU ping-pong fluid fields. Their complete
  velocity/pressure solver is unnecessary here.
- [Efficient Watercolor Painting on Mobile Devices](https://pdfs.semanticscholar.org/4860/517353ebae6717bd5092b3b7dc1d4eae004c.pdf)
  gets mobile performance from reduced-resolution, active wet blocks.
- Chu and Tai's
  [MoXi](https://researchportal.hkust.edu.hk/en/publications/moxi-real-time-ink-dispersion-in-absorbent-paper/)
  demonstrates richer lattice-Boltzmann ink dispersion, but needs more state
  and passes than this engine can justify.
- [Mixbox](https://scrtwpns.com/mixbox.pdf) already gives the application one
  pigment-mixing model. Watercolor must reuse it.

The common result is local transport over a coarse active field. Full
hydrodynamic accuracy is not required to produce spreading, mixing,
granulation, and dark rims.

### Open-source code reviewed

| Project | License | Use here |
| --- | --- | --- |
| [inchkev/watercolor](https://github.com/inchkev/watercolor) | MIT | A CPU implementation close to Curtis et al.; its full-canvas fields and solver are reference material, not a fit for sparse GLES tiles. |
| [komietty/unity-moxi-ink](https://github.com/komietty/unity-moxi-ink) | MIT | A Unity/MoXi lattice-Boltzmann implementation; useful for state and pass comparisons, but too large for this renderer. |
| [Open-Watercolor-Sim](https://github.com/shuoqichen/Open-Watercolor-Sim) | Apache-2.0 | A Taichi GPU simulator with diffusion, drying, edge darkening, and granulation; its desktop runtime cannot be embedded in this Android GLES path. |

No source, dependency, or asset was imported. The implementation is original
code based on the papers above and the already-vendored CC BY-NC 4.0 Mixbox API.

### Rejected approaches

- **Full Curtis, shallow-water, or Navier–Stokes simulation:** velocity,
  pressure, capillary, suspended-pigment, and deposited-pigment fields require
  several global or iterative passes. Cross-tile convergence conflicts with
  sparse, low-latency strokes.
- **D2Q9 lattice Boltzmann:** nine directional distributions precede pigment
  and paper state. Mobile examples need more memory and shader work than the
  visible gain warrants.
- **Spectral Kubelka–Munk:** it requires calibrated pigment spectra and a new
  document colour model. That would disagree with existing Mixbox brushes.
- **A timer or background solver:** pixels changing after pen-up would need
  autonomous history edits, autosave ownership, suspension rules, and catch-up
  semantics. This version changes pixels only under accepted dabs.

## Decision

Use a bounded, gesture-driven cellular model. Watercolor and Water are direct
read-modify-write tools. Each accepted non-zero dab advances the affected wet
and colour fields once. There is no background pass and no pen-up settling.

### User model

- The stable `builtin.paintbrush` preset is shown as **Watercolor**. It lays
  selected pigment and water, then mixes with nearby colour.
- **Water** is a colourless droplet tool. It wets, dilutes, and carries
  existing pigment without changing the selected colour.
- Water transports committed premultiplied pixels from every brush, including
  specialty and Chinese Ink strokes. Watercolor itself requires the Standard
  brush model because its direct path does not consume tuft or bristle state.
- Water is layer-local. Switching tools or layers keeps it alive.
- Live wet cells on the active layer show a faint blue sheen that fades over
  about 12 seconds. The renderer overlay never enters document pixels,
  thumbnails, exports, or history.
- The full rail exposes Water directly. Compact layouts show Brush, Eraser,
  Smudge, Water, Fill, and More; More contains Blur and Eyedropper.
- Watercolor settings expose Water, Spread, Granulation, and Edge darkening.
  Water exposes Size, Water, and Spread. The quick secondary control is Flow
  for Watercolor and Water for the Water tool.
- Direct RMW cannot enforce a stroke-wide opacity ceiling. Watercolor presets
  therefore require opacity 1 and constant pressure opacity; Flow controls
  pigment deposition and pressure can control Flow.

### Wet state

Every colour layer may own a sparse RGBA8 wet grid:

| Channel | Meaning |
| --- | --- |
| R | mobile surface water |
| G/B | high/low bytes of the last 100 ms update tick |
| A | absorbed paper saturation |

One wet texel covers a 4×4 canvas block. Wet storage uses ordinary 256×256
physical slices from the shared `TilePool`; one slice therefore covers
1024×1024 canvas pixels. A fully wet 4096² layer needs a 1024² wet grid:
4×4 slices, or 4 MiB. Small grids are padded to one physical slice.

The G/B tick is a lazy age stamp, not a step counter. Each processed batch
samples the renderer's monotonic clock, quantized to 100 ms. Sampling a cell
multiplies its water and saturation by
`clamp(1 - ageTicks / 120, 0, 1)`, giving a 12 second lifetime.

Each physical wet page also carries a full-width monotonic
`updatedAtNanos`. The presentation refresh releases pages untouched for
12 seconds after retaining their final dirty region. A 16-bit tick wraps after
about 109 minutes; an epoch change age-only re-encodes wet pages and the active
backup at the new tick before the same refresh prunes expired pages. The epoch
advances only after a complete rebase, so wrapped values cannot appear fresh.

While wet tiles exist, a 100 ms presentation clock redraws the fading sheen.
It only presents the lazy timestamp state; it performs no diffusion or settling.

This is time-aware but not frame-rate deterministic. Batches processed at
different times can see different remaining wetness, and more accepted dabs
perform more diffusion steps. Input backlog never creates an autonomous
settling loop.

### Kernel

The wet pass reads a snapshot and writes another texture. It ages the centre
and four neighbours, adds the dab's water to all five source samples, then
applies a bounded four-neighbour diffusion:

```text
A4(x) = (xN + xE + xS + xW) / 4
x'    = clamp(x + m · (A4(x) - x), 0, 1)
```

`m` is bounded by four times `MAX_DIFFUSION = 0.24`, scaled by Spread and
the expanded dab mask. Paper relief is a canvas-anchored integer hash. Its
valleys increase local absorption and capacity; absorbed water moves from R
to A.

The full-resolution colour pass samples that wet result. Mobile plus absorbed
water sets a bounded four-neighbour pigment flow. Procedural paper relief
reduces mobility for Granulation. Watercolor then deposits the selected
pigment; the pigment variant uses Mixbox and the RGB setting uses the existing
linear fallback. Edge darkening increases deposition in a bounded elliptical
rim. Water skips deposition and only transports existing premultiplied colour.
Alpha lock preserves the layer's original alpha.

Pure `WatercolorWetKernel` and `WatercolorColorKernel` implementations mirror
the GLSL equations. They are semantic oracles, not a second runtime path.

### Dab bounds and GLES limit

`WatercolorDabPlan` defines the immutable reference bounds. The live
`WatercolorDabBounds` updates the same primitive edges in place. Both expand a
dab's colour output by

```text
ceil(radius × spread × 0.5), capped at 32 canvas pixels
```

The wet output is the quarter-resolution equivalent. Its source adds one wet
cell on every side, and the full-resolution colour source covers that halo.
This defines four-neighbour reads across colour and wet tile boundaries.

Watercolor diameter is capped at 1960 canvas pixels. The dab, 32 px spread,
cell alignment, and one-cell source halo then fit the GLES 3.0 guaranteed
2048-pixel texture edge. Scratch textures live outside the pool and retain
their session high-water allocation. At maximum size, the colour source,
two wet targets, and backup-copy scratch retain 18.25 MiB.
`MemoryBudget` reports this fixed off-pool ceiling; renderer diagnostics
report actual retained bytes.

### Execution

```text
DabBatch
   │ one accepted non-zero dab, in order
   ▼
WatercolorDabBounds
   ├─ copy colour source + halo ───────────────┐
   └─ copy coarse wet source + one-cell halo ─┤
                                               ▼
                                      wet fragment pass
                                               │
                         ┌─────────────────────┴───────────┐
                         ▼                                 ▼
                colour fragment pass              wet slices/blit
                         │
                         ▼
                   layer slices
```

Source textures are complete pre-dab snapshots, so a pass never samples its
render target. Dabs stay ordered: dab N+1 reads dab N's result. Prediction is
disabled, as for other RMW tools.

A zero-flow dab is ignored. Water with zero effective water is also ignored.
Blank Water over transparent colour updates wet state only: it allocates no
colour tile and creates no history entry. It reports presentation damage so
the wet sheen appears. Later pigment can still react to that wet state.

### History and lifecycle

Colour uses the existing RMW transaction. Before the first colour write, the
engine captures the prior tile; pen-up reads the touched colour tiles and
appends one pixel-history entry. Wet state is never stored in project files,
tile files, thumbnails, exports, or history.

- **Cancel:** touched wet pages are copied once into a gesture backup,
  including their prior timestamps and absence. Cancel restores them and the
  normal RMW colour rollback.
- **Finish:** releases the wet backup. It does not settle or dry the layer.
- **Undo/redo:** clears all wet layers before exact colour restoration.
- **Merge, clear, restore, or delete:** clears wet state for affected layers.
  Flatten clears all wet state. Duplicate starts dry.
- **Reopen or GL context loss:** restores persisted colour and starts dry.
- **Checkpoint:** persists only the completed colour transaction.

The renderer owns wet GPU state; `EngineSession` owns gesture sequencing; the
document and persistence layers see only resulting colour tiles.

### Memory

Let C be a fully painted colour layer and W its fully allocated wet grid.
For N advertised layers:

```text
persistent = N × C + N × W
gesture    = max(
    C × STROKE_BUFFER_RESERVE_LAYERS,
    W × WET_GESTURE_BACKUP_LAYERS
)
required   = persistent + gesture
```

An ordinary buffered stroke and a direct watercolor gesture are mutually
exclusive, so their reserves are not added. Sandwich caches remain sparse
and unreserved. Pool exhaustion refuses an allocation; it does not evict or
overwrite colour.

### Verification

JVM tests cover:

- tick encoding, negative monotonic origins, wrap, 12 second expiry, and page
  reclamation;
- bounded wet diffusion, source bloom, absorption, and paper anchoring;
- premultiplied colour flow, clear-water non-creation, Mixbox deposition,
  granulation, rim deposition, and alpha lock;
- spread bounds, clipping, one-cell halos, and the 1960 px scratch limit;
- wet backup/cancel and edit invalidation;
- blank-Water history semantics, preset migration, tool policies, memory
  caps, and CPU/GL shader contracts.
- wet-overlay cue math, coarse-grid geometry, composition order, and expiry.

Manual device acceptance remains pending. It must cover wet-on-wet colour
mixing, dry-colour transport, four-tile seams, cancel and undo, layer
isolation, rotation/context loss, phone/tablet controls, stylus pressure,
TalkBack, and GL errors on the lowest supported device class.

## Consequences

- Watercolor interaction fits GLES 3.0 without a new dependency, permission,
  source import, or asset.
- Dry idle canvases consume no frames. A wet canvas redraws its transient cue
  at 10 Hz for about 12 seconds, with one retry after a failed presentation,
  and never changes document pixels.
- Wetness is intentionally transient. Undo, reopen, and context loss resume
  from dry persisted colour.
- Quarter-resolution water and one diffusion step per dab are visible
  approximations. A later proposal may add autonomous settling or richer
  pigment fields only with explicit history, suspension, and memory rules.
