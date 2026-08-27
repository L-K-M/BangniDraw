# glm.md — a thorough review of 帮你Draw (v1.0.2)

Scope: close read of the whole app — UI (`ui/`), input (`input/`), engine
(`engine/core`, `engine/gl`), data (`data/`) — against PLAN.md, the
`docs/plan/` design, and the recorded deviations in AGENTS.md/ISSUES.md.
Method: file-by-file reading; every claim below was checked in the source, not
inferred from the docs. The engine core and the persistence path are in
excellent shape (two earlier reviews already live in ISSUES.md); this document
focuses on what remains.

Each entry ends with a verdict: **[fix-now]** (implemented from this document),
**[shovel-ready]** (small, well-scoped, an LLM can pick it up), **[idea]**
(larger or product-flavored), or **[wontfix-ish]** (recorded reasoning).

---

## 1. Bugs

### 1.1 Tool rail icons collide across tools — CONFIRMED
`ToolRail.iconFor` (`ui/canvas/ToolRail.kt`):

- pencil → `Icons.Filled.Gesture` — **the same glyph as the smudge tool**
- airbrush → `Icons.Filled.BlurOn` — **the same glyph as the blur tool**

On the FULL rail five paints and four secondary tools sit in one column, so two
pairs of slots are visually identical; in GROUPED mode the active paint (often
the pencil) sits directly above a smudge button that looks the same. Only the
content descriptions differ. This is a real usability defect for a rail whose
whole job is glance-recognition. `material-icons-extended` is already a
dependency, so distinct glyphs are free (`Pencil`, `Highlight`, `Texture`, …).
Also in the same file: `FULL_PAINT_LAST_INDEX = 4` / `FULL_ERASER_INDEX = 5`
hardcode "exactly five paints, then one eraser"; deriving the divider slots
from the actual list sizes costs nothing and survives a preset change.
**[fix-now]**

### 1.2 Per-frame allocation on the GL render path
`CanvasRenderer.visibleCanvasRect` allocates a `listOf` plus four `Pair`s on
**every** frame — it is called from `rebuildSandwichIfNeeded` inside
`compositeIntoAccum`, which runs on every front-buffered stroke frame and every
committed frame. `10-performance.md` §2.4 (and dozens of comments elsewhere in
this codebase) state the render path allocates nothing. The amounts are tiny,
but the invariant is the project's own rule, the fix is four scratch floats,
and GC pauses on a GL thread are exactly the failure mode §11's budgets exist
to prevent. **[fix-now]**

### 1.3 Eyedropper drag: one synchronous `glReadPixels` per input sample
`PixelReadback.read` is a synchronous readback (a pipeline sync). During an
eyedropper drag, `onStrokeSample` → `engine.sampleColor` →
`frontBuffered.execute { renderer.sampleColor }` runs **per motion event,
including every historical sample** — on a 240 Hz digitizer with unbuffered
dispatch that is hundreds of full-pipeline stalls per second while the pointer
moves. Taps are fine; drags can jank. Throttle previews to one read per frame
and take one final read at pen-up (the commit path must use the last position
anyway). Decision logic belongs in `engine/core` with tests, per the house
rule. **[fix-now]**

### 1.4 Soft eraser is unreachable from the rail
`ToolRail` renders exactly one eraser slot (the id matching `eraserBrushId`).
The soft eraser preset ships, is configurable in Settings as the *eraser-end*
preset, and is selectable only by opening the brush settings sheet while an
eraser is active and switching via chips. Cheap, discoverable fix: long-press
the eraser slot to toggle hard ⇄ soft. **[shovel-ready]** (a `combinedClickable`
on the eraser slot + a `viewModel.toggleEraserSoftness()`; keep the temporary
border/haptics conventions).

