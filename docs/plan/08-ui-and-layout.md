# 08 — UI and layout

**What this document covers.** The two screens (Studio, Canvas), every piece
of chrome on them, how that chrome reshapes itself across window sizes, the
behaviour rules that keep chrome from ever getting between the pen and the
picture, the design language ("Quiet Studio"), the icon treatment, accessibility,
strings, and the Compose component list for `ui/`. It expands PLAN.md §3.4 and
§5; it does not repeat the engine (`03-canvas-engine.md`), tool parameters
(`04-tools.md`), gestures (`07-input-and-stylus.md`) or mixing math
(`09-color-and-mixing.md`) — it says where those surface in the UI and how.
Where a rule here decides something the ViewModel must enforce, the rule is
stated as a `UiState` invariant so it can be unit-tested (PLAN.md decision 7).

## 1. Layout decisions are data, not composables

Everything that *decides* where chrome goes is a pure JVM function in
`ui/canvas/LayoutSpec.kt`, computed once per configuration change and handed
to the composables as a value. Composables only place things. This is
Meltorama's `DockState` lesson: layout logic in a data class is testable with
plain JUnit; layout logic scattered across `if (compact)` branches in
composables is not.

```kotlin
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }      // from window size class
enum class Hand { LEFT, RIGHT }                           // Prefs.handedness
enum class RailMode { FULL, GROUPED, SHORT, DOCK }
enum class PanelMode { FULL_HEIGHT_SHEET, SIDE_SHEET, FLOATING }

data class LayoutSpec(
    val railMode: RailMode,
    val railSide: Hand,            // the rail hugs the drawing hand's side
    val panelSide: Hand,           // panels open on the rail side
    val panelMode: PanelMode,
    val sliderPlacement: SliderPlacement,   // IN_RAIL or LEDGE
    val gridMinCell: Dp,           // Studio only
) {
    companion object {
        fun forWindow(width: WidthClass, heightDp: Int, hand: Hand): LayoutSpec
    }
}
```

| Input | `railMode` | `panelMode` | Sliders |
| --- | --- | --- | --- |
| COMPACT width (any height) | DOCK — bottom row, 6 grouped slots | FULL_HEIGHT_SHEET from the panel side, width `min(320dp, 85 %)` | LEDGE — one thin horizontal slider directly above the dock, with a size/opacity toggle chip (`01-product.md`: one slider visible at a time on compact) |
| MEDIUM/EXPANDED, rail height < `SHORT_MIN` (288 dp) — split-screen slivers | DOCK, as compact | FULL_HEIGHT_SHEET | LEDGE, one slider |
| MEDIUM/EXPANDED, rail height `SHORT_MIN` … < `GROUPED_MIN` (phone landscape) | SHORT — 6 grouped slots, 48 dp, no gaps, no padding | SIDE_SHEET 300 dp, full height | LEDGE at the bottom edge, opposite the rail, two sliders |
| MEDIUM/EXPANDED, rail height `GROUPED_MIN` … < `FULL_MIN` | GROUPED — 6 grouped slots + sliders | SIDE_SHEET 300 dp | IN_RAIL, 120 dp long |
| MEDIUM/EXPANDED, rail height ≥ `FULL_MIN` (tablets) | FULL — every tool that fits (paint presets up to the height's `paintSlotBudget`, the rest in the settings sheet's chip row) + sliders | FLOATING 320 dp card beside the rail | IN_RAIL, 160 dp long |

"Rail height" is window height minus the top strip (48 dp plus the status-bar
inset it pads for). The tool slot is `slot` = 48 dp on MEDIUM and 56 dp on
EXPANDED (`01-product.md` T1: rail tool buttons ≥ 56 dp on expanded widths),
gaps are 4 dp, a divider is 1 dp with 4 dp margins (9 dp), rail padding is
24 dp. The thresholds are these sums, not round numbers, and `LayoutSpecTest`
asserts each mode's content height against them:

| Mode | Height budget | MEDIUM (slot 48) | EXPANDED (slot 56) |
| --- | --- | --- | --- |
| SHORT | 6 × 48 (gap and padding collapse to 0; the slot stays 48 dp because a 360 dp phone in landscape has 288 dp of rail: 360 − 48 − 24 status bar) | `SHORT_MIN` = 288 | 288 |
| GROUPED | 6 × slot + 5 × 4 + 9 + 120 + 24 | `GROUPED_MIN` = 461 | 509 |
| FULL | 10 × slot + 9 × 4 + 2 × 9 + 160 + 24 | `FULL_MIN` = 718 | 798 |

`FULL_MIN` is sized for the v1 catalogue (five paints, one eraser, four
secondary tools). A larger catalogue does not stretch the rail past the
window: `LayoutSpec.paintSlotBudget` solves `paints·slot + (paints−1)·gap +
non-paint ≤ rail height` for the number of paint slots that fit (exactly
five at each `FULL_MIN`, capped by the loaded paint count), the active preset always keeps a slot
(`RailSlotPolicy`), and the remaining presets stay reachable through the
settings sheet's chip row — the same path GROUPED/SHORT/DOCK already use
for every preset but the active one.

SHORT is the one place the rail keeps 48 dp slots on an expanded width (an
S-series Ultra in landscape is ≥ 840 dp wide and ~288 dp tall); T1's 56 dp
holds for GROUPED and FULL. On the device landscape in `research-facts` this
gives: Tab S9 landscape (≈ 885 dp of rail) FULL, 10.4" tablet FULL in
portrait and GROUPED in landscape (≈ 750 dp of rail at expanded width), Z
Fold inner GROUPED or FULL depending on orientation, phone landscape SHORT,
phone portrait DOCK. Foldables and multi-window fall out of the same
function because it is fed the *window*, never the device.

**Grouped slots.** When the rail can't hold ten tools it holds six: **Brush**
(the current brush preset — pencil, ink pen, paintbrush, airbrush or marker),
**Eraser**, **Smudge**, **Blur**, **Fill**, **Eyedropper**. Tapping the
active Brush slot opens the settings sheet whose header is the preset row, so
switching pencil → marker on a phone is tap-tap. The eraser keeps its own slot
in every mode because the S Pen's eraser end and button map to it and the user
needs to *see* that state (`07-input-and-stylus.md`).

## 2. Studio

