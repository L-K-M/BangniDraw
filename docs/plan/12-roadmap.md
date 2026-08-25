# Roadmap — the ten PRs to v1.0, and what comes after

This document expands PLAN.md §10 (the ten PR-sized steps) into something an
agent can execute one PR at a time: per step the goal, the files and classes
it creates (package layout from PLAN.md §3 and `docs/plan/02-architecture.md`),
what it depends on, the acceptance test on a device plus the JVM tests that
land with it, the risks and the prepared fallback, and a size. It also fixes
the **definition of done** every PR is held to, draws the dependency graph
(where two agents can work in parallel), and holds the post-v1 backlog with
one-line designs. PLAN.md's table is the summary; where this document and
PLAN.md disagree, PLAN.md wins and the disagreement is a bug here. Detailed
designs live in the sibling documents cross-referenced from each step; this
one only says *what lands when* and *how we know it landed*.

## 1. Rules of the road

- **One step, one PR, one branch off `main`.** A PR that does two steps is
  two PRs. A step that turns out to be two PRs (see step 2) is split at the
  seam noted below, never at an arbitrary point.
- **Order is a plan, not a contract.** Steps 4–8 can be reordered when a
  review finding or a device test says so; steps 1→2→3 and 9→10 cannot. Any
  reorder is noted in AGENTS.md ("deviations discovered while building").
- **Every step leaves `main` shippable.** A half-built tool is hidden behind
  nothing — it is simply not merged. Principle 6: dropped before half-baked.
- **Sizes**: S ≈ under a day of agent work and under ~800 changed lines; M ≈
  one to three days, ~800–2,500 lines; L ≈ a week or the PR is split.
  Estimates count tests.

## 2. Definition of done — every PR

The checklist is the merge gate; the GLM reviewer and the maintainer both
hold the PR to it. An item that does not apply is stated as not applying in
the PR description, not skipped silently.

| # | Gate | How it is checked |
| --- | --- | --- |
| 1 | CI green: `testDebugUnitTest lintDebug assembleDebug` | `ci.yml` on the PR; wrapper-validation first (CICD.md) |
| 2 | Lint clean — no new baseline entries, no `@Suppress` without a comment saying why | `lintDebug` is a hard gate; the reviewer reads every suppression |
| 3 | JVM tests for every pure-logic class touched; shader contract test updated when any GLSL uniform or declaration order changes | `app/src/test/...` diff present; `docs/plan/11-testing.md` lists what each class must cover |
| 4 | CPU reference and GPU implementation changed together (`Composite`, `DabGenerator` vs the shader) | reviewer diff check; PLAN.md §7 |
| 5 | Strings in `values/strings.xml` **and** `values-b+zh+Hans/strings.xml` — no literals in composables, no English-only key | `lintDebug` `MissingTranslation` is an error, not a warning |
| 6 | AGENTS.md updated for any quirk, footgun, deviation from PLAN.md, or third-party asset provenance | PR description links the AGENTS.md hunk |
| 7 | No new manifest permission, no INTERNET, no new dependency outside `libs.versions.toml` | reviewer checks the manifest and catalog diff |
| 8 | Version numbers appear only in `gradle/libs.versions.toml` (prose says "see libs.versions.toml") | grep in review |
| 9 | Manual device acceptance from this document's step table run and reported in the PR description (device, OS, result) | PR template section |
| 10 | GLM review at steady state per CLAUDE.md: every finding applied, declined with reasons, or refuted with evidence; scorecard posted | CLAUDE.md policy |
| 11 | Merge commit `Merge PR #NN: …`; branch deleted | history convention |

Gate 9 is the one agents forget. Pure-JVM tests cannot say whether a pencil
feels like a pencil; the step table's acceptance column is the *only*
evidence for that, so it is written down before merge, with the device.

## 3. The steps

### Step 1 — Scaffold (S, **landed on main** — commit `915199f`, 2026-08-24, CI green)

**Goal.** A repository that builds, tests, lints, releases, and reviews
itself, with the app launching to two placeholder screens under the right
name and icon. Nothing about drawing yet — but everything about *how this
project ships*, so that every later PR is pure product work.

**Creates** (all landed).

| Area | Files |
| --- | --- |
| Build | `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` (AGP + Kotlin + Compose BOM + Hilt + KSP + serialization + DataStore + graphics-core + input-motionprediction + Mixbox declared), `gradle.properties` (`android.suppressUnsupportedCompileSdk=37`), `local.properties.example`, `app/proguard-rules.pro`, `app/debug.keystore` (checked in, signs both build types — ADR 0005) |
| App | `BangniApp.kt` (Hilt application), `MainActivity.kt` (single activity, edge-to-edge), `ui/navigation/BangniNavHost.kt`, `ui/theme/{Color,Theme,Type}.kt` (saffron-on-indigo accent, light + dark), `ui/home/StudioScreen.kt` + `StudioViewModel.kt` (placeholder shelf with an About dialog carrying the Mixbox notice), `ui/canvas/CanvasScreen.kt` + `CanvasViewModel.kt` (placeholder with two-finger pan/zoom/rotate through the ported transforms) |
| Engine | `engine/core/ViewTransform.kt` and `FitTransform.kt` ported from Meltorama with their test classes (`MIN_SCALE = 0.25f`/`MAX_SCALE = 64f` relative to fit — `07-input-and-stylus.md` §7) |
| Resources | `values/strings.xml`, `values-b+zh+Hans/strings.xml`, adaptive icon from `media-sources/icon.png` via `scripts/generate_icons.py` (full-bleed background PNGs per density in `mipmap-*`, `mipmap-anydpi-v26`, an empty foreground vector) plus the hand-authored monochrome brush vector `drawable/ic_launcher_monochrome.xml`, `xml/backup_rules.xml` + `data_extraction_rules.xml` excluding `projects/` |
| CI/CD | `.github/workflows/ci.yml`, `release.yml`, `zai-code-review.yml`; `.github/dependabot.yml`; `scripts/build.sh`, `install.sh`, `release.sh`, `generate_icons.py`; `.claude/setup-android.sh` |
| Docs | `README.md` (with the `<!-- version -->` marker), `PLAN.md`, `AGENTS.md`, `CLAUDE.md`, `CICD.md`, `CONTRIBUTING.md`, `SECURITY.md`, `REVIEW.md`, `GLOSSARY.md`, `LICENSE` (Unlicense), `docs/plan/*`, `docs/decisions/0001–0005`, `docs/proposals/` (empty), `third-party/mixbox/LICENSE` + `README.md` |

