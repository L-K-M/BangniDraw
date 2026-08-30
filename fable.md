# fable.md — deep review of 帮你Draw (2026-08-30, main @ 8d5f3e6)

Full pass over `main` at v1.2.2+ (post-watercolor, post-Chinese-ink retune,
post-themes, post in-app docs #156), read against PLAN.md, `docs/plan/`,
AGENTS.md, ANALYSIS.md (2026-08-28 consolidation), ds.md, k3.md, ISSUES.md and
REVIEW.md so nothing already decided is re-litigated. Every claim below was
checked in the source on this pass unless marked otherwise. The imagined user
throughout: a young artist with an S Pen tablet, sometimes a phone.

Marks: **[PR]** planned for this session (branch per item) · **[ready]**
shovel-ready for a later session · **[idea]** needs a product call or a
proposal doc · **[verify]** needs a device or measurement before code.

New findings carry `F-N` ids so the later ANALYSIS.md consolidation can
reference them; items that confirm an existing ANALYSIS.md entry cite its id
instead of getting a new one.

---

## 0. State of the ledger

ANALYSIS.md's "Open PR ledger" (#104–#146) is fully stale: every listed PR
merged except #127 (closed unmerged), #135 and #137 (closed as duplicates of
#131/#125). The consolidation pass at the end of this session must delete the
ledger and keep only what those PRs did *not* fix.

On #127 specifically ("preserve Watercolor wetness across the tick epoch
rebase"): re-derived the arithmetic in `WatercolorWetKernel.elapsedTicks` and
`WatercolorPass.resetDryEpoch`/`rebaseWetPages` on this pass. The
`EPOCH_REBASE` force-dry branch (`updatedTick <= nowTick` → `MAX_DRY_TICKS`)
is **correct**: at rebase time every surviving page carries an old-epoch tick,
so a page whose stored tick is at or below the new epoch's current tick is at
least one full modulus (~109 min) old and must dry; pages with larger stored
ticks get the exact modulo age. Mid-stroke epoch crossings are handled
(`stamp` → `resetActiveEpoch` runs before per-dab work), and `pruneExpired`
uses full-precision nanos, not ticks. The premise of #127 does not hold —
record the epoch machinery as verified clean rather than reopening it.

## 1. Bugs and correctness

### F-1 — Mouse wheel and trackpad scroll do nothing on the canvas. [PR]

`CanvasSurface` wires only `setOnTouchListener` and `setOnHoverListener`
(CanvasSurface.kt:172–173); no generic-motion listener exists anywhere, so
`ACTION_SCROLL` from a mouse wheel or keyboard-cover trackpad is dropped.
ANALYSIS D8 already asks for wheel-zoom-at-cursor; this pass confirms the gap
and de-risked the fix: `ViewTransform.gesture(pivotX, pivotY, 0, 0, factor, 0)`
is exactly zoom-about-a-point with the scale clamp built in. Implement a
`handleScroll` primitive on `CanvasTouchHandler` (JVM-testable), a pure
factor policy in `engine/core`, and one `setOnGenericMotionListener` line.

### F-2 — Transparent paper still reads as gray paper in two more places. [PR]

ANALYSIS U9, confirmed narrower than written: the layer-panel *thumbnail*
already draws a real checker (LayerPanel.kt:642–670), but the layer panel's
paper swatch (`PaperSwatch`, LayerPanel.kt:834–841 — `drawRect(checker)` is
one flat `surfaceVariant` fill) and the New Canvas paper swatch
(NewCanvasDialog.kt:558–582, `surfaceVariant` + "∅") both show transparent as
solid gray. A 2×2 quadrant checker at swatch size is the standard fix and does
not "read as noise" the way the rejected 8-px checker would; share one
composable between the three sites.

### F-3 — Paper colour of an existing painting cannot be customised. [PR]

`NewCanvasDialog` gained a sixth custom-HSV paper swatch in #131, but the
Layer panel's paper menu (LayerPanel.kt `paperChoices()`) still offers exactly
five fixed colours. A painting created before the user knew about custom paper
— or whose paper they change their mind about — can never reach the colour the
creation dialog offers. Add the same custom choice to the panel's paper menu,
reusing the compact HSV picker dialog #131 built. (`setPaperColor` already
takes any ARGB; this is UI-only.)

### F-4 — `BrushSettingsSheet` duplicate import. [trivial]

