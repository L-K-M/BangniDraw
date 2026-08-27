# AGENTS.md — operating manual

The operational source of truth for agents (and humans) working on
帮你Draw. When you learn something durable about how this repo behaves — a
quirk, a footgun, a changed convention — **update this document** in the
same PR.

## Name

**帮你Draw** (Bāngnǐ Draw, "helps you draw"). The launcher label is the full
name — it is short enough. The applicationId and package are
`ch.lkmc.bangnidraw` and never change: changing an applicationId breaks
upgrades for everyone who sideloaded a build, and no user ever sees it.
PLAN.md's *Renaming* section lists every file a future rename would touch.

## What this app is

A simple, fast, layered raster drawing app for Android phones and tablets
with first-class S Pen support and Mixbox pigment mixing. Read
[PLAN.md](PLAN.md) first; it is the constitution (product framing,
architecture, decisions, roadmap). The detailed design is in `docs/plan/`
(index at the end of PLAN.md). Deviations from the plan get recorded here
as they happen.

## Build, test, lint

```sh
./gradlew testDebugUnitTest    # the whole test suite (JVM-only, by design)
./gradlew lintDebug            # hard CI gate — keep it clean
./gradlew assembleDebug        # debug APK
scripts/build.sh               # release APK staged into dist/
scripts/install.sh             # build + install + launch on a device
python3 scripts/generate_icons.py   # regenerate launcher PNGs from media-sources/icon.png
```

- JDK 17. Android SDK path via `local.properties` (`sdk.dir=…`) or
  `ANDROID_HOME`. Agent sessions: `.claude/setup-android.sh` bootstraps the
  SDK idempotently (wired as a SessionStart hook). Note that the SDK's
  `aapt2` is x86_64-only — an arm64 Linux sandbox cannot assemble; CI is
  the source of truth there. On an x86_64 sandbox the whole pipeline
  (`testDebugUnitTest lintDebug assembleDebug`) runs locally; the first
  invocation downloads the Gradle distribution and the dependency graph and
  takes a few minutes, every later one is seconds.
- Versions are pinned ONLY in `gradle/libs.versions.toml`. Never add an
  ad-hoc version to a build file; never restate catalog versions in docs.

## Toolchain quirks — don't "fix" these

- `compileSdkVersion("android-37.0")` (string form) is deliberately paired
  with `android.suppressUnsupportedCompileSdk=37` in `gradle.properties`.
  The two move together or not at all.
- There is NO `kotlin-android` plugin: AGP 9 provides built-in Kotlin
  support. Only android-application, kotlin-compose, kotlin-serialization,
  ksp, and hilt are applied.
- `app/debug.keystore` is checked in ON PURPOSE and signs BOTH build types
  (`.gitignore` whitelists it). Zero-secret CI, reproducible builds,
  sideload-only distribution — see `docs/decisions/0005`. Do not "rotate"
  it, do not add signing secrets without a recorded product decision.
- **minSdk is 29** (ADR 0002). Don't add legacy storage or pre-29 fallbacks;
  the permission-free MediaStore path and front-buffered rendering both
  assume it.
- Adaptive icons live in `mipmap-anydpi-v26` even though minSdk is 29;
  `app/lint.xml` silences the (wrong) ObsoleteSdkInt advice. The artwork is
  the adaptive **background** layer (full-bleed PNGs generated per density);
  the foreground is an empty vector; the monochrome icon is a hand-drawn
  brush silhouette.

## Architecture in one paragraph

Single module `:app`, packages first (PLAN.md §3). MVVM with one immutable
UiState per screen (StateFlow from a ViewModel), Hilt DI, single activity,
Compose + Material 3 for the chrome, a SurfaceView driven by a GLES 3.0
engine for the canvas. The document is a stack of layers made of sparse
256×256 premultiplied RGBA8 tiles, pooled on the GPU in texture arrays,
composited per visible tile; strokes are stamped as dabs into a per-stroke
buffer and previewed through `GLFrontBufferedRenderer`; undo is a persisted
journal of tile deltas in the project folder; autosave is the only save;
each painting mirrors to one MediaStore image. Decision logic lives in
`engine/core` as pure JVM classes.

