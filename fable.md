# fable.md — deep review of 帮你Draw (2026-08-31, main @ 116fa79)

Full pass over `main` at v1.2.2+, immediately after the three structural PRs
that landed since the last review: **#171** (`:engine-core` extracted as a KMP
module), **#172** (`:engine-gl` extracted behind a GLES30 facade), **#173**
(input ported off `MotionEvent` onto a pointer-sample record). Those three are
the least-reviewed code in the repository — the previous deep review
(fable.md 2026-08-30, consolidated into ANALYSIS.md at `d4ea4a8`) predates all
of them — so this pass weights them heavily.

Read first, so nothing already settled is re-litigated: `ANALYSIS.md` (all
1139 lines, ids R1–R13 / D1–D9 / U1–U17 / A1–A18 plus Delight, Visual
direction, Review-round follow-ups, Acceptance gate and "Verified clean and
durable notes"), `REVIEW.md` (2525 lines of `R-NNN` declines and refutations),
`ISSUES.md`, `AGENTS.md` ("Deviations discovered while building" and
"Conventions the plan leaves open" in full), `PLAN.md`, `docs/plan/`, and
`DESKTOP.md`.

Method: eight parallel reviewers over disjoint scopes, each required to cite
`file:line` it had actually read; every finding below was then re-verified by
hand against the source before it was written down. Findings that did not
survive that re-check are recorded in §8 rather than deleted, because a
refuted hypothesis is worth as much to the next session as a confirmed one.

Marks: **[PR]** implemented this session (branch per item) · **[ready]**
shovel-ready for a later session · **[idea]** needs a product call ·
**[verify]** needs a device or a measurement before code.

New findings carry `F-N` ids. An item that confirms an existing ANALYSIS.md
entry cites that entry's id instead of taking a new one.

---

## 0. State of the ledger

No open PRs at the start of this session. ANALYSIS.md's backlog is current as
of `d4ea4a8`; the twenty commits since it are PRs #171–#173 plus DESKTOP.md.

Local gate at `116fa79`: `./gradlew testDebugUnitTest lintDebug assembleDebug`
passes clean in this sandbox. The device gate (D7) has still never run, and
nothing below claims a measured device result.

---

## 1. Bugs and correctness

### F-1 — A failed Gallery write publishes a truncated PNG, then orphans it forever. [PR]

**This reopens ISSUES.md's "Considered, not fixed" item 2 with new evidence.**
That entry declined the fix as "cosmetic and self-correcting". Both halves of
that premise are false, and the code proves it:

- *"the row briefly keeps the previous pixels"* — it does not.
  `GalleryExporter.rewrite` opens the row with `openOutputStream(uri, "wt")`
  (GalleryExporter.kt:241). `"wt"` truncates **on open**, so the previous
  pixels are destroyed before the first byte is written.
- *"the next sync rewrites them"* — it does not. The `finally` block
  (GalleryExporter.kt:243-253) clears `IS_PENDING = 0` and sets
  `DISPLAY_NAME` **even when the write threw**, so the truncated file becomes
  immediately visible in the user's Gallery. The exception then propagates to
  `sync()`, which catches `IOException` and returns `null`, so `project.json`
  keeps its **old** `galleryModifiedAt`/`galleryBytes`. On the next sync
  `probeRow` (GalleryExporter.kt:75-77) compares the row's now-truncated
  size/date against those stale recorded values, finds a mismatch, and sets
  `modifiedByOther = true` → `GallerySyncDecision.decide` returns `REINSERT`,
  whose own KDoc reads: *"The URI is forgotten, the item stays as the user
  left it, and a fresh item is inserted."*

The app's tamper-protection — built to stop it overwriting another app's edit
— therefore protects its **own** corrupted file from ever being repaired. Net
user-visible result of one disk-full moment during a sync: a permanently
corrupt image sitting in `Pictures/帮你Draw`, plus a clean duplicate beside it,
with no error surfaced anywhere.

`insert()` already does the right thing two functions below
(GalleryExporter.kt:285-289: *"Never a ghost: a failed insert deletes its
pending row"*); `rewrite()` was simply never given the equivalent guard.

Fix: publish only on success — move the `IS_PENDING`/`DISPLAY_NAME` update out
of `finally` onto the success path, leaving a failed write hidden and
retryable.

### F-2 — A MediaStore hiccup while browsing the shelf kills the app. [PR]

`GalleryExporter.sync` catches only `SecurityException` and `IOException`
(GalleryExporter.kt:125, 135, 144, 147). Its sibling `withdraw` catches
`RuntimeException` generically for exactly this reason
(GalleryExporter.kt:224-230, *"an unexpected provider failure is retryable,
not a reason to orphan the row"*); `sync` never got the same treatment.

`StudioViewModel` calls `exporter.sync(...)` at :353 and :407, both **outside**
the `catch (e: Exception)` blocks at :349 and :402, on
`viewModelScope.launch(Dispatchers.IO)`. An uncaught exception there reaches
the default handler and terminates the process. `syncStale` runs from
`refresh()` on every Studio open and every return from the Canvas, gated only
on `gallerySync` (default `true`) and staleness — so any OEM MediaStore quirk
crashes the app while the user is merely looking at their shelf.

Note for whoever implements ANALYSIS R10: its stated remedy is "a handler on
`appScope`", which **does not reach `viewModelScope`**. Implementing R10
literally leaves this path open.

Fix: broaden `sync`'s catch to match `withdraw`'s — one clause, protects every
caller including the `appScope` one R10 covers.

### F-3 — `AtomicFiles` uses one shared `.tmp` name with no lock, so two writers silently clobber each other. [PR]

`AtomicFiles.write` derives its temp path solely from the target name
(AtomicFiles.kt:44, `target.name + TMP_SUFFIX`) and takes no lock. Two
concurrent writers of the same target interleave destructively:

1. A opens `x.json.tmp`, writes its bytes.
2. B opens **the same path** — `FileOutputStream` truncates — discarding A's
   bytes, and writes its own.
3. A `fsync`s (persisting **B's** content) and renames → **succeeds**, and
   reports success to its caller.
4. B renames → **fails**, `tmp` is gone, and logs a failure it did not have.

So the writer whose data was thrown away is told it succeeded, and the writer
whose data survived logs an error. Two reachable instances today:

- **Brush presets.** `BrushPresetStore.save` is not `@Synchronized` — while
  its sibling `PaletteStore` **is**, an established pattern in the same
  package. Each slider release in `BrushSettingsSheet` spawns a fresh,
  untracked `appScope.launch(Dispatchers.IO)` (CanvasViewModel.kt:1786-1793),
  unlike every other repeatable async action in that file, which cancels or
  tracks its predecessor. Dragging two sliders in quick succession is enough.
- **`project.json`.** `ProjectStore.rename`, `updateGalleryFields` and
  `updateReferenceGalleryFields` are three independent decode→modify→write
  cycles on one file, launched from unrelated `StudioViewModel` coroutines.
  Renaming a painting while the background stale-sync reaches that same
  painting loses the rename — while telling the user it worked.

Fix (staged): give the temp file a unique suffix so writers cannot truncate
each other's in-flight file, and add `@Synchronized` to `BrushPresetStore`
mirroring `PaletteStore`. Full per-project serialization of `ProjectStore`'s
mutators belongs with R3's planned coordinator — and R3's text should be
amended to name `rename()`, which it currently omits.

### F-4 — Merge down silently clears the bottom layer's alpha lock. [ready, S]

`LayerStack.mergeDown` resets the surviving layer's `alphaLock` to `false`
(LayerStack.kt:255-261). Its own comment calls this out: *"alpha lock is the
one prop a merge silently changes."* The panel's confirmation dialog is gated
**only** on blend mode (LayerPanel.kt:261-271), and `layer_merge_body` only
mentions blend modes, so nothing tells the user.

A painter using alpha lock to protect a silhouette merges a layer down and
their next stroke paints straight through the area that used to be protected.
Undo restores the flag, so this is a workflow trap rather than data loss — the
damage is whatever gets painted before they notice. The other four reset
properties are harmless (`locked` is provably a no-op: `mergeDown` already
refuses when either partner is locked, LayerStack.kt:251).

Fix: include `below.props.alphaLock` in `needsConfirm` and give the dialog a
sentence for it.

### F-5 — Fill's "expand" reaches about twice its setting around a corner. [verify / idea]

`FloodFill.expand` dilates in two separable passes: `spreadRow` writes into a
`horizontal` buffer, then `spreadColumn` reads **that buffer** and grants a
fresh `params.expand` budget (FloodFill.kt:185-252). Each leg is wall-checked
independently, so a pixel reachable by ≤`expand` horizontal steps *and then*
≤`expand` vertical steps is covered even when every wall-respecting path is
longer than `expand`. Around a corner with a small gap — precisely the
geometry `expand` exists to bridge — a fill can bleed past the joint into an
adjacent region. The default is `expand = 2` (ToolKind.kt:119), so this is
ordinary use, and the subsequent box-blur `antialias` pass makes the extra
bleed read as a soft edge rather than an obvious artifact. The existing
`expand crosses an antialias skirt but stops at a color wall` test is 1×6, so
it structurally cannot catch a corner case.

**Deliberately not implemented this session.** Separable row+column dilation
with a square (L∞) structuring element is a defensible, conventional design,
and the leak is a property of that choice interacting with per-leg wall
checks. Replacing it with `expand` rounds of 1-px dilation bounds the geodesic
reach exactly, but changes fill output for every existing user and multiplies
the pass count by up to 8 at `MAX_EXPAND`. That is a product call plus a
measurement on a 4096² canvas, not a confident drive-by fix. Recorded with the
mechanism fully traced so the next session can decide rather than re-derive.

### F-6 — `NewCanvasDefaultsPolicy`'s last fallback can select a disabled preset. [ready, tiny]

`NewCanvasPolicy.kt:33-44` correctly keeps its first two fallbacks inside the
`enabled` set, then ends with an unfiltered `indexed.first()`. If no preset
were enabled the dialog would open with a greyed-out row selected. Very likely
unreachable — `MemoryBudget.compute` clamps the GPU tile budget to a 256 MiB
floor, under which `PHONE_SKETCH` always fits — so this is tidiness, not a
live defect. `enabled.firstOrNull() ?: indexed.first()` costs nothing.

---

## 2. Performance

### F-7 — The hottest GL upload in the engine has no buffer orphaning. [PR]

`DabPass.uploadInstances` (DabPass.kt:254-260) writes per-dab instance data
with a bare `glBufferSubData` into a live buffer. It is called inside
`stamp()`'s per-tile loop (DabPass.kt:138-155) **immediately before**
`glDrawArraysInstanced`, so each iteration writes into the range the previous
iteration's draw may still be reading. `ensureInstanceCapacity`
(DabPass.kt:264-288) only calls `glBufferData` while the capacity is *growing*,
so once it stabilises — after the first stroke or two — this bare-subdata path
is the permanent steady state, on every dab batch of every stroke.

The codebase already knows this hazard by name and fixes it everywhere else.
`CompositePass.kt:355-365` does orphan-then-subdata with the comment:
*"Orphan the storage first. Writing into a range the previous page's
glDrawArrays is still reading makes a tiler driver stall the CPU until the GPU
catches up, instead of renaming the buffer — the classic per-frame hitch."*
`SmudgePass.kt:34-40` cites the same rule as the reason for its own structure.
`DabPass` is the one pass still missing it, and it is the one on the latency
path the whole front-buffered architecture exists to protect.

Distinct from ANALYSIS D9's `FullRectQuad` orphaning bullet: that one is
scoped to a mid-stroke *resize*, and `FullRectQuad`'s steady state re-uploads
nothing at all. This is a different object, hit on every dab.

Fix: one `glBufferData(..., null, GL_STREAM_DRAW)` before the subdata, exactly
as `CompositePass` does. Magnitude on real hardware is unmeasured (D7); the
change is one line, matches the in-repo rule, and is strictly safer.

### F-8 — Every MOVE, UP and hover sample pays a `getToolType()` JNI call that is thrown away. [PR]

The record port made `tool` a mandatory argument of `PointerSample.set`, so
`AndroidCanvasInput` now computes `toolOf(e.getToolType(...))` on **every**
sample: AndroidCanvasInput.kt:140 (the historical-sample loop inside
`ACTION_MOVE`), :158 (current MOVE), :174 (UP), :231 (HOVER_MOVE).

Three of those four consumers discard it:
`CanvasTouchHandler.onPointerMove` (:1102) forwards to `handleMove(...)` with
no tool, `onPointerUp` (:1112) likewise, and `onHoverMove` (:1166) calls
`stylus.onHoverMove(x, y, distance)`. Only `onPointerDown` (:1094, :1097) and
`onHoverEnter` (:1162) read `sample.tool`.

The pre-port code never called `getToolType` on any of those paths, so this is
a **new** per-sample JNI cost introduced by the port, on exactly the path
AGENTS.md's zero-allocation rule targets — worst in the historical loop, where
a 240 Hz digitizer batches several samples into one frame.

Fix: tool-less `set`/`setHover` overloads for the three call sites that do not
need it; keep the full signature for DOWN and hover-enter.

### F-9 — Every tool-settings slider republishes the whole Canvas state per drag frame. [PR]

`RmwSettingsSheet` has **eleven** `onValueChangeFinished = {}` no-ops while
`onValueChange` commits on every frame (e.g. :67-68 hardness, :91-92
stabilizer, :106-107 strength, :114-115 pickup rate). `FillSettingsSheet`'s
`FillSlider` passes no `onValueChangeFinished` at all. Each commit reaches
`updateFillParams`/`updateSmudgeParams`/`updateWaterParams`/`updateBlurParams`
(CanvasViewModel.kt:1269-1313), all of which call `updateToolUi()`
(:752-766) — a full `UiState.Ready` copy and `StateFlow` emission — so
`CanvasContent`, a ~2000-line composable, re-executes on every pointer-move
sample of the drag.

The Color panel's mixing-dish slider is a third instance, and an instructive
one: it already debounces the **disk** write (`dishTJob`, 200 ms) but not the
state republish, and the comment above it shows the team is aware of per-frame
recomposition in that very file.

Broader than the known U12, which covers only `BrushSettingsSheet`'s four
pressure-curve knots. These sheets have no need for live intermediate values —
`FillSettingsSheet`'s own header says changes "apply to the next touch".

Fix: local preview state, commit on `onValueChangeFinished`. ~12 sites,
3 files. U12's remaining knot sliders should ride the same change.

### F-10 — Undo and redo mark every layer's thumbnail dirty, not just the ones that changed. [ready, S]

The forward-edit path scopes thumbnail invalidation correctly, using
`changedLayers(before, after)` plus the specific `changedKeys` a pixel op
touched (CanvasViewModel.kt:2493-2497). The undo/redo path passes the **whole
stack**: `pixelLayers = foldedStack.layers.map(Layer::id)`
(CanvasViewModel.kt:2677-2681). One undo of a single stroke on a 16-layer
document therefore queues up to 16 isolated-layer composites and PBO
readbacks instead of one.

A different axis from D6's `LayerThumbnailPass` row, which is about how much
of *one* layer is recomposited — fixing that alone would not help, because the
ViewModel is still declaring all sixteen dirty. The `restores` list already in
scope at :2624 names exactly which layers received pixels.

---

## 3. UI, layout, and visual polish

### F-11 — The layer row's blend-mode caption clips instead of ellipsizing. [PR]

`LayerPanel.kt:601-606` sets `maxLines = 1` with **no** `overflow`, and
Compose's default is `TextOverflow.Clip`. The layer-name `Text` directly above
it (:595-600) correctly sets `overflow = TextOverflow.Ellipsis`, and
`StudioScreen.kt:541-547` does too, so this is a local omission rather than
house style. At 200 % font, or with a longer localized blend-mode name, the
caption hard-clips mid-glyph. One argument.

### F-12 — The two transient readout chips are two different designs. [ready, tiny]

The history/undo-depth readout uses `inverseSurface` + `shapes.large` + no
elevation (CanvasScreen.kt:1296-1299); the zoom/rotation readout uses
`surfaceContainerHigh` + `shapes.medium` + `tonalElevation = 3.dp`
(:1347-1350). Same role, same position, adjacent in time (a two-finger
tap-undo right after a navigation gesture shows both), two visual languages.
U15d proposes a third chip of this family for tool names, so settling one
shared surface now is worth more than the tidiness alone. The `inverseSurface`
treatment reads better over arbitrary artwork.

---

## 4. Localization (zh-Hans)

All four are real terminology defects rather than style preferences, and all
four are in one file. Together they are one small string pass. [PR]

### F-13 — "Stylus" is translated two different ways.

手写笔 in `settings_stylus_only` (:334) and `brush_tip_stylus` (:202); 触控笔
in `help_drawing_body` (:383, twice) and `help_brush_tip_body` (:392). English
uses one word throughout. A user reading the help for the setting they are
looking at meets a different word for the same device. Standardise on 触控笔 —
already the majority usage here and the more common generic term in Chinese
Android UIs.

### F-14 — Help headings drift from the labels they explain, twice onto another feature's term.

English reuses each control's own label as its help heading, so the reader can
map help text back to the control. Chinese substitutes a different word in
several places, and twice the substitute is **already the correct translation
of an unrelated setting**:

- `brush_grain` is 纸张纹理 (:223), but its help paragraph opens 颗粒 (:394) —
  which is `water_granulation` (:137). The help for "Paper grain" reads as if
  it describes "Granulation".
- `fill_reference` is 参考 (:147), but its help line says 取样 (:395) — which
  is `eyedropper_sample` (:232), a different tool entirely.
- Milder: `brush_size` 大小 (:189) vs help 尺寸 (:391);
  `settings_snap_right_angles` 旋转吸附到直角 (:345) vs help 对齐直角 (:383).

### F-15 — 叠加 is used for both the Overlay blend mode and the brush buffer mode.

叠加 is the correct, expected rendering of the **Overlay** blend mode
(`blend_overlay`, :289). It is reused for the unrelated `brush_buffer_mode`
(:224) and `brush_buffer_accumulate` 逐渐叠加 (:226). A user who learned
"叠加 = Overlay" in the Layer panel meets it in Brush Settings meaning whether
repeated dabs keep darkening. Free the word for the blend mode; 叠色 /
逐渐加深 reads more accurately anyway, since the effect is darkening.

### F-16 — `settings_pen_button_none` is wordy.

不执行操作 against siblings 橡皮擦 / 吸管. 无操作 is the idiomatic, length-matched
form.

---

## 5. Build, CI, and the desktop port

### F-17 — CI never builds the configuration that actually ships. [PR]

`ci.yml:49` runs `testDebugUnitTest lintDebug assembleDebug` — debug,
**unminified**. `ci.yml:62` runs `-Pbangnidraw.mixbox=false assembleRelease` —
minified but **Mixbox-stripped**. The combination `release.yml` and
`scripts/release.sh` actually publish — Mixbox-enabled **and** R8-minified — is
never built until a `v*` tag is pushed.

AGENTS.md names this exact failure mode: *"'Works in debug, breaks in release'
is almost always a missing R8 keep rule for a new reflection/serialization
entry point."* The risk is at its highest right now: `:engine-core` and
`:engine-gl` became separate Gradle modules three PRs ago, so R8 traverses a
module boundary that did not exist when `proguard-rules.pro`'s keep rule was
written. (The rule is package-prefixed and still matches both modules — checked
— but that is exactly the fact a refactor can silently break.)

Fix: one more `ci.yml` step, `./gradlew assembleRelease` with default
properties, mirroring the shape of the stripped step already there.

### F-18 — The desktop GLES30 actual hands LWJGL heap buffers, which cannot work. [ready, M — desktop-blocking, zero user impact today]

`engine-gl/src/desktopMain/.../platform/GLES30.kt` bridges Android's
`(count, array, offset)` overloads to LWJGL's buffer-only API with
`IntBuffer.wrap` / `FloatBuffer.wrap` at twelve call sites (:177, :180, :189,
:192, :228, :231, :234, :237, :242, :248, :256, :374). Its own KDoc (:17)
documents this as the design.

`*Buffer.wrap()` is **always** heap-backed — unconditional JDK contract.
LWJGL's generated bindings extract a raw pointer via
`MemoryUtil.memAddress(buffer)`, which for a non-direct buffer does not throw:
it returns a small offset-derived integer. Verified empirically against the
pinned `lwjgl 3.4.3`: a heap `IntBuffer.wrap` yields `memAddress = 16`, a
direct buffer yields a real ~48-bit address. `0x10` is in the unmapped zero
page, so the driver faults the moment it writes the out-parameter.

This is hit by ordinary startup — `glGetIntegerv` in `GlCaps`, `glGenTextures`
in `TilePool`, `glGenFramebuffers` in `GlFbo`, `glGetProgramiv`/`glGetShaderiv`
in `GlProgram` — and by the `Buffer`-passthrough entries used on the reopen
path, which **R-043 deliberately allows to be heap buffers** because Android's
JNI glue tolerates them via `GetPrimitiveArrayCritical`. LWJGL has no such
fallback.

No user impact today: no desktop shell exists (DESKTOP.md Phase 2 is
unlanded), and Android is untouched. But it means DESKTOP.md's "thin one-line
delegation" premise is wrong for about twelve of the sixty-four entries, and
the facade as built cannot start the engine it was extracted to enable.

Fix: `MemoryStack.stackPush()` direct buffers for the array entries (the `n`
is always tiny), and a staging direct buffer in the seam for the
`Buffer`-passthrough entries, since R-043 commits the shared engine to
accepting heap buffers. Not implemented this session: it cannot be verified in
CI without a desktop GL context, and shipping an unverifiable rewrite of
twelve GL entry points is worse than recording it precisely.

### F-19 — Adjacent gaps around the same seam. [ready, tiny each]

- The no-Mixbox build is verified for Android only; the desktop target's
  `nomixbox` flavour is never compiled or tested, and no `nomixboxTest`
  directory exists. Given Mixbox is CC BY-NC 4.0 (ADR 0003) — the whole reason
  the stripped variant exists — a permissive desktop build is a plausible real
  combination. One extra CI invocation:
  `-Pbangnidraw.mixbox=false :engine-gl:desktopTest`.
- Neither `:engine-core` nor `:engine-gl` is covered by any lint task; the
  unqualified `lintDebug` finds none, and those KMP modules register only
  publish-lint-jar tasks. Since #173 the `input/` package — real stylus
  handling — lives in `engine-core`, now unlinted. Worth confirming whether
  AGP's KMP lint wiring exists yet; if it genuinely does not, that belongs in
  AGENTS.md's "don't fix these" list rather than being rediscovered.
- `PlatformImportBanContractTest`'s `QUALIFIED_ANDROID` regex requires a
  lowercase segment after `android.`, so `android.R.*` and `android.Manifest.*`
  slip through; `isComment()` treats `/* x */ code` as fully commented. Both
  are the same "front-runs the compiler" character as the declined R-034, so
  this is optional polish — the desktop compiler is still the hard gate.
- `ClasspathEngineAssets` (desktop) has no tests at all, despite having
  shipped a real alpha-channel bug one commit ago (`f7b5d82`). `MixboxLutTest`
  reads the PNG directly via `ImageIO`, bypassing the decode path that had the
  bug.

---

## 6. Product gaps and journeys

### F-20 — The brush-preset picker is thirteen unlabelled text chips in one scrolling row. [ready, S]

`BrushSettingsSheet.kt:91` puts every same-erase-mode preset into one flat
list and :167-186 renders them as plain-text `FilterChip`s in a single
`horizontalScroll`, with no icons, no grouping, and no cue that more exist
off-screen. This is the only in-flow way to reach the specialty presets beyond
the five on the rail. `ToolGlyphs` already resolves a per-preset icon from the
stored `BrushPreset.icon` key — the chip row simply never calls it.

Distinct from U15d, which is about non-paint tools hidden behind rail
overflow; this is the paint-preset switcher itself. Fix: `leadingIcon` from
the existing glyph resolution, and split core from specialty.

### F-21 — The Studio shelf has no search, sort, or filter. [idea, M]

`ProjectStore.list()` is hard-ordered newest-first (:672-674) with no query,
and `StudioScreen` renders it straight through. For the "quick sketch on the
train" persona accumulating small sketches over weeks, the only way to find an
older painting is to scroll and read thumbnails. Different from the known U11
(duplicate titles) and from the Delight backlog's "Favorites and collections",
which is about starring rather than finding. Smallest useful version: a
title-substring filter applied client-side, no persistence change.

### F-22 — No numeric entry for brush size or opacity. [ready, S]

Every tool parameter is slider-only with a display-only value label
(BrushSettingsSheet.kt:225-238); there is no way to type an exact size to
match a previous stroke. The app already ships the exact pattern needed —
`NewCanvasDialog`'s `DimensionField` is an `OutlinedTextField` with a numeric
keyboard (:410-470). Tap or long-press the value label to swap in that field,
committing on IME action; the drag interaction is untouched.

### F-23 — No canvas rotation lock. [idea, S]

Two-finger navigation always composes rotation (`CanvasTouchHandler.kt`
:709-742); the snap-right-angles preference changes only which angle is
settled on, not whether rotation happens at all. A slightly twisted pinch
always rotates the paper. `docs/plan/01-product.md` §6 benchmarks the rail and
gesture feel against Procreate, which has an explicit rotation lock. One more
`SwitchRow` beside "Snap right angles" and a guard in `applyNavigation` — no
new gesture, which is what that document refuses to add.

---

## 7. Delight

### F-24 — Long-press "+" to create instantly with the remembered size and paper. [ready, tiny]

`NewCanvasDialog` already persists the last custom size and paper colour
across sessions (:92-93, :105-131) so reopening starts where the user left
off, yet a repeat sketcher must still open the dialog and tap Create every
time. `NewPaintingCell` (StudioScreen.kt:399-403) is a plain `.clickable`;
`ResetViewPill.kt:72-77` already demonstrates the `combinedClickable`
tap-plus-long-press pattern. Long-press skips straight to
`onCreate(lastSize, lastPaper)`. Purely additive — the tap default is
untouched, so it costs a first-time user nothing.

### F-25 — A haptic tick as the mixing dish crosses 50 %. [idea, tiny — speculative]

The colour ring already ticks at hue detents via `HueMilestone.crossed`
(ColorPanel.kt:297, :316) and D8 proposes reusing it for the eyedropper drag.
The dish slider — the control for the app's signature pigment feature — has no
haptic at all. Flagged speculative on purpose: unlike a pigment-wheel spoke,
"50 % of a linear mix" is not obviously a landmark worth feeling. Only worth
doing alongside another reason to touch that function.

---

## 8. Checked this pass and found clean

Recorded so the next session does not re-derive them, and because two of these
refute hypotheses this review started with.

- **Bottom-anchored Canvas overlays clear the dock and ledge correctly.** This
  pass began suspecting the storage-full banner had the same defect the fill
  progress card once had. It does not, and cannot: `CanvasOverlayClearance`
  (engine-core, :11-15) yields DOCK 120 dp / SHORT 64 dp / else 16 dp, and the
  banner, the reset pill and the fill card are siblings in **one** `Column`
  under a single shared `padding(bottom = overlayBottomPadding)`
  (CanvasScreen.kt:1367-1434), so they cannot drift apart. Both clearance
  constants decompose exactly into control height plus one 16 dp gap. The
  history and view readouts are top-anchored, so the concern does not apply to
  them at all.
- **R-042's decline (attach-before-events scheduler contract) is correct.**
  Re-traced independently: `frameScheduler` is assigned in exactly one place
  (`AndroidCanvasInput.init`), the adapter is constructed in exactly one place
  (CanvasSurface.kt:89-91) **outside** `key(canvas)`, and a new handler always
  arrives paired with a new adapter. Composition resolves before
  `AndroidView`'s update, so the assignment always precedes any dispatch.
- **The record port preserved input fidelity.** A line-by-line diff of the
  pre-port `onTouch`/`onHover`/`onGenericMotion`/`fill` against the current
  adapter confirms pressure, tilt, orientation, hover distance, scroll ticks,
  button state, `FLAG_CANCELED` handling and the historical-then-current
  ordering are all preserved. The predicted tail's array order matches the
  deleted `fill()` exactly.
- **Engine-core's document, history and blend maths hold.** Merge/flatten
  undo-redo tile-set arithmetic (including the opacity-widening path), the
  eight blend formulas against `docs/plan/05-layers.md` §4, `MemoryBudget`'s
  saturating arithmetic, `HistoryJournal`'s prune and byte accounting across
  repeat undo/redo, and `SandwichPolicy.stale`'s per-operation table were all
  re-derived by hand and found correct.
- **CPU/GLSL twins match.** `StrokeMerge` vs `mergeStroke`, the watercolor
  colour/wet/overlay kernels vs their shaders, and `InkBrushMask` vs
  `dab.frag`'s `inkBrushMask` match constant-for-constant. R-055's deferred
  elliptical-feather asymmetry **has since been fixed** — both sides now use
  an `fwidth`/analytic-gradient feather — which matters because seven shipped
  presets now use `TipShape.Flat`, making R-055's old "unreachable today"
  caveat stale.
- **The module extraction itself is clean.** No leftover `engine/gl`,
  `engine/core` or `mixbox` directories under `app/src/main`; no stray
  `android.opengl.GLES30` imports in `:app`; all 64 facade constants match 1:1
  across facade and both actuals; `engine-core`'s widened members are
  minimum-necessary and compiler-driven; all three modules pin JDK 17.
- **The tripwires added in #173 are weaker than their comments claim.** The
  frame-callback cache bound asserts inside `AndroidCanvasInput`
  (:60-63) and its comment says *"the JVM suite fails loudly"* — but no test
  anywhere references `AndroidCanvasInput` or `Choreographer`, there is no
  Robolectric (R-037 declined adding it), and Kotlin's `assert()` is a no-op
  on Android by default. The claimed net exists on neither side. The sibling
  predictor tripwire *is* meaningfully covered, because its caller is pure JVM
  and the test fake mirrors the check. Fix the comment, or extract a
  platform-agnostic bounded cache a JVM test can drive. Related: the
  `frameScheduler` swap-cancel branch has no test exercising an actual swap —
  every test assigns once, to a fresh handler. That branch is pure JVM and
  `QueuedFrameScheduler` already exists, so it is testable today. [ready, tiny]

---

## 9. Implemented this session

Ordered as worked. Each is its own branch and PR, reviewed to steady state
under `CLAUDE.md`'s rules before merging.

| Item | Branch | What lands |
| --- | --- | --- |
| F-1, F-2 | `fable/gallery-write-integrity` | A failed Gallery write stays hidden and retryable instead of publishing a truncated image; `sync` contains provider faults like `withdraw` already does. |
| F-3 | `fable/atomic-write-races` | Unique temp names so concurrent writers cannot truncate each other; `BrushPresetStore` serialised like `PaletteStore`. |
| F-7 | `fable/dab-buffer-orphan` | `DabPass` orphans before uploading, matching `CompositePass`'s documented rule. |
| F-8 | `fable/pointer-sample-tool` | Tool-less sample overloads for the paths that discard it. |
| F-9, F-11 | `fable/settings-slider-commit` | Tool sliders commit on release; the layer caption ellipsizes. |
| F-13–F-16 | `fable/zh-hans-terminology` | One terminology pass over the Chinese strings. |
| F-17 | `fable/ci-release-build` | CI builds the configuration that actually ships. |
| ANALYSIS follow-ups 1–5 | `fable/contract-test-hygiene` | The five deferred source-contract test fixes, all re-verified against current code. |

Not implemented, and why: **F-5** (Fill expand) is a behaviour change needing a
product call and a 4096² measurement; **F-18** (desktop buffers) cannot be
verified without a desktop GL context, and an unverifiable rewrite of twelve
GL entry points is worse than a precise record. Both are written up above in
enough detail to be picked up cold.
