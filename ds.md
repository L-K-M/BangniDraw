# ds.md — deep review of 帮你Draw (2026-08-27, `16d0d61`)

A full read of the app at v1.0.6-ish: every Kotlin file in `ui/`, `input/`,
`data/`, the decision cores it touches, the strings, and the plan documents.
Everything below was verified in the source (file + behaviour), not inferred
from the docs. Entries carry a confidence tag; the ones marked **[do]** are
the set I intend to implement. The rest are fodder for ANALYSIS.md or a
future proposal.

---

## 1. Bugs

### 1.1 Studio delete toast lies on failure — **[do]**
`StudioScreen.kt` (`onDelete`) toasts `studio_deleted` unconditionally while
`StudioViewModel.delete` is fire-and-forget IO. A failed delete (IO error,
locked file) still says "Deleted". `saveAsNewGalleryItem` already shows the
right pattern (outcome callback → toast). Make `delete` report completion and
toast on the outcome. Severity: low (rare), but it is a *lie about the user's
data*, which this codebase otherwise treats as a sin.

### 1.2 Canvas share chooser has no title — **[do]**
`CanvasScreen.sharePainting` passes `null` to `Intent.createChooser`; the
Studio passes the painting's title. The canvas knows `state.title` — use the
same "Untitled"-falling-back name. One line.

### 1.3 The right-angle snap setting is dead plumbing — **[do]**
`RotationSnap.snapRightAngles` and `CanvasTouchHandler.snapRightAngles`
exist, with KDoc pointing at "`Prefs.snapRightAngles`" — but no such Prefs
key, no Settings row, and `CanvasScreen` never writes the property. The
feature (§7's "snap to 90° multiples", worth having for a rotated-canvas
painter) is unreachable. Either ship it (Prefs key + Settings row + wiring in
the `LaunchedEffect(touch, …)` that already pushes prefs into the handler) or
delete the property. Shipping is better: it is one pref and one row, and the
handler property exists precisely so the pref can reach the snap.

### 1.4 The S Pen eraser-end preset is unreachable — **[do]**
`Prefs.eraserEndPreset` exists and `CanvasViewModel` collects it, but no
Settings row lets the user choose which eraser the eraser end / pen button
uses (hard vs soft). The pref can only ever hold the default. Same fix shape
as 1.3: a small Settings choice.

### 1.5 Layer badge overlaps the layers icon — **[do]**
`TopStrip.ToolCluster`: the count badge is a plain `Surface` in a
`Box(BottomEnd)` with no inset. "12" grows leftward over the glyph; the badge
sits flush in the corner. Inset it a couple of dp and give it an outline or
surface-colored ring so it reads as a badge at any count.

### 1.6 Fill progress card overlaps the dock — **[do]**
`CanvasScreen`: `FILL_PROGRESS_BOTTOM = 24` dp, but the DOCK-mode rail is 56
dp tall — the card's bottom edge sits *inside* the dock and the card is
composed after the rail, so it covers the dock's top half while a fill runs.
Not user-blocking (the busy gate disables the dock during a fill) but wrong.
Reuse the `resetBottomPadding` pattern (`DOCK_CHROME_HEIGHT`).

### 1.7 Eyedropper hover cursor's tip is not where the sample is read — **[do]**
`HoverCursor.drawPipette` draws a diagonal line whose tip circle is at
`center + (8, −8)` px; the actual sample happens at the cursor position
(center). The user aims with a tip that is 8 px off. A centered
circle-plus-crosshair (the conventional form, and what the ring-cursor path
already uses for small brushes) is the honest fix.

### 1.8 Hex/RGB fields fight the cursor while typing — **[do]**
`ColorPanel.ColorFields` keys every field on `remember(color)`; the moment a
complete hex (or all three channels) parses and commits, the field
recomposes from the committed color and the cursor jumps to position 0 while
the user is mid-edit. `draft`-style local editing state (keep the field's own
text until focus leaves, or re-key only on *external* color changes) fixes
all four fields at once.

### 1.9 Reset pill and fill card can occupy the same spot
Both are `BottomCenter` (pill at 16 dp, card at 24 dp, both on the bottom
edge in FULL/GROUPED mode). A fill with a displaced view draws the card over
the pill. Minor; the busy gate makes the pill inert during a fill anyway.
Worth a one-line clearance when the fill card is touched.

