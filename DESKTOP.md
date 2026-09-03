# DESKTOP.md — can BangniDraw run on Mac and Linux?

Research answer, written 2026-08-30. Method: a two-stage agent investigation —
a nine-agent research pass (five codebase-inventory agents over main @
`d4ea4a8`, three external-research agents, one cross-checker), followed by a
seven-agent adversarial verification pass that re-checked every load-bearing
external claim against primary sources (vendor docs, driver sources, upstream
repositories). Claims that failed verification were corrected or dropped;
corrections are folded in below. Line numbers reference main @ `d4ea4a8`.

## Verdict

**Yes — with one product-defining obstacle (stylus input) and one
product-defining unknown (stroke latency, measurable only by prototype).**
In more detail, three answers to three different readings of the question:

1. **Can the code be made to run on macOS and Linux?** Yes, and unusually
   cheaply for a GPU-heavy Android app. The engine's model layer is already
   100% JVM-pure, the GL layer is GLES 3.0 behind a narrow seam, the UI is
   Jetpack Compose (which is source-compatible with Compose Multiplatform's
   desktop target), and the preferences layer sits on a library that already
   ships desktop artifacts. Every needed runtime piece exists and is
   production-proven individually — on Linux natively, on macOS via ANGLE
   (the same GLES-on-Metal layer Safari and Chrome use for WebGL).
   Combining them in one window is unproven glue work (see Compositing).

2. **Would it be the same *drawing* app?** Not without extra native work.
   The wall is stylus input: Compose Desktop (and the JVM itself) delivers
   **no pen pressure, tilt, or eraser state on any desktop OS** — every
   stylus arrives as a mouse. Pressure is reachable, but only through a
   small per-OS native input shim (Cocoa tablet events on macOS; XInput2 or
   SDL3 on Linux). That work is bounded and each leg has a working
   precedent — this document sketches both — but it is native code this
   project doesn't currently have, and the one untested interaction
   (XI2/SDL3 alongside AWT on Linux) is a Phase 0 spike.

3. **Would it feel as immediate as the Android build?** Probably not, and
   the honest answer is "prototype required." Android's
   `GLFrontBufferedRenderer` front-buffer path has **no desktop
   equivalent** — desktop builds would render through a normal compositor
   swapchain (swap-interval 0). No published measurements exist for the
   exact windowed-desktop configuration this app would use, so latency is
   the biggest of the handful of prototype-gated questions collected in
   the unknowns table at the end.

The cheapest way to get BangniDraw on a laptop with real pressure today is
not a port at all: **the existing APK on a ChromeOS tablet/convertible**,
where ARC delivers genuine stylus `MotionEvent`s with pressure and
`androidx.graphics.core` documents ChromeOS support. A desktop port is
worth doing for reach and for mouse/trackpad users; ChromeOS already covers
the "pressure on a laptop" use case with zero code.

## Why this codebase is unusually portable

Census of `app/src/main` at `d4ea4a8` (verified by two independent counts;
"free" = zero `android.*`/`androidx.*` imports):

| Package | Files | Free | Android coupling |
|---|---|---|---|
| `engine/core` (tiles, strokes, brushes, wet sim, history, serialization) | 123 | 123 | **None — 15,738 lines, the whole model layer.** |
| `engine/gl` (renderer, passes, shaders, tile textures) | 29 | 8 | 10,232 lines. 21 files import `android.opengl.GLES30` (68 distinct entry points, ~260 call sites); `android.util.Log` in 4 files (9 call sites); `AssetManager` in one constructor (Mixbox shader/LUT loading) |
| `ui` (Compose screens + canvas glue) | 43 | 4 | Jetpack Compose, 3 `@HiltViewModel`s, `stringResource` (374 strings, 6 plurals); one `AndroidView` site (`CanvasSurface.kt:113`); `EngineSession.kt` (1,418 lines) lives here |
| `data` (prefs, gallery, presets, docs) | 29 | 16 | DataStore file provider, MediaStore, brush-preset `AssetManager` reads (`BrushPresetAssets.kt`) |
| `input` (touch, palm rejection, prediction) | 4 | 2 | `CanvasTouchHandler.kt` (1,461 lines) and `Predictor.kt` (105 lines) consume `MotionEvent` |
| `tools`, app entry | 9 | 7 | Hilt application/activity |
| **Total** | **237** | **160** | 67.5% of files compile with zero Android imports |

