# Glossary

Terms of art in this repo — product vocabulary, engine internals, input,
persistence, color, and process. Each entry is the meaning [PLAN.md](PLAN.md)
and `docs/plan/` use; where a term has a class behind it, the class is named.
When a definition here and a design document disagree, PLAN.md wins and the
disagreement is a bug in whichever document is not PLAN.md.

## Product

- **帮你Draw** (Bāngnǐ Draw, "helps you draw") — the app. The launcher label
  is the full name; applicationId and package are `ch.lkmc.bangnidraw` and
  never change (a changed applicationId breaks upgrades for every sideloaded
  install). Repo `L-K-M/BangniDraw`.
- **Studio** — the home screen: the shelf of paintings plus **+**, storage
  readout, and the Settings/About entry (`ui/home/StudioScreen`). The only
  place a painting is deleted, duplicated or shared.
- **Canvas** (screen) — the editor: the painting edge to edge, a top strip,
  the tool rail, slide-in layer and color panels (`ui/canvas/CanvasScreen`).
  Lowercase *canvas* also means the fixed-size pixel surface of a painting;
  context disambiguates.
- **Painting / project** — one saved document: `filesDir/projects/<uuid>/`
  holding `project.json`, layer tiles, the undo journal and `thumb.png`.
  "Painting" in the UI, "project" in code (`ProjectStore`). A painting is
  what you go on working on; the gallery copy is what other apps see.
- **Paper** — the opaque color under all layers, chosen at creation (white,
  toned, black…). It is not a layer: it cannot be painted on, reordered or
  erased, and it is what the gallery PNG is flattened onto.
- **Shelf** — the Studio's grid of paintings, newest-edited first, with
  thumbnail, title and "edited 5 min ago". Nothing leaves the shelf except
  by the user's hand (decision 8).
- **Tool rail** — the vertical strip of tools with the size and opacity
  sliders, on the left or right by handedness; on compact widths it becomes
  a bottom dock. **Focus** hides it and every other piece of chrome.

## Engine

- **Tile** — a 256×256 RGBA8 premultiplied block of one layer, addressed by
  `TileKey(tx, ty)`. A layer is a sparse grid of tiles: tiles exist only
  where something was painted, so memory, undo, autosave and readback are
  all proportional to what was touched, not to the canvas.
- **Tile pool / page / slice** — all tiles of all layers live in one GPU
  2D texture array (`engine/gl/TilePool`); one array *slice* holds one
  tile, and a *page* is one such array (several pages when the device's
  `GL_MAX_ARRAY_TEXTURE_LAYERS` is smaller than the working set). A
  per-layer index maps `TileKey` → (page, slice). Slices are recycled, not
  reallocated.
- **Layer** — an ordered entry of the `LayerStack`: id, name, opacity,
  `BlendMode`, visibility, alpha lock, and a tile grid. The *active* layer
  is the one strokes land on.
- **Sandwich cache** — two composites kept while a layer is active: every
  layer *below* it (over paper) and every layer *above* it
  (`engine/gl/SandwichCache`). A live stroke then costs three passes
  (below, active ⊕ stroke buffer, above) instead of N, and the cache is
  rebuilt only when the stack or the active layer changes.
- **Stroke buffer** — a per-stroke scratch texture the dabs of the current
  stroke are stamped into, merged into the active layer on commit
  (`engine/gl/StrokeBuffer`). It is why a stroke never exceeds its
  *opacity* however many dabs overlap.
- **Dab** — one stamp of the brush tip: center, radius, hardness, flow,
  color (or erase), rotation, grain phase. `DabGenerator` turns stabilized
  `StrokeInput` samples into dabs; `DabPass` stamps a batch on the GPU.
- **Spacing** — the distance between consecutive dab centers as a fraction
  of the dab radius, walked along the stroke path with a carried-over
  remainder so density is invariant under speed, zoom and resolution.
- **Flow vs opacity** — *flow* is the weight of each dab as it lands in the
  stroke buffer; *opacity* is the ceiling the whole stroke is composited
  at. Low flow + high opacity builds up smoothly to a cap (marker); high
  flow + low opacity is a uniform translucent stroke.
- **Hardness** — how much of the dab radius is fully opaque before the
  edge falls off (1 = hard disk, 0 = fully soft/airbrush). The falloff
  function is the same in the CPU reference and the shader.
- **RMW tool** — a read-modify-write tool (smudge, blur, pigment-mixing
  brushes) whose result at a pixel depends on the pixels already there.
  Because a fragment shader cannot sample the texture it renders to, an
  RMW pass ping-pongs over the dab's rectangle (`SmudgePass`) rather than
  stamping into the stroke buffer.