`androidx.compose.foundation.Canvas` is imported twice
(BrushSettingsSheet.kt:6–7). Harmless; fold into any PR touching the file.

### Verified clean (bugs hunted and not found)

- The watercolor tick-epoch rebase (see §0).
- `Stabilizer.easeAngle` under accumulated unnormalized angles (the modulo
  re-wraps; downstream trig is angle-safe).
- `DabGenerator` batch-overflow resume, tap fix-up generation guard, NaN
  pressure funnels, zero-dt velocity deferral.
- `InkBrushDynamics` segment state machine and its `copyInto` parity for the
  predicted tail; the two-frame (tuft axis vs path tangent) lane model matches
  `dab.frag`'s transcription constant-for-constant.
- Locale parity: the 5 keys absent from zh-Hans are all `translatable="false"`
  brand/format strings.
- `BrushPreview` passes `brushModel` through, so the calligraphy preview
  renders the ink mask rather than a plain ellipse stroke.

## 2. Performance

The paint hot path (touch → stabilizer → dabs → front buffer) remains
allocation-free and was not re-litigated; the standing large items (D3 band
flatten, D4 watercolor per-dab cost, D6 table, D7 device gate) are confirmed
still open and still correctly described in ANALYSIS.md. New notes:

### F-5 — Watercolor's per-dab pipeline, quantified. [verify]

`WatercolorPass.stamp` per accepted dab: `copyColorSource` (N blits) +
`copyWetSource` + one wet FBO draw + `backupWet` (2 blits per newly-touched
wet tile) + reservation + per-output-tile color draws + `writeWet` blits. At
the default paintbrush (size 40, spacing 0.2 → ~4 px step) a 2000-px stroke is
~500 dabs × ~8–15 GL ops. This is D4's cost made concrete; the mitigations
D4 lists (content gate for Water, coalesced source copies, larger effective
RMW spacing) remain the right order to try **after** device measurement.

### F-6 — The wet-overlay 100 ms tick runs for up to 48 s after the last wet dab.

`EngineSession.pumpWetOverlay` re-posts a 100 ms tick while any wet page
exists (`WatercolorOverlayKernel.REFRESH_MILLIS`), each tick re-presenting the
wet sheen and re-checking expiry. That is the accepted design (proposal 0002),
but it means battery/thermal cost continues ~48 s after pen-up. Worth one line
in the D7 measurement script (idle-after-watercolor power) — not code today.

### F-7 — Pressure-curve knot sliders still churn the whole sheet (U12 confirmed).

Each of the four knot sliders calls `onPresetChanged` per drag frame
(BrushSettingsSheet.kt `CurveEditor`), recomposing the entire sheet and
re-triggering the 50 ms preview debounce. U12's fix (local draft, publish on
`onValueChangeFinished`, flush on dismiss) stands. [ready]

## 3. Visual and layout

### F-8 — The DOCK rail is still a square-cornered slab. [PR]

`Dock` (ToolRail.kt:287–315) passes no `shape`; every other rail posture is a
rounded surface. ANALYSIS's visual-direction backlog asks to round only the
dock's top corners so dock and canvas read as one object. One
`RoundedCornerShape(topStart, topEnd)` plus a screenshot sanity check.

### F-9 — HSV marker vanishes at the square's white/black corners (U16a). [PR]

`HsvRingSquareSized` draws both markers as a single `onSurface` stroke ring
(ColorPanel.kt:363–364). In light themes the marker is near-invisible over
dark values; in dark themes over light values. Draw a two-tone halo (light
outer ring + dark inner ring, or vice versa) so the marker is visible on any
sample. Pure UI.

### F-10 — Watercolor presets have no stroke preview (U15j confirmed). [PR]

BrushSettingsSheet.kt:194–200 shows `watercolor_preview_hint` text where every
other brush renders a live stroke. The kernels are pure JVM
(`WatercolorColorKernel`, `WatercolorWetKernel.paperRelief`), so a
deterministic CPU wash — flat band with the model's rim darkening,
granulation speckle from `paperRelief`, spread-softened edges — can render in
`BrushPreview` and be pixel-pinned in tests. It is a settings preview, not a
second live-paint oracle; approximations are fine as long as the four sliders
visibly change it.

### F-11 — Brush-spacing slider wastes its travel. [ready]

