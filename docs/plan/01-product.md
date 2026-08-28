# 01 — Product

This document covers the product: the promise, who it is for, which devices
it is designed for and what each gets, the UX principles as testable rules,
the explicit non-goals, the comparable apps and what we take or refuse from
them, accessibility, localization, and naming. It expands PLAN.md §1
(What this is, Principles) and §5 (Screens) and is the yardstick every other
document in `docs/plan/` is measured against: if a design in
`03-canvas-engine.md` or `08-ui-and-layout.md` contradicts a rule here, one
of the two documents is wrong and the disagreement gets recorded in
AGENTS.md, not papered over. Architecture, engine, tools and UI mechanics are
deliberately *not* here — see the sibling documents named in each section.

## 1. The promise

> **Open it, tap +, and you are drawing.**

One sentence, and everything else is a consequence of it. There is no
project wizard, no format decision, no "save?" prompt, no tutorial gate.
The app is a stack of paper and a box of good tools; the software is the
part you should never notice.

Three properties make the promise true, and they are the three things the
engineering is organized around (PLAN.md §3):

| Property | What the user experiences | Where it is designed |
| --- | --- | --- |
| Fast | the mark appears under the pen tip, not behind it; zoom and rotate never stutter | `03-canvas-engine.md`, `07-input-and-stylus.md`, `10-performance.md` |
| Nothing is ever lost | close the app mid-stroke, reopen, undo still works; every painting is in the gallery | `06-document-and-persistence.md` |
| Feels like real media | pencil shades with its side, blue and yellow make green, smudge drags wet paint | `04-tools.md`, `09-color-and-mixing.md` |

Everything not in service of those three is a candidate for the non-goals
list (§5).

## 2. Who it is for

A single continuum, not two audiences. The same person often sits at both
ends: a quick idea on the phone in the train, worked up on the tablet at
home.

| Persona | Device, input | Session | What they need most | What would drive them away |
| --- | --- | --- | --- | --- |
| **The sketcher** | phone, finger (sometimes an S Pen on an Ultra) | 2–10 minutes | + → draw → done; a pencil and an eraser that just work; the picture in the gallery so it can be sent | setup steps, small targets, chrome over the drawing, a "save as" dialog |
| **The hobbyist** | mid tablet (Tab S6 Lite / A9+ class, 4 GB), S Pen without button | 20–60 minutes | layers, a paintbrush that mixes, fill for flats, undo that never runs out | jank at 60 Hz, memory crashes on a big canvas, a layer cap that appears without warning |
| **The painter** | large tablet (Tab S9/S10 class, 8–16 GB, 120 Hz), S Pen with hover + button | hours, over days | low latency at full pressure/tilt fidelity, many layers on a large canvas, panels that stay out of the picture, hover cursor, eraser end | any perceptible lag, a stroke lost to palm rejection, panels that steal the canvas center |

The design bias is toward the **painter** for engine decisions (latency,
fidelity, memory honesty) and toward the **sketcher** for UX decisions
(zero setup, big targets, few controls). When those pull apart, the
resolution is PLAN.md principle 1: the canvas is the app. A feature the
painter wants that costs the sketcher a step does not ship as a default; it
ships behind the settings sheet or not at all.

Not a target: professional illustrators who need CMYK, print-resolution
color management, PSD round-trips or vector output. They have Krita and
Clip Studio; we do not compete with them (§5, §6).

## 3. Device classes

We design for **window size class**, not device model (PLAN.md §3.4).
Foldables and multi-window fall out of that for free. The classes below are
the ones we actually test on; the facts file's device landscape is the
source of the specs.

