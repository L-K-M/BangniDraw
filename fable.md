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

## 10. Session plan

Implemented this session, each on its own branch with its own PR, per the
review-loop policy in docs/EXECUTION.md:

1. F-1 mouse-wheel/trackpad zoom at the cursor.
2. F-2 transparent-paper quadrant checker (three sites).
3. F-3 custom paper colour in the Layer panel's paper menu.
4. F-8 dock top-corner rounding.
5. F-9 HSV marker two-tone halo.
6. F-12 transient tool-name chip for hidden-tool selection.
7. F-10 watercolor CPU preview in the brush sheet.
8. F-26 Studio empty-state drawing prompts.

Held back deliberately: F-16/F-22 (brush-feel changes without a device),
F-19/F-20/F-23 (wet-kernel changes deserve a proposal doc + shader-twin
review), A1/A2 (large), U5/U6 (architectural), everything marked [verify].