The spacing slider maps linearly over 0.5 %–200 % of diameter
(BrushSettingsSheet.kt:279–289); the useful painting range (2–20 %) occupies
the first tenth of the track. Map through a square-root or log scale the way
`BrushSizeScale` does for size; display value unchanged.

### Confirmed still open from earlier passes

U1 (layer-row fixed height at 200 % font), U7 (left ledge text clipping),
U13 (focus-handle discoverability), U15a (FULL rail on short landscape),
the New-painting-cell tint and shelf-card lift from the visual-direction
backlog.

## 4. UX and convenience

### F-12 — Picking a hidden tool gives no visible confirmation (U15d confirmed). [PR]

In GROUPED mode, Blur and Eyedropper live behind "⋯"; selecting one closes
the menu and leaves "⋯" highlighted with no name shown
(ToolRail.kt:576–604). The transient-readout surface pattern already exists
(the zoom/rotation readout and history readout in CanvasScreen.kt). Show a
one-second tool-name chip under the top strip on tool selection — at minimum
for tools that have no visible rail slot. Closes the loop for every young
user who taps "More".

### F-13 — The painting's title appears nowhere on the canvas. [ready]

Only the a11y `canvasDescription` carries it (ds.md 3.4, still true). The
cheapest honest fix: include the title in the same transient chip family as
F-12 when the canvas opens, and/or after rename. Immersion is by design, so
nothing persistent.

### F-14 — No "Clear painting" action (A16 confirmed). [ready]

Clear Layer exists with confirmation (#105); clearing the whole painting
still requires deleting layers one by one or scrubbing with the eraser.
One overflow item + confirmation + one journaled document action.

### F-15 — Overflow menu is one flat list of nine items.

Share / Export PNG / Export JPEG / Focus / Rename / Tracing image / Guides /
Settings / Help (TopStrip.kt:390–416). Not wrong, but the export pair could
collapse into one "Export…" with a format chooser, making room as actions
accrue (Clear painting, future layer export). [idea — do it when the next
item lands, not before]

### Confirmed still open

U5 (Toasts everywhere on Canvas — counted 8+ `Toast.makeText` call sites in
CanvasScreen alone; the one transient host remains the right consolidation),
U6 (Settings navigates away from the painting), U8, U10, U11, U15c/e/f/h,
U16c/d, D8's remaining input gaps (hue-milestone eyedropper haptic,
two-finger-plus-pen size gesture).

## 5. Missing features (unchanged priority, two adjustments)

The A1–A16 backlog in ANALYSIS.md remains accurate. Two adjustments from this
pass:

- **A1 (custom brush library) got cheaper.** The brush editor already exposes
  ~every `BrushPreset` field and persists per-preset tuning (`BrushTuning`);
  `BrushPresetStore` accepts arbitrary ids. "Save as new brush" is now mostly
  UI + id allocation + rail-budget behavior, not new machinery. It is the
  single highest-leverage missing feature: today every tweak overwrites the
  built-in's tuning, so a child cannot keep two versions of a pencil.
- **A2 (symmetry) remains the top delight-per-effort item** and every seam
  this pass touched (DabGenerator's one-batch emit loop, single journal
  entry per stroke, guide overlays) still accommodates it.

## 6. Exposing what already exists

The brush editor is far more complete than the backlog implies — size,
opacity, flow, hardness, spacing, tip shape/aspect, orientation, three
pressure curves, tilt (size/opacity/elongate), velocity
(size/opacity/threshold), jitter (size/position), stabilizer, grain,
watercolor (load/spread/granulation/edge), pigment mixing, dilution, buffer
mode are all in the sheet. What exists in the engine and still has **no UI**:

- **F-16 — Chinese-ink dynamics are all constants.** `InkBrushDynamics`/
  `InkBrushMask` hardcode ink capacity, bristle width, tuft weight, edge
  drying, response length. Exposing even one — *ink capacity* ("how far a
  stroke paints before fly-white") — as an Advanced slider on the calligraphy
  preset would let artists choose lush vs. dry brushwork. Needs a
  `BrushPreset` field (serializable, defaulted), plumbing into the dab's
  wetness, no shader change (wetness is already per-dab). [ready, M]
- **F-17 — `TipShape.Flat` + `TipOrientation` combinations** support a
  credible flat/angled sketching pencil and a true chisel calligraphy pen
  as pure JSON presets; the shipped set uses only some of the space. New
  presets are one JSON file + name strings + glyph. A "Dotty pen"
  (spacing ≈ 2.0, Max buffer, hardness 1 — a dotted line) is one such
  zero-engine preset kids would love. [idea — presets are cheap but each
  needs a glyph and a feel pass]
- **F-18 — `RmwDabPreset` water parameters**: the Water tool sheet exposes
  water amount + spread but the underlying behavior also carries granulation
  and edge darkening (used by Watercolor). Deliberate simplification —
  record it as such rather than exposing; a colourless tool cannot deposit
  rims. [no change]

## 7. New painting physics and brushes

Ordered by physical plausibility inside the existing engine. The wet system
(surface water + absorbed saturation per 4×4 cell, paper relief hash, pigment
carried by flow) and the ink system (load depletion, lane masks, paper tooth)
are genuinely simulation-shaped, so several classic techniques are within
reach:

- **F-19 — Blotting / lift-out ("dry tissue").** Real watercolorists lift wet
  paint with a tissue to make clouds and highlights. Engine fit: a third
  deposit mode beside `PIGMENT`/`CLEAR_WATER` in `WatercolorColorKernel` —
  `LIFT`: where the dab lands, reduce surface water sharply and scale the
  layer's premultiplied color toward transparent in proportion to how wet the
  cell is (wet pigment lifts, dry pigment stays — physically right and
  self-limiting). One enum value, one kernel branch + GLSL twin, a Water-tool
  mode toggle. The most delightful cheap physics on the table. [idea →
  proposal, M]
- **F-20 — Salt scatter.** Salt on wet wash absorbs water locally and leaves
  pale blooms. Engine fit: a stamp that seeds N random points in the dab
  (jitter machinery exists), each point running the LIFT kernel of F-19 with
  a small radius and a granulation-shaped falloff. Depends on F-19. [idea]
- **F-21 — Finite ink / dip to reload.** `InkBrushDynamics.inkUse` resets
  every stroke ("every stroke starts loaded"). An opt-in "ink economy" mode
  would persist load across strokes and reload on a long-press of the color
  chip (or a dip gesture in the mixing dish) — the calligraphy ritual, real
  fly-white pacing, and a natural rhythm brake. Mechanically tiny (skip one
  reset; add a reload call); entirely a product decision. [idea]
- **F-22 — Rake / dry-lane brush.** The fly-white lane mask is the hard part
  of a rake brush and it already exists. With F-16's `inkCapacity` exposed, a
  "Rake" preset (ChineseInk model, tiny capacity, low flow) produces streaked
  dry-brush lanes from the first dab — grass, hair, wood grain. JSON +
  capacity field only. [ready once F-16 lands]
- **F-23 — Paper dryness / climate.** Watercolor dry time is fixed
  (24 s/unit). A global "paper" setting — Dry (12 s), Normal, Damp (48 s) —
  scales the evaporation rate at sample time; because age is stored as ticks
  and evaporation is computed lazily, a global multiplier applies cleanly to
  existing wet texels. One setting + plumbed constant + GLSL twin. Wet-on-wet
  painters get a slow-drying sheet; sketchers get fast-drying. [idea →
  proposal, S-M]
- **F-24 — Wax resist.** Crayon-then-wash resist needs a per-pixel resist
  channel the premultiplied RGBA8 tile does not have. Same class as Magic
  Ink (already recorded as proposal-or-decline). Record and stop. [likely
  decline]
- **F-25 — Tilt drip** stays the flagship motion-physics proposal (already in
  ANALYSIS's delight list); nothing this pass changes its cost.

## 8. Delight, novel, quirky

- **F-26 — Studio empty-state drawing prompts ("Idea Spark").** Confirmed the
  empty shelf is two gray sentences. ANALYSIS's delight list already endorses
  a dismissible offline prompt; k3 6.3 sized it right (strings + picker, zero
  engine risk). Both locales in the same change. [ready, S]
- **F-27 — Ink-load meter.** While the calligraphy brush draws, a hair-thin
  arc around the hover cursor showing remaining ink (`currentWetness`).
  Charming, but it puts UI on the hot path — only worth it with F-21's ink
  economy, and only if the cursor already redraws that frame. [idea]
- **F-28 — Pen-up haptic tick** (default off), **ambient shelf wash**,
  **mixing-dish drag-to-smear**, **edge resistance**: all still good, all
  still in ANALYSIS's delight list, none contradicted by this pass.

## 9. Aesthetics

The eight-theme system (#141/#148/#150) is coherent: palette decisions live in
`engine/core/ThemeColorPolicy`, screens use scheme roles, and the canvas void
stays neutral per theme tone. Nothing this pass argues with. Still open from
the visual-direction backlog: New-painting-cell `primaryContainer` tint, 1 dp
shelf-art lift, F-8's dock corners, the one OFL display face for Studio
headers. The launcher icon, hover ring, and focus-handle language all hold
together.

## 10. Deep-sweep findings (three targeted passes)

Three focused sweeps — Studio/theme/settings UI, the GL rendering layer, and
the data layer — ran alongside the main-line read. Items marked ✅ were
re-verified in source by the main pass; others carry the sweep's own
confidence and must be re-verified before code.

### Studio, theme, settings (G-series)

- **G-1 ✅ — The Layer panel header can squeeze out its own Close button.**
  LayerPanel.kt:381–426 lays out unweighted title + count, a weighted
  spacer, then FOUR 48 dp buttons (the #156 InfoButton made it four). Row
  starves trailing children, so on ≤320 dp panels or at large font the
  `PanelCloseButton` (#120's whole point) measures toward zero.
  `PanelHeader` (PanelHeader.kt:38) already does it right — weight on the
  title. [PR]
- **G-2 ✅ — Studio card dialogs don't survive rotation.** `menuOpen`,
  `confirmDelete`, `renaming`, `sharing`, `deleteGalleryToo` and the rename
  draft are plain `remember` (StudioScreen.kt:446–449, 600); the screen's own
  `showSettings`/`showNewCanvas` are `rememberSaveable`, so this is drift
  from the app's own convention. A mid-rename rotation silently loses the
  text. [PR]
- **G-9 ✅ — Same for every (i) help popup** (InfoHelp.kt:35 `remember`) and
  the canvas overflow's Help dialog (TopStrip.kt). One-word fixes. [PR, with
  G-2]
- **G-3 ✅ — `help_studio_body` promises a ⋮ that normal cards don't have.**
  The MoreVert button exists only inside the unavailable-painting overlay
  (StudioScreen.kt:527); available cards are long-press-only, which also
  leaves keyboard users with no route to rename/duplicate/share/delete.
  ANALYSIS's delight list already endorses "visible card overflow". [PR]
- **G-4 — Cold start always paints the Saffron-light launch window**, so the
  three dark themes flash cream on every launch (themes.xml pins
  `windowBackground`; the ViewModel's tone is null until DataStore emits).
  Fix needs a synchronously readable tone mirror. [ready, M]
- **G-5 ✅ — zh-Hans names the tracing image three ways** (描图图像 in the
  panel, 参考图 in Settings help, 描摹图 in the two #156 help bodies), plus a
  stray space in `help_storage_body`. The gallery *suffix* 参考图 is a stored
  MediaStore display-name component — leave it; unify the prose on the
  panel's term. [PR]
- **G-6 ✅ — The #156 help bodies are British English** ("colour", 24×) in an
  otherwise American app ("Theme color"); `help_brush_paint_body` mixes both
  in one paragraph. [PR, with G-5]
- **G-7 — Shelf thumbnails letterbox onto the transparency checkerboard**, so
  every non-4:3 painting appears to have a see-through border
  (StudioScreen.kt:479–508). Constrain the checker to the artwork rect.
  [ready, S]
- **G-8 — Unavailable-painting ⋮ may be unreachable under TalkBack** through
  `semantics(mergeDescendants=true){disabled()}`. Unverified: an IconButton
  is its own merging node, so it likely survives; needs a semantics-tree
  check, and G-3's visible ⋮ mostly moots it. [verify]
- **G-10 ✅ — Only `appTheme` survives a DataStore IO failure.** Ten other
  preference flows are bare `dataStore.data.map{}`; an `IOException`
  reaches handler-less collectors and crashes. `PreferenceFlowRecovery`
  exists and is used exactly once. [PR]
- **G-11 — The eight-row Appearance section pushes every other setting below
  the fold**, and nothing on the rows says the last three repaint the app
  dark. A swatch FlowRow and/or Light/Dark sub-headings. [ready, S]
- **G-12 — Canvas help is the ninth item of the ⋮ menu** — the least
  discoverable placement for the screen with the least discoverable
  gestures. [idea]
- **G-13 — "New sketch" opens a dialog titled "New painting"** (zh: 新建草图
  → 新画布); one noun per language would do. [PR, minimal title fix with
  G-5/G-6]

### GL rendering layer (GL-series)

- **GL-1 — Every committed (non-stroke) frame composites the whole canvas,
  not the visible rect.** `drawFrame` passes `fullCanvasRect` into
  `compositeIntoAccum`, so pan/zoom redraw cost scales with canvas size and
  paint coverage — up to ~256 quads per layer per frame on a worked 4096²
  document, at gesture rate, directly against `CompositePass`'s own
  bounded-by-output contract (which only the front-buffered path honors).
  `visibleCanvasRect(screenTransform)` already exists one call above. One
  trap: the tracing reference is deliberately not canvas-bounded, so it
  needs its own viewport-derived rect rather than inheriting the culled one.
  [PR — the session's big performance swing]
- **GL-2 — A watercolor stroke leaves a 10 Hz full-scene redraw running for
  48 s** regardless of actual water load (prune keys on the fixed
  `MAX_DRY_NANOS`, not on the water actually written; light washes are
  visually dry after ~12 s but redraw for 36 more). Track a per-tile upper
  bound of written water at `writeWet` time and expire on it. GL-1 shrinks
  each tick's cost; this shrinks the tail. [ready, M]
- **GL-3 — The wet-overlay damage rect grows monotonically within a
  gesture** (one AABB unioned per dab, cleared only when fully dry), so
  late-stroke fade frames approach full-viewport size. Needs bucketed
  per-tile damage, not a simple clear. [ready, M]
- **GL-4 — `SmudgePass` shares one `FullRectQuad` across three draw sizes**,
  re-uploading 30 floats per size alternation per dab into a buffer the
  previous draw may still read (no orphaning) — the exact tiler stall
  CompositePass documents and defends against. Give absorb/blur their own
  quads. [PR]
- **GL-5 — Between-frame GL entry points (`applyPixelOps`, thumbnail pumps,
  overlay refresh) never invalidate the `GlState` shadow**; correctness
  currently rests on `presentToWindow` happening to end scissor-off. The
  sharpest latent case: `TileCopyPass` blits with no scissor call at all, and
  a stale-enabled scissor would silently truncate a layer duplicate that
  then persists. [ready, S — an `invalidate()` per entry point]
- **GL-6 — `SandwichCache`'s stale flags can never clear on a
  larger-than-viewport canvas** (they wait for the *whole grid* to be built),
  so every frame re-walks visible keys through boxing `HashSet<Int>`
  lookups — an `Integer` per visible tile per half per frame. A dense
  `BooleanArray` fixes both. [PR]
- **GL-7 — Assorted per-present allocations** not in the D6 table:
  `Mat4.orthoYUp` array per present, `ScreenTransform.of`'s boxed `Pair` per
  frame, an unconditional `FitTransform` per front frame, void-band
  `IntRect`s/ArrayList per reference frame, two `IntRect`s per overlay draw.
  [ready, S, batch]
- **GL-8 — `CanvasRenderer.onContextLost()` has no caller** — the §12
  recovery path is dead code, and three `forgetAll`s don't reset their
  `GlFbo`s so it would misbehave even if wired. Needs a decision on who
  detects context loss. [ready/verify, M]
- **GL-9 — An eyedropper pick at radius 4 issues 81 synchronous 1×1
  `glReadPixels`**, each a pipeline stall on the GL thread. One block read
  (or one per touched tile) would be a single stall. [ready, S-M]
- **GL-10 — `TilePool.clear` wipes the whole GlState shadow per fresh tile**
  on the stroke path; it could update just the two fields it changes.
  [ready, tiny]

### Data layer (DL-series)

- **DL-1 — The checkpoint's `.tmp` sweep races concurrent writers** (the
  flusher's next job after the barrier; a tracing-reference import on
  another dispatcher) and can delete their in-flight temp files — surfacing
  as a spurious storage-full banner or a failed import. Sweep on `load`
  only, or skip young files. [ready, S-M — verify the barrier window first]
- **DL-2 ✅(fingerprint) — `ThumbnailWriteResult` is unwired.** `Thumbnails.
  write` returns FAILED and promises the checkpoint keeps its retry flag;
  the only call site discards the result, `finishCheckpoint` clears
  `thumbDirty` unconditionally, and the dead import in CanvasViewModel is
  the tell. A failed thumbnail is never retried until an unrelated edit.
  [PR]
- **DL-3 — A paint-slot DataStore read failure is indistinguishable from
  first run**, and the defaults are then *written back over* the user's
  saved rail arrangement. The exact hazard `PreferenceFlowRecovery` guards
  `appTheme` against, with a persistent overwrite on top. [PR, with G-10]
- **DL-4 — `appScope` has no `CoroutineExceptionHandler`**, and gallery
  sync/share/export run flatten+encode+MediaStore work on it with no
  containment; a `DISPLAY_NAME` conflict or bitmap failure kills the
  process. [ready, S]
- **DL-5 — `history/` is the one project directory never swept for `.tmp`**,
  and a kill between an entry delete and its redo delete orphans `.redo`
  files forever. Both bounded, both cheap to sweep at load. [PR]
- **DL-6 — `GalleryExporter.delete` lacks `withdraw`'s URI containment**
  (R-159's hardening), and a malformed stored URI aborts the whole
  project-delete path before `store.delete` — the painting silently
  survives. Contain it; the delete *ordering* stays R6's open question.
  [ready, S]
- **DL-7 — `TileFlusher.enqueue` after `closeAndJoin` throws into the
  handler-less app scope** from two ungated call sites (layer edit racing a
  fast back-navigation). One guard. [ready, S]
- **DL-8 — `Thumbnails.write` re-reads and re-inflates every tile of every
  visible layer at every pixel-dirty checkpoint, under the checkpoint
  mutex** (~8 k reads / ~2 GiB inflate at the dense ceiling). Distinct from
  R5/D6; wants dirty-tile tracking or the band flattener. [ready, M]
- **DL-9 — A vanished/failed post-write gallery query manufactures the
  legacy "0/0 = ours" tamper state**, permanently disabling §9.2's tamper
  check for that row — the hole R-148 closed, reopened from the other side.
  [ready, S]
- **DL-10 — `committedReferenceName` re-reads and re-parses `project.json`
  on every checkpoint** even for the majority of projects with no reference;
  one `exists()` short-circuit. [ready, tiny]
- **DL-11 — `GalleryNames` truncation can split a surrogate pair**, sending
  an unpaired surrogate to MediaStore for long emoji/CJK titles. Two lines.
  [PR]
- **DL-12 — `TileFlusher.latestRevision` never prunes deleted layers'
  keys.** Bounded (~1 MB worst case); free to fix alongside
  `DeleteLayerDir`. [ready, tiny]

## 11. Session plan

Implemented this session, each on its own branch with its own PR, per the
review-loop policy in docs/EXECUTION.md — ordered by impact, cut from the
bottom if review latency bites:

1. F-1 mouse-wheel/trackpad zoom at the cursor.
2. F-8 dock top-corner rounding.
3. F-2 transparent-paper quadrant checker.
4. DL-2 wire the thumbnail-write retry.
5. G-2+G-9 rotation-safe transient dialogs.
6. G-1 Layer panel header weight fix.
7. G-10+DL-3 preference-flow IO resilience.
8. GL-4 SmudgePass per-size quads.
9. GL-6 SandwichCache dense built-flags.
10. GL-1 visible-rect culling for committed frames.
11. DL-5 history tmp/redo sweep. + DL-11 surrogate-safe names.
12. G-3 Studio card ⋮ for every card.
13. G-5+G-6+G-13 help-string polish (zh terms, en spelling, dialog title).
14. F-9 HSV marker halo. F-3 custom paper in the Layer panel.
15. F-12 hidden-tool name chip. F-10 watercolor preview. F-26 idea sparks.

Held back deliberately: F-16/F-22 (brush-feel changes without a device),
F-19/F-20/F-23 (wet-kernel changes deserve a proposal doc + shader-twin
review), A1/A2 (large), U5/U6 (architectural), GL-2/GL-3/GL-8, DL-1/DL-4/
DL-6/DL-8/DL-9, G-4 — all real, all recorded above with their shapes, none
safely landable in one session without measurement or a design pass.