### 1.5 Divider constants in `LayoutSpec` vs actual slot counts
`LayoutSpec.contentHeight` budgets `FULL_TOOL_COUNT = 10` slots; the rail
renders 5 paints + 1 eraser + 4 secondary = 10 only while the built-in set is
untouched. If a built-in disappears from `brushPresets` (corrupt JSON falls
back to `DEFAULT`-only list — `open()` tolerates `ifEmpty`), the rail still
lays out but the dividers from 1.1 land mid-group. Fixed together with 1.1 by
deriving indices. **[fix-now, with 1.1]**

### 1.6 `Intent.createChooser(send, null)` in Canvas share
`CanvasScreen.sharePainting` passes a `null` chooser title while the Studio
passes the painting's title. One-line consistency fix; use the (possibly
"Untitled") painting name like `StudioScreen` does. **[shovel-ready]**

### 1.7 Studio "Deleted" toast fires before the delete is known to succeed
`StudioScreen`'s delete confirm calls `viewModel.delete(...)` (fire-and-forget
IO) and toasts "Deleted" unconditionally. A failed delete shows a lie. Have
`delete` report completion (like `saveAsNewGalleryItem` already does) and toast
on the outcome. **[shovel-ready]**

### 1.8 Hex field commits reset cursor position
`ColorPanel.ColorFields` keys `remember(color)` on the committed color, so the
moment a complete hex parses and commits, the field recomposes from state and
the cursor jumps to the start while the user may still be editing (e.g. typing
`#FF0000` then wanting to backspace). Track editing state locally and only
re-key on external color changes. Same pattern for the three channel fields.
**[shovel-ready]**

### 1.9 Non-findings worth recording (checked, not bugs)
- `onDrawFrontBufferedLayer` drains all pending batches and folds the tail
  rect in exactly once per stamping frame — correct per §8.1.
- `applyNavigation`'s nav-slot compaction on `ACTION_POINTER_UP` is correct
  (slot-0-first ordering is preserved).
- The `MotionEvent` axis reads are per-pointer (`getAxisValue(…, p, h)`) — the
  classic `actionIndex` bug is absent.
- `Stabilizer.finish`'s catch-up burst is bounded (`MAX_CATCHUP_STEPS`).
- Palm rejection's `stylusNear` is fed from `StylusState` before every down;
  `ACTION_CANCEL` clears stylus contact (the "dead to touch" bug is fixed).

---

## 2. General issues

### 2.1 `REVIEW.md` / ISSUES.md knowledge is not surfaced where agents start
Not a code bug, but this repo's most valuable asset is its recorded reasoning;
nothing links ISSUES.md from README's LLM-disclosure block. One line.
**[wontfix-ish]** (the audience is agents that already read AGENTS.md).

### 2.2 Canvas "Settings" round-trips through the Studio
`TopStrip`'s settings item calls `viewModel.leave(onSettings)` — leaving the
painting (checkpoint, navigation) to open a Studio sheet, then navigating back.
Works, but a painter five minutes into a stroke flow loses view state? (view is
`rememberSaveable`, so no). Acceptable; note only. **[wontfix-ish]**

### 2.3 Fill progress card can overlap the compact dock
The fill progress card is bottom-center with 24 dp padding; on a compact phone
in DOCK mode the dock + ledge are ~104 dp tall and the card sits on top of
them. Both are bottom-anchored; while a fill runs the user cannot reach the
dock anyway (busy gate), so this is cosmetic. **[idea]**

---

## 3. Performance (painting latency / stutter)

Overall: the stroke path is genuinely well-built — preallocated `DabRing`,
front-buffered rendering, predicted tail through copied stabilizer/generator,
sandwich cache with margin, asynchronous PBO readback, zero-allocation input
handler. Beyond 1.2/1.3 above:

### 3.1 Zoom/angle readout recomposes per navigation frame
`ResetViewPill` reads `view` (a state that changes every gesture frame), so
during a pinch the pill's `LaunchedEffect(displaced, strokeActivity, view)`
restarts per frame. Cheap composable, negligible cost — fine. But note the
**UX** consequence below (5.1). **[wontfix-ish]**