**Depends on.** Nothing.

**Acceptance.** Manual: `scripts/install.sh` on a phone and a tablet — the
launcher shows 帮你Draw with the icon, Studio opens, **+** navigates to the
Canvas placeholder and back, device language zh-Hans shows Chinese strings.
JVM: `ViewTransformTest` (gesture composition, clamping, rebase) passes on
CI. `release.yml` dry-run: `scripts/release.sh 0.1.0` without `--push`
produces a consistent bump.

**Risks.** The Meltorama toolchain pair (`compileSdkVersion("android-37.0")`
+ `suppressUnsupportedCompileSdk`) is easy to "fix" into breakage — AGENTS.md
says not to. If the icon generator output looks wrong at small sizes (the
brush stroke thins out), adjust the safe-zone scale in `generate_icons.py`,
never hand-edit the PNGs.

### Step 2 — Engine core (L; split at the seam below if it exceeds L)

**Goal.** Draw on a tiled, layered, GPU-composited canvas with one round
brush, with visibly low latency, and pan/zoom/rotate it. This is the step
where the architecture is proven or disproven; everything after it is
features on a working engine.

**Creates.**

| Package | Classes |
| --- | --- |
| `engine/core` | `Document`, `Layer`, `LayerStack` (single layer used for now, API complete), `BlendMode` (`NORMAL` only wired), `TileGrid`, `TileKey`, `FitTransform`, `GestureArbiter`, `StrokeInput`, `Stabilizer`, `PressureCurve`, `DabGenerator`, `Dab`, `DabBatch`, `BrushPreset` (one hard-coded round preset), `ToolKind`, `Composite` (CPU reference, `NORMAL`), `MemoryBudget`, `CanvasPresets` |
| `engine/gl` | `CanvasRenderer` (the `GLFrontBufferedRenderer` callback pair), `TilePool` (texture-array atlas with runtime-queried limits), `LayerTextures`, `StrokeBuffer`, `DabPass`, `CompositePass`, `SandwichCache` (trivial with one layer, but the pass structure is final), `Readback` (PBO, async), `Shaders` (sources as Kotlin string constants + a `GlShaderContractTest`) |
| `input` | `CanvasTouchHandler`, `StylusState`, `PalmRejection`, `Predictor` (wrapper over `MotionEventPredictor`) |
| `ui/canvas` | `CanvasSurface` (the `AndroidView`-hosted `SurfaceView`), `CanvasViewModel` gains the `EngineSession` handle, a reset-view pill, a debug overlay toggle (`docs/plan/10-performance.md` §5.3) |

**Split seam if needed.** 2a = everything up to a stroke on the
multi-buffered layer with touch only (no front buffer, no prediction, no
stylus axes); 2b = front-buffered path, `Predictor`, stylus axes, palm
rejection. 2a is already a mergeable, testable engine; 2b is the latency
work and is where device-specific surprises live.

**Depends on.** Step 1 (`ViewTransform`, theme, nav).

