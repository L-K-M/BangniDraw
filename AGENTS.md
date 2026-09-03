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
./gradlew testDebugUnitTest    # :app unit tests (engine-core's run below)
./gradlew lintDebug            # hard CI gate — keep it clean
./gradlew assembleDebug        # debug APK
./gradlew :engine-core:desktopTest  # engine model layer, desktop-JVM target
scripts/build.sh               # release APK staged into dist/
scripts/build.sh --install     # desktop .app installed to /Applications (macOS)
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
- **Module layout (DESKTOP.md Phase 1):** `:engine-core` is the pure-JVM
  engine model layer as a KMP module (Google's KMP library plugin for the
  Android target + `jvm("desktop")`). Both targets are JVM, so shared code
  lives in a custom `jvmShared` source set, not `commonMain`. The KMP and
  KMP-library plugins ship inside AGP 9's distribution — apply them by id
  WITHOUT a version; a versioned alias fails with "already on the classpath".
  The KMP library plugin registers no Android unit tests: the module's
  tests run as `:engine-core:desktopTest` only, which is why CI appends it
  to the test/lint/assemble line. `:engine-gl` follows the same shape and
  adds the GLES30 platform facade: all GL calls go through
  `engine.gl.platform.GLES30` (expect object; Android actual delegates to
  `android.opengl.GLES30`, desktop actual to LWJGL with the array/size
  adapters), logging through `platform.GlLog`, assets through
  `platform.EngineAssets` (`AssetManagerEngineAssets` / `ClasspathEngineAssets`).
  The mixbox/nomixbox variant source sets (and the single copy of the
  vendored `mixbox.glsl`/`mixbox_lut.png` in `engine-gl/src/mixbox/assets`)
  live in `:engine-gl`; `:app` still packages those assets for Android from
  that directory. The `input` package (CanvasTouchHandler, StylusState,
  PalmRejection, PointerSample) also lives in `:engine-core` — platform
  events stop at `input/AndroidCanvasInput` (:app), the MotionEvent adapter;
  the handler's platform services arrive as injected seams
  (`GestureDeadlineScheduler`, `FrameScheduler`, `StrokePredictor`).
  `:desktop` is the Compose Desktop shell: a plain JVM module with an
  offscreen ES 3.0 context. `DesktopGlStartup` brings it up: `EglEsContext`
  (EGL directly, no window) first on every platform, then — only where EGL is
  the system's, i.e. Linux — `GlfwEsContext` (the hidden GLFW window) as the
  fallback. macOS has no automatic fallback (see below).
  `-Dbangnidraw.gl.host=egl|glfw` forces one, which is how Linux CI proves
  both still work. GLFW init, window
  creation, destruction, and termination stay on the process main thread; the
  GL thread activates either context and owns the mandatory LWJGL
  `GLES.createCapabilities()`.
  **Its chrome is the Android canvas chrome, not a desktop layout of its
  own:** a 48 dp top strip over a full-bleed canvas, the tool rail floating in
  the hand's corner, a floating colour panel — every dimension from the shared
  `LayoutSpec` (`DesktopChromeLayout.forWindow`), the button colours from
  `ToolRailColorPolicy`, the paint slots from `PaintSlotAssignments` /
  `RailSlotPolicy`, the glyphs from the one copy under
  `app/src/main/java/ch/lkmc/bangnidraw/ui/glyphs/`, which `:desktop` compiles
  through a `kotlin.srcDir` (keep that directory free of `android.*`, which is
  what makes the sharing possible). `compose.materialIconsExtended` is the
  desktop twin of `:app`'s `material-icons-extended`. All four rail modes are
  live, so the window minimum is 640×480 rather than the old sidebar's 960×600.
  What the desktop chrome deliberately omits is only what this shell cannot
  do: Back and Layers (one painting, one layer, no Studio) and the five
  secondary tools (no engine path). Its own rail budget
  (`DesktopRailPolicy.paintBudget`) exists because `LayoutSpec.paintSlotBudget`
  reserves six non-paint slots for those tools and stops computing outside
  FULL; the paints past the budget go to a menu on the rail, since the
  settings sheet Android overflows them into does not exist here. A long-press
  is not a mouse gesture, so the eraser slot's *second* click is what swaps
  hard for soft eraser.
  Rendering is engine → offscreen FBO → glReadPixels → Compose image
  (DESKTOP.md architecture 1), mouse → PointerSample records, in-memory undo
  from the readback mirror, JVM DataStore prefs, and Save PNG to
  `~/Pictures/BangniDraw`. Mirror byte arrays are immutable after publication:
  export shallow-copies its map on the GL thread, then composes and writes on
  the export worker. Do not add an export-time fence wait — stroke commits
  already drain readback, and holding the GL owner can exhaust `DabRing`.
  Export encodes into a sibling temporary file and publishes only the complete
  PNG. Shutdown drains that worker before interrupting GL. Its GL join is
  bounded; if native code does not release the owner, mark the context
  abandoned and skip GLFW teardown rather than destroying a current context.
  GL readback row zero is already canvas top; do not flip it. Heap-buffer
  readback needs an explicit bounded copy because native
  writes do not advance a Java buffer's position.
  On macOS, initialize AWT first, select LWJGL's `glfw_async`, disable GLFW's
  Cocoa menu, and use the ANGLE Metal init hint. Gradle fetches the pinned
  Electron archive, verifies its SHA-256, and stages its ANGLE dylibs and
  licenses as Compose app resources; no native binaries are committed.
  `bangnidraw.angle.dir` remains a development override, followed by Compose
  app resources and the working directory. **Every load of ANGLE goes through
  an absolute path.** GLFW opens EGL with a bare `dlopen("libEGL.dylib")` — a
  leaf name, issued at first window creation, not at `glfwInit` — and the only
  thing that ever made a bundled ANGLE answer that call was a process-wide
  `chdir` into its directory. dyld does search the working directory for a leaf
  name (unchanged from macOS 11 through 26, `Loader::forEachPath`), but only
  while AMFI leaves `allowAtPaths` set, and the search is invisible,
  process-global state that anything else can clobber: GLFW's own
  `GLFW_COCOA_CHDIR_RESOURCES` did exactly that, and the resulting `GLFW error
  65542: EGL: Library not found` produced the byte-identical "OpenGL ES 3.0 is
  unavailable" window (CI run 33668171196). Phase 4 signing is the next thing
  that could take it away. Do not build on it. So macOS loads ANGLE only
  through `Configuration.EGL_LIBRARY_NAME`/`OPENGLES_LIBRARY_NAME`, absolute,
  and **does not use GLFW at all**. LWJGL's `Configuration` never reaches GLFW,
  and its documented remedy does not work either: neither shipped GLFW build
  (`libglfw.dylib`, `libglfw_async.dylib`) exports the `_glfw_egl_library`
  symbol `GLFWNativeEGL.setEGLPath` writes to, so the override silently does
  nothing — and running the GLFW host after ANGLE is already loaded crashed the
  JVM inside `libglfw_async` at init (CI run 33735259059). ANGLE's own libEGL
  then finds libGLESv2 beside itself (`dladdr` on its own module), so a flat
  resources directory is the supported layout. Keep
  `GLFW_COCOA_CHDIR_RESOURCES` false for the Linux-only host's sake: its
  default would relocate the process into `Contents/Resources`.
  **Never reach ANGLE's `eglGetPlatformDisplayEXT` through LWJGL's
  `EXTPlatformBase`.** `EGL.createClientCapabilities` in 3.4.3 reads the client
  extension string only to null-check it — it registers the core versions and
  no client extension — so every `EGL_EXT_platform_base` pointer in
  `EGLCapabilities` is NULL and calling one throws `NullPointerException`.
  `EglEsContext` resolves that entry point by name from the function provider
  and calls it through `JNI`; the same gap is why `eglBindAPI` is guarded by
  `EGLCapabilities.EGL12` (ES is EGL's default bound API anyway).
  The staged dylibs are thin, so a wrong-architecture or too-new-minimum-macOS
  library is *present* and unloadable, which reads like a missing one.
  `resolveAngle` therefore prefers the first candidate this JVM can open over
  the first that merely exists — a stale `-Dbangnidraw.angle.dir`, which README
  hands to `JAVA_TOOL_OPTIONS` for every JVM in that shell, must not shadow the
  bundle — and the report names every candidate with its architecture and
  minimum macOS. `--gl-report` prints that report for a user who never sees
  stdout. Note the upstream asymmetry: the arm64 ANGLE is ad-hoc
  signed and the x86_64 build carries no signature at all, so macOS CI verifies
  signatures on arm64 only. The runners are macOS 26 as of September 2026, and
  Intel remains the untested axis: no runner has ever staged `macos-x64`.
  When Phase 4 signs and notarizes the bundle, a Developer-ID identity turns on
  library validation, which refuses a third-party ANGLE: that build will need
  `com.apple.security.cs.disable-library-validation`. macOS CI verifies both
  dylibs, their host architecture, an ANGLE GL log, one packaged frame,
  the
  Metal display, and the unpackaged `gradlew run` path; Linux CI covers both
  context hosts on every commit. The app bundle remains
  unsigned until Phase 4.
  The diagnostic launch modes (`--gl-report`, `--smoke-window`,
  `--smoke-startup-failure`) end with an explicit `exitProcess`: startup
  initializes AWT, whose threads are not daemons, so the JVM lingers after main
  returns about one run in three — and CI reads these modes through a pipe,
  where a lingering process wedges the step instead of ending it. Redirect
  rather than pipe in CI for the same reason: `timeout` kills the `xvfb-run`
  wrapper, not the JVM it forked. The interactive path is deliberately left to
  exit on its own; it may still be flushing preferences.
  Packaged runtimes require `java.instrument`, `jdk.management`, and
  `jdk.unsupported`.
  Desktop display names come from Android's `app_name`; icons derive from
  `media-sources/icon.png`. Linux keeps the internal id `bangnidraw`. Compose
  resource directories are `macos-arm64`/`macos-x64`, and the desktop package
  version reads Android's `versionName`. Its Kotlin files nest comments badly:
  a `/*` inside KDoc (e.g. `brushes/*.json`) opens a nested comment.
- **Kotlin `internal` does not cross module boundaries.** Declarations in
  `:engine-core` that `:app` (or app tests) consume must be `public`; the
  ~135 declarations widened in the M1 extraction are now that module's API
  — new engine-core code used by the app must land public, and public vals
  from another module never smart-cast (capture locally instead).

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
  the adaptive **foreground** layer (full-bleed PNGs generated per density)
  over solid indigo, so foreground-only launcher surfaces retain it. The
  monochrome icon is a hand-drawn brush silhouette.

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
- AGP 9's built-in Kotlin source sets use `kotlin.srcDir`; `java.srcDir`
  silently leaves conditional `.kt` files out of `compileDebugKotlin`.
- The CPU reference implementations in `engine/core` (`Composite`, the
  mixing formula, dab falloff) and the GLSL must stay trivially close; when
  one changes, change both, and let the unit tests pin the semantics.
- `TileFlusher.checkpointFlush()` is the pixel-to-metadata commit barrier.
  A `false` result must not write `project.json`, clear dirty state, or let a
  leave navigate; the pending mirror still holds newer pixels that disk does
  not. The checkpoint's no-op path must also admit outstanding thumbnail and
  history-delete maintenance, and a retry flag clears only after its work
  succeeds.
- GL and tile storage use RGBA bytes. An `ARGB_8888` bitmap's memory order
  is **probed per device** (`BitmapLayoutProbe`): modern Skia (API ~30+)
  stores R,G,B,A exactly like GL, older port-configured builds store
  B,G,R,A. Route bitmap copies through `PixelChannelOrder` with the probed
  layout — never assume either order, and never trust a version threshold
  over the measurement. (v1.1.4 assumed BGRA unconditionally and swapped
  red/blue in thumbnails and exports on every current device.)
- `DabBounds` and `WatercolorDabBounds` own dab-edge arithmetic. Live
  `DabBatch`, `DabPass`, and `WatercolorPass` paths retain primitive edges;
  do not rebuild `IntRect` or tile-scissor objects per dab.
- `DabGenerator` retains one in-flight segment when a batch fills. Publish
  each full batch and resume that segment before advancing again.
  `StrokeDriver.end` stays active until both the segment and stabilizer
  catch-up finish. An exactly full resumed batch may retain the current input
  as the next pending segment; fullness does not mean the input was dropped.
- Sandwich tile passes must ping-pong into a pool page distinct from both
  sampled pages. `Below` supports every blend mode; `Above` is unavailable
  when a visible non-Normal layer breaks source-over associativity. Grouping
  normal `Above` layers can differ from the direct RGBA8 path because the two
  paths quantize at different grouping boundaries; the CPU oracle pins the
  conservative bound of one LSB per grouped layer.
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
- **Accessible choices expose their relationship.** Radio-style rows share a
  `selectableGroup`; switch rows own the toggle action while their nested
  `Switch` delegates it. Blocking transient errors use assertive live regions.
- **Mixbox is CC BY-NC 4.0** (ADR 0003). The attribution lives in the About
  string, the README and `third-party/mixbox/`; keep all three when
  touching any of them. Vendored `mixbox.glsl`/`mixbox_lut.png` must stay
  byte-identical to upstream commit
  `a1bdb75a668f638ba066aa74bfd32809ed7fef45`. Their SHA-256 values are
  `1ca60762c730405f8df18ef08ea0501d43606a67a6d309a610a077c8781cfce4`
  and `b13d7532033d96d963c7e3a854ba2b4e98b8a44d324456386e9b34e0615552be`.
- The app has **no permissions**. Keep it that way; adding any is a
  product decision requiring an ADR.
- Android caps each edge's system-gesture exclusion to 200 dp vertically.
  Canvas centres that segment beside the side rail; dock mode excludes
  nothing so the bottom system gesture remains available.
- Canvas panels use `LayoutSpec.panelInsets`, derived from `persistentChrome`.
  Do not duplicate rail, dock, ledge, or strip padding in Compose. Panel side
  is the user's physical hand side and must not mirror under RTL layout.
- An `AlertDialog` owns a smaller, separate window. Capture activity-window
  dimensions in its caller before applying screen-fit defaults; do not read
  `LocalWindowInfo` from inside the dialog.
- App display name lives ONLY in `strings.xml` (`app_name`). Never
  hardcode "帮你Draw" in a composable (rename checklist: PLAN.md "Renaming").
- Application palette decisions live in `engine/core/ThemeColorPolicy`;
  `ui/theme/Color.kt` adapts them to Compose. This keeps palette decisions pure
  and JVM-testable while the data layer stores only the choice. Construct the
  complete `ColorScheme`, including tertiary, fixed, inverse, and all
  surface-container roles; a defaulting scheme factory imports baseline
  Material colours. Screen chrome never uses ad-hoc
  `Color(0x…)`; `DebugOverlay`'s fixed diagnostic signal colors are the sole
  exception and stay palette-independent. `AppTheme` is a persisted choice
  among light Saffron (default), Coral, Violet, Teal, and Nineties palettes
  and dark Synthwave, Midnight, and Forest ones; system dark mode and dynamic
  color are deliberately ignored. Each theme declares a `ThemeTone`, which
  picks the system-bar icon appearance, the tone's neutral canvas void, and
  the tone's error roles. Its enum names are
  stored values, so renaming or removing one silently resets affected users to
  Saffron unless a migration rewrites the stored names.
  The canvas void stays neutral.
- Backup allowlists include the whole Preferences DataStore and legacy shared
  preferences, so privacy claims apply to painting data, not settings. Log and
  reset a corrupted preference file with `ReplaceFileCorruptionHandler`
  before flows retry; otherwise observation and writes can remain blocked
  forever.
- The launch window cannot read DataStore. Cold starts keep the background
  and system-bar appearance fixed light; recreation seeds the bars from the
  retained ViewModel's tone. Set `android:forceDarkAllowed` to
  `false`, and add no
  `values-night` override. The fixed launch window remains while the root theme
  owner withholds navigation until the first preference emission; the resolved
  theme's tone then re-applies the bar appearance. Log the first
  `IOException`; if it precedes any successful load, emit Saffron once. Retry
  I/O with backoff, at most five attempts, never replacing a loaded theme; a
  persistent failure ends the flow on the current theme. Cancellation and
  non-I/O failures propagate.
- The GL canvas appearance is startup state, not merely a theme-change update.
  Include `CanvasAppearance` in `EngineSession.configure` before publishing the
  session or allowing its bootstrap frame; the later Compose effect keeps
  theme and density changes synchronized. Otherwise transparent canvases can
  flash the renderer's fallback checker colours.
  While a front-buffered stroke is active, defer checker/void mutation with its
  redraw until the stroke's commit or cancel scene; changing only the dirty
  front scissor makes the new checker patchwork over the old baseline.
  A newer immediate appearance must clear any pending value left by a refused
  stroke.
- **In-app documentation is a button, not a hidden manual.** Every panel or
  sheet header and every Settings section carries an (i)
  (`ui/common/InfoHelp.kt`'s `InfoButton`) opening an `InfoDialog` whose
  whole content is one `help_*_body` string; titles reuse the section's own
  string. Adding a surface means adding its help string in both locales in
  the same change — MissingTranslation makes the second locale a hard gate.
  Bodies are one blank-line-separated paragraph per control, and must state
  the non-obvious interaction (long-press, second-tap) the surface hides.
  The canvas overflow's Help is the one surface with no section string of
  its own; its dialog title is `help_canvas_title`. The desktop shell has no
  string resources yet, so its overflow Help reads `DesktopHelp.canvasBody` —
  same rule, same duty to state the non-obvious interaction (the eraser's
  second click, the right button erasing) and to carry the export directory
  the icon rail no longer prints.
- **Greyscale ARGB cannot encode hue.** `ColorPanel` keeps an `HsvSelection`;
  panel-originated ARGB echoes must not reconstruct HSV, while external colors
  must. Do not key the selection state directly to the current ARGB.
- Scripts follow the family house style: header comment doubles as
  `--help` via the awk one-liner; `==>` / `--` / `!!` log prefixes;
  `set -euo pipefail`.
- Third-party assets (brush grains, sample art, fonts) must be public
  domain / CC0 with provenance recorded in this file when added. Currently
  none besides Mixbox.
- Marker, eraser, spray-can, Watercolor, and pigment-wash rail glyphs are
  repo-owned `ImageVector` silhouettes. Material's alternatives depict
  attention, deletion, or a chart, not these local drawing tools — and its
  droplet belongs to the Water tool alone, so a brush reusing it collides in
  the FULL rail. Preset glyph roles resolve from the
  stored `BrushPreset.icon` key in `engine/core`; eraser mode always wins, and
  unknown keys use the settings glyph.
- Proposals for post-v1 features live in `docs/proposals/` as numbered
  pre-decision docs (same Status/Date header as ADRs); an accepted proposal
  graduates into `docs/plan/12-roadmap.md`, a declined one stays with its
  status flipped so the reasoning isn't lost.
- Tracing references are private project assets, not paint. They reserve one
  layer of tile budget, render in `SandwichCache.Below` above paper, and never
  enter thumbnails, sharing, export, or painting undo. Flattens omit them by
  default; the gallery's reference variant is the one exception (its
  deviation entry below).
  The reference is **not canvas-bounded**: enlarged or dragged past the canvas
  border it keeps drawing over the void (`drawReferenceAcrossVoid`), matching
  the direct composite that has never clipped it. The void pass scissorses
  four rect bands around the canvas instead of drawing unclipped, because an
  unclipped draw would composite the reference a second time inside the
  canvas wherever Below is transparent (transparent paper), and rect bands
  cannot cut a rotated hole — a freely rotated view keeps the clip while
  snapped right angles stay exact. Do not "simplify" the bands away without
  solving the transparent-paper double draw. The cached reference base draws into tile-array FBOs, where logical y = 0
  must land in GL row zero; its tile projection is therefore `orthoYUp`.
  `orthoYDown` flips each 256 px strip even though the direct viewport path
  looks correct.
  Before allocating that cache target, the base must report every reference
  page it may sample and use `allocateNotOn` with the returned live prefix.
  Sampling and drawing one texture-array page is undefined even when the
  slices differ.
  Photo Picker is the import boundary; do not add storage permission or retain
  the picked URI. Checkpoints delete only the superseded committed asset;
  reopen preserves the metadata-named asset even when unreadable and sweeps
  other orphans, so a transient read failure cannot destroy recoverable bytes.
- Checkpoints install their immutable document/history snapshot on Main before
  IO and tag it with `CheckpointGeneration`. GL readback dirties share the
  checkpoint-state lock; completion may clear dirty, content, and thumbnail
  state only while its generation is still current. A newer edit already owns
  those flags and the live document. Finish that generation before gallery
  sync can dirty its new metadata; the metadata belongs to the next
  checkpoint, not the content checkpoint that produced it.

## Deviations discovered while building

Recorded per PLAN.md's rule: when the plan contradicts itself, PLAN.md wins
and the contradiction is noted here.

- **The desktop GLES context is created from EGL directly; GLFW is only the
  fallback.** DESKTOP.md's "The JVM binding" section specifies GLFW with
  `GLFW_EGL_CONTEXT_API` and repeats libGDX's chdir trick for finding a
  bundled ANGLE. Shipped as `EglEsContext` first instead, because the chdir
  is not sound: GLFW opens EGL with `dlopen("libEGL.dylib")` — a leaf name —
  and that resolves a bundled library only through a process-wide `chdir`
  whose effect anything can clobber — GLFW's own `GLFW_COCOA_CHDIR_RESOURCES`
  already did once, for the byte-identical "OpenGL ES 3.0 is unavailable"
  window (CI run 33668171196). What failed on the developer's Mac was never
  established: `gradlew :desktop:run` had no macOS CI coverage and was the
  first suspect, but dyld's working-directory search survives a hardened,
  entitlement-signed `java` (it is gated on AMFI's `allowAtPaths`, which that
  same `java` needs for its own `@rpath/libjli.dylib`), so that story does not
  hold up. The fix does not depend on which one it was. The window
  GLFW provided was hidden, 1x1 and never drawn to — the engine renders to an
  offscreen FBO — so GLFW was only ever a context provider, and EGL provides
  the same context by absolute path, on any thread, with no NSWindow, no
  NSApplication delegate (GLFW replaces AWT's) and no main-queue dispatch.
  The GLFW host stays only where EGL belongs to the system — Linux, where no
  leaf-name lookup of a bundled library is involved — and
  `-Dbangnidraw.gl.host` forces either one so Linux CI proves both still work.
  It is not a macOS fallback: LWJGL's supported path override is a no-op in
  the shipped GLFW builds (the symbol is absent), and the host crashed the JVM
  there once ANGLE was loaded.

- **`*.tmp` is swept "on every save" (06 §2) — except a live writer's own.**
  `ProjectStore.checkpoint` sweeps its directory before writing, so under the
  literal rule a checkpoint deletes the scratch file of any write already in
  flight to the same directory and fails that writer at its rename, for no
  fault of its own. `AtomicFiles` therefore tracks its in-flight temp paths and
  `sweepTmp` skips them. A crashed writer's leftovers — the ones §2 exists to
  collect — are unaffected, because nothing holds them.

- **Two writers of one file must not share a scratch path.** The temp name
  carries a per-write token, not just the target's name. With a shared name
  `FileOutputStream`'s truncate-on-open puts overlapping writers on one inode:
  the first to rename publishes whatever it holds and returns success, the
  rest throw at a rename whose file is gone, and the descriptor that lost keeps
  writing into the *published* target in place. Measured over six concurrent
  1 MiB writers, the bytes that landed belonged every run to a writer that had
  reported failure. `AtomicFiles` only promises that what lands is complete;
  stopping the *lost update* is the caller's job, which is why `PaletteStore`
  and `BrushPresetStore` are `@Synchronized`.

- **v1.0.0 was explicitly authorized without real-device acceptance.** No
  device was available for screenshots, upgrade testing, the phone/tablet
  checklist, TalkBack/Accessibility Scanner, or native zh-Hans review. The
  user directed the release after those blockers were reported; do not infer
  that any device gate passed from the existence of the tag.

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

- **The dab's colour is a uniform; ink and bristle state are per dab.** The
  original §7.3 snippet put `vec3 i_color` at location 7 even though one stroke
  has one colour. It remains `u_color`. `DabBatch` now carries eleven floats:
  `x, y, radius, flow, hardness, angle, aspect, seed, wetness, bristleAlong,
  bristleAcross`; locations 7–10 activate the last four in `DabPass`. Brush
  model and stroke opacity are also uniforms.

- **`builtin.calligraphy` is the stateful Chinese ink brush, not a textured
  chisel.** `BrushModel.ChineseInk` transports a soft tuft axis through turns,
  splays the footprint with pressure, records stationary pressure changes, and
  depletes `Dab.wetness` by swept distance rather than dab count. One seed
  persists for the stroke. CPU and GLSL derive the same stroke-local
  split-bristle lanes; dry gaps are zero coverage while surviving hairs stay
  dark. Paper tooth is one canvas-fixed hash shared by every stroke.
  **The mask uses two frames per dab.** The footprint (ellipse distance,
  edge ramp) uses the lagged tuft axis `dab.angle`; the hair lanes use the
  stroke tangent `dab.pathAngle` with a plain arc-length along phase.
  Fly-white channels are drag channels: they must hug the stroke direction
  even when the tuft trails `atan(responseLength/R)` behind on a curve —
  the earlier single-frame model let lanes cross the stroke near
  perpendicularly on hand-drawn spirals and waves, on both the pre- and
  post-retune builds. In the path frame the centre never crosses its own
  lanes, so the across phase is identically zero and `pathAngle` took the
  dab's eleventh slot from the deleted `bristleAcross`. Prediction must copy
  all of that state. `StrokeDriver` selects
  the dynamics-aware stabilizer
  gate only for `ChineseInk`; `Standard` keeps its position-only sampling.
  This procedural mask adds no third-party asset, and `wetness` is ink load,
  not the post-v1 per-layer water/diffusion channel.
  **Tuning, measured against reference calligraphy and CPU renders of the
  user's own finger gestures:** the splay *eases in* from the round first
  touch over the tuft response length (`strokeTravelled`); snapping to the
  pressure aspect on the second dab read as a balloon-on-ribbon head, and the
  turn test's full-splay assertion therefore runs over a 300 px leg.
  `INK_CAPACITY` is 5 `baseRadius` units of swept contact per halving —
  because every stroke starts loaded (the plan fixes that), fly-white must
  develop *within* one ordinary stroke, not after several.
  `BREAK_LENGTH_PX` 256 keeps hair lanes coherent for long streaks; 32 broke
  them into dashes every couple of tuft-widths. `EDGE_DRYING` 0.5 frays the
  rim even when loaded — a vector-clean edge reads as a marker, not a brush.
  The tip's full-contact aspect is 0.72 (head:body ≈ 1.4), not the
  chisel-like 0.58. The mask constants are interpolated into `dab.frag` from
  `InkBrushMask`, so these values move CPU and GLSL together.

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

- **`GlErrors.checkAllocation` owns the GL call it checks.** Release passes do
  not drain `glGetError`, so a stale pass flag may remain queued. The wrapper
  clears that flag before its operation and checks again after it; issuing the
  allocation first can misattribute the stale error and refuse valid GPU work.

- **graphics-core 1.0.4's callback is not the one `03-canvas-engine.md` §8.2
  names.** The plan writes `onDrawMultiDoubleBufferedLayer(eglManager,
  bufferInfo, transform, params)`; the pinned library actually declares
  `onDrawMultiBufferedLayer(eglManager, width, height, bufferInfo, transform,
  params)` — a different name and two extra `Int`s. Verified against the AAR,
  not guessed. The two sizes are not redundant: `width`/`height` are the
  **surface's** and `bufferInfo.width`/`height` are the **buffer's**, which are
  swapped when the compositor hands over a pre-rotated buffer — which is the
  whole reason the present step is a quad through `u_bufferTransform` and not
  a blit. That quad starts at `Accum`'s logical dimensions; starting at the
  swapped buffer dimensions clips a band after the transform.
- **Window presentation is y-up and flips only its source uv.** `Accum` is a
  viewport-oriented texture, but SurfaceControl consumes GL row zero as the
  HardwareBuffer's top row. The present-only vertex shader therefore flips
  `Accum`'s v coordinate and projects into the buffer with `orthoYUp`; this
  makes both pixels and `BufferScissor` follow graphics-core's transform
  exactly. Reusing the offscreen y-down shader conjugates that transform by
  two vertical flips. Identity devices hide the error, while a 90°/270°
  pre-rotated buffer reverses direction, leaves a visible 180° rotation after
  SurfaceControl, and clips live ink outside its damage rect until pen-up.
- **Canonical 180° buffer transforms are neutralized on both sides.** The
  fallback uses identity for presentation/damage and replaces the matching
  SurfaceControl transform with identity in the same transaction. The first
  Samsung report was inferred to be this path without transform telemetry;
  the later phone/tablet comparison instead exposed the quarter-turn row bug
  above. Keep producer and consumer changes paired if this fallback is touched.
- **Front damage has two bounds.** The inflated window-space scissor decides
  which `Accum` pixels are cleared. Tile selection uses that scissor
  inverse-mapped to canvas space, not the original dab rect. Otherwise the
  clear crosses a tile edge without redrawing its neighbor and leaves 1 px
  white grid seams until pen-up.
- **Viewport resize has one rebase owner.** `CanvasTouchHandler` rebases the
  view from the old fit to the new fit and publishes that resize to GL before
  Compose state. `CanvasRenderer.onSurfaceChanged` adopts the new fit and
  reallocates targets, but never rebases again: callback order can otherwise
  apply the same rotation resize twice and move the paper outside the viewport.
  Ordinary pan/zoom callbacks do not publish directly to GL; `CanvasSurface`
  owns their state-driven redraw so navigation does not schedule two commits.
- **Viewport target recreation must first release reusable FBOs.** Deleting an
  attached texture can leave the FBO holding its old storage while GLES reuses
  the numeric name. `GlFbo` would then mistake the replacement texture for its
  cached attachment, draw into the deleted object, and present the blank new
  target. `CanvasRenderer.onSurfaceChanged` therefore releases `fbo` and
  `readFbo` before replacing `Accum` or `Scratch`.
- **Accum and the window target use different scissor row conventions.**
  `Accum` is an ordinary texture FBO, so its y-down dirty rect becomes
  `height - bottom` for `glScissor`. graphics-core's HardwareBuffer is consumed
  by SurfaceControl in top-first buffer rows; the present quad already accounts
  for that orientation, so its scissor keeps `y = top`. Flipping it again opens
  the vertically reflected damage band: live ink appears only where the stroke
  crosses that reflection, then the unscissored pen-up frame reveals everything.
- **Paper is a transformed canvas quad, not a viewport clear.** The old
  `03-canvas-engine.md` §3.2 step 1 said to clear viewport-sized `Accum` to the
  paper colour, while `08-ui-and-layout.md` §5.1 explicitly defines
  `canvasVoid` as the area outside the paper. The UI styling rule wins: clear
  `Accum` to `canvasVoid`, then draw paper/checker geometry through the same
  `ScreenTransform` as layer tiles. The sandwich's opaque `Below` already
  contains paper and skips that extra quad; transparent paper still draws its
  checker beneath `Below`. Keep a dedicated canvas-sized `FullRectQuad` —
  sharing the viewport present quad alternates dimensions and uploads geometry
  twice per transparent frame.
- **`renderMultiBufferedLayer(Collection<T>)` exists in 1.0.4 but bypasses
  commit coordination.** It does not increment the library's `mCommitCount`,
  so a new front render can race the later release-time clear.
  But `commit()` increments that count before checking whether its render
  target exists; calling it before `surfaceChanged` schedules nothing and
  strands every later render behind a count that cannot fall. The first
  `commit()` after attachment also deadlocks: its displayed buffer is the one
  whose later release would decrement the count, while a second `commit()` sees
  the nonzero count and schedules no replacement. `EngineSession` therefore
  gates every render on its own `SurfaceHolder` callback and seeds each
  generation with exactly one direct multi-buffered frame. Its completion
  makes the attachment ready; only then may ordinary redraws use `commit()` or
  input use the front layer. A generation-tagged GL FIFO marker starts that
  bootstrap only after graphics-core creates its targets; stale markers are
  ignored. A direct multi draw is safe only for this pre-front, pre-commit
  baseline; never mix another one into a ready generation.
  Each attachment gets a fresh `GLFrontBufferedRenderer`, resetting 1.0.4's
  sticky counters, while one shared `GLRenderer` preserves the canvas GL
  resources. The app callback is registered first, so it retires the old
  wrapper before graphics-core handles the same redraw event. Equal Compose
  state is filtered before it requests another redraw. Every actionable gate
  result carries its attachment generation; the dispatcher accepts only the
  matching wrapper, because redraw and upload completion can arrive off-main.
  `Callback2` completion waits for a GL FIFO marker queued by the multi-buffer
  completion callback, after graphics-core submits its transaction. Do not use
  `Transaction.addTransactionCommittedListener`: graphics-core delivers it
  only from API 31, while the app supports API 29. Normal creation stays posted
  so startup configuration reaches GL first; the blocking synchronous-redraw
  fallback creates inline.
- **A commit-backed multi-buffer completion hides the front layer before its
  buffer is released.** Front requests stay queued while `mCommitCount` is
  nonzero. Release marks the front buffer dirty; the next front callback clears
  it before app drawing. `EngineRenderPolicy` therefore rebuilds the cumulative
  live preview once after an active completion, then returns to incremental
  front-buffer drawing. Re-presenting the cumulative stroke every frame defeats
  scan-line racing and produces a moving horizontal cutoff.
- **graphics-core 1.0.4 holds `ParamQueue.mLock` while the app draws a front
  frame.** A raw front request per input batch therefore blocks the input
  thread behind GL work. `EngineSession` keeps one raw request outstanding;
  later batches only latch one follow-up, dispatched from the generation-checked
  completion after a GL FIFO marker. Each live callback snapshots its queue
  depth before returning ring slots, so a producer cannot keep that frame
  draining indefinitely. Pen-up and cancel still drain exhaustively.
- **Prediction never borrows `DabRing`'s final free slot.** A tail is
  replaceable next frame; physical input is not. `onStrokePredicted` must use
  `EngineSession.acquirePredictionDabBatch`, which preserves one slot for real
  input. Real samples and pen-up keep using `acquireDabBatch` and may exhaust
  the ring. Routing prediction through that ordinary path silently restores
  sample starvation under a GL stall.
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
  bearing rather than defensive: `EngineSession.endStroke` and `cancelStroke`
  drain queued `DabRing` slots inside such blocks, so a dropped block can
  strand them without an exception. Clearing the pinned engine at disposal
  keeps blocks from being queued onto a released renderer.
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
- **Finger clock transitions need a real scheduler.** `GestureArbiter.tick`
  describes the 120 ms draw and 500 ms long-press transitions, but a stationary
  finger emits no `ACTION_MOVE` to drive it. `CanvasTouchHandler` owns one
  absolute, view-posted deadline and synchronizes it after every arbiter
  transition; lift, cancel, chords, and handler reset must disarm it. A quick
  touch-drawing tap resolves as Draw + End from its buffered pending sample.
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

- **The gallery keeps a second copy that includes the tracing image** — a
  user-directed product change to proposal 0001's "gallery sync omits it".
  While a reference is visible with opacity above zero (`ReferenceGalleryPolicy
  .includes`, the same gate the renderer's draw applies), every gallery sync
  also mirrors a *reference variant*: the painting flattened with the decoded
  asset composited above paper and below every paint layer. `ReferenceComposite`
  samples it (nearest/bilinear per `FilterPolicy`, premultiplied taps, no
  supersampling — one offline pass, not a per-frame one), and `CpuFlatten`
  composites it as a synthetic source-over layer, so opacity and blend math
  take the same `Composite.tile` path paint layers take. The variant is its
  own MediaStore row — `"<title>" + gallery_reference_suffix` — with its own
  tamper bookkeeping (`referenceGallery*` in `project.json`, sharing
  `lastGallerySyncAt` for the debounce). Reference-only edits move no pixel
  revision, so `CanvasViewModel` counts `referenceEdits` for the due check
  and `applyTracingReference` bumps `updatedAt` — otherwise the Studio's
  on-disk staleness rule would never resync a transform change after reopen.
  When the reference stops qualifying, the row is withdrawn: deleted only
  while still ours and untampered (`GalleryExporter.withdraw`); an edit by
  another app survives and the URI is forgotten, exactly as a REINSERT
  forgets, while a delete that failed retryably keeps the URI for the next
  trigger. A duplicate inherits neither gallery row, and the Studio's delete
  dialog offers both. Privacy: the variant puts the reference photo into the
  shared gallery automatically — the settings help string says so, and
  share/export still never include it.

- **Studio thumbnail identity includes the painting revision.** Checkpoints
  rewrite the same `thumb.png` path, so path-only Compose or image-cache keys
  can retain stale pixels after the shelf refreshes.

- **Step 4's flattens are `CpuFlatten` over `Composite`, not the GL band
  flatten.** Same trade as the thumbnail note above, extended: gallery syncs
  and shares always run after a checkpoint (or from the Studio with no canvas
  open), so the tiles are flushed, the pixels are identical by PLAN §7's
  pinning, and nothing borrows the GL thread. 03 §10.4's `CanvasRenderer
  .flatten` is still owed; when it lands it supersedes both call sites.

- **Fill reference tiles are captured eagerly from the GPU.** `04-tools.md`
  §7 specifies progressive CPU-mirror/PBO faults as the scan reaches each
  tile. Step 8 instead composites every currently resident relevant tile,
  without paper, and synchronously reads each result before CPU scanning.
  This preserves current pixels and reference semantics, but front-loads work
  and uses full-canvas scan/dilation scratch masks rather than a bbox-only
  mask. The device performance gate is therefore still required; replace this
  path with progressive faults if it misses the §7 budget.

- **The gallery-sync debounce is pinned as a pure rule, not a ViewModel
  clock test.** `11-testing.md` §7 puts the 30 s floor case in a
  `CanvasViewModelTest` on `kotlinx-coroutines-test`; the decision instead
  lives in `GallerySyncDecision.isDue` (engine/core) with its own test, and
  the ViewModel only consults it. No `kotlinx-coroutines-test` dependency
  exists yet; add it when a real coroutine-clock test earns it, not before.

- **`PointerSample.tool` is filled only where it is read, and is `null`
  everywhere else.** Reading it costs a `MotionEvent.getToolType` JNI call per
  sample, and only a down (palm rejection, the eraser end) and a hover-enter
  (the cursor) consume it; a move, a lift and every predicted sample continue
  a gesture whose tool was settled at a down. Those fill through
  `setWithoutTool`/`setHoverWithoutTool`, which **clear** the field rather
  than leave it — one record serves every pointer, so a leftover value is not
  even this gesture's: with a palm down as pointer 0 and the pen drawing as
  pointer 1, pointer 0's moves would read `STYLUS`. Hence nullable rather than
  a default: `FINGER` would be as confidently wrong the other way. If a
  consumer ever needs the tool on one of those paths, move its call site back
  to the full `set` — do not read the field and hope.
  `PointerSampleToolContractTest` pins both halves and will fail first;
  `PointerSampleTest` pins the clearing itself.

- **The scaffold's `detectTransformGestures` was deleted, not rewired.** It
  drove a Compose drawing of the paper; pointing it at the engine would make it
  a second owner of touch input, and `07-input-and-stylus.md` §2 makes
  `CanvasTouchHandler` (roadmap 2.4) the single owner of `MotionEvent`. 2.3b
  therefore has no touch navigation at all, and a debug-build-only "nudge view"
  button exists so 2.3b's device check — the reset pill returning to fit — is
  runnable on a device. It goes away with the touch handler.

## Conventions the plan leaves open

- **Redo sidecars use post-edit tile owners.** A merge entry's before payload
  names upper and lower layers, but its redo payload names only the merged
  lower layer. A flatten redo payload names only the flattened result. Never
  derive `.redo` keys from `HistoryCodec.payloadKeys`; use
  `redoPayloadKeys`.
- **All-zero readback removes a sparse tile key.** GPU slices may stay
  resident, but `TileStore` deletes zero tiles, so the immutable layer model
  must drop the key at checkpoint too. History validation accepts subsets of
  recorded keys because this fold can happen between undo/redo cycles. The
  lag cuts both ways and both directions bite validation: a key the fold has
  not yet removed (`LayerHistory`'s add-undo and clear-undo/redo originally
  demanded exact sets) and one it has not yet added (the renderer's
  `prepareCopy`, which compares a duplicate's source set exactly). Two rules
  came out of fixing that: `LayerHistory` never compares tile sets in a
  direction folds can move them (props and identity only where the fold
  decides membership), and an applied undo/redo folds its restore outcomes
  into the model immediately (`LayerTileUpdates.apply` in
  `applyPreparedHistory`) — restores know their exact outcome, so the model
  stops lagging the pixels at all on that path and the GL side's exact checks
  hold again.
- **Recovered structural entries are replayed before disk tiles are relisted.**
  `HistoryStore.load` only proves the post-checkpoint journal prefix; it does
  not update `project.json`'s stale stack. Replay that prefix through
  `HistoryRecovery`, then relist every recovered layer directory.
- **Project duplication commits by directory rename.** Build the copy under
  `<uuid>.duplicating`, write its `project.json` last, then rename it to the
  final UUID. The next Studio listing sweeps abandoned duplicate stages while
  sparing every store instance's active stages through the process-wide
  companion guard.
- **Entry payload keys and changed tile keys differ.** Duplicate and flatten
  write tiles under new owners that have no before-payload. `WriteEntry` must
  flush `LayerEditPolicy.changedTiles`, and layer directories may be deleted
  only after both readback and tile flush report complete.
- **Layer-panel thumbnails stay on the GL boundary.** `LayerThumbnailPass`
  renders an isolated layer into one 128 px target and reads through two PBOs;
  the ViewModel only throttles dirty layer ids and publishes finished pixels.
  Never rebuild panel thumbnails from tile files or on the UI thread.
- **Layer lock is checked before a pixel tool reaches `EngineSession`.** The
  immutable stack refuses structural pixel edits, but strokes bypass those
  operations. `StrokeLayerPolicy` is the matching input-boundary guard; a
  hidden active layer remains drawable and previews until pen-up.
- **Pen-up ends input, not the stroke transaction.** `CanvasActionGate` stays
  closed until the entry is pushed or the unjournaled fallback finishes.
  Every engine end path must report merged or not-merged exactly once; the
  not-merged callback returns on Main. Leave is one terminal gate action:
  system Back, top Back, and Settings coalesce there, then checkpoint only
  after the final stroke outcome. A failed checkpoint or cancelled handoff
  reopens the gate.
- **RMW tile coordinates are canvas-top-first.** Unlike window-space and
  accumulation scissors, an RMW tile target maps canvas row zero directly to
  GL row zero; do not Y-flip `RmwTileScissor`.
- **RMW cancellation resolves current pixels through `TileFlusher`'s FIFO.**
  A direct disk read can race a pending sparse-tile removal or replacement.
  `ResolveCurrent` is the ordering barrier before the captured before-image is
  restored.
- **`TileFlusher` shutdown is a FIFO drain, not cancellation.** Canvas
  teardown takes the checkpoint mutex, runs one final leave checkpoint, closes
  the flusher's channel, and joins its application-scope worker. Closing the
  channel lets the receive loop finish every accepted job before it exits;
  cancelling the worker can strand tile buffers. Expected storage failures are
  contained by each job, complete its result, and retain pending pixels for a
  retry. A non-cancellation bug is captured without escaping the handler-less
  application scope; `closeAndJoin` preserves its cause, and teardown logs it
  rather than claiming the FIFO drained or crashing as the Canvas disappears.
  Lifecycle cancellation remains cancellation and propagates.
  The per-Canvas worker starts synchronously in the ViewModel's property
  initializer and is single-use. `onCleared` detaches the engine session
  before the final checkpoint, so its readback drain cannot remain pending.
  Failed-save leave gating owns retry while the screen exists; after teardown
  no checkpoint producer remains, so retaining the worker cannot recover data.
- **RMW before-images are captured in memory on first tile touch.** Before
  commit, those pixels may exist only on the GPU, so the open stroke cannot
  use the plan's disk journal literally. Pen-up persists the ordinary history
  entry; context loss restores the captured pre-stroke state before reopening
  the persisted document.
- **RMW scratch targets separate logical size from retained capacity.**
  `SmudgePass` keeps its pressure-sized `before` and blur-work textures at
  their high-water dimensions. Smudge pickup textures remain exact-sized
  because their viewport UVs assume the allocation matches `pickupEdge`.
  Viewports use `OffscreenTarget.width`/`height`, shader UVs use
  `capacityWidth`/`capacityHeight`, and `bytes` reports the capacity. Mixing
  those dimensions stretches samples or under-reports GPU memory without
  producing a GL error. Watercolor's colour-before, two wet, and backup-copy
  targets follow the same grow-only rule. Their maximum retained capacity is
  18.25 MiB outside `TilePool`; `MemoryBudget` reports that ceiling and
  renderer diagnostics report actual retained bytes.
- **Generated palette names use a closed token grammar.** Only the four exact
  built-in tokens `@string/palette_painters`, `@string/palette_basic`,
  `@string/palette_recent`, and `@string/palette_my` resolve through resources.
  User names are literal; never resolve arbitrary stored `@string/` values.
- **A failed MediaStore write must leave no row, never a published one.**
  `IS_PENDING` may only be cleared on the success path. `"wt"` truncates the
  row on open, so a failed rewrite has already destroyed the previous pixels;
  publishing what survived puts a partial PNG in the user's gallery *and*
  strands it there, because `sync` returns null, `project.json` keeps the
  pre-write size/date, and the next `probeRow` reads that mismatch as another
  app's edit — which `GallerySyncDecision.REINSERT` answers by forgetting the
  URI and leaving the item alone. So the tamper guard built to protect
  someone else's edit ends up protecting our own corruption. Both `insert`
  and `rewrite` therefore delete the row they were writing and let the next
  sync start clean, through the shared `discardRow` helper. Guard the delete
  itself (`runCatching`) so a provider that also refuses it cannot mask the
  failure being reported — that log line is the only diagnostic either caller
  leaves behind. The **publish belongs inside the same guarded region as the
  write**, not after it, so that either the row is published or it is gone.
  A row left `IS_PENDING` with *complete* pixels by **process death** is
  healed separately — `probeRow` reads `IS_PENDING` and `GallerySyncDecision`
  rewrites a pending row we own ahead of the tamper check — but that reclaim
  needs a later probe to succeed, and it is not a substitute for the guard: a
  publish that *throws* has code still running and must discard the row then
  and there.
- **`RemoteException` is a sibling of `RuntimeException`, not a subtype.**
  `DeadSystemException` -> `DeadObjectException` -> `RemoteException` ->
  `AndroidException` -> `Exception` (checked against the platform jar). A
  provider process dying mid-call — the canonical OEM fault — therefore
  escapes an `IOException`/`RuntimeException` chain entirely. Every
  cross-process call in `GalleryExporter` needs it: the writes, the probe,
  and `withdraw`'s pair. Containing it at the probe must return null rather
  than set `threw`, which means "ownership refused" and routes to REINSERT,
  abandoning a row that may be a reclaimable pending claim.

- **MediaStore entry points contain `RuntimeException`, not just
  `SecurityException`/`IOException`.** `sync` and `withdraw` both run from
  `StudioViewModel`'s background sweep on `viewModelScope`, where an escape
  ends the process while the user is only browsing the shelf. OEM providers
  throw `IllegalArgumentException`/`SQLiteException` in practice. Order the
  clause after `SecurityException`, which is itself a `RuntimeException` and
  keeps its own ownership-lost handling. Note that ANALYSIS R10's remedy — a
  handler on `appScope` — does **not** reach `viewModelScope`.
- **FULL-rail paint slots are durable assignments.** The ordered preset ids
  live in `Prefs`; settings-sheet choices swap into the active slot, while
  rail taps only activate a slot. `LayoutSpec.paintSlotBudget` caps how many
  assignments fit the window. If resize hides the active slot,
  `RailSlotPolicy` projects it into the last visible position without
  mutating assignments. Unknown/deleted ids are dropped and new catalogue ids
  append in `BrushPresets.RAIL_ORDER`. GROUPED/SHORT/DOCK show the active
  assignment. The active index remains session-only.
- **The seven specialty brush presets have no device feel pass.** Their JSON
  parsing, dynamics, grain modes, rail priority, glyph roles, and localization
  are pinned on the JVM; their physical feel still needs stylus testing.
- **HSV fine controls use `HsvChannel`.** Keep their ranges, discrete steps,
  reads, and replacements in that pure enum so visual sliders and accessibility
  adjustments cannot drift. The current-color chip has a named long-click only;
  never add an inert click action.

- **Redo-sidecar accounting can prune both sides of the history cursor.** A
  first undo adds bytes after the original push, so `noteRedoBytes` enforces
  the cap immediately. It drops the oldest applied entries first, then the
  far redo tail if needed; keeping the nearest redo entry preserves a valid
  transition from the current pixels. The returned seqs join `pendingDeletes`
  and remain on disk until the next checkpoint commits their absence.

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

- **Watercolor wetness is transient, coarse, and gesture-driven.** Each
  accepted non-zero Watercolor or Water dab performs one direct GLES 3.0 RMW
  update; there is no background or pen-up settling pass. One wet texel covers
  4×4 canvas pixels, so one physical 256² RGBA8 pool slice covers 1024² canvas
  pixels and a full 4096² wet layer costs 4 MiB. G/B store a 100 ms monotonic
  tick; sampling removes a fixed water volume lazily. Half a water unit lasts
  about 12 seconds, one unit about 24 seconds, and both full reservoirs at
  most 48 seconds. The 100 ms presentation refresh reclaims expired pages.
  Wet texels stay GPU-authoritative, so every page uses the 48-second bound;
  lighter loads may fade before its refresh stops.
  Tick-epoch rollover age-only re-encodes pages plus the
  active backup before the epoch advances, so modulo age cannot resurrect
  stale water. Wet state is not persisted or journaled: cancel restores
  touched wet pages, undo/redo/reopen/context loss start dry, and destructive
  pixel edits clear affected wet layers. Blank Water over an absent tile
  or blocks `TileContentIndex` has classified empty changes wet state but
  creates no colour tile or history entry; over a resident tile that the
  index has not classified, `UNKNOWN` conservatively
  forces the colour path, so the gesture allocates a slice and journals a
  no-op entry whose all-zero after-image folds away at the next checkpoint —
  transient bookkeeping, never a persisted tile. `TileContentIndex` tracks
  alpha occupancy in 4×4 blocks; `UNKNOWN`
  is conservatively occupied, so Water never skips pigment it has not
  classified. Water transports committed premultiplied pixels from every
  brush model, including Chinese Ink. A preset cannot combine
  `WatercolorBehavior` with `BrushModel.ChineseInk`: direct RMW bypasses the
  tuft/bristle path. Wet grids and their one-gesture backup share `TilePool`;
  ordinary stroke and wet-backup reserves are mutually exclusive. The budget
  is `N·colour + N·wet + max(colour reserve, wet reserve)`.

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
