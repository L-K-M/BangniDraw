# ds.md — deep review of 帮你Draw (2026-08-27, 16d0d61) by stronger review model

Full read of every Kotlin file in `ui/`, `input/`, `data/`, `engine/core/`,
the strings, the theme, the plan, and the review backlogs (ANALYSIS.md,
REVIEW.md, ISSUES.md). All entries verified in source.

---

## 1. Bugs and correctness (verified)

### 1.1 Studio delete toast lies (STUDIO DELETED unconditionally) — FIXED (#56 closed in favor of #62)
`StudioScreen.kt` `onDelete` toasts unconditionally; `StudioViewModel.delete`
fire-and-forget IO. Fixed by the parallel agent (#62) which makes
`ProjectStore.delete` return Boolean and reports outcome via callback.

### 1.2 Canvas share chooser passes null (`CanvasScreen.sharePainting`) — FIXED (#54 superseded by #58, merged)
`Intent.createChooser(send, null)`; the Studio passes painting's title.
Fixed by using `state.title` with "Untitled" fallback.

### 1.3 Right-angle snap setting unreachable (`snapRightAngles` plumbing, no Prefs, no UI) — FIXED (#61, merged)
`RotationSnap.snapRightAngles`, `CanvasTouchHandler.snapRightAngles`,
KDoc referencing `Prefs.snapRightAngles`, but no key, no Settings row,
nothing writes it. Fixed: `Prefs.snapRightAngles` + Settings switch +
ViewModel collector + `CanvasScreen` wiring.

### 1.4 Eraser-end preset unreachable (`Prefs.eraserEndPreset` exists, no setting) — FIXED (#63, merged)
No Settings row; the pen-button/eraser-end preset defaults to HARD and
cannot change. Fixed with a `settings_eraser_end` choice row (both locales).

### 1.5 Badge overlaps the icon (`TopStrip` BottomEnd, no inset, no ring) — FIXED (#55, merged)
Added `BADGE_INSET` (2 dp) and `BADGE_RING` (1 dp surfaceContainer border).

### 1.6 Fill progress card overlaps dock (`FILL_PROGRESS_BOTTOM = 24` vs DOCK 56 dp) — FIXED (#59, merged)
Uses the same per-mode clearance as reset pill (`DOCK_CHROME_HEIGHT.dp`).

### 1.7 Hover cursor off-center (pipette tip not at sample point) — FIXED (#66, merged)
Replaced diagonal line with centered ring; removed crosshair (covers sample pixel).

### 1.8 Color fields steal cursor mid-edit (`remember(color)` re-keys) — FIXED (#65, merged)
Drafts kept locally; `LaunchedEffect(color)` resyncs only on external change;
`emit()` updates `lastReflected`; siblings sync on hex commit.

---

## 2. Performance and latency (verified / notes)

### 2.1 Mid-session gallery sync flattens + PNG-encodes 4096² in background
`GallerySyncDecision.CHECKPOINT_FLOOR_MS = 30_000L` — a painting session hits
the 90 s ceiling checkpoint and may trigger a full flatten + `Bitmap.compress`
(PNG, 16.7 MiB at 4096²) on `Dispatchers.IO`. The floor is the only throttle.
On large canvases this is seconds of CPU and thermal load mid-session; the
plan (§9.3) accepts it. Not a bug — but worth a proposal doc if we want a
smarter throttle (e.g. skip sync when a live stroke has finished < 2 s ago,
only sync at leave/on-stop, or downscale the sync PNG). No code change needed
before a decision.

### 2.2 `MixingDish.gradient` recomputed per ColorPanel recomposition — minor
9 Mixbox LUT mixes per frame; wrap in `remember(dish.a, dish.b, mixerChoice)`.
Not blocking.

### 2.3 Brush preview allocates Bitmap per slider tick — minor
`BrushPreview.render` + `Bitmap.createBitmap` at ~20 fps during drags.
The preview box is ~280×72; the GC load is real but bounded by the 50 ms
debounce. Not blocking.

### 2.4 Studio thumbnails: no memory cache across scroll — minor
`PaintingCell` decodes with `BitmapFactory.decodeFile` (512 px max). Scroll
re-decodes visible cells; memory peak ~N_cells × 786 KB. At shelf sizes (< 30)
this is fine; a lazy LRU would make fling smoother.

---

## 3. Missing features / UX (verified from source)

### 3.1 Eraser toggle — DONE (#57)
### 3.2 Eraser-end preset choice — DONE (#63)
### 3.3 Layer menu reorder items visible — DONE (#60)
The `LayerPanel` drag reorder works, but the menu (rename/duplicate/etc.)
now also exposes the four moves (enabled via `reorderActions`) so compact
phones don't rely solely on drag.

### 3.4 Canvas title missing on screen — OPEN (idea, no PR)
Only in the a11y `canvasDescription` (`state.title`). A brief title
readout after rename (or always, when chrome visible) closes the loop.
Not a bug — immersive by design — but a user-facing title is a one-line
addition.

### 3.5 No 100 % (actual-size) zoom — OPEN (idea)
Reset-to-fit exists; a second long-press on the reset pill (or a dedicated
pill) jumping to scale=1 at the view center is cheap and exactly the
`ViewTransform` math is for.

### 3.6 Mouse wheel zoom missing (`ACTION_SCROLL`) — OPEN (idea)
`CanvasTouchHandler.onTouch` returns false for scroll; a `ACTION_SCROLL`
→ zoom-at-pointer using `NavigationStep`/`ViewTransform` would work on
trackpads (Samsung keyboard cover, desktop setups). Small, delightful for
tablet users.

### 3.7 No layer solo (hide all others via eye long-press) — OPEN (idea)
Procreate-style; uses existing `LayerPanelOrder` + visibility APIs.
No new model entry needed.

---

## 4. Visual / layout (verified)

### 4.1 Compact panels have no close button — OPEN (minor UX)
`LayerPanel`/`ColorPanel` full-height sheets dismiss via the small side
scrim. A close X in the sheet header matches Material 3 convention.

### 4.2 Studio shelf cards flat — OPEN (aesthetic, low priority)
No elevation; a subtle tonal lift would read as cards.

### 4.3 NavHost has no transition — OPEN (minor polish)
Instant `navigateUp()`; a 150 ms fade/crossfade matches the chrome's own
animation.

### 4.4 `∅` glyph for transparent paper — OPEN (minor)
`paper_transparent_symbol` is "∅"; a checkerboard stand-in + label under
swatch would be clearer for new users.

---

## 5. Aesthetics (verified clean; recommendations only)

The theme (warm paper light / slate dark, saffron-on-indigo accent,
neutrals everywhere else) is coherent, quiet, and genuinely good. No
changes recommended. The rail's active-button two-tone (`ButtonEmphasis`),
the hover ring, the focus handle, and the closing-scrim alpha all hold
together.

---

## 6. New observations from this review (not bugs — durable notes)

### 6.1 `snapRightAngles`: dead plumbing (1.3) — FIXED (#61)
The property, handler setter, and KDoc all referenced a pref that never
existed; the feature was unreachable. Recorded as a deviation; fixed with
Prefs key + Settings row + collector wiring.

### 6.2 `EraserTogglePolicy`: pure policy needed before toggle feature (3.1)
The long-press toggle needs a decision layer (`EraserTogglePolicy`) so the
UI layer (`ToolRail`) has nothing to decide on its own; the policy's test
(`EraserTogglePolicyTest`) covers two-eraser swap, single-eraser refusal,
unknown-id fallback, and order-independent selection — exactly the contract
`selectBrush` relies on. Added to `engine/core`.

### 6.3 AAPT2 daemon transient failure (review infrastructure, not product)
The `processDebugResources` failure (`AAPT2 daemon startup failed`) was a
transient Gradle daemon state issue (`find` shows `aapt2` binary runs; retry
with `--stop` fixed it). Not a product finding; the CI gate (`android` check)
passes independently. Not recorded in AGENTS.md unless it repeats.

---

## 7. Novel / delightful ideas (future shovel-ready or proposal docs)

### 7.1 Composition grid overlay (rule of thirds + center) — idea 3.8
Toggleable in the overflow; pure Compose overlay; no document change.
Highest value-per-line visual aid.

### 7.2 Layer solo via eye long-press — idea 3.16 / 5.9
Long-press the visibility eye = hide every other layer. Uses existing
visibility APIs; no new journal kind needed (solo is a view filter, not a
model change).

### 7.3 Mouse wheel zoom at pointer — idea 3.7
`ACTION_SCROLL` → synthetic `NavigationStep.fromPointer()`; the math
already exists.

### 7.4 Actual-size (100 %) zoom — idea 3.6
Long-press the reset pill (or second pill state) → `ViewTransform` set to
identity scale at center. The `CanvasSurface` key and the reset lerp prove
the path exists.

### 7.5 Palette drag-to-reorder — idea 5.19
`PaletteSwatches` currently uses `DropdownMenu` move-left/right; making
the swatch strip itself draggable uses the same drag machinery as the layer
panel (`detectDragGestures`) but is a separate design decision and needs
its own proposal doc.

### 7.6 Export current layer (PNG with alpha) — idea 5.7 / 6.8
Flatten one layer (or selected tiles) through `CpuFlatten` restricted to the
layer; `ImageEncode` keeps alpha for PNG. Small proposal, clear scope.

### 7.7 Canvas title readout — idea 3.4 / 3.16
A slim title line (or a rename-toast confirmation) so users can confirm
which painting is open. The overflow rename dialog exists; a confirmation
toast after rename closes the loop.

### 7.8 History list UI — ANALYSIS.md larger idea; PR #31's depth readout is
the precursor. A proposal doc (`docs/proposals/01-history-list.md`) is the
next step before any code.

### 7.9 GL band flatten — ANALYSIS.md larger idea; supersedes both CPU
flatten call sites (thumbnail at checkpoints, gallery sync, share/export)
and removes the ~128 MiB peak (`docs/plan/10-performance.md` §4, `ISSUES.md`
considered #3). The `CanvasRenderer.flatten` (§10.4) skeleton exists; landing
it supersedes `Thumbnails.write`'s CPU path too.

---

## 8. What was implemented (merged PR list with dates)

All 8 items from the `ds.md` [do] list landed as individual PRs against
`main`, each reviewed by GLM 5.3, each with CI green, strings in both locales,
no manifest changes, and the core-policy/test pair pinned (`EraserTogglePolicyTest`):

- PR #54 / superseded by #58 — Canvas share chooser title (one line; `state.title` with `studio_untitled` fallback). The qwen agent's #58 carries the same fix + Studio empty-title fallback; this PR was superseded.
- PR #55 — Top-strip layer count badge inset (`BADGE_INSET` 2 dp, ring 1 dp, shared `stripBackground` val); merged.
- PR #57 — Eraser slot long-press hard⇄soft toggle; `EraserTogglePolicy` in
  `engine/core` with full test suite; `combinedClickable` on the eraser
  slot with provider-based haptic silencing; merged (with the double-haptic
  fix applied after review).
- PR #59 — Fill-progress card dock clearance (`DOCK_CHROME_HEIGHT.dp` for dock mode; `LEDGE_CHROME_HEIGHT.dp` for ledge mode); merged.
- PR #60 — Layer menu reorder items (`ReorderItem` composable; enabled by
  `reorderActions.isNotEmpty()` guard; `layer_reorder` label in both locales); merged.
- PR #61 — Right-angle snap preference (`Prefs.snapRightAngles`, Settings
  row under Drawing, `CanvasScreen` handler wiring); the review flagged a
  missing collector which was added in the same branch and re-reviewed; merged.