There is no NDK/JNI code, no network, no permissions. The JVM test suite
(~1,400 tests in 212 files) already runs without a device or Robolectric
(the AGENTS.md JVM-only test policy did exactly the work a port needs: the
whole model layer is proven to run on a desktop JVM every CI run).

Three deliberate design choices turn out to be porting seams:

- **`CanvasRenderer.drawFrame(frameBufferId, bufferWidth, bufferHeight,
  bufferTransform)`** — the entire GL engine renders into a caller-provided
  framebuffer with a caller-provided transform. Nothing inside `engine/gl`
  knows about surfaces, EGL, or Android buffers. A desktop host just passes
  its own FBO.
- **`Shaders.kt:33`** — every shader goes through a single
  `VERSION_LINE = "#version 300 es"` constant. All shaders are GLSL ES 300.
- **`StrokeDriver` consumes platform predictions** rather than generating
  them: a null predictor is a supported configuration (no predicted stroke
  tail, everything else identical). Desktop input can simply not predict.

## The four seams

The *hard* Android coupling — the parts that need per-platform replacements
rather than mechanical substitution — funnels through four places:

1. **GL bindings** — 21 files call static methods on `android.opengl.GLES30`.
2. **Session/surface glue** — `EngineSession.kt` alone owns
   `GLFrontBufferedRenderer`, `GLRenderer`, `SurfaceControlCompat`, and
   `BufferInfo` (from `androidx.graphics.core`). This file is a rewrite per
   platform, by design.
3. **Input** — `MotionEvent` appears only in `CanvasTouchHandler.kt` and
   `Predictor.kt`. The handler's *output* is platform-neutral engine
   commands, but its input type is Android's `MotionEvent` — sharing its
   1,461 lines of gesture/palm-rejection/stroke logic means introducing a
   neutral pointer-sample record (pressure/tilt/orientation/tool/hover —
   the axis set it already reads) with an Android adapter and a desktop
   one. That refactor is Phase 2 work below.
4. **Data/services** — `Prefs.kt:67`'s
   `context.preferencesDataStoreFile(...)` is the *only* Android-specific
   DataStore call; gallery sync uses MediaStore; memory sizing reads
   `ActivityManager` (`CanvasSurface.kt:256–269`).

The remaining coupling is *soft* — mechanical substitutions each handled in
its own section below: string resources (→ CMP resources), the 9
`android.util.Log` call sites (→ expect/actual), the two `AssetManager`
consumers (→ classpath resources), the one `AndroidView` site (Android-only
by nature), and Hilt (3 ViewModels — see the DI note in the UI section).

## Rendering: getting GLES 3.0 on each OS

### Linux — native, zero translation

Mesa implements OpenGL ES 1.1/2.0/3.0/3.1/3.2 behind the standard
`libEGL`/`libGLESv2`, per its own docs ("although some drivers may expose
lower limited set" — in practice every current major desktop driver exposes
ES 3.x). NVIDIA's proprietary driver also installs `libEGL.so.1` and
`libGLESv2.so.*` and has supported ES 3.2 since its 2015-era 355.xx drivers.
So on Linux the engine's `#version 300 es` shaders run on the *native*
driver with no translation layer at all. Fallbacks exist but shouldn't be
needed: desktop GL with `ARB_ES3_compatibility` (guaranteed in GL 4.3 core,
sometimes layered on 4.2) accepts `#version 300 es` shaders, and
ANGLE-on-Vulkan (Khronos-conformant through ES 3.2) is a portable plan B.

### macOS — Apple's GL is a dead end; ANGLE-on-Metal is the road