### 3.2 Gallery sync + thumbnail encode on background threads
`maybeSyncGallery`/`Thumbnails.write` run `CpuFlatten` (full-canvas RGBA) plus
PNG encode on `Dispatchers.Default`/IO every ~30 s while painting. Background
threads, so no jank on the input path, but on a 4 GB device at 4096² this is
~128 MiB peak per sync (already recorded in ISSUES.md as bounded). The GL band
flatten of `03 §10.4` remains the right fix. **[shovel-ready]** (medium-large;
supersedes both call sites when it lands)

### 3.3 Layer-thumbnail poll loop
10 Hz poll while the layer panel is open; GL renders one 128 px target through
two PBOs. Well-designed; no action. **[wontfix-ish]**

### 3.4 `LayerPanel` `LaunchedEffect(stack.layers.map(Layer::id))`
Allocates a new key list per composition of the panel — main thread, panel
only. Fine. **[wontfix-ish]**

---

## 4. Missing features

All of PLAN.md's post-v1 list is genuinely absent-but-planned (selection +
transform, shape assist, symmetry, gradient fill, image import, crop/resize,
OpenRaster export, wet brushes, grain textures, time-lapse, tile eviction).
`docs/proposals/` is empty — the numbered pre-decision docs the AGENTS.md
process promises have not been written. For the shovel-ready list, prefer these
v1-sized gaps:

### 4.1 Custom paper color at canvas creation
`NewCanvasDialog` still ships the fixed five swatches; its own comment says
"The '+' custom-paper swatch waits for the color panel (roadmap step 7)" —
step 7 (color panel + mixing dish) has landed. Wire a sixth "+" swatch that
opens a minimal hue/sv pick (or adopts the current brush color) and passes it
to `onCreate`. **[shovel-ready]**

### 4.2 Long-press eraser → toggle hard/soft (see 1.4)
**[shovel-ready]**

### 4.3 Live zoom/angle readout during navigation (see 5.1)
**[fix-now]** (implemented from this document)

### 4.4 Pressure-curve "scratchpad" in Settings
The pressure preset is three radio buttons; a small scratch canvas that draws
a test stroke under the current curve would make the choice feel real.
Delightful, self-contained (pure Compose Canvas over a few saved samples).
**[idea]**