## Conventions and footguns

- **Tests are JVM-only** (`testDebugUnitTest`); keep decision logic out of
  composables and the GL renderer so it stays testable. No androidTest
  directory exists; adding one means adding the emulator CI job too.
- **"Works in debug, breaks in release"** is almost always a missing R8
  keep rule for a new reflection/serialization entry point — check
  `app/proguard-rules.pro` first.
- The CPU reference implementations in `engine/core` (`Composite`, the
  mixing formula, dab falloff) and the GLSL must stay trivially close; when
  one changes, change both, and let the unit tests pin the semantics.
- `engine/core/ViewTransform` and `FitTransform` are Meltorama's, ported
  verbatim — except the scale limits: `MIN_SCALE = 0.25f`, `MAX_SCALE = 64f`
  (Meltorama: 0.5 / 8), because pixel work on a 4096² canvas needs to zoom
  far past a photo warper's range (docs/plan/07 §7). Keep the math in sync
  with Meltorama if either side finds a bug; keep the constants ours.
- **User-visible wording is a string resource, everywhere.** ViewModels
  and the renderer have no Context and no locale, so failures travel as
  `@StringRes Int` and the exception's own English text goes to Logcat.
  The app ships `values/` and `values-b+zh+Hans/`, listed in
  `res/xml/locales_config.xml` (Android 13+ per-app language) — add a
  locale to that file in the same change that adds its `values-*` folder.
  Lint's `MissingTranslation` is a hard CI gate, so brand strings carry
  `translatable="false"`.
- **Mixbox is CC BY-NC 4.0** (ADR 0003). The attribution lives in the About
  string, the README and `third-party/mixbox/`; keep all three when
  touching any of them. Vendored `mixbox.glsl`/`mixbox_lut.png` must stay
  byte-identical to upstream.
- The app has **no permissions**. Keep it that way; adding any is a
  product decision requiring an ADR.
- App display name lives ONLY in `strings.xml` (`app_name`). Never
  hardcode "帮你Draw" in a composable (rename checklist: PLAN.md "Renaming").
- Colors come from `ui/theme/Color.kt` — no ad-hoc `Color(0x…)` in
  screens. The theme follows the system (light and dark), no dynamic color.
- Scripts follow the family house style: header comment doubles as
  `--help` via the awk one-liner; `==>` / `--` / `!!` log prefixes;
  `set -euo pipefail`.
- Third-party assets (brush grains, sample art, fonts) must be public
  domain / CC0 with provenance recorded in this file when added. Currently
  none besides Mixbox.
- Proposals for post-v1 features live in `docs/proposals/` as numbered
  pre-decision docs (same Status/Date header as ADRs); an accepted proposal
  graduates into `docs/plan/12-roadmap.md`, a declined one stays with its
  status flipped so the reasoning isn't lost.

## Deviations discovered while building

Recorded per PLAN.md's rule: when the plan contradicts itself, PLAN.md wins
and the contradiction is noted here.

- **The v1 pencil uses the reserved grain key `procedural`.**
  `04-tools.md` §5 says its `grain` stays null until a CC0 texture arrives,
  while roadmap step 5 requires procedural shader noise in the meantime.
  The latter is the user-visible requirement: `DabStamp` and `dab.frag` share
  a canvas-anchored integer hash, so the grain does not swim between dabs.
  No third-party asset was added, so there is no provenance entry yet; an
  eventual CC0 texture replaces the reserved key and records its source here.

- **`merge.frag`'s ERASE branch consults `u_alphaLock`; the plan's skeleton
  does not.** `docs/plan/03-canvas-engine.md` §7.4's table says of ERASE
  "(alpha-lock: the eraser is a no-op on locked layers — 05 §1)", and
  `05-layers.md` §1 agrees. The `merge.frag` skeleton printed directly beneath
  that table returns `L * (1.0 - S.a)` unconditionally, with no `u_alphaLock`
  anywhere in the branch. The table wins: transcribing the skeleton would erase
  through a lock on the GPU while `StrokeMerge` refused to on the CPU, so the
  live preview and the committed result would disagree on any locked layer —
  and §7.5's whole promise is that they are the same arithmetic. Pinned by
  `StrokeShaderContractTest`, because this is the one place the shader
  deliberately departs from the document's literal text.