```
COMPACT (phone portrait, 360 dp)      MEDIUM (600–840 dp)                EXPANDED (≥ 840 dp)
┌──────────────────────────┐          ┌──────────────────────────────┐    ┌───────────────────────────────────────────┐
│ 帮你Draw            ⚙   │          │ 帮你Draw                  ⚙  │    │ 帮你Draw                               ⚙  │
│ 12 paintings · 340 MB ·  │          │ 12 paintings · 340 MB · 5.1 GB│    │ 12 paintings · 340 MB · 5.1 GB free       │
│ 5.1 GB free              │          │ free                          │    │                                           │
│ ┌─────────┐ ┌─────────┐  │          │ ┌──────┐ ┌──────┐ ┌──────┐   │    │ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌────│
│ │  thumb  │ │  thumb  │  │          │ │  +   │ │thumb │ │thumb │   │    │ │  +   │ │thumb │ │thumb │ │thumb │ │thum│
│ │         │ │         │  │          │ │ New  │ │      │ │      │   │    │ │ New  │ │      │ │      │ │      │ │    │
│ └─────────┘ └─────────┘  │          │ └──────┘ └──────┘ └──────┘   │    │ └──────┘ └──────┘ └──────┘ └──────┘ └────│
│ Cat study    Sketch 3    │          │          Cat study  Sketch 3  │    │          Cat study  Sketch 3   Harbour ... │
│ 5 min ago    yesterday   │          │          5 min ago  yesterday │    │          5 min ago  yesterday  3 days ago │
│ ┌─────────┐ ┌─────────┐  │          │ ┌──────┐ ┌──────┐ ┌──────┐   │    │ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌────│
│ │         │ │         │  │          │ │      │ │      │ │      │   │    │ │      │ │      │ │      │ │      │ │    │
│ …                   (+)  │          │ …                             │    │ …                                         │
└──────────────────────────┘          └──────────────────────────────┘    └───────────────────────────────────────────┘
```

- **Grid.** `LazyVerticalGrid(GridCells.Adaptive(gridMinCell))`, `gridMinCell`
  = 150 / 180 / 220 dp for compact / medium / expanded. Each cell: a paper-white
  card with 1 dp hairline (no drop shadow — the picture provides the contrast),
  thumbnail aspect-fit inside a 4:3 box on a subtle checkerboard for transparent
  paper, title (one line, ellipsised), relative time (`DateUtils`
  relative span — "5 min ago", "yesterday"; zh-Hans comes from the platform).
  Sorted by `updatedAt` descending (`06-document-and-persistence.md` §7);
  `key = projectId` so a deletion animates the survivors closing ranks.
- **+ is the first tile on medium/expanded, a FAB on compact.** A first tile is
  discoverable and in the reading order; on a phone the same tile would push
  the newest painting below the fold, and a bottom-end FAB is thumb-reachable.
  Both open `NewCanvasDialog`.
- **Hold menu** (long-press a tile, haptic tick, `DropdownMenu` anchored to
  the tile): **Open**, **Rename**, **Duplicate**, **Share**, **Delete**. Rename
  is not in PLAN.md §5; it is here because a painting otherwise carries
  "Sketch 7" forever and the Canvas overflow is the wrong place to name a
  thing you're not looking at. Duplicate copies the folder (tiles, not the
  undo history — `06-document-and-persistence.md` §8) and appends " copy";
  Share hands the flattened PNG through `ShareCache`
  (`06-document-and-persistence.md` §9.5).
- **Delete confirm dialog.** Title "Delete "Cat study"?", body "The painting and
  its undo history are removed from this device.", a checkbox **"Also delete
  the copy in the gallery"** (default *off* — the gallery copy is the user's
  and deleting it must be an explicit tick; principle 3), buttons Cancel /
  Delete (Delete in error red). The checkbox is hidden when `galleryUri` is
  null. After deletion a toast "Deleted" — no undo-delete in v1, which is why
  the dialog exists.
- **Storage readout line** under the title: "{n} paintings · {bytes} on
  device · {free} free". Bytes = sum of project folder sizes, computed on IO on
  every Studio entry and cached in `StudioUiState`; free = `StatFs` of
  `filesDir`. It answers the only question that ever justifies deleting.
- **Settings/About** is the gear in the top app bar; that screen is listed in
  PLAN.md §5.3 and specified in `12-roadmap.md` step 10 — a plain `LazyColumn`
  of preference rows, not designed further here.
- **Empty state**: centred, no illustration: "No paintings yet." / "Tap + to
  start one. Everything you draw is saved as you go." The second line is the
  only place the app explains autosave, because the Canvas will never prompt.

### 2.1 New Canvas dialog

```
┌────────────────────────────────────────────┐
│ New painting                               │
│                                            │
│ ( ) Phone sketch  1080 × 1920   fits 16    │
│ (•) Square 2048   2048 × 2048   fits 16    │
│ ( ) Tablet        2560 × 1600   fits 16    │
│ ( ) Large 4096    4096 × 4096   fits 7     │
│ ( ) Custom        [4096] × [4096]  fits 7  │
│                   max 4096 × 4096 on this  │
│                   device                   │
│                                            │
│ Orientation   [ ▭ Landscape ][ ▯ Portrait ]│
│ Paper         ● ● ● ● ▩  ＋                │
│                                            │
│                     [ Cancel ]  [ Create ] │
└────────────────────────────────────────────┘
```