### 1.10 `snapRightAngles`' sibling: layer-panel drag has no auto-scroll
On a long layer list you cannot drag a layer past the visible viewport —
there is no edge auto-scroll. The a11y custom actions (move up/down/top/
bottom) cover the functional gap; a visible menu item would too (see 3.3).
Not a crash, but a real reachability limit for a 12-layer stack on a phone.

### 1.11 `StudioViewModel.delete` failure leaves the shelf stale — **[do]**
Same root as 1.1: `delete` never reports; also on failure the shelf is not
re-listed (the `refresh()` only runs on success). The outcome callback of
1.1 should re-list on failure too, so a failed delete doesn't silently
pretend the painting is gone.

---

## 2. Performance

### 2.1 Mid-session gallery sync flattens + PNG-encodes up to 4096² in the background
`CanvasViewModel.maybeSyncGallery` runs after every due checkpoint; a
steadily painting session hits the ceiling checkpoint every 90 s and each
due sync does a full `CpuFlatten` + `Bitmap.compress(PNG)` on the app IO
scope. At 4096² the encode alone can take multiple seconds of CPU — thermal
and battery load while the user is actively painting, plus IO-pool
contention with the flusher's deflate. The 30 s floor is the only throttle.
Mitigations worth considering: don't sync while a stroke is live or within a
few seconds of pen-up; or only sync on LEAVE/ON_STOP and at the ceiling when
the *quiet* window is also elapsed. The plan's §9.3 says the floor exists
for exactly this reason; it just may not be enough on big canvases. Needs a
device measurement before changing anything.

### 2.2 `MixingDish.gradient` recomputed on every ColorPanel recomposition
`MixingDishControls` calls `mixSteps(a, b)` (9 Mixbox mixes) on every
recomposition of the color panel — including unrelated ones (palette
switches, haptic toggles). Nine LUT mixes are cheap but not free; a
`remember(dish.a, dish.b, mixerChoice)` makes it zero. Micro.

### 2.3 Brush preview allocates a Bitmap per slider tick
`BrushSettingsSheet` debounces 50 ms, then renders a fresh
`BrushPreview` + `Bitmap.createBitmap` per change while a slider drags —
~20 fps of small allocations on `Dispatchers.Default` during the whole drag.
Fine for a sheet, but the drag is exactly when the user is staring at the
canvas; render only when the value settles (or reuse the bitmap). Micro.

### 2.4 Studio thumbnails re-decode on every scroll
Each `PaintingCell` decodes `thumb.png` (up to 512 px) with no memory cache
across cells; scrolling the shelf re-decodes per cell on IO and uploads a
fresh `ImageBitmap` per visible cell. Acceptable at shelf sizes; a small
LRU (or `remember` keyed by path) would make fling scrolling buttery. Micro.

### 2.5 Layer-panel thumbnail bitmaps on the main thread
`LayerPanel.LayerThumbnail` runs `Bitmap.createBitmap` on the main thread per
thumbnail update (every 100 ms poll while the panel is open with dirty
layers). 128² bitmaps are cheap; the pattern is the concern, not the number.

### Verified clean on the hot paths
The touch path is genuinely allocation-free (`CanvasTouchHandler` scratch
arrays, `DabRing`, no lambdas per sample), the front-buffered drain is
exhaustive at pen-up, the predicted tail is frame-coalesced, and
`EngineRenderPolicy` keeps the live frame incremental. PR #51 (per-dab
allocation removal) is real. The remaining latency levers are device-side
(120 Hz, unbuffered dispatch) and the known `NavigationStep` two-transform
allocation, both documented in the roadmap.

---

## 3. Missing features and UX gaps

### 3.1 Long-press the eraser slot toggles hard ⇄ soft — **[do]**
The soft eraser preset ships but is reachable only through the brush
settings sheet; the rail renders exactly one eraser. A long-press on the
eraser slot swapping `eraserBrushId` between `HARD_ERASER_ID` and
`SOFT_ERASER_ID` with a haptic tick puts both erasers one gesture away.
`ToolRail.brushSlot` needs a `combinedClickable`; the toggle is ViewModel
state (and, per 1.4, the natural companion: make the eraser *end* follow
this choice too — see 3.2).

### 3.2 Eraser-end preset choice (see 1.4) — **[do]**
Wire `Prefs.eraserEndPreset` to a Settings row (Hard / Soft eraser), so the
S Pen's eraser end and barrel button use the eraser the user actually wants.
Two small PRs that pair naturally.