### 4.5 Brush "save as new preset"
Users can mutate the seven built-ins but cannot fork one into a new rail slot
(`BrushPresetStore` already supports arbitrary ids; `railOrder` sorts unknown
ids after built-ins). **[idea]** (needs product wording for the rail overflow —
see 1.5's divider fix)

### 4.6 History list UI
The journal is capped and persisted but only reachable as linear undo/redo. A
"history" sheet (step count, byte budget readout already computed in
`readyState`) is a natural extension. **[idea]**

---

## 5. Visual issues / layout

### 5.1 No zoom feedback *during* a pinch
`ResetViewPill` sets `visible = false` on **every** `view` change and then
waits 150 ms — so while the user is actively pinching there is no readout at
all, only after they stop. Every serious drawing app shows a transient
zoom%/angle chip during the gesture. The handler already knows navigation is
active (`decisions.onNavigate`/`onNavigateEnd`); surface that to the host and
show a small chip that tracks the live transform. **[fix-now]**

### 5.2 Top strip layer-count badge overlaps the layers icon
`ToolCluster`'s badge Box pins the count at the icon's bottom-end corner with
no inset; "12" grows leftward over the glyph. Minor styling: inset the badge a
couple of dp and use the surface color ring so it reads as a badge at any
count. **[shovel-ready]**

### 5.3 Curve editor is four anonymous sliders
"Knot 1…4" is honest but opaque. Two cheap upgrades: (a) draw the actual curve
(`Curve` is evaluable — `BrushPreview` machinery exists) in a small graph
above the sliders; (b) label the axes "pressure → size". Pure presentation.
**[shovel-ready]** (a) / **[idea]** (b)

### 5.4 Eyedropper hover cursor
The pipette glyph (diagonal line + small circle at the tip end) is quirky and
its "tip" is offset from the actual sample point at the ring center. A
centered circle-plus-crosshair is the conventional, legible form. **[idea]**

### 5.5 Studio thumbnails
4:3 cells with `ContentScale.Fit` — a square 2048² painting floats in a
landscape cell. Fit is correct; a subtle inner border or aspect-aware cell
would tighten the shelf. **[wontfix-ish]**

---

## 6. User-friendliness / speed / convenience

### 6.1 Two-finger tap undo is undiscoverable
The first-run hint covers draw/navigate/undo — good — but the gesture itself
(two-finger tap) is the only undo gesture and is only shown once. The overflow
menu could carry a "gestures" line, or the reset pill area could flash the
shortcut on first undo-button press. Low priority. **[idea]**

### 6.2 Panels close on stroke start only on compact widths
Per plan (§8). On expanded widths the layers panel stays open while painting —
correct, but the *color* panel on a tablet steals ~320 dp of canvas; a
"collapse to strip" affordance while drawing would be nicer than binary
open/closed. **[idea]**

### 6.3 Rename dialog confirm button says "Rename" for layers too
Shared string works; a layer-specific verb ("Apply") is marginally better.
**[wontfix-ish]**

### 6.4 New Canvas dialog forgets the last-used size
Custom fields always reset to 2048. Remembering the last custom size in
DataStore is one preference away. **[shovel-ready]**

---

## 7. Aesthetics

- The saffron/indigo identity is coherent and the dark scheme is genuinely
  nice (slate, not black). No changes proposed to the palette.
- Tool buttons: the active state (border + filled container) is clear; the
  *temporary* dashed border is a nice touch.
- The hover cursor uses hard-coded white/black strokes — correct over any
  painting. Keep.
- Studio shelf could adopt the app's rounded-corner language more (cells are
  4 dp rectangles; panels are 20 dp rounded). A consistent 12 dp cell radius
  would echo the canvas chrome. **[idea]**

---

## 8. Novel / delightful / quirky ideas

1. **Mixing dish drag-to-mix**: let a drag on the dish strip smear the mix
   continuously (the slider is precise but feels like a form control; a smear
   gesture would feel like paint). The math (`MixingDish.gradient`) already
   exists.
2. **Canvas edges resist then give** (rubber-band overshoot on pan): pure
   `ViewTransform` policy, makes the canvas feel physical.
3. **Pressure ghost**: while the hover cursor is up, a faint filled disc at
   the *current* pressure-mapped size (pen-hover pressure exists on some
   digitizers) — makes brush dynamics legible before ink lands.
4. **Stroke statistics in the debug overlay already exist** — surface a
   painter-facing "latency" stat in About ("your device draws at ~X ms") for
   the enthusiasts.
5. **Time-lapse** is already planned post-v1; a cheap precursor is "playback
   of the current session's undo journal" (the journal already holds every
   tile delta — replay = apply entries at intervals).
6. **Ambient shelf**: the Studio shelf's newest painting rendered large and
   dim behind the app name — one `thumb.png`, big polish for zero risk.
7. **Haptic texture**: a clock-tick when the eyedropper crosses a hue
   milestone (the `HueMilestone` helper already exists for the picker ring).
8. **Quirky**: name the mixing dish. A single localized string ("dish" →
   "调色盘") with a small icon would give the color panel a mascot.

---

## 9. What was implemented from this document

| Entry | PR | Outcome |
| --- | --- | --- |
| 1.1 + 1.5 tool-rail icon collisions + divider indices | #22 | merged (3 review rounds: 2 applied, 1 declined) |
| 1.2 zero-allocation `visibleCanvasRect` | #23 | merged (3 rounds: 2 applied, 1 declined) |
| 1.3 eyedropper read throttling | #30 | merged (1 info round, verified) |
| 5.1 live zoom/angle readout during navigation | #35 | merged (1 round, major finding refuted) |

Cleared by parallel work (not this document): the Studio empty state now
shows on every width (#33); RGB fields stack responsively under font scale
(#39) — though 1.8's cursor-reset concern is still open underneath.