- **Front-buffered layer vs multi-buffered layer** — the two surfaces of
  `GLFrontBufferedRenderer`. The *front-buffered* layer is drawn to
  directly, without waiting for vsync, and shows the stroke in flight; the
  *multi-buffered* layer is the normally swapped surface holding the
  settled picture. While the pen is down only the dirty rectangle of the
  front layer is redrawn.
- **Commit** — pen-up: the stroke buffer is merged into the active layer,
  the multi-buffered layer is redrawn, the front layer is cleared
  (`commit()` on the renderer), the history entry is recorded and the
  dirtied tiles are queued for readback. `cancel()` is its opposite —
  the stroke is rolled back (palm rejection, `ACTION_CANCEL`).
- **Predicted tail** — dabs generated from `MotionEventPredictor`'s guess
  of the next few milliseconds of pen travel, drawn into the front layer
  only and redrawn from the last real sample on every batch. It never
  enters the stroke buffer, so a wrong guess never reaches the document.
- **Readback** — copying dirtied tiles from the GPU to their CPU mirror
  through asynchronous PBOs (`engine/gl/Readback`), so persistence never
  stalls the GL thread. The GPU is the working set; the CPU mirror exists
  only for tiles dirtied since the last save.
- **Dirty set** — the `TileKey`s of the active layer touched since the last
  commit (for compositing) or since the last flush (for persistence).
  `TileGrid` derives it from dab rectangles; the two sets are tracked
  separately because they clear at different times.
- **View transform** — the similarity transform (pan, uniform zoom,
  rotation) between canvas pixels and screen pixels, ported from
  Meltorama's tested `ViewTransform`. Input goes through its inverse to
  become canvas-space `StrokeInput`; compositing goes through it forward.
  Rotation snaps near 0°.
- **Fit transform** — the `ViewTransform` that shows the whole canvas
  centered in the viewport (`FitTransform`); the initial view, and what
  the reset-view pill returns to.

## Input

- **Stylus vs finger** — `MotionEvent.getToolType()`: `TOOL_TYPE_STYLUS`
  and `TOOL_TYPE_ERASER` are the pen, `TOOL_TYPE_FINGER` is touch. The
  stylus always draws; a single finger draws unless *stylus only* is on;
  two or more fingers navigate.
- **Hover** — the pen near but not touching the screen
  (`ACTION_HOVER_*`, `AXIS_DISTANCE`). Drives the size-accurate hover
  cursor (`HoverCursor`) and `StylusState` (stylus-near palm rejection);
  hovering never paints.
- **Eraser end** — the other end of an S Pen, reported as
  `TOOL_TYPE_ERASER`. Mapped to the eraser preset for the duration of the
  contact; the selected tool is restored when the stylus end returns.
- **Barrel button** — the S Pen side button: `BUTTON_STYLUS_PRIMARY`, or
  `BUTTON_SECONDARY` on older Samsung firmware, both handled. Default
  action is eraser-while-held; configurable to eyedropper.
- **Palm rejection** — ignoring touch pointers while a stylus is down or
  hovering (plus a short grace after hover exit), and rolling back the
  in-flight stroke on `ACTION_CANCEL` / `FLAG_CANCELED`
  (`input/PalmRejection`). A rolled-back stroke leaves no history entry.
- **Arbiter** — `GestureArbiter` (pure JVM): from the timeline of pointers
  it decides *draw*, *navigate* (two-finger pan/zoom/rotate), *tap-undo*
  (two-finger tap), *tap-redo* (three-finger tap) or *sample* (long
  press). A stroke started by a lone finger is cancelled if a second
  finger lands within the arbiter's window.
- **Stabilizer** — smoothing of `StrokeInput` positions before dab
  generation (`Stabilizer`): a pull-string / exponential lag with a
  per-preset strength. Strong on the ink pen, near zero on the pencil.
- **Pressure curve** — the mapping from reported pressure (0..1, may
  exceed 1, clamped) to a 0..1 dynamics value (`PressureCurve`): a global
  user curve in Settings composed with each preset's per-parameter curves
  for size, opacity and flow.
- **Tilt** — `AXIS_TILT`, radians from perpendicular (0) to flat (π/2).
  Presets map it to wider, lighter dabs (pencil shading with the side).
- **Orientation** — `AXIS_ORIENTATION`, the compass direction the pen
  leans in, radians. Used with tilt to elongate a dab along the lean, and
  by the marker to align its squared tip.

## Persistence

- **Journal** — the on-disk undo history, `history/<seq>.entry`, one file
  per step (`HistoryJournal` in core, `HistoryStore` on disk). The pixels
  are the document and the journal is the undo (decision 3): nothing is
  replayed, only tile contents restored.
- **History entry** — one undo step: a header (layer, kind, sequence) plus
  the *before* tiles of everything the edit changed, deflated. Once undone
  it also holds the *after* tiles so redo is exact. Non-pixel edits
  (layer add/remove/reorder/merge/property) record their own inverse
  instead of tiles.