### 3.3 Layer menu lacks Move up/down/top/bottom — **[do]**
The a11y `customActions` already implement all four moves
(`LayerPanelOrder.actions`); the visible menu only offers rename, duplicate,
merge, clear, locks, blend modes, delete. Reordering is drag-only, which is
awkward on a phone sheet with a long list and impossible without a touch
screen. Add the four items to `LayerMenu` (strings exist: `layer_move_up`
etc. — they are currently a11y-only).

### 3.4 No canvas title anywhere on the canvas screen
The painting's name appears only in the accessibility description. You
cannot tell which painting you are in, and rename (overflow menu) gives no
feedback that it worked. A slim title in the top strip (or a rename toast)
would close the loop. The strip is full; a title readout that appears
briefly after rename is the cheapest honest version.

### 3.5 No way to see the current layer's name on compact
The strip's badge shows "2/4"; the active layer's name is panel-only. On a
phone the layer panel is a sheet that dismisses on select — so selecting a
layer hides its name. Minor; a long-press on the badge could show it.

### 3.6 No "100%" (actual-size) zoom action
Reset-to-fit exists; for pixel work the other anchor is 1:1. A long-press on
the reset pill (or a second pill state) that jumps to 100 % at the view
center is cheap and exactly what the `ViewTransform` math is for.

### 3.7 Mouse wheel does not zoom
`CanvasTouchHandler.onTouch` returns false for `ACTION_SCROLL`; a trackpad
user (Samsung keyboard cover, desktop-ish setups) cannot zoom. Wheel zoom at
the pointer position is the standard behaviour and slots into the existing
`NavigationStep`/`ViewTransform` path with a synthetic pan-anchor. Small,
delightful on tablets.

### 3.8 No composition guides (rule of thirds / center) — **[do?]**
The single most-requested drawing-app aid that is also trivial: a toggleable
non-persistent grid overlay (thirds + center cross) drawn in Compose over
the SurfaceView, from the overflow menu. No document change, no journal
entry, ~40 lines. Strong value-per-line.

### 3.9 Post-v1 roadmap (unchanged, restated)
Selections + transform, rulers/shape assist, symmetry, gradient fill,
wet/watercolor, brush grains, image import, crop/resize, tile eviction,
OpenRaster export, time-lapse, custom brush import, pressure calibration —
all in `docs/plan/12-roadmap.md` §5. None shipped yet; the list is the
product's future and nothing here displaces it.

---

## 4. Visual and layout issues

### 4.1 Compact panels have no close button
`LayerPanel` and `ColorPanel` on a phone are ~85 %-width full-height sheets;
dismissal is the sliver of scrim on the side. A close X in the header (the
layer panel has one in spirit — its More menu) would make dismissal obvious.
Material 3 sheets normally carry one.

### 4.2 Studio cards are flat
Bordered rectangles, no elevation, no hover state on wide screens. The
`Improve Studio cards` PR (PR #46) helped; a touch of tonal elevation on the
thumbnails would make the shelf read as a shelf. Aesthetic, low priority.

### 4.3 NavHost has no transition
Studio ⇄ Canvas swaps instantly. A short fade/crossfade would soften the
mode change — the canvas screen's own chrome already animates in/out.

### 4.4 The `∅` transparent-paper glyph
`paper_transparent_symbol` renders as "∅" in the New Canvas dialog's swatch —
clever but cryptic to a first-time user; the checkerboard stand-in colour
plus a label under the swatches would be clearer. Very minor.

### 4.5 Focus-handle visibility
The 6 dp handle at 35 % alpha on the canvas edge is easy to miss; the
drag-in threshold (24 dp) is generous but undiscoverable. A first-use nudge
(when focus mode is first entered) would teach it. Minor.

---

## 5. Aesthetics

- The theme (warm paper light, slate dark, saffron-on-indigo accent) is
  coherent and quiet — genuinely good. No changes recommended.
- The rail's active-button border + `railButtonColors` two-tone is readable
  at a glance.
- The dock mode (phone) is plain: ten… six buttons in a row with no
  dividers and no slider affordance (sliders move to the ledge). The
  transition between dock and ledge modes is abrupt — a shared surface
  treatment would help. Minor.
- The hover cursor's black+white double ring reads well on any paper;
  keep it.

---

## 6. Novel / delightful ideas

1. **Rule-of-thirds / center grid toggle** (3.8) — cheapest big win.
2. **Two-finger double-tap resets the view** — gesture parity with the
   two-finger-tap undo; the arbiter already owns tap timing.
3. **Wheel zoom at the pointer** (3.7).
4. **Layer "solo"**: long-press the eye icon = hide every other layer.
   Procreate-style, one gesture, no new UI.
5. **Palette swatch drag-to-reorder** — the menu's move-left/right is
   functional but feels like a form; a drag affordance on the swatch strip
   is delightful. (The layer drag machinery is reusable.)
6. **"Actual size" (100 %) zoom anchor** (3.6).
7. **Export current layer as PNG** — flatten one layer with alpha; asset
   makers' favourite. Cheap (`CpuFlatten` already takes a layer set… it
   flattens the stack; a single-layer variant is a small addition).