Apple's native OpenGL is capped at 4.1 on every Mac, deprecated since
macOS 10.14 (deprecated, not removed — it still ships and runs natively on
Apple Silicon), and its driver exposes `ARB_ES2_compatibility` but **not**
`ARB_ES3_compatibility` — so `#version 300 es` shaders *cannot compile* on
Apple's driver, and macOS has never shipped EGL or GLES libraries at all.
Porting the shaders to GLSL 4.1 core is possible but buys a deprecated API.

The road is **ANGLE's Metal backend**: it implements ES 2.0 and 3.0
("complete" in ANGLE's support matrix) on top of Metal. Two precision
points from verification: ANGLE's Khronos conformance certifications
(through ES 3.2) are for its **Vulkan** backend — the Metal backend is
feature-complete and production-proven (it is what Safari 15+ and Chrome
run WebGL/WebGL2 on for all Macs) but **not Khronos-certified**; and it
caps at ES 3.0 — no 3.1/3.2, so no compute/SSBO headroom on macOS. The
engine targets exactly ES 3.0 today, which lands precisely on the
"complete" cell.

### The JVM binding: LWJGL — with a mandatory (thin) adapter

LWJGL 3 ships `org.lwjgl.opengles.GLES30` static bindings and can point its
loader at ANGLE's libraries (`Configuration.OPENGLES_LIBRARY_NAME`,
`EGL_LIBRARY_NAME`, with explicit-init flags). But a straight import swap
**will not compile**: the engine uses Android's `(count, int[], offset)`
overloads (`glGenBuffers(1, vbo, 0)`, `glGetIntegerv(pname, v, 0)`, …)
which LWJGL doesn't have. The port needs a facade class named `GLES30` with
Android-shaped static signatures delegating to LWJGL — 68 one-line
methods (the verified count of distinct entry points; all are standard
ES 3.0 and all exist in LWJGL: `glMapBufferRange`, `glFenceSync`,
`glTexStorage3D`, `glFramebufferTextureLayer`, `glBlitFramebuffer`,
instanced draws, etc.). With `expect`/`actual` or a source-set swap, the 21
GL files then compile unchanged.

Windowing/context: GLFW with `GLFW_CLIENT_API=GLFW_OPENGL_ES_API` +
`GLFW_CONTEXT_CREATION_API=GLFW_EGL_CONTEXT_API`, plus GLFW 3.4's
`GLFW_ANGLE_PLATFORM_TYPE` init hint (Metal/Vulkan/D3D11) — ANGLE is a
first-class tested path in GLFW's EGL code. One deployment wrinkle: GLFW
dlopens EGL under fixed sonames (`libEGL.dylib` on macOS), so the bundled
ANGLE dylibs must be discoverable — libGDX solves this by chdir-ing to the
dylib directory around `glfwInit`. *(Shipped differently: chdir is not
reliable, because it is process-global state anything can clobber — and
LWJGL's `GLFWNativeEGL.setEGLPath` is no remedy either, being a no-op with
the shipped GLFW builds, which lack the symbol it patches. The context now
comes from EGL without GLFW — see AGENTS.md's deviation "The desktop GLES
context is created from EGL directly; GLFW is only the fallback".)*

**Existence proof (with two deltas):** libGDX has shipped this plumbing —
LWJGL3 + GLFW(EGL) + ANGLE-Metal — on macOS in production since 2021, at
ES 2.0 on x86_64. Its ES 2.0 ceiling is an *integration* choice (four
hardcoded version lines and a missing GLES30 wrapper), not a natives
limit: the `gdx-angle-natives` binaries it downloads are a current (2026,
M151-era) ANGLE build whose README says they serve ES 2 *and* 3. But the
two deltas — ES 3.0 and arm64 — are exactly what this port needs, so
libGDX proves the plumbing loads and renders, not the configuration; the
ES 3.0-on-arm64 combination is Phase 0's first spike (Chrome/Safari prove
ANGLE's code itself is fine on Apple Silicon; `gdx-angle-natives`
advertises x86_64 only, so build or source arm64 dylibs).

One ruled-out route: `lwjgl3-awt` (GL on an AWT canvas) has **no EGL/ANGLE
path on macOS** — its macOS backend is CGL/NSOpenGL only — so AWT-embedded
GL cannot host ANGLE on the one OS that needs it.