- **The dab's colour is a uniform, not a per-instance attribute.** §7.3's
  `dab.vert` snippet declares `layout(location = 7) in vec3 i_color`, but §6
  states twice over that this is wrong: "Colour and the stroke opacity are per
  stroke (uniforms), never per dab", and the eight per-dab fields it lists as
  `DAB_STRIDE` do not include colour. `02-architecture.md` §3.2 pins the same
  eight-float layout for `DabBatch`. A ninth per-instance `vec3` would
  contradict both and send one stroke's single colour 1 024 times per batch, so
  it is `u_color`.

- **`maxCanvasEdge` is not bounded by `GL_MAX_TEXTURE_SIZE`.**
  `docs/plan/11-testing.md` §3.11 lists `maxCanvasEdge <= glMaxTextureSize`
  as a `MemoryBudgetTest` claim, but `docs/plan/10-performance.md` §4 — which
  owns `MemoryBudget` — states the opposite in a code comment ("never bounded
  by `glMaxTextureSize` … tiles are 256"), and it is right: a canvas is never
  a texture, only a grid of 256 px slices, so a device with the ES 3.0
  minimum `GL_MAX_TEXTURE_SIZE` of 2048 can still hold a 4096 canvas. PLAN.md
  §3.1 and decision 1 side with 10 §4. `MemoryBudgetTest` therefore asserts
  the two claims that are real (the pool spans enough slices for every layer,
  and `poolArraySlices <= glMaxArrayLayers` once queried) and not the third.
  `glMaxTextureSize` stays in `DeviceMemory` because the viewport-sized
  `Accum`/`Scratch` targets of `03-canvas-engine.md` §3.2 are real textures
  and will need it.

- **Merge down bakes the lower layer's opacity into *every* one of its tiles.**
  `docs/plan/05-layers.md` §4.1 contradicts itself: its mechanism says
  "bottom's tiles at keys the top does not have are untouched", while its
  appearance rule says "bottom's opacity is baked into the merged pixels" and
  "a normal bottom at *any* opacity merges exactly". Both cannot hold — the
  merged layer is reset to Normal at 100 %, so an untouched tile of a 50 %
  bottom layer jumps to fully opaque. The guarantee wins (it is also the
  stronger promise to the user, and PLAN.md decision 10 only requires the
  weaker one), so `mergeDown` widens `PixelOp.Merge.keys` and
  `HistoryEntry.LayerMerge.lowerTiles` to all of the bottom layer's tiles
  **when its opacity is not 1**. Blend mode alone never widens them: a
  bottom-only tile is composited over transparent, where every *separable*
  blend mode reduces to source-over — which is all eight of ours. A
  Porter-Duff compositing operator would not: `source-in` over a transparent
  destination is transparent, not the source, so a bottom-only tile would not
  survive the merge and undo would be lossy. `erase` and `alphaLocked` are
  such operators, but they are tool operations in `Composite`, not entries in
  `BlendMode`, and `mergeDown` never routes through them. Revisit this rule if
  one ever becomes a layer blend mode. So the ordinary bottom-at-100 % merge still rewrites only the
  shared tiles, exactly as 05 §4.1 and `06-document-and-persistence.md` §5.2
  describe, and 06 §5.2's "lower's tiles at upper's keys only" is a superset
  violation only in the faded case, where undo would otherwise be unable to
  restore what the merge overwrote. `PixelOp.Merge` also carries
  `bottomProps`, because the model already holds the reset props by the time
  the GL thread runs the op.
- **`LayerStack.nextName` cannot survive undo or reopen on its own.** The
  counter that names "Layer N" only grows along a chain of operations, but no
  `HistoryEntry` variant carries it and `ProjectFile`
  (`06-document-and-persistence.md` §3) has no field for it — so an
  add → undo → add sequence, or simply closing and reopening a painting,
  reissues a default name that 05 §1 says is never reused. Nothing in v1 keys
  off a generated name yet, so this is recorded rather than fixed here.
  **Roadmap step 3 owns it** — and `12-roadmap.md` has since settled the
  mechanism: the counter stays on `LayerStack`, `project.json` gains a field
  for it, and undo never restores it. Two tests, because the note above
  describes two distinct failures: `add → undo → add` yields a fresh name
  (the journal half), and **save → reopen → add** yields a fresh name (the
  persistence half). A design that fixes only the first still reissues "Layer
  3" the next morning. **3a landed the persistence half**: `project.json`
  carries `nextLayerName` (06 §3), and `ProjectStore.load` floors it at one
  past the highest `@string/layer_default N` actually present, so a pre-field
  or hand-edited file cannot reissue a live name
  (`ProjectStoreTest`). **3b landed the replay half**: the loader also floors
  the counter over every record the recovered journal embeds
  (`highestDefaultNameIn`), including on layers replay deletes, and
  `@string/layer_default` ships `translatable="false"` so the scan cannot
  under-recover under a second locale.

- **Two uniforms in `03-canvas-engine.md` §3.1's vertex shader are dead, and
  are not in `engine/gl/Shaders.kt`.** `u_viewport` is only ever an input to
  building `u_projection`, which §3.1 itself says happens on the JVM, and
  `v_canvas` is declared by the §3.3 fragment snippet and never read by it. A
  uniform that is declared and never *read* is optimized out by the driver, so
  `glGetUniformLocation` returns −1 and `GlProgram.link` throws at startup —
  which means copying the plan's snippet verbatim breaks every device while
  looking perfectly consistent on the JVM. `GlShaderContractTest` asserts both
  directions (declared ⇒ listed, listed ⇒ read) and names `u_viewport`
  explicitly so re-adding it from the plan fails in CI. `v_canvas` comes back
  with `DabPass`/`MergePass` in roadmap 2.4, where a body reads it.
- **The blend dispatch in `Shaders.COMPOSITE_FRAG` is generated from
  `BlendMode`, not written out.** `11-testing.md` §4 asks the contract test to
  catch "a mode falling through to normal"; generating the GLSL from an
  exhaustive Kotlin `when` makes that a compile error instead, and the test
  then checks the generator's output rather than a hand-transcription. Adding
  a `BlendMode` now fails to build in `Shaders.glslFor` until it has GLSL.
- **`TilePool` and `LayerTextures` have pure twins in `engine/core`**
  (`SliceAllocator`/`SliceHandle`, `TileIndex`), which the class map in
  `03-canvas-engine.md` §15 does not list by name but does require in its
  closing paragraph: everything decision-shaped gets a pure-JVM twin with
  tests. Free-list arithmetic and the "never allocate on a page this pass
  samples" rule are exactly the code that is wrong *silently* — a double free
  hands one slice to two layers — and none of it needs a GL context to test.
  Keep new pool logic on the core side; the `engine/gl` classes should stay
  "call the twin, then issue GL calls".

- **graphics-core 1.0.4's callback is not the one `03-canvas-engine.md` §8.2
  names.** The plan writes `onDrawMultiDoubleBufferedLayer(eglManager,
  bufferInfo, transform, params)`; the pinned library actually declares
  `onDrawMultiBufferedLayer(eglManager, width, height, bufferInfo, transform,
  params)` — a different name and two extra `Int`s. Verified against the AAR,
  not guessed. The two sizes are not redundant: `width`/`height` are the
  **surface's** and `bufferInfo.width`/`height` are the **buffer's**, which are
  swapped when the compositor hands over a pre-rotated buffer — which is the
  whole reason the present step is a quad through `u_bufferTransform` and not
  a blit.
- **`renderMultiBufferedLayer(Collection<T>)` exists** in 1.0.4, which
  `03-canvas-engine.md` §8.6 left open ("if graphics-core exposes it in the
  pinned version (to verify)"). It does, so `EngineSession.redraw()` calls it
  and the `empty-param commit()` fallback the plan describes is not needed.
- **`execute` blocks and render requests ARE FIFO on the GL thread.**
  `03-canvas-engine.md` §8.3 flags this as an assumption "to verify against
  graphics-core", with a prepared fallback (do the merge at the top of the
  multi-buffered callback, keyed by stroke id in `params`). Verified against
  1.0.4's bytecode, so the fallback is **not needed** and 2.5a's `commit()`
  follows §8.3's shape exactly. The chain is
  `GLFrontBufferedRenderer.execute → GLRenderer.execute → GLThread.execute →
  mHandler.post(runnable)`, while a render request goes
  `GLThread.requestRender → HandlerUtilsKt.post → mHandler.postAtTime(runnable,
  token, SystemClock.uptimeMillis())`. Both reach the **same** `Handler` on the
  one `HandlerThread`, both stamped `uptimeMillis()`, and neither uses
  `sendMessageAtFrontOfQueue` — `postAtTime`'s token only exists so the message
  can be removed later, and does not reorder. Android's `MessageQueue` inserts
  after every message whose `when` is less than or equal, so equal timestamps
  run in post order.
- **`GLFrontBufferedRenderer.execute` after release DROPS the block — it does
  not throw.** It checks `isValid()` and, when false, takes a `Log.w` branch and
  returns; the runnable never runs. That is what makes PR #13's R-063 fix load
  bearing rather than defensive: `EngineSession.stampDabs` returns its
  `DabRing` slot *inside* the queued block, so a dropped block silently strands
  a slot, and a few of those leave `acquireDabBatch` returning null forever with
  no exception anywhere to say why. Clearing the pinned engine at the disposal
  seam is what keeps blocks from being queued onto a released renderer at all.
- **`glBindFramebuffer(GL_FRAMEBUFFER, …)` sets the read *and* draw binding**,
  so `Accum → Scratch` needs two `GlFbo`s — one bound `GL_READ_FRAMEBUFFER`,
  one `GL_DRAW_FRAMEBUFFER`. Blitting through a single FBO object makes the
  destination its own source and copies the texture onto itself, with no GL
  error and a plausible-looking result at every zoom where the backdrop
  happens not to matter.
- **`requestUnbufferedDispatch(int)` takes a source *class*, not a source.**
  `07-input-and-stylus.md` §2.1 writes
  `requestUnbufferedDispatch(InputDevice.SOURCE_STYLUS)` and marks it "(to
  verify)"; verified, and it is wrong. The parameter carries the platform's
  `@InputSourceClass` annotation, so the argument must be one of the
  `SOURCE_CLASS_*` constants — `SOURCE_STYLUS` is `0x4002`, and only its low
  bit is `SOURCE_CLASS_POINTER` (`0x2`), so passing it would probably have
  worked while being the wrong value. Android Lint fails the build on it
  (`WrongConstant`), which is what caught it. The hover call therefore passes
  `InputDevice.SOURCE_CLASS_POINTER`, which covers a hovering pen and costs
  nothing extra. The API levels either overload arrived at — 30 for the `int`
  one, 21 for the `MotionEvent` one — are read out of the SDK's own
  `data/api-versions.xml`, not assumed.