8. **"Clear painting" journaled action** — today a blank restart means
   delete + create; a whole-document clear (one `ClearLayer`-style entry
   across layers) is honest and undoable.
9. **Ambient Studio shelf** (already in ANALYSIS.md): the newest painting's
   thumb rendered large and dim behind the app name. One image, big polish.
10. **Session playback from the undo journal** (already in ANALYSIS.md) —
    cheap precursor to the planned time-lapse.
11. **Mixing-dish drag-to-smear** (already in ANALYSIS.md) — `MixingDish`
    already computes the gradient; make the strip draggable.
12. **Canvas edge resistance / rubber-band** (already in ANALYSIS.md).
13. **Hue-milestone haptic on eyedropper drag** (already in ANALYSIS.md) —
    `HueMilestone` exists for the ring; reuse it on the picker drag.
14. **Pressure scratchpad in Settings** (already in ANALYSIS.md) — draw a
    test stroke under the current curve before committing to it.
15. **History list UI** (already in ANALYSIS.md) — the journal is deep but
    invisible; a sheet listing recent entries is the natural next step after
    the long-press readout.
16. **GL band flatten** (already in ANALYSIS.md) — supersedes both CPU
    flatten call sites and removes the ~128 MiB peak. The biggest perf item
    left in v1.

---

## 7. Verified clean (do not re-litigate)

- Front-buffered drain and tail-rect folding (§8.1); nav-slot compaction on
  `ACTION_POINTER_UP`; per-pointer axis reads; the stabilizer's bounded
  catch-up; palm rejection's `stylusNear` feed; the `MotionEventPredictor`
  ownership decision; zoom-readout liveness; the whole ISSUES.md review-2
  list (matches ANALYSIS.md's "Verified clean").
- Storage: tmp+rename+fsync ordering, `project.json` last, journal prefix
  validation, delete-vs-checkpoint race handling, gallery row ownership.
- Threading: `@Volatile` document handoff, flusher single worker, action
  gate ordering, leave handoff/grace, RMW capture FIFO.
- The dock's slot count (6 × 48 dp fits a 320 dp phone); the ledge mode's
  geometry; the exclusion rect math (200 dp cap).

---

## 8. Implementation plan (what I will build)

Ordered by value/risk; each in its own branch + PR per the repo's rules:

| # | Entry | PR |
| --- | --- | --- |
| 1 | 1.2 share chooser title | one line, `CanvasScreen.kt` |
| 2 | 1.5 layer badge inset | `TopStrip.kt` |
| 3 | 1.1 + 1.11 honest delete outcome + re-list on failure | `StudioScreen` + `StudioViewModel` + test |
| 4 | 3.1 eraser long-press toggle (hard ⇄ soft) | `ToolRail` + `CanvasViewModel` + strings + test |
| 5 | 1.6 fill card clears the dock | `CanvasScreen.kt` |
| 6 | 3.3 layer menu move items | `LayerPanel.kt` + strings |
| 7 | 1.3 right-angle snap setting | `Prefs` + `SettingsSheet` + wiring + strings + test |
| 8 | 1.4 eraser-end preset setting | `SettingsSheet` + strings |

Items 1.7 (hover pipette), 1.8 (field cursor), 1.9 (pill/card), 2.2–2.5,
3.4–3.8, 4.x, and the ideas section go to ANALYSIS.md for future sessions —
they are real but need either product judgment, a device, or more care than
a drive-by PR warrants.

After the PRs merge, this document's contents merge into ANALYSIS.md with
the cleared items removed and the new shovel-ready entries consolidated.