## UI layer: Compose Multiplatform

The UI is ordinary Jetpack Compose, and Compose Multiplatform (CMP) 1.12.0
(stable, 2026-08-25, based on Jetpack Compose 1.12.0) uses the **identical
`androidx.compose.*` package names** on desktop — UI code ports mostly by
moving files into a shared source set. Kotlin 2.4.10 is fine: JetBrains'
stated policy is "the latest Compose Multiplatform is always compatible
with the latest version of Kotlin" (there is no per-version table anymore),
and 2.4.10 is the current stable. ViewModel/`viewModelScope` run on desktop
via stable `org.jetbrains.androidx.lifecycle` 2.10/2.11 artifacts;
`navigation-compose` is stable for desktop at 2.9.x (2.10 is alpha-only).
The 374 strings + 6 plurals move from Android resources to CMP's
multiplatform resources (mechanical; `stringResource` keeps its name).

**Dependency injection:** Hilt is Android-only, but its footprint here is
small — 12 files touch Hilt/`@Inject` and there are exactly three
`@HiltViewModel`s (`CanvasViewModel`, `StudioViewModel`,
`AppThemeViewModel`). The pragmatic desktop story is to keep Hilt in the
Android app module and construct the three ViewModels directly on desktop
(the multiplatform lifecycle artifacts support plain
factory construction); a shared KMP DI framework (Koin, kotlin-inject) is
an option but overkill for three objects. This is Phase 2 glue, not a
wall.

**Build restructure (the one refuted premise):** the classic KMP recipe —
apply `org.jetbrains.kotlin.multiplatform` next to AGP and call
`androidTarget()` — is **dead on AGP 9**: JetBrains' migration doc states
the KMP plugin is no longer compatible with `com.android.application`/
`com.android.library` (escape-hatch properties exist but rely on variant
APIs slated for removal in AGP 10). The supported shape is: shared modules
apply Google's `com.android.kotlin.multiplatform.library` plugin; the app
entry-point module keeps `com.android.application` with AGP 9's built-in
Kotlin — which is exactly how `app/build.gradle.kts` is already configured
(no `org.jetbrains.kotlin.android` plugin). So the restructure means
*extracting* `engine/core`, `engine/gl`, and shared UI into KMP library
modules, not converting the app module. Known sharp edge: CMP resources
had an AGP 9 + KMP-library packaging bug (CMP-9547) — pin current versions
and test resource packaging early.

## Compositing: the biggest open engineering question

On Android, Compose UI floats above the engine's SurfaceView layers,
composited by the OS. On desktop, Compose renders through Skiko (Skia),
and the engine needs a GLES context — getting both into one window has
three candidate architectures, none free:

1. **Engine renders offscreen; UI shows it as an image.** The engine draws
   into its FBO (its native contract already), the host blits/reads into a
   Skia-visible texture or bitmap each frame. Simplest and most portable;
   the cost is an extra copy per frame and cross-API texture sharing
   (GLES-under-ANGLE ↔ Skiko-Metal on macOS; GL ↔ GL on Linux, where Skiko
   itself renders OpenGL and plain GL texture sharing may suffice).
2. **Compose renders into the engine's GL context** via
   `CanvasLayersComposeScene` (Compose-on-caller-owned-GL). Verified state:
   the API exists in CMP 1.12.0 but is `@InternalComposeUiApi`, and
   JetBrains deleted the official lwjgl-integration example in July 2026
   ("please use libraries supported by the community") — technically alive,
   explicitly unsupported. On macOS it also inverts the GL problem: Skia's
   GL backend (`DirectContext.makeGL` is compiled in on all desktop OSes)
   would sit on deprecated Apple GL 4.1, or Skia and the engine must share
   an ANGLE context — unproven territory.
3. **Heavyweight interop** (engine in an AWT/Swing surface via `SwingPanel`)
   with the experimental `compose.interop.blending=true` flag for overlays.
   Verified constraints: blending works with Metal (macOS) and offscreen
   Swing rendering; OpenGL is *not* in the supported list, so Linux (where
   Skiko defaults to GL) needs the offscreen render mode; still marked
   "for evaluation purposes" in 1.12.0 docs.