- **§8.1's step 5 is not a separate pass — the tail is drawn *by* §7.5's
  preview.** The plan lists "draw the predicted tail on top, restricted to the
  same rect" as a fifth step after the composite, which reads as a second draw.
  It is not implemented as one, and §9 is why: the tail is "composited as if it
  were part of the stroke buffer — the same `mergeStroke` preview", so
  `preview.frag` samples all three textures and evaluates
  `mergeStroke(mergeStroke(layer, stroke), tail)` in one pass. A separate pass
  would have to re-derive the stroke's own merge to blend against, which is the
  duplicate composition path §7.5's whole promise rules out. Anyone tracing
  §8.1 will look for step 5 and find `CompositePass.drawPreview`'s `tail`
  parameter instead.
- **Nobody documents who owns the `MotionEvent` `MotionEventPredictor.predict()`
  returns, so we do not recycle it.** The class's own javadoc says nothing, and
  the two implementations the library switches between differ: on Android 14+
  `SystemMotionEventPredictor.predict` forwards the platform
  `MotionPredictor`'s event straight through, while below it
  `MultiPointerPredictor.predict` builds one with `MotionEvent.obtain` (both
  read out of the 1.0.0 bytecode). Android's own stylus guide shows the sample
  simply dropping the result. Recycling an event the platform still owns
  corrupts a pool the app does not control, and the failure lands somewhere
  else entirely; not recycling one we do own is a small object per frame. The
  asymmetry decides it — do not "fix" this into a `recycle()` without a source
  that actually settles ownership.