| Class | Typical hardware | Window | Input | What it gets |
| --- | --- | --- | --- | --- |
| **Phone, compact** | Pixel / Galaxy S (non-Ultra); 6–7" | compact width (< 600 dp) | finger; occasionally stylus | Bottom tool dock, full-height panel sheets, one slider visible at a time, canvas presets small (phone-sized), touch drawing on by default, focus mode one tap away |
| **Phone with S Pen** | Galaxy S22–S25 Ultra | compact | S Pen (hover, tilt, button) + finger | Everything above plus hover cursor, eraser end, side-button action, palm rejection; "stylus only" lives in Settings and is mentioned in the single first-run hint (S4) when a stylus is detected — never a prompt on stylus touch (F2) |
| **Mid tablet** | Tab S6 Lite / A9+; 10.4–11", 4 GB, 60–90 Hz | medium or expanded | S Pen (no button on S6 Lite) + finger | Rail layout, floating panels, mid canvas presets; `MemoryBudget` (PLAN.md decision 4) visibly caps layers on large canvases rather than crashing |
| **Large tablet** | Tab S9 / S10 family; 11–14.6", 8–16 GB, 120 Hz | expanded (≥ 840 dp) | S Pen with hover + button + finger | The full experience: 120 Hz stroke path, large canvas presets, highest layer cap, panels floating beside the rail, handedness respected |
| **Foldable** | Galaxy Z Fold; 7.6" inner, S Pen Fold Edition (no hover button) | compact when folded, medium/expanded open; resizes live | S Pen + finger | The layout re-flows on fold/unfold without leaving the painting or resetting the view; no assumption that the stylus has a button |
| **Desktop mode** | Samsung DeX, Chromebook, freeform windows | any, resizable | mouse + keyboard (+ stylus) | A nicety, not a target: pointer hover behaves like stylus hover, mouse wheel zooms, a handful of keyboard shortcuts (undo/redo, bracket keys for size, `E` eraser — to verify against the platform's key-event conventions). Never required for any feature |

Rules that follow from the table:

- **No feature is stylus-only.** The eraser end and side button are
  shortcuts to things the rail already has. A finger-only phone can do
  everything, just with more taps (§4, rule R1).
- **No feature is tablet-only.** Layers, mixing, fill and focus mode exist
  on the phone. What changes is layout, presets and the memory budget.
- **Canvas presets are per class, not per device** (`CanvasPresets`,
  PLAN.md §3): a phone default fits its own screen at 1:1; a large tablet
  default is a painting. Custom sizes are allowed within `MemoryBudget`,
  and the New Canvas dialog prints the layer cap the chosen size implies
  before the user commits.
- **The app is one app across classes.** Same tool names, same icons, same
  gestures, same order in the rail/dock. A user who learned it on a phone
  finds nothing renamed on the tablet.

## 4. UX principles as testable rules

PLAN.md §1 states six principles. This section turns the UI-facing ones
into rules that a reviewer can check against a build (or, where possible,
a JVM test against `DockState`-style pure layout logic — see
`08-ui-and-layout.md` and `11-testing.md`). Each rule has an ID so roadmap
acceptance tests and PR reviews can cite it.

### Reach

| ID | Rule | How to check |
| --- | --- | --- |
| R1 | Every tool in PLAN.md §6 is reachable in **≤ 2 taps** from the canvas with no panel open (tap tool in rail; or tap rail overflow then tool on compact) | count taps per tool on each size class |
| R2 | Undo, redo, size and opacity are **1 tap or 1 gesture** from any state where a stroke is possible | top strip buttons + two/three-finger tap; sliders on the rail |
| R3 | The active tool's settings sheet opens by **tapping the active tool again**; no long-press-only affordance exists anywhere for a primary action (long-press is always a shortcut to something also reachable by tap) | enumerate long-press handlers; each must have a tap route |
| R4 | Layers and color are **one tap** to open and **one tap on the canvas** to dismiss | panel open/close counts |

### Focus on the painting

| ID | Rule | How to check |
| --- | --- | --- |
| F1 | **Chrome never covers the center of the canvas.** With every panel open, the central 60 % × 60 % of the canvas viewport is unobstructed on medium/expanded (`08-ui-and-layout.md` §4.3); on compact a full-height sheet may cover it *only while open* and closes on any canvas tap | overlay-rect test in pure layout code (`LayoutSpecTest`) |
| F2 | **No modal dialog while a stroke is possible.** Dialogs exist only in the Studio (new canvas, delete confirm) and for destructive layer ops (delete a non-empty layer, flatten). While the canvas accepts input there is no dialog; every sheet/panel is dismissable by tapping the canvas | grep for dialog composables in `ui/canvas`; each must be a destructive-op confirm |
| F3 | **Focus mode hides all chrome** with one tap (or the double-tap-with-stylus-button gesture, to verify it does not collide with system gestures) and restores it with one tap on the same edge handle; the handle is the only thing drawn | screenshot comparison |
| F4 | A live stroke **collapses nothing and opens nothing**; panels that are open stay where they are (a sheet on compact closes on stroke start because it covers the canvas — see F1) | manual |
| F5 | System bars are transient in the canvas (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, facts file) so an edge stroke is a stroke, not a navigation gesture | manual on gesture-nav devices |

### Tactile

| ID | Rule | How to check |
| --- | --- | --- |
| T1 | Every control is **≥ 48 × 48 dp** touch target (A1) and the rail's tool buttons are ≥ 56 dp on expanded widths (except the SHORT rail of a phone in landscape, where 288 dp of rail only holds six 48 dp slots — `08-ui-and-layout.md` §1) | JVM test over the layout constants (every clickable size ≥ 48 dp) plus a Compose UI test or a manual audit with the Accessibility Scanner; the `ui/components` primitives hardcode ≥ 48 dp targets, and the rule is enforced in code review — lint check IDs to verify, none assumed |
| T2 | Sliders are **thin to look at, fat to touch**: a 4 dp track with a 48 dp hit slab; dragging shows the value numerically and, for size, a live circle at true canvas scale near the pen | manual |
| T3 | Discrete changes (tool switch, layer select, snap-to-0° rotation, undo) give a **haptic tick**; continuous changes (sliders, zoom) do not, except at detents | audit haptic calls |
| T4 | Tap feedback is **≤ 1 frame**: visual state changes are driven from `UiState`, never awaited from persistence or the GL thread | code review |

### Honest

| ID | Rule | How to check |
| --- | --- | --- |
| H1 | Limits are **printed before they bite**: layer cap in the layer panel header ("6 of 8"), size ceiling in the New Canvas dialog, undo depth in Settings, storage in the Studio | screenshots |
| H2 | **Nothing is silently downgraded**: if a tool cannot run (GL capability missing, memory), the tool is greyed with a reason on tap, not swapped for a lookalike | code review of fallback paths |
| H3 | **Deleting is deliberate**: only the Studio deletes paintings, always with a confirmation naming the painting and asking about the gallery copy; no swipe-to-delete anywhere | grep for delete affordances |
| H4 | There is **no "save" verb** in the UI at all — no button, no menu item, no prompt. The word appears only in Settings ("autosave") and About | string-resource audit |

### Simple

| ID | Rule | How to check |
| --- | --- | --- |
| S1 | The rail/dock holds **at most 10 tools** in v1 (the §6 table) and never scrolls; new tools displace, they do not append | constant + test |
| S2 | The top strip holds **at most 6 actions** (back, undo, redo, layers, color, menu); everything else is inside the menu | constant |
| S3 | Settings has **one level**: no nested screens beyond About/licenses | manual |
| S4 | **No onboarding flow.** One dismissable first-run hint on the canvas (gestures); nothing else stands between launch and drawing | manual: cold install to first stroke in ≤ 3 taps (launch, +, Create) |

## 5. Non-goals for v1

Each one is a real request we expect and are refusing on purpose. The
reasoning is recorded so the refusal survives the next person who asks.

| Non-goal | Why not in v1 | Path, if ever |
| --- | --- | --- |
| **Vector layers / shapes / paths** | A vector model is a second document model with its own undo, hit-testing, rendering and export; it doubles the engine for a feature none of our personas asked for. PLAN.md decision 1 commits to raster. | Never as a full model. Straight-line and shape *assist* for raster strokes is post-v1 (PLAN.md §6). |
| **Text tool** | Fonts, editing, kerning, RTL, IME — a text tool that is not embarrassing is a project by itself, and it drags in a font license question (§9 of PLAN.md). | A proposal in `docs/proposals/` if demand appears; would rasterize on commit. |
| **Animation / time-lapse** | Frame management, onion skin, export encoders — a different app. Time-lapse recording is cheap-looking but needs a persistent frame log and a MediaCodec pipeline. | Time-lapse is on the post-v1 list (PLAN.md §10); frame animation is not. |
| **Cloud sync / accounts** | Violates PLAN.md principle 5 (no INTERNET permission, ever). The gallery copy *is* the sync: every system backup/photo service already picks it up. | Never as a first-party feature. |
| **Photo editing** (adjustments, filters, crop of imported photos) | Different tool, different UX, huge surface. Import-as-layer is the only paint-photo feature we will ever want. | Tracing references shipped after v1. Import as a paint layer and crop/resize of the *canvas* remain post-v1. |
| **Custom brush import formats** (ABR, Procreate `.brush`, Krita bundles) | Each is a reverse-engineered format with texture assets we cannot redistribute; parsing them correctly is endless. Our brushes are our JSON (`BrushPreset`, PLAN.md decision 9). | Our own JSON export/import of presets is cheap and may ship in a point release; foreign formats never. |
| **PSD / ORA / layered export** | PSD is a large, poorly specified format; ORA is simple but nobody's gallery opens it. v1 output is a flattened PNG/JPEG in the gallery and the share sheet. | OpenRaster export is on the post-v1 list — it is a zip of PNGs and fits our tile model. PSD is not planned. |
| **Selections and transform** | Genuinely wanted, but they are a mode with their own gesture arbitration, marching-ants rendering, and interaction with every tool; done half-way they are worse than absent (principle 6). | First post-v1 proposal (PLAN.md §10). |
| **Color management / CMYK / wide gamut** | Mixbox is defined in sRGB; the tile format is RGBA8; the gallery is sRGB PNG. Consistency beats accuracy here. | Not planned. |
| **Multiple canvases open at once, tabs** | The Studio → Canvas → Studio loop is the whole navigation. Tabs would put chrome on the canvas (F1) and complicate the "nothing is lost" guarantees. | Not planned. |
| **Plug-ins, scripting** | An offline, permission-free app with no extension surface has no attack surface. | Not planned. |
| **Monetization** | Mixbox is CC BY-NC 4.0 (PLAN.md decision 5); the app is non-commercial as distributed. No ads, no IAP, no store listing in v1 (sideload via GitHub Releases, ADR 0005). | A commercial build would need a Mixbox license or `RgbMixer` — a product decision with an ADR. |

## 6. Reference points

What we borrow is a *feel* or a single interaction, never a feature list.
Comparisons are for design discussion only; no competitor name appears in
the app, README marketing or store copy (there is none).

| App | Borrow | Refuse |
| --- | --- | --- |
| **Procreate** | The bar: chrome is a thin strip plus a side rail with two sliders; the canvas is the app. Two-finger tap undo, three-finger redo. QuickMenu-style "tap active tool for settings". Layer panel as a slide-in. | The gesture taxonomy (dozens of configurable multi-touch gestures), brush studio depth, the 200-brush library. Procreate's *feature count* is not what makes it feel good. |
| **Krita** (Android) | The engine truths: tiled layers, per-dab flow vs per-stroke opacity, stabilizer, RMW smudge, honest memory. Fill with "expand"/"grow" so line art has no halos. | The docker UI, menus-in-menus, dozens of blend modes, resource bundles, vector layers, everything about its onboarding. |
| **Infinite Painter** | The clean tool rail, the "fewer, excellent brushes" default set, its adaptive phone/tablet layout without becoming a different app. | Perspective guides, pattern tools, cloud, subscriptions. |
| **Autodesk Sketchbook** | Radial-menu speed for size/opacity with the pen in hand (our sliders on the rail chase the same latency); the pencil's tilt shading; the predictive stroke ("steady stroke"). | The full-screen marking menu language, symmetry rulers (post-v1), text, the distortion tools. |
| **MediBang Paint** | Nothing UI-wise. It is the cautionary example: cloud accounts, ads, comic panels, fonts, a settings tree with hundreds of items. | The sprawl. |
| **Samsung PENUP** | S Pen conventions its users already know: eraser end erases, side button as modifier, hover shows the tool cursor, the "coloring" simplicity for first-time users. | The social network, the challenges, the account, live drawing replay. |

Summary of the refusals: **feature sprawl**. Each of the references above
except PENUP added a feature per quarter until the first-run experience was
a wall. PLAN.md principle 6 exists because of that pattern; §5 above is its
enforcement.

## 7. Accessibility basics

Accessibility is not a v1 stretch goal; the rules below are gates in
PR 9 (adaptive UI polish) and PR 10 (v1.0) acceptance and are cheap if
done from the first composable.

| ID | Rule | Notes |
| --- | --- | --- |
| A1 | Every interactive element has a **≥ 48 × 48 dp** touch target (T1) | Enforced as in T1 (constants test, Accessibility Scanner audit, `ui/components` primitives); no `Modifier.size` under 48 dp on a clickable |
| A2 | Every control has a **TalkBack content description** naming the action and, for toggles/sliders, the state and value ("Opacity, 60 %", "Layer 3, hidden") | `contentDescription` / `stateDescription` on every icon-only control; Android lint's `ContentDescription` check covers View XML only, so for Compose the gate is a UI test asserting every icon-only node has a description plus an Accessibility Scanner pass; any Compose lint found for this must not be suppressed |
| A3 | **No color-only state.** Active tool = filled shape + accent, not accent alone; hidden layer = eye icon + dimmed row; unsaved/dirty is never shown (H4). Lock and visibility use distinct glyphs | design review of `ui/components` |
| A4 | Chrome text and icons meet **WCAG AA contrast (4.5:1 text, 3:1 icons)** against the chrome background in both light and dark; the accent (saffron) on indigo is checked as a pair | contrast check on `ui/theme` tokens |
| A5 | **Text scales**: chrome uses `sp`, layouts survive 200 % font scale without clipping (labels may wrap or be replaced by icons + description) | run at largest system font size |
| A6 | The canvas itself is a `SurfaceView` and cannot be described pixel by pixel; it exposes one node whose description states the painting's name, size, active tool and layer, and the gesture hint | required so TalkBack users can at least navigate around it |
| A7 | **Haptics and animation are optional** (Settings); reduced-motion system setting disables panel slide animations | `Settings.Global.ANIMATOR_DURATION_SCALE` respected via the standard Compose path |
| A8 | Handedness setting mirrors the rail so one-handed phone use works for either hand | PLAN.md §3.4 |

Deliberately out of scope for v1: switch-access drawing, voice control of
tools, and alternative input for the canvas itself — drawing is inherently
pointer-based; what we guarantee is that everything *around* the canvas is
accessible.

## 8. Localization

- Ships with **`values/` (English) and `values-b+zh+Hans/` (Simplified
  Chinese)** from v1.0 (PLAN.md §10 PR 10), listed in
  `res/xml/locales_config.xml` for per-app language on Android 13+. The
  Meltorama rule applies: add the locale to `locales_config.xml` in the same
  change that adds its `values-*` folder. Lint `MissingTranslation` is a
  hard gate, so every string is translated or marked
  `translatable="false"`.
- **All user-visible wording is a string resource.** ViewModels and the
  engine have no `Context` and no locale; errors travel as `@StringRes Int`
  (family convention, Meltorama AGENTS.md). Tool names, layer default names
  ("Layer 1"), blend-mode names and the names of *built-in* presets in the JSON are
  string *keys* resolved through resources, not literal text, so a
  shipped preset file never carries English. User-authored names are
  different: a preset the user creates or renames (`filesDir/brushes/`,
  PLAN.md §6) and a layer the user renames store the typed text as-is. The
  `name` field therefore takes either a resource key with a fixed prefix
  (e.g. `@string/`) or a literal; resolution order — key resolves through
  resources, anything else is displayed verbatim — is specified in
  `04-tools.md` and `06-document-and-persistence.md`.
- **Numbers and sizes follow the locale**: storage readout via
  `Formatter.formatFileSize`, percentages and pixel sizes via
  `NumberFormat`. The one exception is canvas dimensions, always shown as
  `W × H px` because that is how every reference app and every user
  writes them.
- **The name is not translated.** 帮你Draw is the same string in every
  locale (`translatable="false"`); the pinyin "Bāngnǐ Draw" appears only
  in the README and About for people who cannot read the characters.
- **Text-length discipline**: Chinese is shorter than English almost
  everywhere, so layouts are tuned against English and checked in Chinese;
  German and similar long locales are not shipped in v1, so no reservations
  for them beyond A5.
- Traditional Chinese (`zh-Hant`), Japanese and Korean — the other large
  stylus-tablet markets — are welcome contributions after v1; the string
  discipline above means a new locale is a folder, not a code change.

## 9. Naming and branding

- **Display name: 帮你Draw**, exactly, everywhere the user can see it:
  launcher label, Studio title, About, gallery folder (`Pictures/帮你Draw/`),
  share-sheet subject, release titles (`帮你Draw vX.Y.Z`). No romanized
  fallback in the UI; Android and every current file system handle the
  characters. Pinyin "Bāngnǐ Draw" is explanatory text in README/About only.
- The launcher label is the full name — five glyphs, well under Android's
  ellipsis limit — so unlike Meltorama there is no short form.
- **Never hardcode the name in a composable**; it lives in `app_name`
  (`translatable="false"`) and the single `GalleryExporter` folder constant.
  PLAN.md "Renaming" lists every place a rename would touch.
- **`applicationId = ch.lkmc.bangnidraw` never changes** (debug builds add
  `.debug`). It is invisible to users and changing it breaks upgrades for
  every sideloaded install (PLAN.md header; ADR 0005). Kotlin package,
  `rootProject.name` and the APK basename `bangnidraw-vX.Y.Z.apk` are
  likewise ASCII and fixed.
- **Icon**: `media-sources/icon.png` (1254 × 1254, saffron 帮 with a brush
  on an indigo→violet gradient) is the only source of truth; launcher
  assets are generated from it by `scripts/generate_icons.py` and never
  hand-edited. The app's single accent color is taken from the icon's
  yellow and the chrome's dark tone from its indigo (`08-ui-and-layout.md`
  §5); no second brand color exists.