Recommendation: **architecture 1** — it keeps every component on its
supported path and matches the engine's existing FBO contract. This is a
prototype-first item; it is engineering risk, not feasibility risk (worst
case is a per-frame readback, which is a performance tax, not a wall).

## Input: the stylus wall (product-defining)

Verified state of the ecosystem, 2026-08-30:

- **Compose Desktop delivers no pressure/tilt/eraser on any OS.** Its AWT
  event bridge (`ComposeSceneMediator.desktop.kt`) hardcodes
  `PointerType.Mouse`. The only in-flight fix, PR #3218, is open,
  **Windows-only**, opt-in, covers pressure/eraser (tilt not mentioned);
  JetBrains said 2026-08-07 they are "investigating long-term support."
  No macOS/Linux counterpart exists.
- **The JVM has no pen API.** JDK-8193925 ("Java should provide support
  for reading tablet pen pressure") is an unresolved P4 enhancement. The
  only JVM tablet library, JPen, last released in 2015 (last code 2019).
- **GLFW has no pen API either** (issue #403 open since 2014; the WinTab
  PR was dropped from the 3.4 milestone).

So a pressure-first desktop build requires a small native input layer.
The good news: the required work is small, per-OS, and each leg has a
verified, working precedent:

- **macOS: a ~200-line Cocoa JNI shim** on the AWT content view reading
  `NSEvent` tablet events (`tabletPoint`/`tabletProximity`: `pressure`,
  `tilt`, `rotation`, `tangentialPressure`, eraser via
  `pointingDeviceType == .eraser`). This is byte-for-byte the same data
  SDL's and Qt's macOS pen backends read, it covers Wacom-class tablets
  *and* Sidecar Apple Pencil ("advanced stylus support" means exactly
  "handles AppKit tablet events" — not PencilKit), and JPen proved the
  AWT+JNI pattern years ago. SDL3 cannot substitute here: inside an AWT
  process, AWT owns the macOS main-thread event pump SDL needs, and SDL's
  external-window path installs a conflicting NSWindow delegate.
- **Linux: XInput2 on the AWT window** (own display connection,
  `XISelectEvents` on the window id from JAWT), or — likely less code —
  **SDL3 via LWJGL's official `lwjgl-sdl` bindings** in input-only mode:
  SDL3 can adopt a foreign X11 window (`SDL_CreateWindowWithProperties` +
  `SDL_HINT_VIDEO_X11_EXTERNAL_WINDOW_INPUT`, default on, added for
  exactly this case) and its pen API delivers pressure + tilt on X11.
  Either way, **Wayland sessions are covered through XWayland**, whose
  emulated stylus exposes wacom-compatible pressure (0–65535) and tilt
  valuators (xorg-server ≥ 1.20, i.e. everywhere since 2018). One
  time-bomb to defuse: JetBrains Runtime 2026.1 defaults AWT to its new
  native Wayland toolkit, which removes the X11 window id and (currently)
  any pressure channel — pin `-Dawt.toolkit.name=XToolkit` until a
  `zwp_tablet_v2` path exists.
- **SDL3's Pen API** (3.2+, first-class: proximity/down/up/motion/button/
  axis events; pressure, tilt X/Y, distance, rotation, slider, tangential
  pressure; eraser; 5 buttons; hover) is the best single cross-platform
  pen abstraction today, but per-backend coverage varies (Wayland fullest;
  X11 pressure+tilt only with heuristic eraser detection; macOS
  pressure/tilt/rotation/tangential). It only replaces *all* hand-written
  layers if an SDL-owned window hosts the canvas — a bigger architectural
  swing than the two shims above.

Downstream of the shim, the logic exists but the plumbing doesn't yet:
`CanvasTouchHandler` reads pressure/tilt/orientation/tool-type from actual
Android `MotionEvent`s, so Phase 2 introduces the neutral pointer-sample
record described under seam 3, with a `MotionEvent` adapter on Android and
the native shims filling the same record on desktop. The handler's gesture
and palm-rejection logic then ships unchanged. Prediction can be disabled
(null predictor is supported); desktop input rates are modest anyway.