- Presets come from `CanvasPresets.forDevice(result: MemoryBudget.Result):
  List<CanvasPreset>` (pure JVM, `engine/core`; the list and the signature
  are `10-performance.md` §4's): Phone sketch 1080×1920, Square 2048²,
  Tablet 2560×1600, Large 4096², plus Custom bounded by
  `result.maxCanvasEdge`. The default radio is the largest preset that fits
  the device's screen. The layer counts in the mock are illustrative (a
  4 GB device per `05-layers.md` §6.3's table); the code, not the mock, is
  the truth. Presets above `maxCanvasEdge` render disabled with "Too large
  for this device".
- Every row shows **"fits N layers"** = `MemoryBudget.compute(device,
  size).maxLayers` (decision 4; the one budget API, named as in
  `10-performance.md` and `05-layers.md`). The Custom row recomputes it on
  every keystroke; a side above `maxCanvasEdge` disables Create and the
  helper text says why in `05-layers.md` §6.4's sentence. `maxCanvasEdge`
  only admits sizes at which `MIN_USEFUL_LAYERS` (4) plus the stroke-buffer
  reserve fit (`10-performance.md` §4), so a size the dialog offers can
  always hold a few layers. N is also what the layer
  panel later shows as its cap, so the two never disagree.
- **Orientation** swaps width/height (a segmented button; hidden for square
  presets). **Paper**: white, warm white, mid-gray, black, transparent
  (checkerboard swatch), and **+** which opens the color panel's picker in a
  nested dialog. Paper is a document property, not a layer
  (`05-layers.md`); the swatch row is the only place it is chosen in v1.
- Create → `ProjectStore.create(spec)` → navigate to Canvas with the new id.
  No title field: the painting starts as "Sketch {n}"
  (`Prefs.nextSketchNumber`, `06-document-and-persistence.md` §10) and is
  renamed from the Studio. Fewer fields between the tap and the first stroke.
- The dialog is the same composable at every width; on compact it is a
  full-width `AlertDialog`, otherwise a 420 dp one. Nothing else adapts.

## 3. Canvas screen

```
EXPANDED, right-handed, RailMode.FULL, layer panel open (FLOATING)
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ‹   ↶  ↷        ▤ ■ ⋮                                                            │  top strip 48 dp
├──────────────────────────────────────────────────────────────────┬──────┬────────┤
│                                                                  │Layers│ ✎ pencil│
│                                                                  │ 3/16 │ ✒ ink  │
│                                                                  │  ＋  │ 🖌 brush│  ← active: saffron ring
│                                                                  │──────│ ⌇ air  │
│                        (canvas, edge to edge,                    │▣ Line│ ▬ marker│
│                         nothing persistent within                │  ◉ 100│────────│
│                         the central 60 % × 60 %)                 │▣ Colo│ ◻ eraser│
│                                                                  │  ◉ 80 │────────│
│                                                                  │▣ Sket│ ☁ smudge│
│                                                                  │  ◉ 40 │ ◌ blur │
│                                                                  │──────│ ◆ fill │
│                                                                  │      │ ⊙ pick │
│                                                                  │      │────────│
│                                                                  │      │ ┃ ┃    │  size · opacity
│                          [ ↻ Reset view ]                        │      │ ┃ ┃    │  160 dp
└──────────────────────────────────────────────────────────────────┴──────┴────────┘

MEDIUM, phone landscape, RailMode.SHORT           COMPACT, phone portrait, RailMode.DOCK
┌───────────────────────────────────────────┐     ┌──────────────────────────┐
│ ‹  ↶ ↷              ▤ ■ ⋮                 │     │ ‹  ↶ ↷        ▤ ■ ⋮     │
├─────────────────────────────────────┬─────┤     ├──────────────────────────┤
│                                     │ 🖌  │     │                          │
│                                     │ ◻   │     │                          │
│           (canvas)                  │ ☁   │     │        (canvas)          │
│                                     │ ◌   │     │                          │
│                                     │ ◆   │     │                          │
│ ━━━━━━━━●━━━━━━  ━━━━━━━━━━●━━━━     │ ⊙   │     │                          │
└─────────────────────────────────────┴─────┘     │ ━━━━━━━━━━━●━━━━━━ [size]│  ledge (one slider + toggle)
  ledge: size · opacity                            │ 🖌  ◻   ☁   ◌   ◆   ⊙   │  dock 56 dp
                                                   └──────────────────────────┘
```

Left-handed mirrors everything horizontally: rail and panels on the left,
ledge sliders start from the right, the top strip's *tool* cluster moves to
the left and the navigation cluster to the right. `LayoutSpec.railSide` is
the single source; composables use `Row` with `reverseLayout`-style ordering
driven by it, never a second set of composables.

### 3.1 Top strip

48 dp tall, `surfaceContainer` background, 1 dp hairline below, drawn as an
overlay over the SurfaceView (Compose overlays sit above a SurfaceView
without `setZOrderOnTop` — `research-facts`). Window insets: the strip pads
for the status bar; the canvas does not (it runs under everything).

| Slot | Icon | Action | Notes |
| --- | --- | --- | --- |
| Back | ‹ | leave: `CanvasViewModel.leave()` → checkpoint + gallery flush, then pop | same as hardware back when no panel is open |
| Undo | ↶ | `undo()` | disabled state from `HistoryJournal.canUndo`; long-press shows "n steps" |
| Redo | ↷ | `redo()` | |
| Layers | ▤ | toggle `Panel.LAYERS` | badge shows active layer index when panel closed |
| Color | ■ | toggle `Panel.COLOR` | the icon *is* the current color swatch (24 dp rounded square with hairline) |
| Overflow | ⋮ | menu: Share…, Export PNG / JPEG, Focus mode, Rename, Settings | one level; nothing inside opens another menu |

Six slots (PLAN.md §3.4, `01-product.md` S2); the brush settings sheet has
no strip button — it opens by tapping the active tool again (§3.2).

Undo/redo live here rather than on the rail because they are not tools: they
are used with the off hand while the pen keeps drawing, and the two-finger /
three-finger taps (`07-input-and-stylus.md`) are their gesture twins.

### 3.2 Tool rail

- One tool column. SHORT is `slot` + 8 dp wide; GROUPED and FULL are 104 dp
  wide so two 48 dp slider slabs plus 4 dp side padding cannot overlap.
  Buttons are `slot`-sized targets (48 dp on MEDIUM, 56 dp on EXPANDED,
  §1) with a 40 dp rounded (radius `r.md`) visual; gap 4 dp (0 in SHORT).
  Icons are 24 dp `ImageVector`s — no icon font or bitmaps. **Rail and dock
  slots carry no text label** (the tool
  names in the mocks are annotations): the name lives in the
  `contentDescription` and in the settings sheet header, so no locale can
  overflow a slot. Where a chip *does* carry text (the DOCK ledge's
  size/opacity toggle, the preset row of §3.5), it is sized for the longest
  shipped translation (zh-Hans 不透明度 is five glyphs) and falls back to
  its icon + description when the text would clip at 200 % font scale
  (`01-product.md` A5).
- **Active tool**: filled `accentContainer` (saffron at 20 % over surface)
  plus a 2 dp saffron ring; a `stateDescription` of "selected" for TalkBack.
  Never colour alone.
- **Tap active again → settings sheet** (`Panel.BRUSH_SETTINGS`); tap another
  tool switches with a haptic tick and closes any open sheet.
- **Eraser slot** highlights in a second way — a dashed ring — while the S
  Pen eraser end or button is *temporarily* selecting it (`StylusState`), so
  the user can tell "I chose the eraser" from "I am holding the pen upside
  down".
- **Sliders** (`ThinSlider`): two vertical tracks side by side at the rail's
  foot, each a 4 dp track inside a 48 dp hit slab (`01-product.md` T2 —
  thin to look at, fat to touch), 160 dp long (120 dp in GROUPED), thumb
  20 dp. The left/upper one is **size**, the other **opacity**; they edit
  the *active tool's* `size` and `opacity` (`04-tools.md`). Size maps
  logarithmically (`sizeMin` … the preset's `sizeMax`), opacity linearly
  0–100 %. Touching the slab jumps the thumb, then drags; once the finger
  has left the slab by more than 32 dp perpendicular (measured from the
  slab's edge, not the track) the gain drops to 0.25× for fine adjustment
  (the finger is off the control, so nothing is occluded).
  While dragging, a **preview blob** appears beside the slider: a circle at
  the true on-screen size (capped at 96 dp, with the number "142 px" beneath
  when capped) filled with the current colour at the current opacity, on a
  checker chip. Release → blob fades in 150 ms. The blob is a Compose
  overlay; the engine is not involved.
- **Ledge** (SHORT and DOCK): the same sliders horizontal (4 dp track,
  48 dp slab), sitting 8 dp above the dock / bottom edge. In SHORT both
  show, each taking half the width minus margins, on the side opposite the
  rail. In DOCK only **one** shows at a time (`01-product.md`, compact
  phone row) with a 48 dp toggle chip at its end reading "size" / "opacity";
  the choice is `UiState.ledgeSlider`, remembered per session. Same preview
  blob, above the thumb.

### 3.3 Layer panel

Slides in from the rail side (spring, ~220 ms) as `panelMode` dictates.
Content is identical in every mode; only the container differs.

```
┌ Layers  3 / 16 ────────────── ＋ ┐   header: count / MemoryBudget cap, add
│ ≡ ▣ Line art        👁  100  ⋮  │   row 64 dp: drag handle, 48 dp thumb, name, eye, opacity, overflow
│ ≡ ▣ Colour          👁   80  ⋮  │   ← selected: indigo left bar + indigoContainer background
│ ≡ ▣ Sketch          ◌    40  ⋮  │   hidden layer: dimmed row, eye icon crossed
│ ▢ Paper (warm white)            │   paper row: not a layer, tap = change paper colour
└─────────────────────────────────┘
```

- **Tap a row** selects it (indigo). **Tap the eye** toggles visibility.
  **Tap the opacity number** replaces the name with an inline `ThinSlider`
  until the row loses selection. **Drag the handle** (or long-press anywhere
  on the row) reorders with a haptic tick on each slot change; the drop is
  one `LayerStack.move` journal entry (`05-layers.md`).
- **Per-row overflow** ⋮: Rename, Duplicate, Merge down, Clear, Alpha lock
  (checkable), Lock (checkable), Blend mode ▸ (a flat list of the eight
  modes in `05-layers.md` §4), Delete. Behaviour is `05-layers.md` §7:
  refused actions stay visible and tapping one shows the `Refusal` hint
  (Delete of the only layer → "Can't delete the only layer — Clear
  instead?", a locked layer → "Layer is locked"); Delete of a layer needs no
  confirm — it is undoable; Merge down confirms only when a blend mode is
  not normal (05 §4.1). The header overflow holds Flatten, which always
  confirms (05 §4.4).
- **＋** adds an empty layer above the selected one and selects it. At the
  cap ＋ (and Duplicate) stay enabled; tapping shows the one-line
  explanation with the numbers from `05-layers.md` §6.4 ("This 4096×4096
  canvas allows 15 layers on this device. Merge or delete a layer to add
  one.") as a `TransientToast`. Nothing dims silently (decision 4).
- Thumbnails come from the GPU `Thumbnail` pass (`05-layers.md` §7),
  refreshed at most every 500 ms per layer while the panel is open.
- The panel is 300–320 dp wide; on compact it is a full-height sheet of
  `min(320dp, 85 %)` width so a strip of canvas always remains tappable.

### 3.4 Color panel

The picker is a **hue ring around an SV square** (not a wheel+triangle):
the square gives saturation and value independent axes, so "same hue, a bit
lighter" is a straight vertical drag — the move a painter makes most; the
triangle couples them. The ring keeps hue at a constant, thumb-friendly
radius, and the square inside the ring wastes no space, which matters at
300 dp. A hue *strip* (the other common layout) was rejected because a 300 dp
strip resolves 360° at 0.8° per dp, half what the ring's circumference gives.

```
┌ Colour ──────────────────────────────┐
│        ╭───────────────╮             │
│      ╱ ┌─────────────┐ ╲             │   ring: hue; square: S (x) × V (y)
│     │  │             │  │            │   crosshair thumb, 24 dp, hairline halo
│     │  │      +      │  │            │
│      ╲ └─────────────┘ ╱             │
│        ╰───────────────╯             │
│ ■■ ■  #3A6FD8   R 58  G 111 B 216    │   current / previous chips; hex + RGB fields (editable, validated)
│ [Painter's] [Basic] [Recent] [+]     │   palette switcher chips (09 §9.1)
│ ● ● ● ● ● ● ● ●                      │   active palette's swatches, 8 per row on compact;
│ ● ● ● ● ● ● ●                        │   tap = select, long-press = replace / delete / move
│ Mixing dish                          │
│ ● ━━━━━━━━●━━━━━━━━━━━━ ●            │   wells A, B and the t slider
│ [ ■ ■ ■ ■ ▣ ■ ■ ■ ■ ]                │   9-step strip A→B (09 §9.3)
│        ( use )  ( add to palette )   │
└──────────────────────────────────────┘
```

- **Fields.** Hex accepts `#RGB`, `#RRGGBB`, `RRGGBB`; RGB fields 0–255.
  Editing any field updates the picker; the picker updates the fields on
  drag-end (not per frame, so the text doesn't flicker under the thumb).
- **Model.** The panel renders `ColorUiState` (current, previous,
  palettes, active palette id, dish, mixer flag), a slice of
  `CanvasUiState` written by `CanvasViewModel` (the one writer,
  `02-architecture.md` §4.2);
  palettes, their persistence (`PaletteStore`, `filesDir/palettes/`), the
  built-in **Painter's** and **Basic** sets and the **Recent** palette (the
  last 16 colours painted with, Prefs `recent_colors`) are all
  `09-color-and-mixing.md` §9's — this section only lays them out. Long-press
  the current chip adds it to the active palette; a swatch long-press opens
  the replace / delete / move menu with a haptic tick.
- **Mixing dish.** Layout of `09-color-and-mixing.md` §9.3: wells A and B
  (tap = fill with current, long-press = eyedrop into it), the `t` slider,
  the 9-step strip from `ColorMixer.mix(a, b, i/8)` — `MixboxMixer` by
  default, `RgbMixer` if the user switched `Prefs.mixer` in Settings (`09-color-and-mixing.md` §4) — and the **use** /
  **add to palette** buttons. A hint under the strip on first open: "Blue
  and yellow make green here." The dish is where the user *learns* that the
  brush mixes, which is why it lives in the panel and not behind a menu.
- Selected swatch: indigo ring. Every swatch is 32 dp visual inside a 48 dp
  target (8 dp padding; `01-product.md` A1 — nothing clickable under 48 dp).

### 3.5 Brush settings sheet

Opens from the active tool's second tap. Same container
rules as the other panels. Header: the tool name and, in GROUPED/SHORT/DOCK
modes, the **preset row** (pencil · ink · brush · airbrush · marker as chips).
The **Eraser** slot's sheet carries its own two-chip preset row (hard ·
soft) in *every* rail mode — the rail has one eraser slot (PLAN.md §6, S1),
so the soft eraser is reachable only here: tap Eraser, tap again, tap
Soft (R1: two taps to select, one more to switch preset). The chosen
eraser preset is remembered per session and is what the S Pen eraser end
and button use unless `Prefs.eraserEndPreset` says otherwise (`04-tools.md` §9).
Then the parameters of `04-tools.md`, grouped so the sheet reads top-down
from "what you change every minute" to "what you set once":

| Group | Parameters | Control |
| --- | --- | --- |
| Stroke | size, opacity, flow | `ThinSlider`s with numeric readout; mirror the rail sliders |
| Tip | hardness, spacing, shape (round / squared, orientation-following) | sliders; shape as segmented buttons |
| Dynamics | pressure → size / opacity / flow curves; tilt effect; velocity effect; jitter | each curve a `CurveEditor` (a 96 dp square with the four draggable knots of `04-tools.md`'s `Curve`); tilt/velocity/jitter as sliders |
| Stabilizer | strength | slider; 0 = off |
| Mixing | pigment mixing on/off and dilution (brushes); strength and pickup rate (smudge) | switch; sliders. Shown only when the active mixer is pigment (`09-color-and-mixing.md` §4) |

- A **live stroke preview strip** (full width × 72 dp) sits under the
  header: a fixed S-curve with a pressure ramp, rendered by the CPU
  reference dab pipeline in `engine/core` (`DabGenerator` → CPU stamping →
  `Composite`) on `Dispatchers.Default`, debounced 50 ms. Using the CPU
  reference rather than the GL engine keeps the preview off the GL thread
  and guarantees it is pinned to the same math the shaders are tested
  against (PLAN.md §7). It renders with the current colour on the paper
  colour, at 1:1 px with size capped at 48 px in the strip.
- Tool-kind gating: smudge/blur show Stroke (no opacity), Tip, Dynamics
  (size only), Stabilizer, Mixing; fill shows tolerance, contiguous/global,
  sample all layers, expand px, anti-alias; eyedropper shows "sample: composite
  / current layer". Absent groups are absent, not disabled.
- **Reset to preset** at the foot restores the built-in JSON; edits persist
  per preset through `BrushPresetStore` immediately (no Save button — the
  same rule as everything else).

### 3.6 Focus mode

Overflow → Focus, or the keyboard `Tab` on DeX. All chrome animates out
(strip up, rail sideways, ledge down, panels closed); the canvas already
runs edge to edge so nothing moves. System bars go immersive with
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` so an edge swipe doesn't fight a
stroke (`07-input-and-stylus.md`). What remains: a **focus handle** — a 6 ×
48 dp rounded pill at the rail edge, vertically centred, at 35 % opacity —
tap or drag inward to return. Hardware back returns too (a `BackHandler`
that fires before the leave handler). The reset-view pill and toasts stay
available in focus mode; the hover cursor stays. Focus is a `UiState`
flag, remembered per session, not persisted: reopening the app should show
the chrome.

### 3.7 Reset-view pill

Shown at the bottom centre (above the dock/ledge) whenever
`ViewTransform != FitTransform.of(canvas, window)` beyond tolerance (scale
±1 %, rotation ±0.5°, pan ±4 dp). Label "Reset view" with the current zoom
and angle as a caption ("183 % · 12°"), which doubles as the readout people
otherwise ask for. Tap → the `ViewTransform` springs to fit (spring, ~300 ms,
`dampingRatio` 0.8) with a haptic tick on arrival. It fades in 150 ms after
the gesture that displaced the view ends (never mid-gesture) and is hidden
while a stroke is in flight.

### 3.8 Hover cursor

`HoverCursor` is a Compose `Canvas` overlay fed by `UiState.hover`
(screen x, y, tool, size px, eraser-end flag) from the stylus hover events.
It draws a circle of the true dab diameter in screen pixels — 1 px white
inside 1 px black so it reads on any paper — a 3 px crosshair when the
diameter is under 6 px, a dashed circle for the eraser end, a pipette glyph
for the eyedropper. Hidden on `HOVER_EXIT`, hidden while touching, hidden
for fingers; a mouse over the canvas gets the same ring (pointer hover
behaves like stylus hover, `01-product.md` §3, `07-input-and-stylus.md`
§9). Hover moves are not
latency-critical the way strokes are, so a Compose overlay is acceptable;
if profiling (`10-performance.md`) shows it lagging the pen visibly, the
fallback is to draw it in the front-buffered layer — the API exists, the
cost is a per-hover render.

### 3.9 Toasts

`TransientToast` — not Material `Snackbar`: no action button, no swipe, does
not take focus, never overlaps chrome. A pill under the top strip (or at the
top edge in focus mode), 1.8 s, fade in/out 120 ms. One at a time; a new
message replaces the current one rather than queueing (a queue of stale
"Added to gallery" notices is noise). Sources: "Added to gallery" (first
gallery write of a session only — subsequent in-place rewrites are silent;
never the word "save", `01-product.md` H4),
"Copied", "Deleted", "Layer limit reached", gallery-write failure ("Couldn't
update the gallery copy"), stylus-only mode reminders ("Touch drawing is off
— Settings"). Toasts are deferred while a stroke is in flight (§4).

### 3.10 First-run hint

On the first Canvas ever (`Prefs.hintShown == false`), a card over the
canvas centre — the one time chrome is allowed there, and only before the
first stroke: three lines with small glyphs — "Draw with a finger or pen",
"Two fingers: move, zoom, rotate", "Tap with two fingers to undo" — and
"Got it". Any tap dismisses it and does not draw; the flag is written on
dismissal. It is never shown again, including after a reinstall only if
`Prefs` survived (it does not; that is fine).

## 4. Behaviour rules (UiState invariants)

These are enforced in `CanvasViewModel` and tested in `CanvasUiStateTest`
with a fake engine; the composables just render the state.

```kotlin
enum class Panel { LAYERS, COLOR, BRUSH_SETTINGS, OVERFLOW }

data class CanvasUiState(
    val openPanel: Panel? = null,
    val focus: Boolean = false,
    val strokeInFlight: Boolean = false,
    val dialog: CanvasDialog? = null,        // only ever non-null when !strokeInFlight
    val pendingDialog: CanvasDialog? = null, // parked here until the stroke ends
    val pendingActions: List<CanvasAction> = emptyList(), // undo/redo/layer ops/paper colour parked the same way (rule 2)
    val toast: ToastMessage? = null,
    val viewIsIdentity: Boolean = true,
    val hover: HoverState? = null,
    val hint: Boolean = false,
    // …tool, colour, layers, history flags
)
```

1. **Panels are modeless and dismiss on canvas touch; the dismissing touch
   never draws.** While `openPanel != null`, `CanvasScreen` places a
   transparent, non-darkening `Box` over the canvas area (excluding the
   panel, strip and rail) whose `pointerInput` consumes the *first* pointer
   down, calls `dismissPanel()`, and swallows the rest of that gesture. The
   SurfaceView never sees it, so `GestureArbiter` never sees it, so nothing
   is stamped. The strip and rail stay live under an open panel — switching
   tools with the layer panel open is a normal thing to do. On expanded
   widths where panels float, the rule is the same; "modeless" means
   nothing is greyed out, not that the canvas draws through a panel.
2. **No dialog while a stroke is in flight.** `requestDialog(d)` sets
   `dialog` only if `!strokeInFlight`, else `pendingDialog`; `onStrokeEnd()`
   promotes `pendingDialog`. Toasts follow the same rule. Something popping
   up mid-stroke would steal the pointer and orphan the stroke; deferring
   costs nothing. The same guard applies to the low-memory and
   gallery-failure dialogs raised from IO.
   **Document-mutating chrome actions are parked the same way**: undo,
   redo, every layer operation (the strip and rail stay live under the off
   hand while the pen is down, so a tap on Undo mid-stroke is normal) and
   a paper-colour change requested while `strokeInFlight` go into
   `pendingActions` and run at `onStrokeEnd()` *after* the commit — a
   sandwich rebuild or a tile upload during a front-buffered stroke would
   invalidate the front layer (`03-canvas-engine.md` §8.6) and an undo
   before the merge would journal against a layer the stroke is about to
   change. Tool, preset, colour and slider changes apply to `UiState`
   immediately but never to the stroke in flight: its `StrokeSpec` is
   fixed at pen-down (`03-canvas-engine.md` §6, `04-tools.md` §9) and the
   new values take effect at the next `ACTION_DOWN`. `CanvasUiStateTest`
   covers both (§8, `11-testing.md` §3.16).
3. **Chrome never covers the canvas centre.** Persistent chrome (strip,
   rail/dock, ledge, pill, toast, focus handle) is confined to the edges; the
   central 60 % × 60 % of the window is free of it in every `LayoutSpec`
   (asserted by `LayoutSpecTest` against the dp geometry). Transient
   panels may overlap it on compact — they are dismissed by the remaining
   canvas strip — and the first-run hint is the one deliberate exception.
4. **Rail ≥ 48 dp targets, one column**; when that column doesn't fit, tools
   *group* (§1), they never shrink and never scroll.
5. **Landscape phones get the rail, portrait phones get the dock** — via the
   window size class and rail height in `LayoutSpec.forWindow`, never
   `Build.MODEL` or "is tablet".
6. **Handedness mirrors the layout** (`Prefs.handedness`, default RIGHT):
   rail, panels, ledge direction, strip clusters, focus handle. The default
   is right because the drawing hand should not cross the picture to reach
   the rail: the rail sits *under the pen hand*, panels open on that side
   so the off hand keeps the strip.
7. **Hardware back** order: dialog → panel → focus mode → leave. One step
   per press.
8. **Leaving** (`leave()`): stroke in flight is committed first, the
   checkpoint and gallery flush run with a translucent scrim ("Closing…" —
   not "Saving", `01-product.md` H4)
   only if they take longer than 300 ms, then pop. No prompt, ever
   (decision 8).

## 5. Design language — "Quiet Studio"

The picture is the only saturated thing on screen. Chrome is warm-gray
paper and graphite; the icon's two colours appear only where the user can
act (saffron) or has chosen (indigo). Controls are chunky and answer with a
tick; motion is a short spring, never a flourish.

### 5.1 Tokens (`ui/theme/`)

| Token | Light | Dark | Used for |
| --- | --- | --- | --- |
| `surface` | `#F5F2EE` | `#1C1A18` | Studio background, sheets |
| `surfaceContainer` | `#EBE7E1` | `#26231F` | top strip, rail, dock, panels |
| `surfaceContainerHigh` | `#E0DBD3` | `#312D28` | pressed states, dish wells |
| `outline` (hairline) | `#CFC8BE` | `#3F3A33` | 1 dp borders, dividers |
| `onSurface` | `#2B2724` | `#ECE7E0` | text, icons |
| `onSurfaceVariant` | `#5F5952` | `#A69F95` | captions, relative times, disabled |
| `accent` (saffron) | `#A87200` | `#FFB400` | active tool ring, slider thumbs, FAB, + tile, focused fields |
| `accentContainer` | saffron @ 20 % | saffron @ 24 % | active tool fill |
| `select` (indigo) | `#2A1FD0` | `#7A73FF` | selected layer, selected swatch, checked chips |
| `selectContainer` | indigo @ 12 % | indigo @ 20 % | selected row background |
| `error` | `#B3261E` | `#F2B8B5` | Delete button, failure toast |
| `canvasVoid` | `#B8B2AA` | `#0F0E0D` | the area outside the paper when zoomed out |

Saffron in light mode is darkened to `#A87200` because the icon's `#FFB400`
(and even `#E0A000`, ≈ 1.9:1) cannot hold WCAG's 3:1 non-text contrast on a
light surface; the icon's `#FFB400` is used as-is in dark. Computed ratios
(WCAG relative luminance), to be copied into `Color.kt` comments:

| Pair | Light | Dark |
| --- | --- | --- |
| `accent` ring on `surfaceContainer` | 3.4:1 | 8.8:1 |
| `onSurface` on `surfaceContainerHigh` (worst text pair) | 10.7:1 | ≥ 10:1 |
| `onSurfaceVariant` on `surfaceContainerHigh` (worst caption pair) | 5.0:1 | 5.2:1 |
| `onSurfaceVariant` on `surface` | 6.2:1 | ≥ 6:1 |

`onSurfaceVariant` is `#5F5952` rather than a lighter gray because `#6B655E`
would sit at ≈ 4.2:1 on `surfaceContainerHigh`, under the 4.5:1 text floor.
Both schemes are built by hand in `Color.kt` and passed to
`MaterialTheme(colorScheme = …)`; dynamic colour is *off* — a wallpaper-tinted
studio would fight the painting. Light/dark follows the system
(`isSystemInDarkTheme()`); there is no in-app override in v1.

| Token | Value |
| --- | --- |
| `r.sm` / `r.md` / `r.lg` | 8 / 14 / 22 dp — chips & swatches / buttons & rows / panels & dialogs |
| elevation | tonal only: panels `surfaceContainer` + hairline; no shadows except the preview blob (2 dp) |
| type | Material 3 defaults, trimmed: `titleMedium` 18 sp for panel headers, `bodyMedium` 14 sp, `labelSmall` 12 sp captions; numeric readouts use `FontFeatureSetting("tnum")` so "100" and "80" don't jitter |
| icon | 24 dp, 2 dp stroke, rounded caps; 20 dp in rows |
| min target | 48 dp everywhere, palette swatches included (32 dp visual + 8 dp padding); rail tool buttons 56 dp on EXPANDED (§1) |
| motion | springs, `dampingRatio` 0.8, `stiffness` medium (panels) / high (tool switch, pill); nothing longer than ~300 ms; reduced-motion → `snap()` |
| haptics | `performHapticFeedback` tick on: tool switch, layer reorder slot change, rotation snap to 0°, reset-view arrival, slider detents (opacity 0/50/100 %), hue-ring detents at 0/60/120/… ° (`09-color-and-mixing.md` §9.1), long-press menus. Never on stroke events. Off via Settings |

Haptic constant choice: the baseline tick constant available at API 29 —
to verify against the platform's `HapticFeedbackConstants` table before
use; newer segment/confirm constants are gated by SDK check.

### 5.2 Icon and adaptive icon

`media-sources/icon.png` is a saffron calligraphic 帮 with a brush on an
indigo→violet gradient, 1254 × 1254. `scripts/generate_icons.py` (Blipbird
pattern) emits:

- `mipmap-*/ic_launcher_bg.png` — the **full-bleed artwork** scaled to the
  108 dp adaptive canvas per density and used as the foreground. A solid
  indigo background covers edges exposed by launcher motion. Verify the 帮 and
  brush on circle and squircle masks on a device; generation alone cannot
  validate launcher cropping.
- `drawable/ic_launcher_monochrome.xml` — a brush silhouette vector for
  themed icons on Android 13+. It exists
  (`app/src/main/res/drawable/ic_launcher_monochrome.xml`, hand-authored
  with the scaffold, not generated): the brush alone, inside the 66 dp safe
  zone of the 108 dp canvas — the 帮 glyph is dropped on purpose because a
  calligraphic character tinted one colour at themed-icon sizes reads as a
  smudge.
- No legacy launcher PNGs: minSdk 29 always supports adaptive icons.

The in-app About screen reuses the artwork; nowhere else in the UI shows the
gradient — the icon is the one loud thing the app owns.

## 6. Accessibility

- Every icon button has a `contentDescription` from `cd_*` strings; the
  active tool adds `stateDescription = selected`; the colour swatch button
  reads "Colour, #3A6FD8".
- **Traversal order** is set with `traversalIndex`: top strip → rail/dock →
  sliders → open panel → canvas. The canvas SurfaceView is wrapped in a
  semantics node "Canvas, {w} × {h}" with custom actions Undo and Redo, so a
  TalkBack user can reach them without the strip.
- Sliders publish `ProgressBarRangeInfo` and `setProgress`; the preview
  blob is `invisibleToUser` (decorative).
- Panels announce on open (`liveRegion = Polite`: "Layers panel opened").
- Minimum sizes as in §5.1; text contrast ≥ 4.5:1 and non-text (ring,
  thumb) contrast ≥ 3:1 on every surface pair in the token table — the
  computed ratios are in §5.1 and are recorded in `Color.kt` comments.
- **Reduced motion**: when the system's animator duration scale is 0 (to
  verify the exact check: `ValueAnimator.areAnimatorsEnabled()`), every
  spring becomes `snap()` and the reset-view spring becomes an instant
  transform; nothing depends on an animation finishing.
- Haptics and touch drawing are independent toggles; TalkBack's explore-by-
  touch will intercept the canvas — that is a known limitation stated in the
  Settings help text, not something v1 works around.

## 7. Strings

- All user-visible text in `res/values/strings.xml` (en) and
  `res/values-b+zh+Hans/strings.xml` (zh-Hans; `01-product.md` §8). Lint `MissingTranslation` is an
  error, so a string can't ship untranslated. `app_name` is
  `translatable="false"` and is exactly `帮你Draw` in both.
- Naming: `studio_*`, `newcanvas_*`, `canvas_*`, `layers_*`, `color_*`,
  `brush_*`, `settings_*`, `toast_*`, `dialog_*`, and `cd_*` for content
  descriptions. Plurals (`studio_count_paintings`, `layers_count`) use
  `<plurals>`; zh-Hans has the `other` case only.
- Relative times, byte sizes and numbers come from platform formatters
  (`DateUtils`, `Formatter.formatShortFileSize`, `NumberFormat`) so they
  localise without strings.
- Tool and built-in preset names are string resources: a built-in JSON's
  `name` is `"@string/preset_pencil"` and resolves through `R.string`; a
  user preset's `name` is a literal shown verbatim (`04-tools.md` §5.1,
  `01-product.md` §8).
- The Mixbox notice text (About) is untranslated-verbatim from the license
  header plus a translated one-line explanation (ADR 0003).

## 8. Compose component list (`ui/`)

Matches PLAN.md §3's package layout; names here are the file names.

| File | Responsibility |
| --- | --- |
| `ui/navigation/BangniNavHost.kt` | `StudioRoute` and `CanvasRoute(projectId)`; single `NavHost`; passes ids only (`02-architecture.md` §7) |
| `ui/theme/Color.kt`, `Theme.kt`, `Type.kt`, `Shape.kt`, `Motion.kt` | §5.1 tokens; `BangniTheme {}`; `Motion.spring()`/`snap()` honouring reduced motion |
| `ui/components/ChunkyIconButton.kt` | 48 dp (56 dp on EXPANDED) target / 40 dp visual icon button with active + dashed-ring states, tick haptic |
| `ui/components/ThinSlider.kt` | 4 dp track in a 48 dp hit slab, vertical/horizontal, jump-then-drag, off-slab fine gain, detent haptics, optional preview slot |
| `ui/components/PreviewBlob.kt` | the size/opacity blob shown while a `ThinSlider` drags |
| `ui/components/Swatch.kt` | 32 dp colour chip in a 48 dp target, checker under-layer, selected ring, drag source |
| `ui/components/ConfirmDialog.kt` | title/body/checkbox/confirm-destructive dialog used by delete flows |
| `ui/components/TransientToast.kt` | §3.9 |
| `ui/components/ToolIcons.kt` | `ImageVector`s for every tool and strip action |
| `ui/components/CurveEditor.kt` | four-knot editor for `04-tools.md`'s `Curve` |
| `ui/home/StudioScreen.kt` | scaffold: app bar + storage line + grid + FAB/first tile; hosts dialogs |
| `ui/home/StudioViewModel.kt` | `StudioUiState` (paintings, storage, dialog); open/create/rename/duplicate/share/delete |
| `ui/home/PaintingTile.kt` | one shelf cell + hold menu |
| `ui/home/StorageLine.kt` | the readout line |
| `ui/canvas/NewCanvasDialog.kt` | §2.1; presets from `CanvasPresets`, live layer-fit |
| `ui/canvas/CanvasScreen.kt` | composes surface + chrome per `LayoutSpec`; the dismiss box; `BackHandler` chain |
| `ui/canvas/CanvasViewModel.kt` | `CanvasUiState`, §4 invariants, engine/tool/history commands, leave() |
| `ui/canvas/LayoutSpec.kt` | pure JVM §1 |
| `ui/canvas/CanvasSurface.kt` | `AndroidView { SurfaceView }` wired to `CanvasRenderer` and `CanvasTouchHandler`; nothing else |
| `ui/canvas/TopStrip.kt` | §3.1 |
| `ui/canvas/ToolRail.kt` | §3.2 rail (FULL/GROUPED/SHORT) and dock (DOCK) — one composable, orientation from `LayoutSpec` |
| `ui/canvas/SliderLedge.kt` | the horizontal slider pair (SHORT) or single slider + toggle (DOCK) |
| `ui/canvas/LayerPanel.kt` | §3.3 incl. drag-reorder |
| `ui/canvas/ColorPanel.kt` | §3.4 layout over `ColorUiState` (`09-color-and-mixing.md` §9) |
| `ui/canvas/MixingDish.kt` | the dish; talks to `ColorMixer` only |
| `ui/canvas/HsvPicker.kt` | the ring + square, `Canvas` drawn, pointer math in a small pure helper |
| `ui/canvas/BrushSettingsSheet.kt` | §3.5 groups + preview strip |
| `ui/canvas/StrokePreview.kt` | CPU-rendered preview bitmap, debounced |
| `ui/canvas/PanelHost.kt` | the container that is a sheet / side sheet / floating card per `PanelMode` |
| `ui/canvas/ResetViewPill.kt` | §3.7 |
| `ui/canvas/FocusHandle.kt` | §3.6 |
| `ui/canvas/HoverCursor.kt` | §3.8 |
| `ui/canvas/FirstRunHint.kt` | §3.10 |
| `ui/home/SettingsSheet.kt` | PLAN.md §5.3; a sheet from the Studio menu, not a route (`02-architecture.md` §7); preference rows over `Prefs`; About + licenses |

Tests that belong to this document: `LayoutSpecTest` (mode per
width/height/hand; the §1 height budgets; the 60 % clear-zone assertion;
mirror symmetry — named in `12-roadmap.md` step 9), `CanvasUiStateTest`
(dialog deferral, panel dismissal, back-press chain, focus), `HsvMathTest`
(ring/square hit-testing and HSV↔RGB round trips), and `CanvasPresetsTest`
(`11-testing.md` §3.12: preset list against fake budgets,
disabled-above-ceiling) — all listed in `11-testing.md` §3.16.
`LayoutSpec.forWindow(width, heightDp, hand)` is the signature
`02-architecture.md` §5 and `12-roadmap.md` step 9 carry.