- **Cursor** — the position in the journal that the document currently
  reflects, stored in `project.json`. Undo moves it back, redo forward;
  a new edit truncates everything after it.
- **Prune** — dropping the oldest entries when the journal exceeds its
  step or byte cap (stated in the UI). Pruning loses the ability to undo
  those steps and nothing else; the document is unaffected.
- **Checkpoint** — writing `project.json` so that everything flushed so
  far is a consistent, reopenable document. Driven by `AutosavePolicy`
  (quiet-after-edit with a ceiling; constants in the class), `ON_STOP`,
  and leaving the canvas.
- **Autosave** — the only save. Dirty tiles flush on the IO thread after
  every stroke; checkpoints follow the policy above. There is no manual
  save and no "save?" prompt because there is nothing to lose.
- **Gallery mirror / sync** — the one MediaStore image in
  `Pictures/帮你Draw/` each painting owns (`galleryUri`), rewritten in
  place on a debounced schedule and on leave by `GalleryExporter`. If the
  entry is gone or no longer ours, a fresh one is inserted. Can be turned
  off in Settings.
- **Commit point** — the rename of `project.json.tmp` → `project.json`,
  done *last* after every tile and history file has been written
  tmp+rename. Before it, a crash leaves the previous document; after it,
  the new one. There is no in-between.

## Color

- **Mixbox** — Secret Weapons' pigment-mixing library (CC BY-NC 4.0; ADR
  0003). CPU via the `com.scrtwpns:mixbox` jar, GPU via the vendored
  `mixbox.glsl` and `mixbox_lut.png`. It is the reason blue + yellow
  makes green here and the reason the combined app cannot be sold.
- **Latent** — Mixbox's internal representation of a color: pigment
  concentrations plus a residual (`Mixbox.LATENT_SIZE` floats on the CPU,
  a `mat3` in GLSL). Latents are linear, so mixing N colors is a weighted
  sum of latents followed by `latent_to_rgb`; RGB is never averaged
  directly.
- **Pigment mixing** — the per-preset flag that makes a brush's dab merge
  and the smudge tool go through `ColorMixer` instead of alpha blending
  in RGB. Off for the pencil and ink pen; on for the paintbrush and
  smudge.
- **Mixing dish** — the area of the color panel where two swatches are
  blended by a slider, Mixbox-style, and the result can be taken as the
  current color or saved as a swatch.
- **ColorMixer** — the interface (`engine/core/ColorMixer`) with two
  implementations: `MixboxMixer` (default, `engine/mixbox/`) and
  `RgbMixer` (component-wise lerp of the stored sRGB values — not linear
  light). Swapping them is one binding, which is what keeps the license decision reversible.
- **Premultiplied vs straight alpha** — tiles, the stroke buffer and all
  compositing use premultiplied RGBA (color already scaled by alpha), so
  blending is a single `src + dst·(1−src.a)`. Mixbox wants straight
  sRGB, so a mixing merge un-premultiplies, mixes, and re-premultiplies;
  the LUT must be loaded *without* premultiplication.
- **sRGB** — the color space of tiles, the gallery PNG and Mixbox's
  inputs/outputs. The engine does not work in linear light; `RgbMixer`
  lerps in sRGB to match, and the CPU `Composite` reference pins the same
  choice for the shaders.

## Process

- **ADR** — an Architecture Decision Record in `docs/decisions/`,
  numbered, for choices that are hard to reverse (tiles on the GPU,
  minSdk/toolchain, Mixbox, journal undo, zero-secret signing). Written
  before the code, amended never — a reversal is a new ADR.
- **Proposal** — a design note in `docs/proposals/` for a post-v1 feature,
  written before it is scheduled. A proposal argues the feature and its
  cost; a roadmap step schedules it.
- **Steady-state review** — the point at which the automated GLM review
  of a PR (`zai-code-review.yml`) yields no new valid findings for two
  rounds, re-raises declined items, or only has out-of-scope remarks;
  the PR is then merged. Policy in [CLAUDE.md](CLAUDE.md). Human review
  comments are never subject to the cutoff.
- **Roadmap step** — one PR-sized entry of PLAN.md §10 with its own
  acceptance test in `docs/plan/12-roadmap.md`. Steps land in order;
  a step that cannot pass its acceptance test is not merged half-done.
- **Family contract** — the conventions shared with the user's other apps
  (Meltorama2000 and siblings): least-privilege, concurrent, timed-out
  workflows with wrapper validation; `scripts/build.sh` / `install.sh` /
  `release.sh`; zero-secret signing; versions only in
  `gradle/libs.versions.toml`; PLAN / AGENTS / CICD / GLOSSARY as the docs
  furniture.