A mouse/trackpad-only desktop build needs none of this and still delivers
the full engine — synthetic pressure from speed or a pressure slider is a
reasonable v1, and is how many desktop art apps degrade.

## Latency: no front-buffer path on desktop

Android's stroke-latency trick — `GLFrontBufferedRenderer` writing directly
into a single-buffered `HardwareBuffer` layer posted via
`SurfaceControlCompat` with `USAGE_FRONT_BUFFER` (verified mechanism; it
does *not* use `EGL_KHR_mutable_render_buffer`) — has no desktop
equivalent:

- Mesa implements `EGL_KHR_mutable_render_buffer` **only for the Android
  platform** (single assignment in `platform_android.c`, gated on
  `ANDROID_API_LEVEL >= 24`; no X11/Wayland/DRM platform sets it).
- Wayland's only tearing knob, `wp_tearing_control_v1`, is a per-surface
  *hint* compositors may ignore; major compositors honor it essentially
  only for fullscreen surfaces.
- Realistic windowed posture: **swap-interval 0 / mailbox swapchain** with
  damage-rect scissoring, i.e. one to two frames behind the compositor.

Published numbers to calibrate expectations (none is the exact windowed
macOS/Linux GLES-app measurement — that requires a prototype): whole-system
input-to-photon on desktops measures 50–200 ms (Dan Luu's camera studies);
a tuned *fullscreen* KDE-Wayland gaming setup reached ~9 ms; Android
front-buffer stylus capability is ~16 ms at 60 Hz / ~4 ms at 120 Hz. The
structural floor matters more than the poles: a windowed swapchain sits
one to two compositor frames behind the pen — 16–33 ms at 60 Hz before the
app draws anything — so the Android 120 Hz front-buffer figure is out of
reach by construction, and the prototype's job is to measure how far
behind the desktop build lands and whether that still *feels* right.
(ProMotion/120 Hz displays halve the floor; so does rendering the
in-progress stroke with minimal batching.)

## Data layer

- **Preferences:** `androidx.datastore:datastore-preferences-core:1.2.1`
  publishes `jvm`, `linuxX64/Arm64`, `macosX64/Arm64` variants, and the JVM
  artifact carries exactly the API `Prefs.kt` uses:
  `PreferenceDataStoreFactory.create(corruptionHandler, migrations, scope,
  produceFile: () -> File)` (plus an okio-`Path` variant). Swap the one
  `preferencesDataStoreFile` call for a desktop config-dir path and the
  whole prefs layer ports as-is.
- **Gallery:** MediaStore has no desktop analog; the natural mapping is
  export-to-directory (e.g. `~/Pictures/BangniDraw`) with the existing
  name-generation logic. Internal project storage is plain files already.
- **Memory sizing:** don't use `Runtime.maxMemory()` — it reports the JVM
  *heap* ceiling (default ¼ of RAM), and the tile pool is native/GPU
  memory. The right analog of `ActivityManager.MemoryInfo.totalMem` is
  `com.sun.management.OperatingSystemMXBean.getTotalMemorySize()` (JDK
  module `jdk.management`, present in jpackage runtimes). GPU-memory GL
  extensions are a vendor lottery *and* desktop-GL-only per Khronos'
  registry — a GLES-on-ANGLE context never sees them on any OS; keep the
  existing `GL_MAX_*` limit queries and derive tile budgets from system
  RAM.
- **Logging:** nine `android.util.Log` call sites across four `engine/gl`
  files — trivial expect/actual.
- **Assets:** two `AssetManager` consumers — `engine/gl` loads
  `mixbox.glsl` + `mixbox_lut.png`, and `BrushPresetAssets.kt` lists and
  reads the brush-preset JSON files. Both move to classpath resources on
  desktop.

## Packaging and distribution

The CMP Gradle plugin's `nativeDistributions` (jpackage-based, JDK 17+)
produces Dmg/Pkg on macOS and Deb/Rpm on Linux. Traps and facts verified:

- **No cross-compilation** — `packageDmg` only runs on macOS, deb/rpm only
  on Linux. CI needs per-OS runners (the repo's release workflow gains a
  matrix).
- `TargetFormat.AppImage` is **not** Linux AppImage — it is jpackage's
  unpacked "app-image" *directory*. Real AppImage/Flatpak/Snap means
  wrapping that directory yourself (`appimagetool`, `flatpak-builder`).
- **Bundling ANGLE dylibs (macOS):** use `nativeDistributions.
  appResourcesRootDir` with per-OS/per-arch subfolders; files land in the
  bundle's resources dir (located at runtime via the
  `compose.application.resources.dir` system property) and must be loaded
  **directly from the bundle** — JetBrains' signing docs warn that
  extract-from-JAR-then-load breaks signing/sandbox.