- **Voice**: UI copy is short, plain, and never cute. Verbs on buttons
  ("Create", "Delete", "Duplicate"), nouns on panels ("Layers", "Color").
  No exclamation marks, no emoji, no "oops". Error text states what
  happened and what the user can do, in one sentence each.
- **Licensing statement is part of the brand**: About shows the Unlicense
  for our code and the Mixbox CC BY-NC 4.0 notice verbatim (PLAN.md
  decision 5), and the README says the app is non-commercial as
  distributed. That sentence is not optional copy; it is what makes the
  Mixbox dependency legitimate.

## 10. What "done" means for the product

v1.0 is done when, on each device class in §3, a first-time user can:

1. install the APK, launch, tap **+**, accept the default size, and make a
   pencil mark — within 3 taps and without reading anything (S4);
2. draw with every tool in PLAN.md §6 and tell them apart blind;
3. on one layer, lay down yellow and paint blue over it with the
   paintbrush and see green (mixing samples the active layer, PLAN.md
   §3.1 — cross-layer mixing is not promised); then add a layer and
   confirm add/reorder/hide/delete work;
4. zoom, rotate, fill a line-art region without halos, undo twenty steps;
5. kill the app from Recents mid-stroke, reopen, find the painting intact
   and undo still working;
6. find the painting, up to date, in the system gallery — exactly one
   entry;
7. do all of the above with TalkBack announcing every control (A2) and in
   Chinese (§8).

Anything that ships in v1 and fails one of those on any class in §3 is a
release blocker, not a known issue.