- **`thumb.png` is composited on the CPU, not through `CompositePass`.**
  `06-document-and-persistence.md` §6.4 renders the thumbnail on the GL
  thread at checkpoints, with a 250 ms timeout for a surface that is gone.
  Roadmap 3c writes it on IO from the flushed tiles instead: at a checkpoint
  the tiles are on disk by §5.6's ordering, a NORMAL source-over stack is
  the same arithmetic either way (and NORMAL is all v1 produces — a future
  non-NORMAL blend logs and approximates as source-over until step 4's
  flatten brings §10.4's GL machinery), and the GL thread is never borrowed
  mid-gesture at all — the risk 06's timeout exists to hedge. Revisit when
  the flatten lands.

- **Step 4's flattens are `CpuFlatten` over `Composite`, not the GL band
  flatten.** Same trade as the thumbnail note above, extended: gallery syncs
  and shares always run after a checkpoint (or from the Studio with no canvas
  open), so the tiles are flushed, the pixels are identical by PLAN §7's
  pinning, and nothing borrows the GL thread. 03 §10.4's `CanvasRenderer
  .flatten` is still owed; when it lands it supersedes both call sites.

- **The gallery-sync debounce is pinned as a pure rule, not a ViewModel
  clock test.** `11-testing.md` §7 puts the 30 s floor case in a
  `CanvasViewModelTest` on `kotlinx-coroutines-test`; the decision instead
  lives in `GallerySyncDecision.isDue` (engine/core) with its own test, and
  the ViewModel only consults it. No `kotlinx-coroutines-test` dependency
  exists yet; add it when a real coroutine-clock test earns it, not before.

- **The scaffold's `detectTransformGestures` was deleted, not rewired.** It
  drove a Compose drawing of the paper; pointing it at the engine would make it
  a second owner of touch input, and `07-input-and-stylus.md` §2 makes
  `CanvasTouchHandler` (roadmap 2.4) the single owner of `MotionEvent`. 2.3b
  therefore has no touch navigation at all, and a debug-build-only "nudge view"
  button exists so 2.3b's device check — the reset pill returning to fit — is
  runnable on a device. It goes away with the touch handler.

## Conventions the plan leaves open

- **What "`engine/core` is pure JVM" actually forbids.**
  `docs/plan/02-architecture.md` §1 writes the rule as "`kotlin.*` and
  `java.util` only", but the plan itself puts `@Serializable` on two
  `engine/core` types — `LayerRecord` (`06-document-and-persistence.md` §3)
  and `BrushPreset` (`04-tools.md` §2). The operative rule is therefore: **no
  `android.*`, no Compose, no coroutines, no GL, and nothing that needs a
  device to run** — kotlinx-serialization annotations are allowed exactly
  where the plan declares a core type serializable, because they are pure JVM
  and the JVM test suite still runs the class unchanged. Anything else from
  the serialization ecosystem (custom serializers, `Json` instances, file
  IO) stays at the `data/` boundary.
- **`HistoryEntry.LayerProps` deliberately shares its name with the model's
  `LayerProps`.** `docs/plan/06-document-and-persistence.md` §5.2 is normative
  for the entry kinds' *names*, and it calls the props-change entry
  `LayerProps`. A file that touches both types qualifies the entry
  (`HistoryEntry.LayerProps`) or aliases on import; do not rename the nested
  class to resolve the collision.
- **The entries' id fields are `LayerId`, not `String`.**
  `06-document-and-persistence.md` §5.2 writes them as strings, and it is
  normative — but for the *encoding*, and the encoding is unchanged:
  `LayerId` is a `@JvmInline value class` over the same string, so
  `history/<seq>.entry` holds exactly what §5.2 says. What the type buys is
  the trust boundary: an entry decoded from a hand-edited file cannot hand an
  unvalidated id to a path join, which is the same reason `LayerId` carries a
  constructor guard at all. The claim is pinned, not just asserted:
  `LayerStackTest`'s "a layer id that is not a single path segment is refused"
  runs `LayerRecord(id = "../../evil").toProps()` and requires it to throw. The
  serialized type is `LayerRecord`, whose `id` is a plain `String`, so the guard
  runs where `toProps` constructs the `LayerId` — no serializer ever sees the
  value class, and a future custom serializer for it would still have to keep
  that test green. This reverses an earlier reading recorded here, which took
  §5.2's field types as binding on the in-memory shape too.
- **Generated layer names are a closed grammar, not a prefix.** Only three
  stored forms resolve through resources at display time:
  `@string/layer_flattened`, `@string/layer_default <int>`, and
  `<name> @string/layer_copy_suffix` — where `<name>` is resolved by this same
  grammar, **recursively**, so a copy of a copy of a default-named layer still
  shows localized text plus two suffixes rather than a raw resource token.
  Duplication appends the suffix to the stored name, so the suffixes do stack
  and the inner substring matches the third form, not the first two. Anything else is shown verbatim — which
  is what lets a user type `"@string/app_name"` as a layer name and see it
  back unchanged, and what keeps a duplicate of a default-named layer
  translatable (`01-product.md` §8: no English text in a stored name). A
  user-typed name survives unless it exactly matches one of the three forms —
  `"@string/layer_default 7"` is indistinguishable from a generated name and
  resolves as one — nearly free, since it displays as the text it already reads
  as, with one real cost: because the stored string resolves through resources
  at display time, a later locale switch re-renders a name the user typed by
  hand. The resolver arrives with the layer panel in step 6 and
  must implement exactly that grammar, not "resolve any `@string/` token".
- **Test fixtures live in `app/src/test/resources/fixtures/…`**, addressed
  through `javaClass.getResourceAsStream("/fixtures/…")`.
  `docs/plan/11-testing.md` §2 names the folder but not its root, and only a
  resources root is on the test classpath.

## CI/CD

Three workflows (details: [CICD.md](CICD.md)): `ci.yml` (tests + lint +
debug APK on every PR/main push), `release.yml` (v* tags → verified,
published APK), `zai-code-review.yml` (GLM reviews every PR; respond
per [CLAUDE.md](CLAUDE.md)). Family contract on every workflow:
least-privilege permissions, explicit concurrency, timeouts, wrapper
validation.

## Releasing

`scripts/release.sh X.Y.Z --push` (shared lkm-release engine) bumps
versionName, auto-increments versionCode by exactly 1, rewrites the README
version marker, commits, tags `vX.Y.Z`, pushes. **Never hand-edit
versionCode. Never create a `v*` tag by hand.** Bump the most-minor version
component + versionCode on every non-trivial change set.

## Review process

PRs are reviewed by GLM automatically. Findings are triaged
apply/decline/refute per [CLAUDE.md](CLAUDE.md); declined findings and
their reasons accumulate in [REVIEW.md](REVIEW.md) so later rounds (and
later agents) don't flip-flop.

The repository defaults to **hybrid** review: the first review is full, then
follow-ups review changes since the last completed review plus a rotating
sample of older PR changes. Keep hybrid for normal review/fix cycles.

Before the next review-triggering push, an implementer may apply exactly one
scope label:

- `zai-review:full` — use for high-risk changes, after a force push, or for a
  deliberate final deep audit.
- `zai-review:hybrid` — restore the normal delta-plus-rotating-audit mode.
- `zai-review:incremental` — use only for a low-risk, latency-sensitive
  follow-up after a completed full review; it omits the rotating old-code audit.

Labels override the workflow setting but do not themselves start a review; the
next synchronize, reopen, or other configured PR event does. Remove an override
to return to the repository's hybrid default. Missing/incomplete state or
non-ancestor history automatically falls back to a full review.