- **macOS signing is mandatory in practice:** Developer ID + Hardened
  Runtime + notarization (Gatekeeper's "app is damaged" dialog otherwise);
  every bundled dylib must be signed too. The plugin has a `signing {}`
  DSL and `notarizeDmg`/`notarizePkg` tasks. This is the only recurring
  cost of the whole port (an Apple Developer account).
- **Flatpak** works for JVM apps (Flathub's openjdk SDK extension), but the
  manifest needs `--socket=x11` outright — not `fallback-x11` — because
  AWT still has no Wayland backend (OpenJDK's Wakefield is a prototype),
  plus `--device=dri`. Stylus input then rides the XWayland emulation
  described above.

## Licensing: Mixbox

`com.scrtwpns:mixbox` (and the vendored `mixbox.glsl`/`mixbox_lut.png`) is
**CC BY-NC 4.0** — non-commercial only, exactly as the repo's ADR 0003
already records for Android. Gratis desktop builds distributed without
revenue are squarely within the license; anything revenue-adjacent is not
(the ADR's standing rule: nothing in this repo may introduce revenue while
Mixbox is present). Desktop builds inherit the same attribution
obligations (About screen + README), and the existing
`bangnidraw.mixbox=false` Gradle property — which swaps the `src/mixbox`
source set for `src/nomixbox`, binds `RgbMixer`, and assembles shaders
with plain `mix()` — works unchanged as the license-free fallback. A
commercial license exists (mixbox@scrtwpns.com) if that ever matters.

## Alternatives to a port

Ranked by effort-to-value for the actual use case (drawing with pressure
on a laptop):

1. **ChromeOS, zero code.** The existing APK runs under ARC on ChromeOS
   tablets/convertibles; ARC delivers real stylus `MotionEvent`s with
   pressure, and `androidx.graphics.core` documents ChromeOS support for
   the low-latency path. This is the only no-work option that keeps
   pressure *and* low latency.
2. **The port described here, mouse-first.** KMP restructure + LWJGL/ANGLE
   + Compose Desktop; ship without pressure, add the native shims later.
3. **The full port with input shims** — the complete answer on Mac+Linux.
4. **Waydroid (Linux only)** runs the APK in a container at near-native
   speed; stylus fidelity depends on its input stack — verify before
   recommending. The stock Android emulator is not a real option (no
   pressure, translation overhead).

## Suggested path (if the port is pursued)

- **Phase 0 — spikes (the unknowns that gate everything):**
  1. LWJGL + GLFW + ANGLE-Metal on an arm64 Mac: create an ES 3.0 context,
     compile the engine's most complex shader, render tiles offscreen.
     (Also settles the arm64-ANGLE-dylib question.)
  2. Offscreen-FBO → Compose `Image` compositing loop at 60/120 Hz on both
     OSes; measure the copy cost (architecture 1 above).
  3. XInput2/SDL3 pen events coexisting with an AWT window on X11 and
     XWayland (the one interaction verification flagged as high-plausibility
     but unproven: XI2 device-event selection alongside AWT's own).
  4. Latency feel test: swap-interval-0 stroke loop vs. the Android build
     side by side.
- **Phase 1 — KMP restructure:** extract `engine/core` (pure, moves as-is)
  and `engine/gl` (behind the 68-method GLES30 facade) into
  `com.android.kotlin.multiplatform.library` modules; Android app keeps
  building identically. This phase is also a pure win for the Android
  codebase (enforced layering, desktop-JVM tests for GL logic via the
  facade).
- **Phase 2 — desktop shell:** Compose Desktop window, desktop
  `EngineSession` (GLFW/ANGLE context + FBO compositing), desktop
  `Prefs`/gallery/memory adapters, strings to CMP resources, direct
  ViewModel construction in place of Hilt, and the neutral pointer-sample
  record that ports `CanvasTouchHandler` off `MotionEvent`. Mouse-only,
  synthetic pressure. This is a usable app.
- **Phase 3 — pen input:** Cocoa tablet shim (macOS), XInput2-or-SDL3 leg
  (Linux), each filling the Phase 2 pointer record so
  `CanvasTouchHandler`'s existing axis model works unchanged.
- **Phase 4 — packaging:** per-OS CI runners, signing/notarization,
  deb/rpm + Flatpak, ANGLE bundling.

Rough shape: phases 0–2 are a few weeks of focused work each at most —
the engine itself needs almost nothing; the work is shell, build, and
glue. Phase 3 is small in lines but native and per-OS. Most phase 0 spikes
retire engineering risk with known fallbacks; the one that can return
"don't ship this" rather than "do it differently" is latency feel
(phase 0.4), so treat it as a gate for the later phases, not a
formality.

## What was verified, and residual unknowns

Every external claim above survived an adversarial verification pass
against primary sources (ANGLE's README/support matrix, Mesa and SDL and
xorg-server sources, LWJGL/GLFW docs and sources, JetBrains docs/PRs,
Apple/Khronos/Oracle docs, Maven/Google module metadata). Corrections that
pass produced are already folded in — notably: ANGLE-Metal is *not*
Khronos-certified (and caps at ES 3.0); the LWJGL "import swap" needs a
mandatory 68-method adapter; `androidTarget()`-next-to-AGP is dead on
AGP 9; `TargetFormat.AppImage` isn't AppImage; GPU-memory GL extensions
are invisible from GLES contexts. A third checking pass over this
document's own draft then corrected its census arithmetic and flagged the
overclaims that the current wording repairs.

Honest unknowns that only prototypes can settle:

| Unknown | Why it's open | Gate |
|---|---|---|
| Windowed stroke latency on macOS/Linux | No published measurements for this configuration exist | Phase 0.4 |
| GLES↔Skiko compositing cost | Cross-API texture sharing vs. readback is machine-dependent | Phase 0.2 |
| arm64 ANGLE dylibs | `gdx-angle-natives` advertises x86_64; building ANGLE is routine but unverified here | Phase 0.1 |
| XI2 + AWT coexistence | Precedented (SDL external-window mode, JPen) but not spiked | Phase 0.3 |
| CMP internal APIs (`CanvasLayersComposeScene`) | Unsupported; avoid (architecture 1 doesn't need them) | — |
| JBR Wayland default (2026.1) | Removes the X11 input path; pin XToolkit until a tablet-v2 shim exists | Phase 3 |

## Bottom line

Mac and Linux builds are **possible and architecturally cheap** — this
codebase was accidentally designed for it (a 15,738-line pure-JVM engine
core, a single-constant shader version line, an FBO-parameterized
renderer, input confined to one handler). The rendering and UI stacks are
solved problems with production precedents (libGDX's ANGLE path, Safari's
ANGLE-Metal, Mesa's native GLES). What stands between "runs on a Mac" and
"is BangniDraw on a Mac" is stylus pressure — absent from the entire JVM
desktop stack, recoverable only through the two small native shims this
document sketches (each leg with a working precedent, one interaction
left to a spike) — and stroke latency, which no source can promise and
one week of prototyping can measure. If laptop drawing with pressure is
the goal *today*, a ChromeOS device running the existing APK is the
zero-effort answer while a port matures.