**Acceptance.** Manual, on a Galaxy Tab S-class 120 Hz tablet and on a 60 Hz
phone: a fast S Pen scribble shows no visible gap between pen tip and ink
end; the predicted tail never leaves a hook on pen-up; a resting palm while
the pen is down leaves no mark; two-finger pinch-zoom-rotate is smooth at
any zoom and rotation snaps near 0°; two-finger tap does nothing yet but
does *not* draw a dot (the arbiter's tap window). Debug overlay reports the
budgets from `docs/plan/10-performance.md` §2 inside target. JVM:
`GestureArbiterTest` (pointer timelines → draw/navigate/tap), `StabilizerTest`
+ `DabGeneratorTest` (spacing invariant under zoom, pressure curves,
zero-length stroke = one dab), `TileGridTest` (dirty rect → keys, edges),
`CompositeTest` (`NORMAL` against hand-computed premultiplied pixels),
`MemoryBudgetTest`, `GlShaderContractTest`, `FitTransformTest`.

**Risks and fallbacks.**

| Risk | If it bites |
| --- | --- |
| Front-buffer usage unsupported on a test device (the library falls back internally, but latency then looks ordinary) | Accept the fallback; the debug overlay shows which path is live so a bad review is not mistaken for a bug. Never write our own SurfaceControl path. |
| `GL_MAX_ARRAY_TEXTURE_LAYERS` low on a real GPU | `TilePool` already sizes itself from the queried value and allocates several arrays; `MemoryBudget` uses the real number. No code path may assume 256. |
| Ring-buffer overrun at 120 Hz with historical + predicted samples | Overrun policy is "coalesce, never drop a pen-up"; the overlay counts overruns; if non-zero on a Tab S, batch dabs per input event rather than per sample. |
| Allocation on the touch path (a lambda, a boxed Float) shows up as GC jank | The `CanvasTouchHandler` test asserts no allocation via the pattern in `docs/plan/10-performance.md` §2.4; profile with Perfetto before merging 2b. |

**PR breakdown (written 2026-08-24, `docs/EXECUTION.md` Step A).** The step is
L, so it is split at the 2a/2b seam above and 2a is split again where the
diff would otherwise pass ~1,500 lines. Each PR builds, tests and lints on
its own, and each is useful on its own.

Status: ⬜ not started · 🔁 open, in review · 🟢 landed on `main` (with commit).

| PR | Branch | Scope (one line) | Acceptance check | Status |
| --- | --- | --- | --- | --- |
| 2.1 | `fable/engine-core-document` | `engine/core` document model: `PerfConstants`, `TileKey`/`IntRect`/`TileGrid`, `LayerId`/`LayerProps`/`Layer`/`LayerRecord`/`BlendMode`/`LayerStack` (+ `StackEdit`/`StackResult`/`PixelOp`/`HistoryEntry` declarations), `Document`, `Composite` (CPU reference, all eight modes), `MemoryBudget`, `CanvasPresets`, `Clock`/`RandomSource` | JVM: `TileGridTest`, `LayerStackTest`, `CompositeTest`, `MemoryBudgetTest`, `CanvasPresetsTest` green; `lintDebug` clean. No device check (nothing user-visible changes) | ✅ #7, merged 2026-08-25 |
| 2.2 | `fable/engine-core-stroke` | `engine/core` stroke math: `StrokeInput`(+batch), `PressureCurve`, `Stabilizer`, `Dab`/`DabBatch`/`DabRing`, `DabGenerator`, `BrushPreset`/`Curve`/`ToolKind` with the one round preset | JVM: `StabilizerTest`, `DabGeneratorTest` (+ golden stroke), `PressureCurveTest`, `DabRingTest`, `BrushPresetTest`. Still no device check | ⬜ |
| 2.3 | `fable/engine-gl-compositor` | `engine/gl` foundation and the compositor: `GlCaps`/`GlProgram`/`GlFbo`/`GlState`, `Shaders`, `TilePool`, `LayerTextures`, `ScreenTransform`, `CompositePass`, `SandwichCache`, `CanvasRenderer` (multi-buffered path only), `EngineSession`, `ui/canvas/CanvasSurface`, reset-view pill | Device: open a new canvas — the paper colour fills the fitted canvas rect at the right size and orientation, and the reset-view pill returns to fit after a programmatic nudge of the transform (the debug overlay is 2.5's). No touch navigation yet: `input/` is 2.4, so the view is driven programmatically here (see the note below). JVM: `GlShaderContractTest`, `GlslDeclarationOrderTest`, `ScreenTransformTest` (`ScreenTransform` is created here — pure math, so it is the one piece of this row a JVM test can pin end to end) | ⬜ |
| 2.4 | `fable/stroke-path-touch` | The stroke lands on pixels, with touch: `StrokeBuffer`, `DabPass`, `MergePass`, `Readback`, `input/` (`GestureArbiter`, `PalmRejection`, `StylusState`, `CanvasTouchHandler`) | Device: one finger draws a stroke that survives pen-up; two-finger tap does not leave a dot; two-finger pinch/zoom/rotate is smooth and rotation snaps near 0°, during a stroke as well as between strokes. JVM: `GestureArbiterTest`, `PalmRejectionTest`, `StylusStateTest`, `CanvasTouchHandlerTest` (the no-allocation assertion of `docs/plan/10-performance.md` §2.4 — the step-2 risk table above names it as the mitigation for touch-path GC jank, so it is a gate, not an optional extra), and the merge blend math cross-checked on the JVM against PR 2.1's CPU `Composite` reference wherever no GL context is needed (PLAN.md §7: the CPU reference is what pins the shader semantics). **This completes 2a** | ⬜ |
| 2.5 | `fable/front-buffered-stylus` | 2b: the front-buffered path (`onDrawFrontBufferedLayer`, `commit`, `cancel`), `TailBuffer`, `Predictor`, stylus axes (pressure/tilt/orientation/eraser end), palm rejection on device, unbuffered dispatch, debug overlay | Device: the full step-2 acceptance list above (S Pen scribble with no visible gap, no hook on pen-up, resting palm leaves no mark, overlay budgets inside target). JVM: `StabilizerTest` gains the predicted-tail cases — rationale in the note below. **This completes step 2** | ⬜ |

*Why the predicted-tail cases and nothing else in 2.5.* The tail runs through a
*copy* of the stabilizer state (`04-tools.md` §4), so "continues the stabilized
line" and "never advances the real state" are pure claims, and they are the "no
hook on pen-up" risk in testable form. No `PredictorTest`/`TailBufferTest`:
`Predictor` is a thin wrapper whose whole purpose is that core never sees the
androidx type (`02-architecture.md` §2.6) and `TailBuffer` is a GL object
(§2.3) — neither holds logic a JVM test could pin, which is why
`11-testing.md` gates prediction on the device checklist instead.

**Split seam for 2.3.** It is the largest row and may well pass the ~1,500-line
criterion once written. Rule 1 of this document says a PR that turns out to be
two is split *at a named seam, never at an arbitrary point*, so the seam is
fixed here in advance: **2.3a** = the GL foundation that has no opinion about
compositing (`GlCaps`, `GlProgram`, `GlFbo`, `GlState`, `Shaders`, `TilePool`,
`LayerTextures`, with `GlShaderContractTest` and `GlslDeclarationOrderTest`);
**2.3b** = everything that draws (`ScreenTransform` + its test, `CompositePass`,
`SandwichCache`, `CanvasRenderer`, `EngineSession`, `CanvasSurface`, the
reset-view pill) and the device acceptance check, which belongs to the half
that puts pixels on screen. Measure before writing; split only if it exceeds.

**Why 2.3 has no touch navigation.** An earlier draft of the 2.3 row asked the
device check for two-finger pinch/zoom/rotate, which it cannot deliver: every
gesture component (`CanvasTouchHandler`, `GestureArbiter`, `PalmRejection`,
`StylusState`, all of `docs/plan/07-input-and-stylus.md`) is scoped to 2.4, and
`07` §2 makes `CanvasTouchHandler` the single owner of `MotionEvent` — so
pulling "just the viewport half" into 2.3 would split one coherent area across
two PRs, which rule 1 forbids, and grow the row that already needed a split
seam. The gesture clauses therefore live in 2.4, where the code does. 2.3 still
stands on its own: it is the PR that makes the canvas appear, `ScreenTransform`
is fully pinned on the JVM without a device, and the reset-view pill exercises
the view path end to end by setting the transform directly.

Decisions taken while planning, to be restated in each PR description:

- `HistoryEntry` is declared in 2.1 (not step 3) because `LayerStack`'s
  tested contract is "each operation returns the entry that inverts it"
  (`docs/plan/05-layers.md` §3.1, §5, `11-testing.md` §3.7). Only the
  declaration lands here; the journal, the on-disk codec and the
  `Stroke`/`Fill` payload capture stay in step 3 where the roadmap puts them.
- `Composite` ships all eight blend modes in 2.1 rather than `NORMAL` only:
  the eight are one `when` and `docs/plan/05-layers.md` §4 is normative for
  them, so writing seven of them later would be a second review of the same
  table. Only `NORMAL` is *wired* in the renderer in step 2, as the step says.

### Step 3 — Document, undo, Studio (M)

**Goal.** Nothing is ever lost. Paintings persist in their project folder,
undo is a journal of tile deltas that survives process death, the Studio
lists them newest first and can create, open and delete.

**Creates.** `engine/core`: `HistoryJournal`, `HistoryEntry`,
`AutosavePolicy` (Meltorama's constants, ported). `data`: `ProjectStore`
(folder per uuid, `project.json` written last, tmp+rename), `TileStore`
(deflated premultiplied RGBA8, `<tx>_<ty>.tile`), `HistoryStore`
(`history/<seq>.entry`), `Prefs` (DataStore), `CpuTile`. `ui/home`:
`StudioScreen` becomes real — shelf grid, hold menu (delete with confirm;
duplicate and share are stubs until step 4), storage readout;
`NewCanvasDialog` (presets from `CanvasPresets`, custom within
`MemoryBudget`, paper color). `ui/canvas`: undo/redo in the top strip, the
"history capped at N steps / M MB" readout. Thumbnail writer (`thumb.png`
from the readback composite on checkpoint).

**Carried in from PR 2.1's review** (both recorded in AGENTS.md, the first
also as REVIEW.md R-001) — this step must not land without them:
`ProjectStore.load` must refuse to turn a malformed layer id into a path —
count the layer among the unreadable and never throw the open away. (Note the
granularity: `docs/plan/06-document-and-persistence.md` §4 returns "the count of
unreadable **tiles**" with the load result, and a dropped layer is not a tile;
step 3 either adds an unreadable-layers count beside it or amends §4 to cover
both, because reporting a lost layer as N lost tiles is a misleading readout.)
Separately, the `LayerStack.nextName` counter must survive undo and reopen.

Two more came out of PR 2.1's later rounds. `ProjectStore.load` must degrade on
a **case-insensitive** id collision rather than throwing: `LayerStack` refuses
one at construction, but two ids differing only by case name one directory on a
Windows or macOS copy, and a document that arrives that way must open with a
layer counted among the unreadable, not fail. And the loader's `Json` instance
must be able to decode a NaN or Infinity token (`allowSpecialFloatingPointValues`,
or a coercing serializer for `opacity`) — `LayerRecord.toProps` already degrades
such a value, but kotlinx's default decoder throws on the token before it is
ever reached, so §4's "one bad field must never fail an open" would not hold.
That is REVIEW.md R-020, deferred here rather than declined.

Step 3 is the **latest** the layer-id→path guard may arrive, not the date it is
scheduled for: it lands with the first code that builds a path out of a layer
id, whenever that ships. As it turned out, PR 2.1 brought it forward — `LayerId`'s
constructor now rejects anything that is not one safe path segment, and
`LayerRecord.toPropsOrNull` returns `null` for such a record instead of
throwing. What is left for this step is the *policy*: `ProjectStore.load` must
call `toPropsOrNull`, drop the layer, and count it among the unreadable rather
than failing the open.

The counter's mechanism is decided rather than left open, because a hard gate
with an undecided design gets settled arbitrarily under pressure: the counter
stays on `LayerStack` where `05-layers.md` §3 puts it, `project.json` gains a
field for it, and **undo never restores it**. That is the whole point — it is
not journalled state, it is a monotonic allocator, so an add → undo → add must
still yield a fresh name. A journal-entry slot would restore the old value on
undo and reissue the name, which is the bug. Crash recovery is the single path
that *rebuilds* the counter rather than reading it: `project.json` is only
written at a checkpoint, so after the kill this step's own acceptance performs
it is stale with respect to any layer added since. On journal replay, re-derive
the counter as the **maximum of the persisted value and one past the high-water
mark of every default name in the recovered stack** — the layers loaded from
the checkpoint as well as every name the journal assigns during replay,
including to layers the replay then deletes. Matching `@string/layer_default N`
only, since a user-typed name carries no number to honour. The scan assumes
`@string/layer_default` is not localized; the app ships `values/` and
`values-b+zh+Hans/`, so if that string is ever translated, names written under
one locale stop matching the pattern under another and the scan under-recovers.
`max(persisted, high-water + 1)` still floors it at the last checkpoint, but
names added since could be reissued — so mark the string
`translatable="false"`. Persisting the counter at add time is *not* the
alternative it looks like: it would mean writing `project.json` off-checkpoint
or journalling the counter, and this paragraph rules out both. Taking the replayed
names alone would be wrong in the commonest case this step tests: a kill with
no layer adds since the checkpoint replays no adds at all, so the high-water
mark is empty and the counter would reset to 1 beside a checkpoint that already
holds a "Layer 1".

**Depends on.** Step 2 (tiles and readback are what gets saved).

**Acceptance.** Manual: paint, kill the process mid-stroke sequence with
`adb shell am crash ch.lkmc.bangnidraw.debug` (or `adb shell run-as
ch.lkmc.bangnidraw.debug kill -9 <pid>`; `am kill` is a no-op on the
foreground app and does not count), reopen — the painting is intact to the
last completed stroke and undo still steps back through earlier strokes;
`adb shell am force-stop` during a checkpoint — `project.json` is either
old or new, never torn. Studio shows the painting with a fresh thumbnail and "edited
just now"; delete asks for confirmation. JVM: `HistoryJournalTest`
(undo/redo/truncate-on-new-edit/prune, round trip through the on-disk
encoding), `LayerStackTest` invariants for the ops the journal inverts,
`AutosavePolicyTest` (quiet window, ceiling), `ProjectStoreTest` on a
temporary folder (write-last commit point, recovery from a stray `.tmp`),
`TileStoreTest` (deflate round trip, premultiplied bytes untouched).

**Risks.** Journal growth on big brushes — each step stores before-tiles
for every touched tile; a full-canvas airbrush sweep on 4096² is up to 256
tiles. The cap is bytes as well as steps, and pruning is by bytes first
(`docs/plan/06-document-and-persistence.md`). If IO cannot keep up with a
fast painter, tiles queue per layer and coalesce (last write wins) — the
readback handover already carries a sequence number so a stale tile never
overwrites a newer one.

### Step 4 — Gallery sync and share (S)

**Goal.** One gallery image per painting, always the latest version;
share and export from the Studio hold menu.

**Creates.** `data/GalleryExporter` (MediaStore insert with
`RELATIVE_PATH = Pictures/帮你Draw`, `IS_PENDING`, then in-place rewrite via
`openOutputStream(uri, "wt")`; re-insert when the item is gone or throws
`SecurityException` after a reinstall), `data/ShareCache` (a `FileProvider`
cache dir for the share sheet), export sheet (PNG / JPEG quality),
`galleryUri` in `project.json`, the "delete gallery copy too?" question,
the gallery-sync toggle in `Prefs`.

**Depends on.** Step 3 (flattened readback + `project.json` field).
Independent of steps 5–8.

**Acceptance.** Manual: paint, leave the canvas — the system Gallery shows
the painting under Pictures/帮你Draw; paint more, leave again — the same
entry updated, not a second one; delete the gallery item by hand, paint
again — a fresh entry appears; ownership loss: on the debug build copy the
project folder out (`adb shell run-as ch.lkmc.bangnidraw.debug tar -c
files/projects | adb exec-out ...` — `adb backup`/`restore` will not do, the
backup rules from step 1 exclude `projects/`), uninstall, reinstall, copy
it back the same way via `run-as`, open the painting and paint — the old
`galleryUri` throws `SecurityException`, no crash, a fresh entry is
inserted. Share sends a PNG to another app. Checklist rows G1–G3 of
`docs/plan/11-testing.md` §8. JVM: the gallery-debounce case of
`CanvasViewModelTest` (`docs/plan/11-testing.md` §7) and a pure decision-table object
`GallerySyncDecision` ((uriPresent, isOwner, threw, modifiedByOther) → Insert /
Rewrite / Reinsert — `docs/plan/06-document-and-persistence.md` §9.2) — `GalleryExporter` itself stays Android-only and untested; PNG
bytes from `Composite` flatten of a fixture.

**Risks.** OEM gallery apps sometimes cache the old bitmap after an
in-place rewrite — the `DATE_MODIFIED` + `IS_PENDING` toggle in the
research facts is what invalidates them; if a Samsung Gallery still shows
stale, verify (to verify on device) whether a `SIZE` update is also needed
before resorting to delete+insert (which would lose the "one entry" promise
only for that device).

### Step 5 — Tool set (M)

**Goal.** Every preset in PLAN.md §6 feels like its name: pencil, ink pen,
paintbrush, airbrush, marker, hard and soft eraser, plus the eyedropper;
S Pen eraser end and side button mapped.

**Creates.** `tools/Tool`, `BrushTool`, `EraserTool`, `EyedropperTool`;
`BrushPreset` full parameter set (size, opacity, flow, hardness, spacing,
pressure curves ×3, tilt, velocity, jitter, stabilizer, pigment flag —
mixing itself is step 7, the flag round-trips now); `data/BrushPresetStore`
(built-in JSON in `assets/brushes/`, user edits in `filesDir/brushes/`);
`DabPass` grows the grain, hardness and oriented (squared) tip paths;
`DabGenerator` dynamics; `ui/canvas/ToolRail` with the two thin sliders,
`BrushSettingsSheet`, `HoverCursor` (size-accurate for stylus hover);
`StylusState` gains `TOOL_TYPE_ERASER` and button → eraser/eyedropper.

**Depends on.** Step 2. Can run concurrently with steps 3–4 on a branch
that only touches `tools/`, `DabPass`, `DabGenerator`, `BrushPreset` and the
rail; rebases cleanly because step 3 does not touch those files.

**Acceptance.** Manual, per row of PLAN.md §6: pencil shades wider and
lighter when the S Pen is tilted; ink pen line is smooth with a strong
stabilizer and no grain; marker builds to its opacity cap and never past;
airbrush is soft and slow; flipping the S Pen erases, the button erases
while held, the eyedropper picks the composite color; hover shows the brush
circle at the right size at any zoom. JVM: `DabGeneratorTest` per preset
(a fixture stroke → dab list with expected sizes/flows), `PressureCurveTest`,
`BrushPresetStoreTest` (JSON round trip, unknown keys ignored, built-ins
never overwritten), `StylusStateTest` (button/eraser precedence),
`GlShaderContractTest` extended for the new uniforms.

**Risks.** Presets feel wrong on the first device pass — expected; tuning
is JSON edits, not code, and the acceptance is re-run per preset, so this
step may take two device passes. Grain textures must be CC0 with provenance
in AGENTS.md (PLAN.md §9); until one exists, the pencil grain is procedural
noise in the shader.

### Step 6 — Layers (M)

**Goal.** Add, delete, duplicate, reorder by drag, merge down, flatten,
opacity, blend modes, visibility, alpha lock — with the memory budget
shown honestly.

**Creates.** `BlendMode` complete set, `Composite` for every mode,
`CompositePass` shader branches, `SandwichCache` doing real work (below and
above composites, invalidated per layer-op), `LayerStack` ops journaled as
inverses in `HistoryJournal`, `ui/canvas/LayerPanel` (slide-in; drag
handle; per-layer thumbnail from the readback), the layer cap readout from
`MemoryBudget`.

**Depends on.** Step 3 (journaled layer ops). Concurrent with 4, 5, 7, 8
apart from the shared `HistoryJournal` inverse-entry API, which step 3
defines up front.

**Acceptance.** Manual: 8 layers on a 4096² canvas on an 8 GB tablet,
paint on the middle one with layers above set to multiply and screen — the
live stroke previews correctly through them (sandwich) and the frame time
stays inside `docs/plan/10-performance.md` §2.3; reorder by drag, undo the
reorder, kill the app, reopen, redo it. Adding a layer past the cap shows
the stated limit instead of crashing. JVM: `CompositeTest` every blend mode
against hand-computed pixels including the alpha-lock case,
`LayerStackTest` (reorder/merge/delete/duplicate invariants, ids stable),
`HistoryJournalTest` for each non-pixel inverse, `MemoryBudgetTest` cap
table for the device classes in `docs/plan/10-performance.md` §1.

**Risks.** Sandwich invalidation bugs show as "the stroke previews wrong
until pen-up"; the CPU `Composite` on a small fixture document is the
oracle — a JVM test composes N layers two ways (full and sandwich) and
asserts equality, so the invalidation rules are pinned before the shader.

### Step 7 — Mixing, smudge, blur (M)

**Goal.** Blue + yellow = green: Mixbox in the dab merge for pigment
brushes, a smudge tool that drags color, a blur tool, the mixing dish in
the color panel.

**Creates.** `engine/mixbox/MixboxMixer` (CPU, via the jar; ADR 0003),
`engine/core/RgbMixer`, `ColorMixer` interface and the switch in `Prefs`;
`mixbox.glsl` vendored (with its header intact) and `mixbox_lut.png` loaded
unpremultiplied without mipmaps into a dedicated texture; `SmudgePass`
(ping-pong RMW over the dab rect; blur is a `SmudgePass` variant with a
separable kernel, `docs/plan/04-tools.md` §Smudge/Blur),
`tools/SmudgeTool`, `BlurTool`; `ui/canvas/ColorPanel` (HSV wheel, swatches,
mixing dish); the About-screen license text lands here too, not in step 10,
because the notice ships with the code.

**Depends on.** Step 5 (presets carry the pigment flag; the paintbrush is
the first pigment brush). Step 6 is not required.

**Acceptance.** Manual: paintbrush blue over yellow with pigment on gives a
green, not grey-brown, stroke; with `RgbMixer` selected the same stroke goes
grey; the mixing dish shows the same green; smudge drags a hard edge into a
tail and stays the right color; blur softens without darkening. JVM:
`ColorMixerTest` (blue + yellow → green hue band for `MixboxMixer`; `RgbMixer`
component-linear in stored sRGB), `SmudgeKernelTest` against the CPU reference on a fixture,
`GlShaderContractTest` asserts `mixbox_lut` is bound and the `mixbox.glsl`
header is present verbatim (license compliance is a test).

**Risks.** The LUT arrives premultiplied or sRGB-decoded and every mixture
is subtly wrong — the acceptance stroke catches it, and the loader asserts
the probe texel (research facts, Mixbox section). Latent mixing in a
fragment shader is the most expensive per-pixel work in the app; if the
paintbrush misses the frame budget on a 4 GB tablet, the fallback is a
lower-resolution stroke buffer for pigment brushes at high zoom-out, decided
by `docs/plan/10-performance.md` §7, never dropping Mixbox on that device.

### Step 8 — Fill (S)

**Goal.** Bucket fill that fills line art without halos.

**Creates.** `engine/core/FloodFill` (CPU scanline flood over the sampled
tiles; tolerance, contiguous/global, expand by N px, anti-aliased edge),
`tools/FillTool` (samples current or all layers via the readback, uploads
the result tiles, journals them like a stroke), fill options in the
settings sheet.

**Depends on.** Steps 3 (readback to CPU, journal) and 5 (`Tool`,
`ToolRail`, `BrushSettingsSheet` to be reachable from). Independent of 6–7.

**Acceptance.** Manual: fill inside inked line art with expand = 2 px — no
light halo along the lines; a gap in the line leaks as expected with
contiguous on and the tolerance stated; fill on an empty layer referencing
all layers works (the colouring-book workflow); undo restores. Fill of a
full 4096² region completes within the budget in
`docs/plan/10-performance.md` §2.7. JVM: `FloodFillTest` on fixtures
(tolerance boundaries, expand, gap behaviour, anti-aliased edge pixels
hand-computed, global mode).

**Risks.** Whole-layer CPU fills on a big canvas are slow on a phone —
the fill runs on `Dispatchers.Default` with a progress indicator and is
cancellable; the tiles land atomically as one journal entry.

### Step 9 — Adaptive UI polish (M)

**Goal.** One app that is roomy on a tablet and usable one-handed on a
phone, chosen by window size class.

**Creates.** Compact layout (rail → bottom dock, panels → full-height
sheets), expanded layout (panels float beside the rail), handedness,
focus mode, gesture shortcuts finalised (two-finger tap undo, three-finger
tap redo, long-press eyedropper) with haptic ticks, `HoverCursor` polish,
first-run hint, `TopStrip` final, immersive mode with
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, keyboard shortcuts for DeX as a
nicety (`docs/plan/07-input-and-stylus.md` §9).

**Depends on.** Steps 5–8 (all controls exist to be laid out). Not
before — laying out placeholders twice is waste.

**Acceptance.** Manual on a phone, a Tab S, and a Fold (fold/unfold while
painting; multi-window split with another app): no control unreachable,
no panel covering the active stroke area on compact, focus mode hides
everything and a tap brings it back, rotation of the device keeps the
view transform (checklist rows D6, N2, N3, U1–U4 of `11-testing.md` §8).
Accessibility gates `01-product.md` §7 A1–A8: an Accessibility Scanner
pass over both screens with zero "touch target" and "missing label"
findings, the chrome at 200 % font scale without clipping, TalkBack
reaching undo/redo through the canvas node, reduced-motion honoured. JVM:
`LayoutSpecTest` (`LayoutSpec.forWindow(width,
heightDp, hand)` → the `docs/plan/08-ui-and-layout.md` §1 table),
`CanvasUiStateTest` (dialog and action parking, dismissal, back chain,
`08-ui-and-layout.md` §4) and
`GestureArbiterTest` extended for the multi-finger taps.

**Risks.** Gesture shortcuts fight drawing on touch-only devices (a
two-finger tap that lands as two dots). The arbiter's tap window is tested
from pointer timelines; if real devices still misfire, the fix is a timing
constant in `GestureArbiter`, with the timeline that failed added as a test.

### Step 10 — v1.0 (S)

**Goal.** Ship.

**Creates.** Settings screen (handedness, stylus-only, S Pen button
action, pressure curve as the Softer / Linear / Harder gamma preset — the
guided per-device calibration is post-v1, §5 — haptics, gallery sync), About (licenses with the
Mixbox CC BY-NC notice, version), a zh-Hans pass over every string with a
native-reading review, README screenshots from a real device
(`media-sources/screenshots/`), CHANGELOG entry, `scripts/release.sh 1.0.0
--push`.

**Depends on.** Everything.

**Acceptance.** The tagged GitHub Release `帮你Draw v1.0.0` carries
`bangnidraw-v1.0.0.apk` and its sha256; the APK installs as an upgrade over
an earlier `assembleRelease` build (a step-3-era release APK or the rolling
CI artifact built as release — same `ch.lkmc.bangnidraw` applicationId,
same debug keystore) with the paintings intact. Note that
`assembleDebug` installs are `ch.lkmc.bangnidraw.debug`, a separate app:
the upgrade test is release-over-release, nothing carries over from a
debug install; every step's manual acceptance is
re-run on the release APK on one phone and one tablet and recorded in the
release notes. The product definition of done, `01-product.md` §10 items
1–7, is walked on each device class of `01-product.md` §3 that is
available — item 7 (TalkBack announcing every control, and the whole walk
in zh-Hans with the device language set) is the one this step adds — and
the full `11-testing.md` §8 checklist including S1 (no permissions) is
run once on the tagged APK. A failing item is a release blocker, not a
known issue.

**Risks.** `versionName`/tag mismatch or a hand-made tag — `release.yml`
gates on it; only the script cuts releases (PLAN.md §8).

## 4. Dependency graph and parallelism

```
 1 Scaffold
 │
 2 Engine core  (2a touch + multi-buffer  →  2b front buffer + stylus)
 │
 ├──────────────────────────┐
 3 Document + undo + Studio 5 Tool set        ← 5 needs only 2; branch early
 │ │ │                      │
 │ │ └─ 8 Fill ─────────────┤   ← 8 needs 3 and 5 (tool rail)
 │ └─── 6 Layers            7 Mixing + smudge + blur   ← 7 needs 5 (pigment flag)
 └───── 4 Gallery + share   │
                            │
 ┌──────────┴───────────────┘
 9 Adaptive UI polish   ← needs 4, 5, 6, 7, 8
 │
10 v1.0
```

| After this lands | These can run concurrently |
| --- | --- |
| 2 | 3 and 5 |
| 3 | 4, 6 (with 5 still in flight) |
| 3 and 5 | 8 |
| 5 | 7 |
| 4, 5, 6, 7, 8 | 9, then 10 |

Two agents is the practical maximum: one on the persistence spine (3 → 4/6, then 8 once 5 is in)
and one on the tool spine (5 → 7). Both branch from `main`, and the second
to merge rebases. The files where they *do* meet are `HistoryJournal` (6 adds
inverse entries, 8 adds fill entries) and `CanvasViewModel`'s `UiState`;
step 3 lands the journal's entry-type API and the `CanvasUiState` fields for
tool and layer state up front so later PRs add cases, not shapes.

## 5. Post-v1 backlog

Each item enters through a proposal (§6) and, once accepted, a row in PLAN.md
§10. The one-line designs here are the starting argument, not the decision.

| Item | One-line design | Size | Needs |
| --- | --- | --- | --- |
| Selections + transform | A selection is a mask layer (1-channel tiles) held in `Document`; lasso/rect/magic-wand write it; move/scale/rotate renders the selected pixels through `ViewTransform`-style math into a floating layer that is journaled as a pixel edit on commit. | L | 6 |
| Rulers / shape assist | `Stabilizer` gains a constraint stage: snap the stroke to a line, ellipse or bezier ruler placed with two fingers; strokes stay ordinary dab strokes, so every brush works on a ruler. | M | 5 |
| Symmetry | `DabGenerator` emits N mirrored/rotated dab copies per input dab about an axis in canvas space; one journal entry; a guide overlay in Compose. | S | 5 |
| Gradient fill | `FillTool` variant: linear/radial gradient between two swatches, optionally mixed through `ColorMixer` (a pigment gradient), masked by the flood region. | S | 8 |
| Wet / watercolor brushes | A per-layer wetness tile channel and a diffusion RMW pass ticked on a timer while wet; pigment via Mixbox latents; dries to the layer. The first brush needing a timer-driven pass. | L | 7 |
| Brush grains | Tiling grain textures (CC0, provenance in AGENTS.md) sampled in `DabPass` in canvas space so the grain does not swim; preset field `grain` with scale and depth. | S | 5 |
| Import image as layer / reference | Photo picker → decode → tiles on a new layer (scaled to the canvas) or a floating reference panel with its own pan/zoom that is not part of the document. | M | 6 |
| Canvas crop / resize | Document-space change journaled as a whole-document entry (all layers' before-tiles); crop by rect, resize by resampling on the GPU into a new tile set. | M | 3 |
| Tile residency / eviction | `TilePool` becomes an LRU over slices with CPU mirrors for evicted tiles; lifts the layer cap from `MemoryBudget`; the readback handover already exists. | L | 3 |
| OpenRaster export | `.ora` zip: `stack.xml` + one PNG per layer + `mergedimage.png` + thumbnail; blend modes map to the OpenRaster composite-op names. Import is a separate proposal. | S | 6 |
| Time-lapse | Record the checkpoint composites (or every Nth stroke's flattened readback) as frames; encode with MediaCodec on export, Meltorama's movie pipeline pattern. | M | 4 |
| PSD import (?) | Read only the layer bitmaps and basic blend modes from PSD; everything else is dropped with a notice. Questionable value against effort; the proposal must argue for it. | L | 6 |
| Custom brush import | Accept a `BrushPreset` JSON (and grain PNG) via the share sheet / file picker into `filesDir/brushes/`; validate against the schema; no code formats from other apps in the first cut. | S | 5 |
| Pressure calibration UI | A guided Settings screen that asks for a lightest and a hardest stroke on the current pen and records the 5th/95th-percentile floor and ceiling into `Prefs.pressureCalibration` (keyed by `InputDevice.descriptor`); the data model and the `PressureCurve` composition already ship in v1 with the identity calibration and the Softer / Linear / Harder preset (`07-input-and-stylus.md` §2). | S | 10 |

Not on the list, deliberately (PLAN.md §1): vector layers, text tools,
photo adjustments, cloud sync, accounts.

## 6. Proposal process (post-v1)

Meltorama's convention, adopted unchanged:

- A proposal is `docs/proposals/NNNN-<slug>.md`, numbered in order of
  creation, with the ADR-style header:

  ```markdown
  # NNNN — Title

  - **Status:** proposed | accepted — roadmap #N | declined
  - **Date:** YYYY-MM-DD
  ```

- It argues for the feature *and prices it*: the user-facing behaviour,
  the engine touch points (which classes from PLAN.md §3), the journal
  shape (how it undoes), the memory and frame-time cost against
  `docs/plan/10-performance.md`, the tests, and the size. It ships no code.
- Accepted proposals graduate into PLAN.md §10 as a new numbered row and
  get their own PR; declined ones stay with status `declined` and the
  reasons, so the argument is not re-had.
- Anything that changes a numbered decision in PLAN.md §4 is an ADR in
  `docs/decisions/`, not a proposal.
- Proposals may be written by agents; acceptance is the maintainer's.