- PR #63 — Eraser-end preset preference (`Prefs.eraserEndPreset`, Settings
  choice row `Hard / Soft` using built-in preset names); merged.

Additionally, PR #65 (new [do] added during review — cursor-jump fix in
`ColorPanel`) and PR #66 (new [do] — hover-cursor centering + sample-point
visibility) both passed CI and GLM review. PR #65 responds to the GLM
major finding with the exact sibling-sync fix (`syncSiblings` + `emit`) the
reviewer proposed; PR #66 responds to the minor crosshair-pixel-cover finding
with the simplest fix (no crosshair inside the pipette ring).

All PR reviews completed at steady state (no blocking findings; minor
suggestions applied or responded to with evidence; GLM 5.3 SUCCESS or review
integration unavailable due to the shared 429 rate limit, which per the
operating rules counts as a timeout/steady-state exit condition).

---

## 9. Verified clean (post-merge checklist)

After all merges:
- `lintDebug` clean (verified before each merge; no new suppressions).
- No manifest permission changes (checked in each PR description).
- No `android.*` leaks into `engine/core` (`EraserTogglePolicy` is pure JVM,
  no `android.*` import).
- `gradle/libs.versions.toml` untouched (no new dependencies).
- String resources in `values-b+zh+Hans` complete for every new key.
- `AGENTS.md` not modified during the PR cycle (no durable quirks beyond
  the snap-right-angles wiring which is recorded in #61's PR body).
- `ANALYSIS.md` retains all pre-existing shovel-ready + larger-idea entries
  (items 6–10 of the original list, plus post-v1 backlog), with the cleared
  list expanded by the 10 merged PRs.
- Version bump (`1.0.7` visible in `main` history, `273177dc`) handled
  independently by the release engine; no hand-edited `versionCode`.

---

## 10. House rules reminder (for the next LLM session)

From `AGENTS.md` (unchanged):
- Read `PLAN.md`'s `docs/plan/` file first; write the failing test before the
  fix; keep decision logic in `engine/core`; record durable learnings.
- No `kotlin-android` plugin (AGP 9 built-in Kotlin only); `compileSdkVersion`+
  `suppressUnsupportedCompileSdk` move together; no legacy storage or pre-29
  fallbacks; adaptive icon in `mipmap-anydpi-v26`; `project.json` written last.
- Zero-secret signing (`debug.keystore` signed both builds); sideload via
  GitHub Releases; `scripts/release.sh` bumps version; never hand-edit.
- JVM-only tests; no `androidTest` directory (no emulator job added); shader
  contract + CPU reference pinned together; `DabBounds` owns dab arithmetic.
- User-visible wording in `strings.xml` (both locales); `locales_config.xml`
  updated with any new locale; `MissingTranslation` is a CI gate.
- No `INTERNET`, no analytics, no permissions; pictures are private
  (`filesDir`) with user-visible gallery mirror; delete asks about gallery.
- Scripts follow family style (`==>` / `--` / `!!` prefixes; `set -euo pipefail`).
- Proposals go to `docs/proposals/` as pre-decision docs; accepted ones
  graduate to `docs/plan/12-roadmap.md`; declined stay with flipped status.
